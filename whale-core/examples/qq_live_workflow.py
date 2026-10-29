"""QQ 实况工作流运行器：WeQ SSE 推送 → 配对 → DeepSeek → 待审回复草稿。

这是 W09 的第一条**真实数据**管道（Phase 1 = 收 + AI + 草稿 + 审批；真实发送是
Phase 2，见 docs/research-weq-deepseek-qq.md §6）。

用法（在 whale-core/ 下）：
    python examples/qq_live_workflow.py run                 # 启动监听（前台，Ctrl+C 退出）
    python examples/qq_live_workflow.py approve <draft_id>  # 人工批准一条草稿（走完整账本）
    python examples/qq_live_workflow.py show                # 查看待审草稿列表

密钥与身份（都不入仓、不打印值）：
    读取 infra/call-bridge/secrets/qq_workflow.env（KEY=VALUE，# 注释）：
      WEQ_SSE_TOKEN   必填，与 WeQ「设置 → WeQ 服务 → SSE 推送」的 accessToken 一致
      QQ_SELF_UIN     本机 QQ 登录的小号（默认 3899713055），用于过滤自发消息
      OWNER_QQ        你的主账号 QQ 号（配对所有者）；不填 = 观察模式（只报告未配对来件）
      DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL
                      未填 key 时用 stub 脑验证管道（回复带 [stub] 标记）

WeQ 侧前置：桌面端处于任意模式（离线即可），账号已打开，SSE 推送目标指向
    http://127.0.0.1:18797/whale 并填同一 token。
"""
from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

# Windows 控制台默认 GBK：不可编码字符（emoji 等）降级替换；重定向到日志时行缓冲，
# 保证后台运行也能实时看到事件。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        try:
            _stream.reconfigure(errors="replace", line_buffering=True)
        except Exception:
            pass

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "whale-core" / "src"))

from whale_core.life.channels import OwnerChannelRegistry          # noqa: E402
from whale_core.life.initiative import ProactiveOutbox, ProactivePrefs  # noqa: E402
from whale_core.life.qq_workflow import QqInboundWorkflow           # noqa: E402
from whale_core.life.wq_push import WeQSsePushListener              # noqa: E402
from whale_core.memory.store import InMemoryStore                   # noqa: E402

DEFAULT_SECRETS = REPO_ROOT / "infra" / "call-bridge" / "secrets" / "qq_workflow.env"
DEFAULT_STORE = REPO_ROOT / "whale-core" / ".state" / "qq_workflow.sqlite3"


def load_secrets(path: Path) -> list[str]:
    """KEY=VALUE 载入环境（不覆盖已有环境变量）；只返回键名列表，绝不打印值。"""
    loaded: list[str] = []
    if not path.exists():
        return loaded
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip().strip('"').strip("'")
        if key and value and key not in os.environ:
            os.environ[key] = value
            loaded.append(key)
    return loaded


class StubBrain:
    """无 DEEPSEEK_API_KEY 时的占位脑：验证管道，回复显式带 [stub] 标记。"""

    def reply(self, text: str, *, persona: str = "", memories=(), history=()) -> str:
        n_mem = len(list(memories))
        return (f"[stub 脑] 已收到「{text[:40]}」（召回记忆 {n_mem} 条）。"
                f"在 secrets 里填 DEEPSEEK_API_KEY 并重启后由 DeepSeek 生成真实回复。")


