# whale-core（鲸鱼智能层）

> 本目录是 whale-io 的 **智能层**：只定义对基础设施的意图接口与事件订阅，**不写设备细节**。  
> 语言：Python 3（便于以后接百炼 / Agent）。包名：`whale_core`。

## 定位

| 层 | 职责 | 本仓？ |
|----|------|--------|
| **Whale Core** | 推理与行动选择（perceive → decide → act） | ✅ |
| **Memory** | 记忆存储协议 | ✅ |
| **Perception** | 感知事件类型 + 适配器协议 | ✅ |
| **Initiative** | 打扰控制 / 提醒策略 | ✅ |
| **Actions** | ShowCard / PlaceCall / Speak 等意图 + Gateway 协议 | ✅ |
| **Whale UI** | 人格、文案、语音风格、HUD 呈现意图 | ✅ |
| **基础设施** | 真正上镜、音频桥、前台监测、固件 | ❌ 在 `../infra/` 或上游 Turbo-IO，本目录不动 |

### 现有四基础设施（本仓不实现、不嵌入）

1. **Turbo-IO / dashboard** — PhoneBridge、云推送上镜、轻量 HUD 执行面  
2. **VoIP 来电音频桥** — 自管来电 UI + PCM 桥（默认 `127.0.0.1:18799`）  
3. **前台监测** — 前台 / 最近应用监测与截图（Root / 非 Root）  
4. **眼镜固件** — **暂停**；除非另有书面说明，不刷固件  

依赖方向：**智能层 → 契约 → 基础设施**。Whale UI 与上镜管道分离：`ui/` 只定人格与呈现意图，真正上镜仍由 Turbo-IO / dashboard 执行。

项目概述：[whale-io 根 README](../README.md) 与 [`../docs/`](../docs/)。

## 目录说明

```
whale-core/
  src/whale_core/
    core/         # 推理主循环与类型
    memory/       # MemoryStore + ContinuityContext
    perception/   # 事件类型；ContextReasoner → SituationBrief
    initiative/   # QuietHours / Disturbance / ProactiveCallPlanner
    actions/      # 意图 + Gateway stub（含 PhoneControl / Composite）
    ui/           # 人格 / 语音风格 / HUD 意图
  tests/          # smoke
  docs/contracts.md
  docs/vision-map.md   # 原设想 → 类型落点
```

契约边界见 [docs/contracts.md](docs/contracts.md)；与用户原设想对照见 [docs/vision-map.md](docs/vision-map.md) 与 [`../docs/vision-coverage.md`](../docs/vision-coverage.md)。


## P0 stub 怎么跑

本地可跑、**不真连**设备 / HTTP / 密钥。覆盖：人格连续性、情境推理、打扰策略。

```bash
cd whale-core   # 在 whale-io 仓根执行
uv sync --extra dev
uv run pytest                 # 全绿
uv run pytest tests/test_p0_stubs.py -q
```

### 演示：「课表 + 安静时段 → 不打扰」

```python
from datetime import datetime, time
from zoneinfo import ZoneInfo
from whale_core.core.agent import WhaleAgent
from whale_core.core.router import StubModelRouter
from whale_core.core.types import Decision, Observation
from whale_core.actions.intents import ShowCard
from whale_core.initiative.policy import DisturbancePolicy, QuietHoursPolicy
from whale_core.memory.store import InMemoryStore, PersonaContinuity
from whale_core.memory.continuity import remember_episode
from whale_core.perception.events import ContextCourse, ContextWeather, ContextLocation

TZ = ZoneInfo("Asia/Shanghai")

class CardRouter(StubModelRouter):
    def route(self, observation: Observation) -> Decision:
        sit = (observation.context or {}).get("situation") or {}
        return Decision(
            intents=(ShowCard(title="提醒", body=sit.get("headline", "")).to_intent(),),
            rationale="demo",
        )

class NightAgent(WhaleAgent):
    def perceive(self, events):
        obs = super().perceive(events)
        ctx = dict(obs.context)
        ctx["now"] = datetime(2026, 10, 28, 23, 15, tzinfo=TZ)  # 静默
        return Observation(
            event_ids=obs.event_ids, summary=obs.summary,
            context=ctx, observed_at=obs.observed_at,
        )

store = InMemoryStore()
store.put_persona_continuity(PersonaContinuity(persona_name="鲸鱼", tone="简洁"))
remember_episode(store, summary="昨晚确认了明早高数", key="ep:course")

agent = NightAgent(
    router=CardRouter(),
    memory=store,
    policy=DisturbancePolicy(
        quiet=QuietHoursPolicy(start=time(22, 0), end=time(7, 0), tz=TZ),
    ),
)
decision, receipts = agent.step([
    ContextCourse(course_title="高数", location_label="教一-301",
                  starts_at=datetime(2026, 10, 29, 8, 0, tzinfo=TZ)),
    ContextWeather(condition="晴", temp_c_range="12-20"),
    ContextLocation(place_label="宿舍", coarse_area="校园"),
])
assert decision.suppress and not decision.intents and receipts == []
print(agent.last_situation().headline)       # 含课表/天气/位置
print(agent.last_continuity().persona_summary)
```

关键类型：`ContinuityContext`、`ContextReasoner`、`SituationBrief`、`DisturbancePolicy`、`QuietHoursPolicy`、`PersonaContinuity`。


## P1 stub 怎么跑

本地可跑、**不真连**设备 / HTTP / ADB / 麦克风。覆盖：主动呼叫提议、手机控制回执、环境语音进情境。

