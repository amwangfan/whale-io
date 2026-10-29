"""泛化插件事件：新输入点无需改核心事件类型。

``PluginEvent`` 是 ``PerceptionEvent`` 子类，直接进 ``WhaleAgent.perceive`` /
``ContextReasoner`` 既有链路（未知 kind 只做摘要，不触发核心情境规则——
插件语义由宿主在 router/policy 层消费）。

trust 语义与「紧急不可自升」约定一致：``owner_verified`` 只能由宿主可信路径
（如 OwnerChannelRegistry 配对成功后）设置；核心决策不因 trust 提权。
"""

from __future__ import annotations

from dataclasses import dataclass

from whale_core.perception.events import PerceptionEvent


@dataclass(frozen=True)
class PluginEvent(PerceptionEvent):
    """插件产出的感知事件。

    - ``kind``：manifest 中声明的命名空间 kind（如 ``email.received``）；
    - ``plugin_id``：产出方插件；
    - ``payload``：插件自定义形状（邮件=发件人/主题摘要，日程=标题/时间，
      刷视频=内容元数据，雷鸟纪要=纪要片段文本——**不含原始音频字节**，
      沿用 ambient.speech_segment 的采集边界）；
    - ``trust``：external（默认，不可信）| owner_verified（宿主鉴权后设置）。
    """

    plugin_id: str = ""
    trust: str = "external"

    def __post_init__(self) -> None:
        if not (self.plugin_id or "").strip():
            raise ValueError("PluginEvent requires plugin_id")
        if "." not in (self.kind or ""):
            raise ValueError(f"PluginEvent kind must be namespaced: {self.kind!r}")
        if self.trust not in ("external", "owner_verified"):
            raise ValueError(f"invalid trust level: {self.trust!r}")
