<div align="center">

# whale-io

*身无彩凤双飞翼，心有灵犀一点通*

鲸鱼智能层 × 雷鸟 iO 眼镜 / 手机 / VoIP 来电基础设施

</div>

---

## 概述

**whale-io** 是鲸鱼设备侧的协议与集成仓：定义端云「事件 in / 意图 out」契约，并提供落地到 **雷鸟 iO 眼镜、Android 手机与 VoIP 来电通话** 的桥接组件与集成设计文档。智能层只定义契约，不写设备细节。

配套仓库：

- **whale-core（鲸鱼智能层）**：记忆、感知、打扰策略与行动意图，独立公开仓 <https://github.com/amwangfan/whale-core>。
- **whale-mobile（Android 宿主）**：手机 / VoIP / 音频桥的配置、构建与部署入口 <https://github.com/amwangfan/whale-mobile>。
- **Turbo-IO**：眼镜 PhoneBridge 与云推送上镜的上游项目 <https://github.com/Turbo1123/Turbo-IO>。

## 功能

| 功能 | 说明 | 使用条件 |
|------|------|----------|
| 协议与集成契约 | 端云消息契约、分层架构与集成设计文档 | 与 whale-core 同步演进 |
| VoIP 来电与音频桥 | [`infra/call-bridge/`](infra/call-bridge/)：自管 Telecom + 全屏来电 UI，PCM 音频桥 / 信令（默认 `127.0.0.1:18799`） | 配置、构建与部署见 whale-mobile |
| 前台感知 | [`infra/fg-monitor/`](infra/fg-monitor/)：前台 / 最近应用监测与截图，Root 与非 Root flavor | 配置、构建与部署见 whale-mobile |
| 眼镜推卡 | 云推送上镜的 HTTPS 约定与联调清单见 [`docs/cloud-push.md`](docs/cloud-push.md) | 依 Turbo-IO 上游通道联调 |

## 目录

| 路径 | 内容 |
|------|------|
| [`docs/`](docs/) | 项目概述、分层架构、愿景覆盖对照、操作指导（云推送、APK 安装、音频桥、安全） |
| [`infra/call-bridge/`](infra/call-bridge/) | VoIP 来电 App 与 PCM 音频桥（Java Android + Python） |
| [`infra/fg-monitor/`](infra/fg-monitor/) | 前台监测 + 截图 App（Kotlin · Gradle） |

推荐阅读顺序：[`docs/overview.md`](docs/overview.md) → [`docs/architecture.md`](docs/architecture.md) → [`docs/vision-coverage.md`](docs/vision-coverage.md) → [`whale-core/docs/contracts.md`](https://github.com/amwangfan/whale-core/blob/main/docs/contracts.md)。

## 使用与测试

- 手机 / VoIP / 音频桥：按 [`whale-mobile README`](https://github.com/amwangfan/whale-mobile#readme) 的当前流程配置、构建与部署。
- 智能层：克隆公开仓本地跑测试（不连设备 / 网络 / 密钥）：

```bash
git clone https://github.com/amwangfan/whale-core.git
cd whale-core && uv sync --extra dev && uv run pytest -q
```

本仓安全回归可独立离线运行（不导入真实配置、不连接设备或外部服务）：

```bash
python3 tests/test_ring_hub_security.py -v
```

## 后续计划

- 双脑：真实数据适配器、完整 Dashboard API 契约与远端同步联调（见 [`docs/dual-brain.md`](docs/dual-brain.md)）
- 云端多模型编排与人格维持的落地（见 [`docs/cloud-model-orchestration.md`](docs/cloud-model-orchestration.md)）
- 微信、桌面、邮件、日程等新端按插件架构接入（见 [`docs/plugin-architecture.md`](docs/plugin-architecture.md)）
- 眼镜上镜链路的真机联调

## 安全与文档

- 密钥只在本机填入，不入库：phone Token、`SIGNAL_TOKEN`、`DASHSCOPE_API_KEY` 等通过环境变量或本机私有文件（如 `infra/call-bridge/secrets/`，已 gitignore）提供。
- 仓内不含 APK、keystore、录音、真实公网 / Tailscale IP、SSH 端口、设备序列号；文档统一用 `<PUBLIC_IP>`、`<TAILSCALE_IP>`、`<SSH_PORT>`、`<serial>`、`<SIGNAL_TOKEN>` 等占位符。
- debug keystore 构建前自行生成（见 [`infra/call-bridge/README.md`](infra/call-bridge/README.md)）；提交前自扫方法见 [`docs/security.md`](docs/security.md)。

设计文档：[功能意图规范](docs/companion-intents.md) · [模块组合与闭环](docs/companion-pipelines.md) · [分阶段验收](docs/companion-roadmap.md) · [开源参考](docs/reference-projects.md) · [云端多模型编排](docs/cloud-model-orchestration.md) · [云端运维与 shell 分级](docs/cloud-operations.md) · [角色卡](docs/persona-cards.md) · [插件架构](docs/plugin-architecture.md) · [双脑架构](docs/dual-brain.md)
