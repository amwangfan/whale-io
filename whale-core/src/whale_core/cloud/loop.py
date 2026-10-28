"""云端行动闭环 stub。

入站事件 → Companion/Reasoner（或规则选择器）选行动
→ 经 Actions 网关发出「推送卡片」或「shell 白名单」意图。

默认不发真实 HTTP；MockHttpActionGateway 只记录拟发送载荷。
密钥 / Base URL 只读环境变量，不入库、不编造。
"""

from __future__ import annotations

from typing import Protocol, Sequence

from whale_core.actions.gateway import ActionGateway, CompositeActionGateway
from whale_core.actions.intents import ShellWhitelist, ShowCard
from whale_core.cloud.events import InboundEvent
from whale_core.core.types import ActionIntent

# 与 call-bridge BRIDGE_TOOLS 对齐的最小白名单（云端侧只声明意图，不执行 shell）
DEFAULT_SHELL_ALLOWLIST: frozenset[str] = frozenset(
    {
        "what_time",
        "bridge_status",
        "ring_fake_call",  # 实现名；用户文案用 VoIP 来电
        "list_workspace",
        "tail_bridge_log",
        "restart_bridge",
    }
)


class ActionSelector(Protocol):
    def select(self, event: InboundEvent) -> Sequence[ActionIntent]:
        """从入站事件选出零或多条行动意图。"""
        ...


class RuleActionSelector:
    """最小可跑规则选择器（无 LLM、无网络）。

    - 消息含「卡片」「推送」→ show_card
    - 消息匹配白名单工具关键词 / 显式 tool:name → shell_whitelist
    - tool_result → 推一张结果摘要卡；若 tool_name 在白名单则附带回执意图
    """

    def __init__(self, *, allowlist: frozenset[str] | None = None) -> None:
        self.allowlist = allowlist or DEFAULT_SHELL_ALLOWLIST

    def select(self, event: InboundEvent) -> Sequence[ActionIntent]:
        text = (event.text or "").strip()
        if event.kind == "tool_result":
            intents: list[ActionIntent] = [
                ShowCard(
                    title="工具结果",
                    body=text[:500] or f"{event.tool_name or 'tool'} done",
                    layout_hint="tool_result",
                ).to_intent()
            ]
            name = (event.tool_name or "").strip()
            if name and name in self.allowlist:
                intents.append(
                    ShellWhitelist(tool_name=name, args={"echo_result": True}).to_intent()
                )
            return intents

        # message
        lower = text.lower()
        intents_out: list[ActionIntent] = []

        explicit = _explicit_tool(text, self.allowlist)
        if explicit:
            intents_out.append(ShellWhitelist(tool_name=explicit).to_intent())

        keyword_tool = _keyword_tool(text, self.allowlist)
        if keyword_tool and keyword_tool not in {i.payload.get("tool_name") for i in intents_out}:
            intents_out.append(ShellWhitelist(tool_name=keyword_tool).to_intent())

        if any(k in text for k in ("卡片", "推送", "通知")) or "card" in lower or "push" in lower:
            intents_out.append(
                ShowCard(title="云端推送", body=text[:500] or "（空消息）").to_intent()
            )

        # 无匹配时：默认回一张轻量确认卡，保证闭环可观测
        if not intents_out:
            intents_out.append(
                ShowCard(
                    title="已收到",
                    body=text[:500] or f"from:{event.source or 'unknown'}",
                    layout_hint="ack",
                ).to_intent()
            )
        return intents_out


def _explicit_tool(text: str, allowlist: frozenset[str]) -> str | None:
    # 支持 "tool:what_time" / "shell:bridge_status"
    for prefix in ("tool:", "shell:"):
        if prefix in text:
            frag = text.split(prefix, 1)[1].strip().split()[0]
            name = frag.strip(",.;")
            if name in allowlist:
                return name
    return None


def _keyword_tool(text: str, allowlist: frozenset[str]) -> str | None:
    rules: list[tuple[tuple[str, ...], str]] = [
        (("几点", "what time", "现在时间"), "what_time"),
        (("桥状态", "bridge status", "查桥"), "bridge_status"),
        (("voip", "来电", "响铃"), "ring_fake_call"),
        (("工作区", "list workspace", "列目录"), "list_workspace"),
        (("桥日志", "bridge log"), "tail_bridge_log"),
        (("重启桥", "restart bridge"), "restart_bridge"),
    ]
    lower = text.lower()
    for keys, name in rules:
        if name not in allowlist:
            continue
        if any(k in text or k in lower for k in keys):
            return name
    return None


class CloudActionLoop:
    """最小可跑闭环：handle(event) → receipts。"""

    def __init__(
        self,
        *,
        gateway: ActionGateway | None = None,
        selector: ActionSelector | None = None,
    ) -> None:
        self.gateway = gateway or CompositeActionGateway()
        self.selector = selector or RuleActionSelector()
        self.last_intents: tuple[ActionIntent, ...] = ()
        self.last_receipts: list[str] = []

    def handle(self, event: InboundEvent) -> list[str]:
        intents = tuple(self.selector.select(event))
        self.last_intents = intents
        receipts = [self.gateway.dispatch(i) for i in intents]
        self.last_receipts = receipts
        return receipts
