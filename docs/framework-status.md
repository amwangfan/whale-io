# 三层框架状态（可执行）

> **入口边界**：手机 / VoIP / 音频桥唯一维护与部署入口为 [whale-mobile](https://github.com/amwangfan/whale-mobile)；本仓 `infra/` 是归档兼容副本，以下历史观测不等于当前部署保证。
>
> 2026-11-01：**眼镜硬件已到**（雷鸟 iO 真机在手），P1 上镜联调解禁。
> 2026-11-01 晚核查（Windows 侧会话，实测）：
> - **dashboard-service 其实已部署且在东京**（不在 N100）：`https://rayneo.wangfanlab.com/turbo/health`
>   → 200 `{"ok":true,"deviceConnected":false,"mode":"api-only"}`；Cloudflare → 东京 nginx
>   （rayneo.wangfanlab.conf）→ turbo-dashboard:18796。`deviceConnected:false` = 等 vc258 实验宿主接入。
> - **手机侧就位**：Magic8 Pro（BKQ-AN10 / Android 17）ADB 在线；官方宿主 `com.rayneo.venus.pub`
>   v1.0.10 运行中并向外提供 fe35/fe36 GATT 服务；眼镜蓝牙已连（用户确认 + GATT 活跃）。
>   **验机通过（19:12，用户目视双确认）**：`cmd notification post` 的测试通知同时出现在
>   镜片与手机通知栏——官方宿主对 shell 来源通知也全量镜像（无白名单限制），
>   意味着「adb → 通知 → venus → 镜片」可作本地演示/降级显示的兜底通路
>   （云端链路仍需 vc258 轮询 dashboard，见阻塞）。
> - **新异常：东京 sshd 不可达**（tailscale <TAILSCALE_IP>:<SSH_PORT> 拒连；tailscaled/nginx/dashboard
>   均正常）——管理通道丢失，需阿里云控制台恢复；恢复前东京侧只做 HTTP 面操作。
> 旧记录（已过时）：~~N100 上 dashboard-service 未部署~~（核查对象错了机器，见上）。刷固件仍暂停。

## 对照

| 层 | 职责 | 状态 | 代码 / 文档 |
|----|------|------|-------------|
| **1 云端** | 数据处理、大模型宿主机、收信息、shell 控制、消息推送 | **可推进**；dashboard 已在东京部署（2026-11-01 观测），端到端待验收 | [whale-core](https://github.com/amwangfan/whale-core)（行动闭环 stub）、[cloud-push.md](cloud-push.md) |
| **2 手机** | 雷鸟 iO + VoIP 来电；推上眼镜 + 来电聊天（ASR→文本→DS→TTS 或原生 realtime） | **可推进**；维护部署走 mobile | [whale-mobile](https://github.com/amwangfan/whale-mobile)、[voip-bridge.md](voip-bridge.md)（历史方案） |
| **3 眼镜** | 通知、互动、提示 | **可推进（2026-11-01 硬件到位）**；刷固件仍暂停 | 上镜联调按 P1 清单 |

## 已完成（相对可跑）

- whale-core：P0/P1/Life stub、perceive→decide→act、Actions 网关、双脑记忆契约
- 云端行动闭环 stub：入站事件 → 选行动 → 推送卡片 / shell 白名单意图（mock HTTP，密钥仅 env）
- 云端 shell 权限分级：managed（白名单工具）/ safe（+网页搜索类只读）/ personal（仅认证 owner 的非高危自由命令）+ 高危硬拒绝清单 + `cmd:` 意图闭环与拒绝卡（`whale_core.cloud.shell_policy`，测试全绿；执行器沙箱仍 [规划]）
- call-bridge 历史副本：Omni realtime 路径 + `BRIDGE_TOOLS` 白名单（[归档说明](../infra/call-bridge/README.md)）；持续维护与部署请转 whale-mobile
- 文案统一为「VoIP 来电」（非「假来电」用户向文案）

## 阻塞

- **vc258 实验宿主 APK 不在本机**（历史记录为远端另一台机器，待取回）——这是当时已知阻塞之一，并非唯一阻塞或安装后必通的保证。按 whale-mobile 当前流程取回/构建与安装，配置 dashboard 地址及本机私有 phone Token 文件 `<PHONE_TOKEN_FILE>` 后，仍须验证认证与轮询契约、`deviceConnected:true`、手机/眼镜连接、卡片真显及回执；任一步失败都需单独排查。
- **东京 sshd 拒连**（2026-11-01 晚发现）：dashboard/nginx 服务面正常但无法登录运维；需控制台（VNC/救援）恢复 sshd，恢复前不安排东京侧变更
- ~~dashboard-service 未部署~~ → **已证伪**：部署在东京且健康（见顶部 2026-11-01 晚核查）
- 公网桥部署：无既有流程则不新开公网暴露

## 下一步

### P0（本周可做）

1. 云端：把行动闭环 stub 接到真实入站适配（QQ/OneBot 等仍外置），mock HTTP → 可选真实 HTTPS writer（env Token）
2. VoIP：按 [whale-mobile](https://github.com/amwangfan/whale-mobile) 当前入口，本机 / USB reverse 跑通 `BRIDGE_MODE=omni`；需要工具时 `BRIDGE_TOOLS=1`
3. 手机雷鸟 iO：保持云推送轮询契约；无眼镜时只验 approve/receipt，不声称镜片必显
4. 云端运维：部署缺省 `WHALE_SHELL_TIER=managed`；执行器沙箱单元、进程整合与条件轮询按 [cloud-operations.md](cloud-operations.md) P1 推进

完整方案与 P0 验收表见 [cloud-model-orchestration.md](cloud-model-orchestration.md)（模型四档路由、记忆单写者同步、聊天记录备份/恢复、频控反馈调权、语义三道门）；运维与 shell 分级见 [cloud-operations.md](cloud-operations.md)。

### P1（硬件已到，2026-11-01 起进行中）

1. 眼镜：官方宿主配对验机 **✅ 2026-11-01 通过（镜片+通知栏双确认）** → vc258 实验宿主（待取回）→ 通知 / HUD 卡片真显、互动提示
2. 端到端：云端卡片意图 → dashboard → 手机 → 上镜
3. 固件：仅在有书面说明后刷机

## 一句话提醒

三层按已验证条件推进；眼镜硬件已到且本地通知镜像已验，云端卡片到镜片的端到端链路仍待验收；刷固件继续暂停，别把计划、历史健康检查或安装 APK 写成闭环完成。
