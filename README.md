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

## 双脑能力与演进

参见 [双脑架构与 P0/P1 上线清单](docs/dual-brain.md)。实时电话走 Omni 原生语音，DeepSeek 是独立文字陪伴客户端；转写共享记忆是可选功能，不会使通话等待 DeepSeek。真实数据适配器、完整 Dashboard API 契约和远端同步仍需后续联调。

## 安全说明

- **密钥只在本机填入，不入库**：phone Token、`SIGNAL_TOKEN`、`DASHSCOPE_API_KEY` 等通过环境变量或本机私有文件（如 `infra/call-bridge/secrets/`，已 gitignore）提供。
- 仓内不含 APK、keystore、录音、真实公网 / Tailscale IP、SSH 端口、设备序列号；文档统一用 `<PUBLIC_IP>`、`<TAILSCALE_IP>`、`<SSH_PORT>`、`<serial>`、`<SIGNAL_TOKEN>` 等占位符。
- debug keystore 构建前自行生成（见 [`infra/call-bridge/README.md`](infra/call-bridge/README.md)）。
- 提交前自扫方法见 [`docs/security.md`](docs/security.md)。
