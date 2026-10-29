"""插件网关：kind ``plugin_action`` 的单一执行点（策略内嵌，不可绕过）。

回执约定（可观测闭环，与 shell_denied 同款——拒绝不静默）：

- denied        → ``plugin-denied:{plugin}:{action}:{reason}``
- needs_approval→ ``plugin-hold:{plugin}:{action}``（宿主据此走人审 / Outbox 草稿）
- ready         → ``plugin:{plugin}:{action}:ok|fail:{receipt}``

executor 抛异常按 fail 处理，不炸派发循环；非 plugin_action 意图原样忽略
（组合网关里可与其它子网关并存）。
"""

from __future__ import annotations

from typing import Any, Mapping

from whale_core.core.types import ActionIntent
from whale_core.plugins.policy import PluginActionPolicy, PluginVerdict
from whale_core.plugins.registry import NullPluginExecutor, PluginRegistry


class PluginGateway:
    def __init__(self, registry: PluginRegistry, policy: PluginActionPolicy) -> None:
        self.registry = registry
        self.policy = policy
        self.last_verdict: PluginVerdict | None = None
        self.dispatch_log: list[str] = []

    def dispatch(self, intent: ActionIntent) -> str:
        if intent.kind != "plugin_action":
            return f"plugin-gateway:ignored:{intent.kind}"
        payload: Mapping[str, Any] = intent.payload or {}
        plugin_id = str(payload.get("plugin_id") or "")
        action = str(payload.get("action") or "")
        target = str(payload.get("target") or "")
        args = dict(payload.get("args") or {})
        verdict = self.policy.evaluate(
            plugin_id,
            action,
            is_owner=bool(payload.get("is_owner")),
            explicitly_approved=bool(payload.get("explicitly_approved")),
            args=args,
        )
        self.last_verdict = verdict
        if verdict.status == "denied":
            receipt = f"plugin-denied:{plugin_id}:{action}:{verdict.reason}"
            self.dispatch_log.append(receipt)
            return receipt
        if verdict.status == "needs_approval":
            receipt = f"plugin-hold:{plugin_id}:{action}"
            self.dispatch_log.append(receipt)
            return receipt

        executor = self.registry.executor(plugin_id) or NullPluginExecutor()
        try:
            ok, text = executor.execute(action, target, args)
        except Exception:
            ok, text = False, "executor error"
        receipt = f"plugin:{plugin_id}:{action}:{'ok' if ok else 'fail'}:{str(text)[:120]}"
        self.dispatch_log.append(receipt)
        return receipt
