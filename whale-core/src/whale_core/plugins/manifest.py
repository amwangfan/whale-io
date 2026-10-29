"""插件清单：能力声明 + 风险分级。注册时校验，运行期只读。

安全约定（与 shell_policy 同款思路——护栏在核心，隔离在宿主）：

- 事件 kind 必须带命名空间（``a.b`` 形式），且**不得冒充核心事件**
  （核心 kind 与核心命名空间前缀列入拒绝清单）；
- 动作名**禁止包装任意 shell**（shell/exec/run 等保留名一律拒绝）——
  系统 shell 只有 ``cloud.shell_policy`` 分档一个入口；
- 每个动作必须在 ``action_risk`` 声明风险级，未声明的动作在策略层直接拒绝；
- 清单不含任何凭据；插件配置（token 等）只存在宿主机私有配置 / env。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping

PLUGIN_ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{1,63}$")
EVENT_KIND_RE = re.compile(r"^[a-z0-9_]+(?:\.[a-z0-9_]+)+$")
ACTION_NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")

# 核心感知事件与其命名空间：插件不得声明，防止伪造核心语义
CORE_EVENT_KINDS = frozenset(
    {
        "phone.foreground",
        "schedule.tick",
        "location.hint",
        "ambient.speech_segment",
        "context.course",
        "context.weather",
        "context.location",
        "nav.route_progress",
    }
)
CORE_EVENT_PREFIXES = ("phone.", "schedule.", "location.", "ambient.", "context.", "nav.")

# 保留动作名：任意 shell 只能走 cloud.shell_policy 分档，插件不得提供等价物
FORBIDDEN_ACTION_NAMES = frozenset(
    {"shell", "exec", "execute", "system", "command", "run", "spawn", "eval"}
)


class RiskClass(str, Enum):
    """动作风险级（决定门控强度，见 plugins.policy）。"""

    READ_ONLY = "read_only"        # 只读查询，无外部副作用（如搜邮箱、看日程）
    COMMUNICATE = "communicate"    # 对外发送 / 通知（发信息、写邮件、桌面提醒）
    TRANSACTION = "transaction"    # 交易 / 不可逆外部副作用（买东西、下单、支付）


@dataclass(frozen=True)
class PluginManifest:
    """一个插件的能力声明。inputs/动作全部显式枚举，注册时校验。"""

    plugin_id: str
    name: str
    version: str
    author: str = ""
    description: str = ""
    homepage: str = ""
    inputs: tuple[str, ...] = ()  # 命名空间事件 kind，如 "email.received"
    actions: tuple[str, ...] = ()
    action_risk: Mapping[str, str] = field(default_factory=dict)
    process_model: str = "external"  # external（外置进程，推荐）| in_process（owner 自写）

    def __post_init__(self) -> None:
        if not PLUGIN_ID_RE.fullmatch(self.plugin_id or ""):
            raise ValueError(f"invalid plugin_id: {self.plugin_id!r}")
        if not (self.name or "").strip() or not (self.version or "").strip():
            raise ValueError("manifest requires name and version")
        if self.process_model not in ("external", "in_process"):
            raise ValueError(f"invalid process_model: {self.process_model!r}")

        seen_inputs: set[str] = set()
        for kind in self.inputs:
            if not EVENT_KIND_RE.fullmatch(kind or ""):
                raise ValueError(f"invalid event kind {kind!r}: must be namespaced a.b")
            if kind in CORE_EVENT_KINDS or kind.startswith(CORE_EVENT_PREFIXES):
                raise ValueError(f"event kind {kind!r} impersonates a core event")
            if kind in seen_inputs:
                raise ValueError(f"duplicate input kind {kind!r}")
            seen_inputs.add(kind)

        seen_actions: set[str] = set()
        for action in self.actions:
            if not ACTION_NAME_RE.fullmatch(action or ""):
                raise ValueError(f"invalid action name {action!r}")
            if action in FORBIDDEN_ACTION_NAMES:
                raise ValueError(
                    f"action {action!r} is reserved: arbitrary shell must go through "
                    "cloud.shell_policy tiers, never through plugins"
                )
            if action in seen_actions:
                raise ValueError(f"duplicate action {action!r}")
            seen_actions.add(action)

        for action, risk in dict(self.action_risk).items():
            if action not in seen_actions:
                raise ValueError(f"action_risk declares unknown action {action!r}")
            try:
                RiskClass(risk)
            except ValueError:
                raise ValueError(f"invalid risk class {risk!r} for action {action!r}") from None

    def risk_of(self, action: str) -> RiskClass | None:
        """动作风险级；未声明返回 None（策略层将拒绝执行）。"""
        raw = dict(self.action_risk).get(action)
        return RiskClass(raw) if raw is not None else None
