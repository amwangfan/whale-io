# 与基础设施的边界契约

智能层（本仓）**只**通过意图与事件订阅与基础设施交互。基础设施侧保留全部设备 / 网络实现。

## 依赖方向

```text
Whale Core / Memory / Perception / Initiative / Actions / Whale UI
        │
        ▼  契约（事件 in / 意图 out）
Turbo-IO·dashboard · 假来电音频桥 · 前台监测 · （固件暂停）· 未来控制桥 / 采集
```

禁止反向依赖：基础设施模块不得 import `whale_core` 业务决策（可共享纯 DTO 若另抽，当前未抽）。

原设想覆盖对照见 [vision-map.md](vision-map.md) 与 [`../../docs/vision-coverage.md`](../../docs/vision-coverage.md)。

## 入站：感知事件（基础设施 → 智能层）

| 事件 | 语义 | 典型来源（基础设施） | 智能层不做 |
|------|------|----------------------|------------|
| `phone.foreground` | 前台应用变化 | 前台监测仓 | ADB / Accessibility / 截图 |
| `schedule.tick` | 日程到期 | 本机提醒 / 日历推送 | 厂商日历 SDK |
| `location.hint` | 粗粒度地点标签 | 可选位置源 | 精确坐标入库 |
| `ambient.speech_segment` | 环境语音片段**元数据** | 未来采集 / ASR | 原始音频字节入库、麦克风 API |
| `context.course` | 课程表条目 | 日历 / 课表源 | 厂商日历写回 |
| `context.weather` | 天气粗标签 | 天气源 | 供应商 Key / 账号 |
| `context.location` | 粗位置标签（校园等） | 位置源 | **勿存精确坐标** |
| `nav.route_progress` | 导航进度（供提醒） | 导航集成 | 地图 SDK 嵌入 |

适配器协议：`PerceptionAdapter.poll() → Iterator[PerceptionEvent]`。

## 出站：行动意图（智能层 → 基础设施）

| 意图 kind | 语义 | 落地侧 | 智能层不做 |
|-----------|------|--------|------------|
| `show_card` | HUD 卡片 | Turbo-IO / dashboard HTTPS | 像素渲染、approve、蓝牙 |
| `place_call` | 假来电会话 | 假来电 App + 音频桥 | TCP PCM、厂商通话 API |
| `speak` | 语音播报 | TTS / 通话桥 | API Key、供应商账号 |
| `phone_action` | 打开应用 / 通知等 | **未来手机控制桥** | ADB / Accessibility |
| `navigate_hint` | 路线提醒卡片 | dashboard / HUD | 导航引擎 |
| `ambient_listen` | 启停环境听写（仅意图） | 未来采集侧 | 麦克风权限与录音 |

Gateway 协议：`ActionGateway.dispatch(ActionIntent) → str`。

- `HttpDashboardGateway` 默认 base：`https://rayneo.wangfanlab.com/turbo`（stub，不发 HTTP）  
- `LocalBridgeGateway` 默认：`127.0.0.1:18799`（stub，不建 TCP）  
- `PhoneControlGateway`：回执 `stub-phone-control:{action}:{target}`（不启 ADB / 子进程）  
- `AmbientListenGateway`：回执 `stub-ambient-listen:on|off`（不碰麦克风）  
- `CompositeActionGateway`：按 `intent.kind` 路由到上述子网关

## Memory

| 类型 | 语义 |
|------|------|
| `MemoryKind.episode` | 情节记忆 |
| `MemoryKind.preference` | 用户偏好 |
| `MemoryKind.persona_continuity` | 人格连续性 |
| `MemoryRecord` | 统一记录事件形状 |
| `PersonaContinuity` | 人格快照读写 stub（经 MemoryStore） |
| `ContinuityContext` | 组装人格摘要 + 近期 episode/preference，供 decide |


## 情境推理（Perception stub）

| 类型 | 语义 |
|------|------|
| `ContextReasoner` | 规则/模板：course/weather/location/**ambient** → `SituationBrief`（非 LLM） |
| `SituationBrief` | 结构化情境摘要（含 `ambient_note`），写入 Observation.context["situation"] |
| `extract_ambient_events` / `AmbientEventTags` | 环境语音元数据标签；**不**存音频字节 |

## Initiative

| 策略 | 语义 |
|------|------|
| `QuietHoursPolicy` | 静默时段抑制（保留） |
| `DisturbancePolicy` | 提醒 vs 不打扰：quiet + 前台忙碌；`urgent` 可放行 |
| `ProactiveCallPolicy` | 何时允许主动 `place_call`（静默拦；urgent / allow / elevated+suggest 放行） |
| `ProactiveCallPlanner` | 规则提议 `PlaceCall`，再经 `ProactiveCallPolicy` 过滤 |
| `PhoneActionPlanner` | 规则提议 `PhoneAction`（监测 ≠ 控制） |

## Session / UI

| 类型 | 语义 |
|------|------|
| `SessionState` | 多设备会话：`device_id` + `conversation_id` + `last_turn`；agent 可读 |
| `WhaleLook` | 形象 / 像素画 / 惊喜 HUD **设计意图**（非像素资源） |

## Whale UI vs 上镜管道

| Whale UI（本仓 `ui/`） | 上镜管道（Turbo-IO / dashboard） |
|------------------------|----------------------------------|
| 人格、禁忌、语气 | PhoneBridge / CardTransport |
| 语音风格偏好（无 Key） | 实际 TTS / 通话音频 |
| HUD 文案与 layout_hint | 真实卡片 approve 与眼镜显示 |
| WhaleLook 形象字段 | 选图 / 动画 / 像素渲染 |

## 版本与演进

1. 先稳定本文件与 Python 类型。  
2. 再在基础设施仓加「契约消费者」适配（可选独立 DTO 包）。  
3. 真连网关仅在本地配置注入，**永不**把 Token / 真实 IP 写入本仓。
