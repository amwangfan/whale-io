"""WeQ 桌面内置 Web 服务的真实发送端口（W09 Phase 2 · 主账号身份=本机登录小号）。

通道说明：WeQ 对外部 MCP 刻意只读；本端口走的是**内置 Web 服务的用户界面同款
tRPC 接口**（`/_auth/login` + `/trpc/account.sendElements`），等价于用户自己在
浏览器里操作自己的 WeQ——token 由用户从 WeQ 设置复制注入（secrets），不破解、
不复用助手令牌、不绕过任何权限门。

前置与边界：
- 需要 WeQ 完整模式（QQ 在线 + 已授权 attach）；离线模式下 sendElements 会被
  WeQ 拒绝，本端口如实返回 False（账本记 unknown，不自动重试）；
- Web token 每次 WeQ 重启轮换，需要重新复制（长期方案等上游受控发送接口，
  见 docs/research-weq-deepseek-qq.md §6.3）；
- send 返回 True 仅表示 QQ 服务端受理（ok=true），不代表已读；
- 只做 direct_message 渠道、单一绑定对端（owner uid）；不群发、不换目标；
- token 不进日志与异常文本。
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Callable

from whale_core.life.initiative import ProactiveDraft

MAX_TEXT_CHARS = 4000


class WeQSendError(RuntimeError):
    """WeQ Web 通道失败（登录、HTTP、协议、被拒）。异常文本不含 token。"""


class WeQWebSendPort:
    """OutboundPort 实现：经 WeQ 内置 Web tRPC 发送 c2c 文本。

    opener 可注入以便离线测试；默认 urllib.request.urlopen。
    """

    def __init__(self, base_url: str, token: str, *, target_uid: str,
                 expected_account_uin: str | None = None,
                 expected_account_config_id: str | None = None,
                 timeout: float = 15.0,
                 opener: Callable[..., object] | None = None) -> None:
        if not base_url.strip():
            raise ValueError("base_url required")
        if not token:
            raise ValueError("token required (从 WeQ 设置 → WeQ 服务 → Web 与 Remote 复制)")
        if not target_uid.strip():
            raise ValueError("target_uid required (owner 的 QQ uid，u_ 开头)")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.target_uid = target_uid
        self.expected_account_uin = expected_account_uin
        self.expected_account_config_id = expected_account_config_id
        self.timeout = timeout
        # urlopen 的第二位置参数是 data 而非 timeout：必须包一层关键字传参。
        self._opener = opener or (lambda req, t: urllib.request.urlopen(req, timeout=t))
        self._cookie: str | None = None
        self.last_receipt: dict | None = None

    # ── 内部 HTTP ─────────────────────────────────────────────

    def _post(self, path: str, body: dict, *, auth: bool) -> tuple[int, dict | str, str]:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if auth:
            if not self._cookie:
                raise WeQSendError("no session (login first)")
            headers["Cookie"] = self._cookie
        request = urllib.request.Request(self.base_url + path, data=data,
                                         headers=headers, method="POST")
        try:
            with self._opener(request, self.timeout) as response:  # type: ignore[operator]
                raw = response.read()
                set_cookie = response.headers.get("Set-Cookie") if hasattr(response, "headers") else None
                status = getattr(response, "status", 200)
        except urllib.error.HTTPError as exc:
            try:
                raw = exc.read()
            except Exception:
                raw = b""
            status = exc.code
            set_cookie = None
        except (urllib.error.URLError, OSError, ValueError):
            raise WeQSendError("weq web unreachable") from None
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            parsed = raw.decode("utf-8", "replace")[:200]
        return status, parsed, set_cookie or ""

    def login(self) -> None:
        status, _body, set_cookie = self._post("/_auth/login", {"token": self.token}, auth=False)
        if status != 200 or "weq_session=" not in set_cookie:
            raise WeQSendError(f"weq web login failed (http {status})")
        # 只取会话 cookie 本体，不带其它属性
        for part in set_cookie.split(","):
            chunk = part.strip().split(";", 1)[0]
            if chunk.startswith("weq_session="):
                self._cookie = chunk
                return
        raise WeQSendError("weq web login returned no session cookie")

    # ── OutboundPort ─────────────────────────────────────────

    def send(self, draft: ProactiveDraft) -> bool:
        if draft.channel != "direct_message":
            return False
        text = draft.text
        if not text.strip() or len(text) > MAX_TEXT_CHARS:
            return False
        payload = {
            "json": {
                "peerType": "c2c",
                "targetId": self.target_uid,
                "elements": [{"kind": "text", "textContent": text}],
            }
        }
        if self.expected_account_uin:
            payload["json"]["expectedAccountUin"] = self.expected_account_uin
        if self.expected_account_config_id:
            payload["json"]["expectedAccountConfigId"] = self.expected_account_config_id
        try:
            if self._cookie is None:
                self.login()
            status, body, _ = self._post("/trpc/account.sendElements", payload, auth=True)
            if status == 401:  # 会话过期：重登一次再试（仅此一次）
                self.login()
                status, body, _ = self._post("/trpc/account.sendElements", payload, auth=True)
        except WeQSendError:
            return False
        if status != 200 or not isinstance(body, dict):
            return False
        # tRPC + superjson: {"result":{"data":{"json":<SendMessageOutcome>}}}
        outcome = (((body.get("result") or {}).get("data") or {}).get("json"))
        if not isinstance(outcome, dict):
            return False
        self.last_receipt = outcome
        return bool(outcome.get("ok"))