```bash
cd whale-core   # 在 whale-io 仓根执行
uv sync --extra dev
uv run pytest                 # 全绿（P0 + P1）
uv run pytest tests/test_p1_stubs.py -q
# 单条演示：允许主动呼叫 vs 静默拦截
uv run pytest tests/test_p1_stubs.py::test_proactive_call_allow_non_quiet_place_call_bridge_receipt -q
uv run pytest tests/test_p1_stubs.py::test_proactive_call_quiet_without_urgent_no_place_call -q
```

### 演示：「允许主动呼叫 vs 静默拦截」

```python
from datetime import datetime, time
from zoneinfo import ZoneInfo
from whale_core.core.agent import WhaleAgent
from whale_core.core.router import StubModelRouter
from whale_core.core.types import Decision, Observation
from whale_core.actions.gateway import CompositeActionGateway, LocalBridgeGateway
from whale_core.initiative.call_planner import ProactiveCallPlanner
from whale_core.initiative.policy import AlwaysAllowPolicy, ProactiveCallPolicy, QuietHoursPolicy
from whale_core.memory.store import InMemoryStore
from whale_core.perception.events import ScheduleTick

TZ = ZoneInfo("Asia/Shanghai")

class EmptyRouter(StubModelRouter):
    def route(self, observation: Observation) -> Decision:
        return Decision(intents=(), rationale="demo")

class CtxAgent(WhaleAgent):
    def __init__(self, *a, now=None, extra=None, **k):
        super().__init__(*a, **k)
        self._now, self._extra = now, dict(extra or {})
    def perceive(self, events):
        obs = super().perceive(events)
        ctx = dict(obs.context); ctx["now"] = self._now; ctx.update(self._extra)
        return Observation(event_ids=obs.event_ids, summary=obs.summary,
                           context=ctx, observed_at=obs.observed_at)

quiet_never = QuietHoursPolicy(start=time(3, 0), end=time(3, 0), tz=TZ)
day = CtxAgent(
    router=EmptyRouter(), policy=AlwaysAllowPolicy(),
    call_policy=ProactiveCallPolicy(quiet=quiet_never),
    call_planner=ProactiveCallPlanner(),
    gateway=CompositeActionGateway(bridge=LocalBridgeGateway()),
    memory=InMemoryStore(),
    now=datetime(2026, 10, 28, 14, 0, tzinfo=TZ),
    extra={"allow_proactive_call": True},
)
d, r = day.step([ScheduleTick(title="午间")])
assert any(i.kind == "place_call" for i in d.intents)
assert "127.0.0.1" in r[0] and "18799" in r[0]

quiet = QuietHoursPolicy(start=time(22, 0), end=time(7, 0), tz=TZ)
night = CtxAgent(
    router=EmptyRouter(), policy=AlwaysAllowPolicy(),
    call_policy=ProactiveCallPolicy(quiet=quiet),
    call_planner=ProactiveCallPlanner(),
    gateway=LocalBridgeGateway(), memory=InMemoryStore(),
    now=datetime(2026, 10, 28, 23, 0, tzinfo=TZ),
    extra={"allow_proactive_call": True},
)
d2, r2 = night.step([ScheduleTick(title="夜")])
assert all(i.kind != "place_call" for i in d2.intents) and r2 == []
```

关键类型：`ProactiveCallPlanner`、`PhoneControlGateway`、`CompositeActionGateway`、`AmbientEventTags` / `extract_ambient_events`；`SituationBrief.ambient_note`。

## Companion Life（可测试的新能力，非生产渠道）

新增 `memory/curation.py` 和 `life/{state,discovery,initiative,channels,runtime}.py`，将“鱼的记忆、内部状态、主动搭话、B站生活、多端接入”转化为独立协议与保守执行门槛。完整意图见 [功能规范](../docs/companion-intents.md)，组合调用与依赖见 [运行闭环](../docs/companion-pipelines.md)，真实可用性以 [验收表](../docs/companion-roadmap.md) 为准。

```bash
cd whale-core
python -m pytest tests/test_life_memory.py tests/test_life_pipelines.py -q
python examples/life_scenario.py
```

- `MemoryCurator` 的长期记忆必须显式确认，带 `evidence_id` 并可删除；`WhaleCompanion(..., curator=...)` 才使用此安全检索路径。
- `InnerStateStore` 只是角色表达、兴趣与未尽话题元数据；不代表 AI 真实感情。
- `DiscoveryPlanner` 不访问 B站或其他网站，只将经过外部适配器提供的可信 URL/标题转换成候选。
- `ProactiveOutbox` 保守默认关闭、按渠道授权和审批后才调用注入的发送器；`accepted` 只表示渠道接受，不等于用户已读。
- `OwnerChannelRegistry` 只验证显式绑定，QQ/OneBot 的网络接入与账号认证尚未实现。

## 安全

- 禁止写入真实公网 / Tailscale IP、Token、密钥、本机用户路径。  
- 域名示例可用：`https://rayneo.wangfanlab.com/turbo`  
- 桥默认：`127.0.0.1:18799`  
- 详见 [`../docs/security.md`](../docs/security.md)。

## 开发与测试

```bash
cd whale-core   # 在 whale-io 仓根执行
uv sync --extra dev   # 或: pip install -e ".[dev]"
uv run pytest         # 或: python -m pytest
```

当前阶段：契约 + **P0/P1 可跑 stub**（连续性 / 情境 / 打扰 / 主动呼叫 / 手机控制 / 环境语音）；仍不真连设备。
