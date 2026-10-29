"""泛化插件动作意图（kind ``plugin_action``）：新输出点无需改核心意图目录。

风险级**不由意图自declare**——``PluginGateway`` 执行时从注册表 manifest 解析，
防止伪造意图提权。``is_owner`` / ``explicitly_approved`` 与
``Incoming.meta.owner_authenticated`` 同一信任模型：只能由宿主可信路径
（OwnerChannelRegistry 配对 / ProactiveOutbox 人审）注入，不从外部原文读取。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping

from whale_core.core.types import ActionIntent


@dataclass(frozen=True)
class PluginAction:
    """声明由某插件执行一个动作（发信息 / 写邮件 / 买东西 / 桌面通知……）。"""

    plugin_id: str
    action: str
    target: str = ""  # 语义目标：收件人 / 商品 id / 应用名等，由插件解释
    args: Mapping[str, Any] = field(default_factory=dict)
    is_owner: bool = False
    explicitly_approved: bool = False
    priority: int = 0

    def to_intent(self) -> ActionIntent:
        return ActionIntent(
            kind="plugin_action",
            payload={
                "plugin_id": self.plugin_id,
                "action": self.action,
                "target": self.target[:500],
                "args": dict(self.args),
                "is_owner": self.is_owner,
                "explicitly_approved": self.explicitly_approved,
                "executor": f"plugin:{self.plugin_id}",
            },
            priority=self.priority,
            reason="plugin.action",
        )
