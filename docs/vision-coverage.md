# 原设想 vs 草稿覆盖（权威对照）

> 来源：用户给出的「原本功能设想 / 草稿覆盖 / 判断」对照表。  
> 原则：智能层（whale-core）只定契约与 stub；基础设施仓执行设备细节。  
> 配套：[`whale-core/docs/vision-map.md`](../whale-core/docs/vision-map.md)（类型落点）。

## 优先级约定

| 优先级 | 含义 | 本表条目 |
|--------|------|----------|
| **P0** | 核心缺口：记忆、情境推理、打扰策略 | 长期记忆与人格连续性；课程表/位置/天气推理；主动决策提醒 vs 不打扰 |
| **P1** | 下一刀：主动呼叫、手机控制意图、环境语音事件 | AI 主动打电话；对手机执行操作；全天环境语音感知 |
| **P2** | 体验与多端：导航、会话、形象渲染 | 智能导航与路线提醒；多设备会话连续性；鲸鱼形象/像素画/惊喜 HUD |

已覆盖或仅差策略升格的条目不单独占 P 级，但表中仍写「下一刀」。

> **进度（本地）**：whale-core P0 tip 已可跑；**P1 stub 已本地可跑**（主动呼叫 / 手机控制 / 环境语音），已随 **whale-io** 首次提交入库。

---

## 完整对照

| 原设想 | 覆盖 | 判断 | 优先级 | 落在哪一层 | 现有 stub / 文档 | 下一刀 |
|--------|------|------|--------|------------|------------------|--------|
| 云端主动推送到眼镜 | 已描述 | 核心链路已设计 | — | 基础：Turbo-IO / dashboard；智能：`ShowCard` 意图 | [cloud-push.md](cloud-push.md)、[overview.md](overview.md)；`whale_core.actions.ShowCard` | 联调 HTTPS stub → 真机时再注入本机 Token（不入库） |
| AI 主动打电话 | 部分 | 有音频桥；智能层 **P1 stub 本地可跑** | **P1** | 基础：假来电 + 音频桥；智能：`PlaceCall` + `ProactiveCallPlanner` + `ProactiveCallPolicy` | [voip-bridge.md](voip-bridge.md)；whale-core P1 stub | 真连桥侧仍只收意图（[`infra/call-bridge/`](../infra/call-bridge/)） |
| 感知手机当前使用状态 | 部分 | 有监测模块 | — | 基础：前台监测；智能：`phone.foreground` | `PhoneForeground`；[`infra/fg-monitor/`](../infra/fg-monitor/) | 事件字段稳定后接适配器；忙态供 `DisturbancePolicy` |
| 全天环境语音感知 | 部分 | 智能层 **P1 stub 本地可跑**（ambient→情境） | **P1** | 基础：未来采集/ASR；智能：`AmbientSpeechSegment` + `ambient_note` + `AmbientListen` | `extract_ambient_events`；**不含原始音频字节入库** | 本地采集适配器（另仓）；本仓不碰麦克风 |
| 长期记忆与人格连续性 | 未描述 | 核心缺口 | **P0** | 智能：`memory/` | `MemoryStore` + **`MemoryRecord`**（episode / preference / persona_continuity）+ **`PersonaContinuity`**（新） | 区分三类记忆读写；人格快照跨会话 |
| 结合课程表、位置、天气的推理 | 未描述 | 核心缺口 | **P0** | 智能：感知事件 + Core 情境折叠；基础：日历/天气/粗位置源 | `context.course` / `context.weather` / `context.location`（新）；已有 `location.hint` | Observation.context 聚合；路由侧情境推理 stub |
| 主动决策：提醒还是不打扰 | 未描述 | 核心缺口 | **P0** | 智能：`initiative/` | 已有 `QuietHoursPolicy`；升格 **`DisturbancePolicy`**（前台忙碌 + 静默时段）（新） | 综合忙态与 quiet hours；紧急覆盖规则仍不得绕过鉴权 |
| 对手机执行操作 | 部分 | 智能层 **P1 stub 本地可跑**（PhoneControlGateway） | **P1** | 智能：`PhoneAction` + `PhoneControlGateway`；基础：未来手机控制桥 | whale-core P1 stub；监测 ≠ 控制 | 真控制桥另仓；**不**在智能层写 ADB |
| 鲸鱼形象、像素画、惊喜 HUD | 基础 | 缺形象与渲染设计 | **P2** | 智能：`ui/` 设计意图；基础：上镜管道渲染 | `WhalePersona` / `HudPresentation`；新 **`WhaleLook`**（形象/像素/惊喜字段，非像素资源） | 设计字段 → 基础设施选图/动画；本仓不放位图 |
| 智能导航与实时路线提醒 | 未描述 | 需位置和导航集成 | **P2** | 智能：`nav.route_progress` + `NavigateHint`；基础：导航/位置 | 新事件与意图 stub | 进度事件驱动提醒卡片；粗位置标签，勿存精确坐标 |
| 多设备会话连续性 | 未描述 | 需统一会话状态 | **P2** | 智能：`core.SessionState`；agent 可读 | **`SessionState`**（device_id + conversation_id + last_turn）（新） | 会话键跨眼镜/手机；存储仍走 MemoryStore |

---

## 分层速记

```text
原设想功能
  → 智能层契约（事件 in / 意图 out / 记忆与策略）  ← 本对照驱动 whale-core 扩展
  → 基础设施执行（Turbo-IO · 音频桥 · 监测 · 未来控制桥 / 采集）
```

## 相关文档

- [architecture.md](architecture.md) — 分层与依赖方向  
- [security.md](security.md) — 禁止 Token / 真实 IP 入库  
- [`whale-core/docs/contracts.md`](../whale-core/docs/contracts.md) — 类型级契约  
- [`whale-core/docs/vision-map.md`](../whale-core/docs/vision-map.md) — 本表 → 类型落点  