def build_brain():
    if os.environ.get("DEEPSEEK_API_KEY"):
        from whale_core.core.deepseek import DeepSeekCompanion
        print("[brain] DeepSeek（base_url=%s, model=%s）" % (
            os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
            os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")))
        return DeepSeekCompanion()
    print("[brain] [!] stub 脑（DEEPSEEK_API_KEY 未配置；只验证管道，不产生真实回复）")
    return StubBrain()


def build_store(path_str: str):
    if path_str == ":memory:":
        return InMemoryStore()
    from whale_core.memory.sqlite_store import SqliteMemoryStore
    path = Path(path_str)
    path.parent.mkdir(parents=True, exist_ok=True)
    return SqliteMemoryStore(path)


def risk_prefs() -> ProactivePrefs:
    """风控预算：每日上限与静默时段从 secrets/env 注入（默认保守：10 条/天，22–7 点静默）。

    Phase 2 接入真实出站后，该预算由 ProactiveOutbox 在触达传输层之前强制执行；
    发送结果 unknown 不自动重试（账本既有语义），任何风控信号出现即人工停机。
    """
    from datetime import time as dtime
    return ProactivePrefs(
        enabled=True,
        allowed_channels=frozenset({"direct_message"}),
        daily_limit=int(os.environ.get("DAILY_LIMIT", "10")),
        quiet_start=dtime(int(os.environ.get("QUIET_START", "22"))),
        quiet_end=dtime(int(os.environ.get("QUIET_END", "7"))),
    )


def cmd_run(args) -> int:
    token = os.environ.get("WEQ_SSE_TOKEN", "")
    if not token:
        print("错误：WEQ_SSE_TOKEN 未配置（secrets 文件或环境变量），监听器 fail closed 拒绝启动。")
        return 2
    self_uin = os.environ.get("QQ_SELF_UIN", "3899713055")
    owner_qq = os.environ.get("OWNER_QQ", "").strip()
    bindings = {("qq", owner_qq): "owner"} if owner_qq else {}
    if not owner_qq:
        print("[配对] [!] 观察模式：OWNER_QQ 未配置，所有来件只报告不处理（不会喂 AI）。")
    else:
        print(f"[配对] owner 绑定：(qq, {owner_qq}) → owner")

    store = build_store(args.store)
    prefs = risk_prefs()
    print(f"[风控预算] 每日上限={prefs.daily_limit} 静默时段={prefs.quiet_start.hour}:00–{prefs.quiet_end.hour}:00")
    workflow = QqInboundWorkflow(store, build_brain(), prefs=prefs)
    registry = OwnerChannelRegistry(owner_id="owner", bindings=bindings)

    def on_owner(event):
        print(f"\n← [owner] conv={event.conversation} msg={event.message_id} "
              f"text={event.text[:60]!r}")
        try:
            result = workflow.handle_owner_event(event)
        except Exception as exc:  # AI/存储失败不打断监听
            print(f"  [工作流失败] {type(exc).__name__}: {exc}")
            return
        if "skipped" in result:
            print(f"  [闸门] skipped={result['skipped']}")
            return
        print(f"  [AI 草稿] {result['draft_id']} verdict={result['verdict']} ({result['reason']})")
        print(f"  {result['reply'][:300]}")
        if args.auto_approve and result["verdict"] == "needs_approval":
            dispatch_approved(store, result["draft_id"])
        elif result["verdict"] == "needs_approval":
            print(f"  → 批准发送记录：python examples/qq_live_workflow.py approve {result['draft_id']}")

    def on_unpaired(incoming):
        print(f"[未配对] account={incoming.account} conv={incoming.conversation} "
              f"msg={incoming.message_id}（在 secrets 设 OWNER_QQ={incoming.account} 并重启即可配对）")

    def on_mass(meta):
        print(f"[mass] {meta['chatType']}:{meta['convId']} seq {meta['fromSeq']}→{meta['toSeq']} "
              f"count={meta['count']}（合并事件，不进消息管道）")

    listener = WeQSsePushListener(
        registry, expected_token=token, self_uin=self_uin,
        on_owner_event=on_owner, on_unpaired=on_unpaired, on_mass=on_mass,
        accept_group=args.accept_group, port=args.port)
    try:
        listener.start()
    except OSError as exc:
        print(f"错误：监听 {args.port} 失败：{exc}")
        return 2
    print(f"[监听] http://{listener.host}:{listener.port}/（等 WeQ SSE 推送；Ctrl+C 退出）")
    try:
        import time
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        listener.stop()
        print(f"\n[停止] 已处理事件去重窗口={listener.dropped} 条因过载丢弃")
    return 0


class ConsoleSendPort:
    """Phase 1 无真实出站通道：如实打印并返回 False（账本记 unknown，不冒充受理）。"""

    def send(self, draft) -> bool:
        print(f"[Phase-2 未接入] 本应发往 QQ（{draft.channel}）：{draft.text[:200]}")
        return False


def dispatch_approved(store, draft_id: str) -> str:
    outbox = ProactiveOutbox(store)
    prefs = ProactivePrefs(enabled=True, allowed_channels=frozenset({"direct_message"}),
                           daily_limit=20)
    verdict = outbox.dispatch(draft_id, prefs, now=datetime.now(timezone.utc),
                              port=ConsoleSendPort(), explicitly_approved=True)
    print(f"[账本] {draft_id}: {verdict.status} ({verdict.reason})")
    return verdict.status


def cmd_approve(args) -> int:
    store = build_store(args.store)
    dispatch_approved(store, args.draft_id)
    return 0


def cmd_show(args) -> int:
    store = build_store(args.store)
    outbox = ProactiveOutbox(store)
    ids = [k.split("life:draft:", 1)[1] for k in store.keys() if k.startswith("life:draft:")]
    if not ids:
        print("（没有草稿）")
        return 0
    for draft_id in sorted(ids):
        draft = outbox.get(draft_id)
        ledger = outbox.ledger(draft_id)
        print(f"- {draft_id} [{ledger.get('status', '?')}] {draft.text[:80]!r}" if draft
              else f"- {draft_id} [损坏]")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--secrets", default=str(DEFAULT_SECRETS))
    parser.add_argument("--store", default=str(DEFAULT_STORE),
                        help="记忆/账本存储（sqlite 路径或 :memory:）")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_run = sub.add_parser("run", help="启动 SSE 监听 + AI 工作流")
    p_run.add_argument("--port", type=int, default=int(os.environ.get("WQ_PUSH_PORT", "18797")))
    p_run.add_argument("--accept-group", action="store_true",
                       help="同时接收群消息（默认只收私聊；群内容不可信）")
    p_run.add_argument("--auto-approve", action="store_true",
                       help="演示用：草稿直接走批准账本（Phase 1 出站为 stub，仍不会真实发送）")
    p_run.set_defaults(fn=cmd_run)
    p_ap = sub.add_parser("approve", help="人工批准一条草稿")
    p_ap.add_argument("draft_id")
    p_ap.set_defaults(fn=cmd_approve)
    p_ls = sub.add_parser("show", help="列出草稿与账本状态")
    p_ls.set_defaults(fn=cmd_show)
    args = parser.parse_args()
    loaded = load_secrets(Path(args.secrets))
    if loaded:
        print(f"[secrets] 已载入键：{', '.join(loaded)}（值不打印）")
    return args.fn(args)


if __name__ == "__main__":
    raise SystemExit(main())
