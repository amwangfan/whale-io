"""泛化插件层：新输入 / 输出点不改核心（微信、桌面、邮件、日程、纪要……）。

- 输入：``PluginEvent``（PerceptionEvent 子类）+ manifest 声明的事件 kind；
- 输出：``PluginAction`` 意图（kind ``plugin_action``）+ ``PluginExecutor``；
- 门控：``PluginActionPolicy`` 按 read_only / communicate / transaction 分级；
- 执行：``PluginGateway``（策略内嵌的单一执行点），可挂进 CompositeActionGateway。

不做自动发现：注册永远显式（宿主组装代码调用 ``PluginRegistry.register``）；
不可信插件用外置进程（QQ OneBot / WeQ 适配器先例），核心不 import 插件业务代码。
"""

from whale_core.plugins.events import PluginEvent
from whale_core.plugins.gateway import PluginGateway
from whale_core.plugins.intents import PluginAction
from whale_core.plugins.manifest import (
    CORE_EVENT_KINDS,
    FORBIDDEN_ACTION_NAMES,
    PluginManifest,
    RiskClass,
)
from whale_core.plugins.policy import PluginActionPolicy, PluginVerdict
from whale_core.plugins.registry import (
    NullPluginExecutor,
    PluginExecutor,
    PluginRegistration,
    PluginRegistry,
)

__all__ = [
    "PluginEvent",
    "PluginAction",
    "PluginManifest",
    "RiskClass",
    "PluginRegistry",
    "PluginRegistration",
    "PluginExecutor",
    "NullPluginExecutor",
    "PluginActionPolicy",
    "PluginVerdict",
    "PluginGateway",
    "CORE_EVENT_KINDS",
    "FORBIDDEN_ACTION_NAMES",
]
