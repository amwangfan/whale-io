"""QQ 入站 → AI 工作流（记忆召回 + DeepSeek 回复草稿 + 审批闸门）。

与 life/initiative 的保守默认对齐：
- 策略前置闸门：prefs 未启用或 direct_message 渠道未授权时**不调用 AI**（省 token，
  也不产生草稿）；静默时段/每日额度等仍由 ProactiveOutbox.inspect 在草稿后裁决。
- 回复只进 ProactiveOutbox 成为待审草稿（direct_message 渠道强制逐条人工批准），
  本模块**不发送任何消息**。真实发送是 Phase 2（NapCat 小号或 WeQ 受控发送接口，
  见 docs/research-weq-deepseek-qq.md §6）。
- AI 走 core.companion.WhaleCompanion：人格连续性上下文 + MemoryCurator 召回 +
  DeepSeek 生成 + 情节记忆回写，一条入站消息触发完整闭环。
- 同一 message_id 的草稿幂等（draft_id=qq-reply:<msgId>；监听层另有 msgId 去重）。
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Callable

from whale_core.core.companion import TextCompanion, WhaleCompanion
from whale_core.life.channels import OwnerEvent
from whale_core.life.initiative import ProactiveDraft, ProactiveOutbox, ProactivePrefs
from whale_core.memory.curation import MemoryCurator
from whale_core.memory.store import MemoryStore

REPLY_CHANNEL = "direct_message"


class QqInboundWorkflow:
    """OwnerEvent → 回复草稿 + 审批裁决。单实例串行使用（监听层已保证按序派发）。"""

    def __init__(self, store: MemoryStore, brain: TextCompanion, *,
                 prefs: ProactivePrefs | None = None,
                 now_fn: Callable[[], datetime] | None = None) -> None:
        self.store = store
        self.companion = WhaleCompanion(store, brain, curator=MemoryCurator(store))
        self.outbox = ProactiveOutbox(store)
        self.prefs = prefs if prefs is not None else ProactivePrefs(
            enabled=True, allowed_channels=frozenset({REPLY_CHANNEL}), daily_limit=20)
        self._now = now_fn or (lambda: datetime.now(timezone.utc))

    def handle_owner_event(self, event: OwnerEvent) -> dict:
        """处理一条已配对的所有者消息，返回结构化结果（不含发送行为）。

        返回键：
        - skipped: 策略闸门未过（disabled/channel_denied），未调用 AI；
        - reply / draft_id / proposed / verdict / reason: 正常路径；
        - empty_reply: AI 返回空文本，不产生草稿。
        """
        # 前置闸门（便宜的先做）：整体开关与渠道授权。
        if not self.prefs.enabled:
            return {"skipped": "disabled"}
        if REPLY_CHANNEL not in self.prefs.allowed_channels:
            return {"skipped": "channel_denied"}

        text = event.text.strip()
        if not text:
            return {"skipped": "empty_text"}

        reply = self.companion.chat(text)
        if not reply or not reply.strip():
            return {"empty_reply": True, "draft_id": None}

        draft_id = f"qq-reply:{event.message_id}"
        draft = ProactiveDraft(
            draft_id=draft_id, text=reply,
            reason="QQ 入站消息的回复草稿（owner 已配对）",
            evidence=(f"qq:{event.conversation}:{event.message_id}",),
            channel=REPLY_CHANNEL,
        )
        proposed = self.outbox.propose(draft)
        verdict = self.outbox.inspect(draft_id, self.prefs, now=self._now())
        return {
            "draft_id": draft_id,
            "reply": reply,
            "proposed": proposed,
            "verdict": verdict.status,
            "reason": verdict.reason,
        }
