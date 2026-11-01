# 三层框架状态（可执行）

> 2026-11-01：**眼镜硬件已到**（雷鸟 iO 真机在手），P1 上镜联调解禁。
> 2026-11-01 晚核查（Windows 侧会话，实测）：
> - **dashboard-service 其实已部署且在东京**（不在 N100）：`https://rayneo.wangfanlab.com/turbo/health`
>   → 200 `{"ok":true,"deviceConnected":false,"mode":"api-only"}`；Cloudflare → 东京 nginx
>   （rayneo.wangfanlab.conf）→ turbo-dashboard:18796。`deviceConnected:false` = 等 vc258 实验宿主接入。
> - **手机侧就位**：Magic8 Pro（BKQ-AN10 / Android 17）ADB 在线；官方宿主 `com.rayneo.venus.pub`
>   v1.0.10 运行中并向外提供 fe35/fe36 GATT 服务；眼镜蓝牙已连（用户确认 + GATT 活跃）。
>   验机测试通知已发送（`cmd notification post`），镜片显示待用户目视确认。
> - **新异常：东京 sshd 不可达**（tailscale 100.100.154.46:22 拒连；tailscaled/nginx/dashboard
>   均正常）——管理通道丢失，需阿里云控制台恢复；恢复前东京侧只做 HTTP 面操作。
> 旧记录（已过时）：~~N100 上 dashboard-service 未部署~~（核查对象错了机器，见上）。刷固件仍暂停。

## 对照

| 层 | 职责 | 状态 | 代码 / 文档 |
|----|------|------|-------------|
| **1 云端** | 数据处理、大模型宿主机、收信息、shell 控制、消息推送 | **可推进**；dashboard 待部署 | `whale-core/`（行动闭环 stub）、`docs/cloud-push.md` |
| **2 手机** | 雷鸟 iO + VoIP 来电；推上眼镜 + 来电聊天（ASR→文本→DS→TTS 或原生 realtime） | **可推进** | `infra/call-bridge/`、`docs/voip-bridge.md` |
| **3 眼镜** | 通知、互动、提示 | **可推进（2026-11-01 硬件到位）**；刷固件仍暂停 | 上镜联调按 P1 清单 |

## 已完成（相对可跑）

- whale-core：P0/P1/Life stub、perceive→decide→act、Actions 网关、双脑记忆契约
- 云端行动闭环 stub：入站事件 → 选行动 → 推送卡片 / shell 白名单意图（mock HTTP，密钥仅 env）
- 云端 shell 权限分级：managed（白名单工具）/ safe（+网页搜索类只读）/ personal（仅认证 owner 的非高危自由命令）+ 高危硬拒绝清单 + `cmd:` 意图闭环与拒绝卡（`whale_core.cloud.shell_policy`，测试全绿；执行器沙箱仍 [规划]）
- call-bridge：Omni realtime 路径 + `BRIDGE_TOOLS` 白名单（见 `infra/call-bridge/audio-bridge/README.md` 与下文 docs）
- 文案统一为「VoIP 来电」（非「假来电」用户向文案）

## 阻塞

- **vc258 实验宿主 APK 不在本机**（在远端另一台机器，待取回）——`deviceConnected:true` 与"卡片真显上镜"的唯一硬阻塞；取回后 `adb install` + 填 dashboard 地址与 phone Token（候选：`~/.ssh/phone-fanwang_io.token`）即可接通轮询契约
- **东京 sshd 拒连**（2026-11-01 晚发现）：dashboard/nginx 服务面正常但无法登录运维；需控制台（VNC/救援）恢复 sshd，恢复前不安排东京侧变更
- ~~dashboard-service 未部署~~ → **已证伪**：部署在东京且健康（见顶部 2026-11-01 晚核查）
- 公网桥部署：无既有流程则不新开公网暴露

## 下一步

### P0（本周可做）

1. 云端：把行动闭环 stub 接到真实入站适配（QQ/OneBot 等仍外置），mock HTTP → 可选真实 HTTPS writer（env Token）
2. VoIP：本机 / USB reverse 跑通 `BRIDGE_MODE=omni`；需要工具时 `BRIDGE_TOOLS=1`
3. 手机雷鸟 iO：保持云推送轮询契约；无眼镜时只验 approve/receipt，不声称镜片必显
4. 云端运维：部署缺省 `WHALE_SHELL_TIER=managed`；执行器沙箱单元、进程整合与条件轮询按 [cloud-operations.md](cloud-operations.md) P1 推进

完整方案与 P0 验收表见 [cloud-model-orchestration.md](cloud-model-orchestration.md)（模型四档路由、记忆单写者同步、聊天记录备份/恢复、频控反馈调权、语义三道门）；运维与 shell 分级见 [cloud-operations.md](cloud-operations.md)。

### P1（硬件已到，2026-11-01 起进行中）

1. 眼镜：官方宿主配对验机 → vc258 实验宿主 → 通知 / HUD 卡片真显、互动提示
2. 端到端：云端卡片意图 → dashboard → 手机 → 上镜
3. 固件：仅在有书面说明后刷机

## 一句话提醒

三层能推的一起推；**不能推的（眼镜）明确停**，别用假硬件叙事填进度。
