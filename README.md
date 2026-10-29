<div align="center">

<h1>whale-io</h1>

<h3><i>海内存知己，天涯若比邻</i></h3>

<sub>鲸鱼智能层 × 雷鸟 iO 眼镜 / 手机 / VoIP 来电基础设施</sub>

</div>

---

**whale-io** 是一个 monorepo：上层是 **鲸鱼智能层（whale-core）**——记忆、感知、打扰策略与行动意图；下层是让它落地到 **雷鸟 iO 眼镜、Magic8 手机与 VoIP 来电通话** 的基础设施。智能层只定义「事件 in / 意图 out」契约，不写设备细节。

## 目录导航

| 路径 | 内容 | 语言 / 构建 |
|------|------|-------------|
| [`docs/`](docs/) | 项目概述、分层架构、原设想覆盖对照、操作指导（云推送、APK 安装、音频桥、安全） | Markdown |
| [`whale-core/`](whale-core/) | 智能层 Python 包 `whale_core`：Core / Memory / Perception / Initiative / Actions / UI，P0+P1 可跑 stub | Python 3.11+ · uv |
| [`infra/call-bridge/`](infra/call-bridge/) | VoIP 来电 App（自管 Telecom + 全屏来电 UI）与 PCM 音频桥 / 信令（默认 `127.0.0.1:18799`） | Java（Android）+ Python |
| [`infra/fg-monitor/`](infra/fg-monitor/) | 前台 / 最近应用监测 + 截图 App（Root 与非 Root flavor） | Kotlin · Gradle |

推荐阅读顺序：[`docs/overview.md`](docs/overview.md) → [`docs/architecture.md`](docs/architecture.md) → [`docs/vision-coverage.md`](docs/vision-coverage.md) → [`whale-core/docs/contracts.md`](whale-core/docs/contracts.md)。

### 不在本仓

- **Turbo-IO**（PhoneBridge、云推送上镜、轻量 HUD）：上游 <https://github.com/Turbo1123/Turbo-IO>。本地对它的改动另行管理，不并入本仓。
- **眼镜固件**：暂停；无书面说明不刷。

## 快速开始

```bash
# 智能层：本地跑测试（不连设备 / 网络 / 密钥）
cd whale-core
uv sync --extra dev
uv run pytest -q

# 音频桥（开发机）+ 手机 USB 反向转发
cd infra/call-bridge
./audio-bridge/start.sh
adb reverse tcp:18799 tcp:18799

# 前台监测 App（需自备 JDK 17 与 Android SDK）
cd infra/fg-monitor
JAVA_HOME=<JDK17_PATH> ANDROID_HOME=<ANDROID_SDK_PATH> ./scripts/build.sh
```

云推送上镜的 HTTPS 约定与联调清单见 [`docs/cloud-push.md`](docs/cloud-push.md)（域名示例 `https://rayneo.wangfanlab.com/turbo`）。

## 鲸鱼的产品意图与生效链路（从这里开始）

- **[功能意图规范](docs/companion-intents.md)**：12 项能力分别解决什么体验问题、怎样输入/输出、哪些不该自动执行。
- **[模块组合与真实闭环](docs/companion-pipelines.md)**：双脑 → 记忆 → 内部状态 → 候选 → 授权/不打扰 → 眼镜/电话/QQ；附演示与回执定义。
- **[状态与分阶段验收](docs/companion-roadmap.md)**：区分代码已测试、设备未联调与未来目标，避免将计划说成已实现。
- **[开源参考与借鉴边界](docs/reference-projects.md)**：MaiBot、AstrBot、DSH 鱼的记忆/主动搭话/鱼的心情/B站生活、小九九等。
- **[云端多模型编排方案](docs/cloud-model-orchestration.md)**：电话/QQ/眼镜各端的模型路由（realtime/ASR/TTS/文本）、记忆同步与聊天记录备份、人格维持、通知频控与语义边界。
- **[云端运维与 shell 分级](docs/cloud-operations.md)**：端云交互优化（条件轮询/批量上行/幂等重放）、后台占用收敛（单常驻进程/按需拉起/资源上限）、shell 权限 managed/safe/personal 三档与高危硬拒绝。
- **[鲸鱼角色卡](docs/persona-cards.md)**：内置 7 张可选可切换人格卡（群友/温柔/妈妈/妹妹/游戏/迷你等流派，参考社区角色卡合集），支持导入社区卡；只改表达层，全局禁忌与权限策略不可被卡覆盖。
- **[插件架构](docs/plugin-architecture.md)**：泛化智能层输入/输出点——清单 + 注册表 + 风险门控（read_only / communicate / transaction）；微信、桌面、邮件、日程、纪要、购物等新接入不改核心，插件禁止包装任意 shell。
- **[可运行的无网络演示](whale-core/examples/life_scenario.py)**：用户兴趣 → 内容候选 → 默认禁止 → 单次批准 → 模拟渠道受理。

**默认边界**：**主动功能全部允许**——主动联系、主动打电话、主动消息、代用户操作 QQ/B站，经账号/设备级授权后即可开启，不再是产品禁区；采集类保持「不擅自」（录音/截图/环境听写需显式授权）；**唯一硬性隔离：系统 shell 类高危能力**——必须分档裁决 + 宿主机沙箱，高危模式任何档位硬拒绝（见 [docs/cloud-operations.md](docs/cloud-operations.md)）。小型代码原型与真实接入不是一回事。

## 双脑能力与演进

参见 [双脑架构与 P0/P1 上线清单](docs/dual-brain.md)。实时电话走 Omni 原生语音，DeepSeek 是独立文字陪伴客户端；转写共享记忆是可选功能，不会使通话等待 DeepSeek。真实数据适配器、完整 Dashboard API 契约和远端同步仍需后续联调。

## 安全说明

- **密钥只在本机填入，不入库**：phone Token、`SIGNAL_TOKEN`、`DASHSCOPE_API_KEY` 等通过环境变量或本机私有文件（如 `infra/call-bridge/secrets/`，已 gitignore）提供。
- 仓内不含 APK、keystore、录音、真实公网 / Tailscale IP、SSH 端口、设备序列号；文档统一用 `<PUBLIC_IP>`、`<TAILSCALE_IP>`、`<SSH_PORT>`、`<serial>`、`<SIGNAL_TOKEN>` 等占位符。
- debug keystore 构建前自行生成（见 [`infra/call-bridge/README.md`](infra/call-bridge/README.md)）。
- 提交前自扫方法见 [`docs/security.md`](docs/security.md)。
