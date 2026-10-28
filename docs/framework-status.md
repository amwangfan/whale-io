# 三层框架状态（可执行）

眼镜硬件未到 → **上镜联调 / 刷机暂停**。本批只推云端 + VoIP。

## 对照

| 层 | 职责 | 状态 | 代码 / 文档 |
|----|------|------|-------------|
| **1 云端** | 数据处理、大模型宿主机、收信息、shell 控制、消息推送 | **可推进** | `whale-core/`（行动闭环 stub）、`docs/cloud-push.md` |
| **2 手机** | 雷鸟 iO + VoIP 来电；推上眼镜 + 来电聊天（ASR→文本→DS→TTS 或原生 realtime） | **可推进（VoIP）**；上镜等硬件 | `infra/call-bridge/`、`docs/voip-bridge.md` |
| **3 眼镜** | 通知、互动、提示 | **阻塞**（硬件未到） | 固件 / 上镜联调暂停 |

## 已完成（相对可跑）

- whale-core：P0/P1/Life stub、perceive→decide→act、Actions 网关、双脑记忆契约
- 云端行动闭环 stub：入站事件 → 选行动 → 推送卡片 / shell 白名单意图（mock HTTP，密钥仅 env）
- call-bridge：Omni realtime 路径 + `BRIDGE_TOOLS` 白名单（见 `infra/call-bridge/audio-bridge/README.md` 与下文 docs）
- 文案统一为「VoIP 来电」（非「假来电」用户向文案）

## 阻塞

- **眼镜硬件未到**：卡片真上镜、镜侧互动、刷机一律不做
- 公网桥部署：无既有流程则不新开公网暴露

## 下一步

### P0（本周可做）

1. 云端：把行动闭环 stub 接到真实入站适配（QQ/OneBot 等仍外置），mock HTTP → 可选真实 HTTPS writer（env Token）
2. VoIP：本机 / USB reverse 跑通 `BRIDGE_MODE=omni`；需要工具时 `BRIDGE_TOOLS=1`
3. 手机雷鸟 iO：保持云推送轮询契约；无眼镜时只验 approve/receipt，不声称镜片必显

### P1（硬件到了再开）

1. 眼镜：通知 / HUD 卡片真显、互动提示
2. 端到端：云端卡片意图 → dashboard → 手机 → 上镜
3. 固件：仅在有书面说明后刷机

## 一句话提醒

三层能推的一起推；**不能推的（眼镜）明确停**，别用假硬件叙事填进度。
