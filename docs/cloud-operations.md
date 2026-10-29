# 云端运维：端云交互、后台占用与 shell 权限分级

> **目标**：云端方案（[cloud-model-orchestration.md](cloud-model-orchestration.md)）落地后的运维层设计——1) 优化端侧（手机 / 桥 / 眼镜链路）与云端的交互；2) 压低端云宿主机常驻占用；3) 给云端**分级的** shell 权限：默认只放行白名单工具，safe 档只加网页搜索类只读工具，personal 档面向完全个人使用放开**非高危**命令。
>
> 状态标注沿用仓内约定：**[已测试]** = CI 覆盖；**[已有代码]** = 可用未联调；**[规划]** = 未实现。宿主机示例一律占位符（`<WORKSPACE>`、`<PRIVATE_ENV_FILE>`），密钥只走 env（[security.md](security.md)）。

## 1. 云端后台占用：盘点与收敛

### 1.1 现状盘点

| 组件 | 形态 | 常驻成本 | 状态 |
|------|------|----------|------|
| audio-bridge（18799） | 阻塞 accept，通话期每连接一线程 | 低（空闲 ≈ 一个 Python 进程 RSS） | [已有代码] |
| ring_hub 信令（18800） | 独立常驻进程 | 低，但**多一个进程** | [已有代码] |
| 记忆转写线程 | 桥内有界队列 worker | 忽略不计（已有背压丢弃） | [已测试] |
| CloudActionLoop / Outbox | 库形态，无自带进程 | 取决于宿主进程 | [已测试] |
| QQ 适配器（OneBot 外置 / 官方 API） | 计划独立进程 | 中（Node/Python 常驻） | [规划] |
| 模型 | **全部出站 API**（R/C/T/J 四档） | 零本地常驻 | 方案已定 |

### 1.2 收敛原则

1. **单常驻进程** [规划，P1]：bridge + ring_hub + CloudActionLoop + Outbox tick 并入一个 supervisor 进程（线程隔离，音频线程优先）；QQ 适配器若必须独立进程（OneBot 生态），也同机部署、由同一 supervisor 拉起看管。目标：**云宿主机常驻 ≤ 2 个自有进程**。
2. **按需拉起（socket activation）** [规划，P1]：无眼镜/无通话的日常时段，桥可由 systemd `.socket` 单元托管，首个连接到达才启动服务进程，空闲 TTL 自动退出——常驻占用趋近于零。
3. **本地零模型常驻**：自托管 ASR / 嵌入（FunASR、BGE-M3，见编排方案 2.4）如启用，一律 on-demand 单元 + 用完即停，不与桥同进程。
4. **定时器驱动，不驻留忙循环**：Outbox 60 s tick、备份 cron / Litestream sidecar、周报每周一次；禁止任何 `while True: sleep(0.1)` 式轮询进云端代码。
5. **资源硬上限 + 观测**：每个自有 unit 配 `MemoryMax` / `CPUQuota` / `TasksMax`（起点建议 512M / 50% / 64，实测调整）；周报附宿主机 RSS 峰值、CPU 分钟、通话分钟、轮询次数——占用要可度量才可收敛。

```ini
# 示例：/etc/systemd/system/whale-bridge.service（占位符，非真实部署值）
[Service]
User=whale                     # 非 root 专用用户
WorkingDirectory=<WORKSPACE>
EnvironmentFile=<PRIVATE_ENV_FILE>
MemoryMax=512M
CPUQuota=50%
TasksMax=64
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=<WORKSPACE>
PrivateTmp=yes
```

## 2. 端云交互优化

安全模型不变：**手机只出站**（HTTPS 轮询 + 信令），云端不向手机开入站；PCM 旁路（18799）延迟优先，不并入本优化。

### 2.1 下行（云 → 端）

| 措施 | 说明 | 状态 |
|------|------|------|
| 条件轮询 | 卡片/指令队列带版本号，端侧 `If-None-Match` → 无变化回 **304 空 body**，省流量省云端序列化 | [规划，P1] |
| 轮询档位 | 活跃/亮屏 30–60 s；闲置 300 s；深夜（对齐 quiet hours）600 s；全部 ±20% 抖动防同频 | [规划，P1] |
| 紧急旁路 | 需要立刻响铃/上镜只走既有信令通道（ring_hub 18800），**不靠拉高轮询频率** | [已有代码] |
| digest 聚合 | 低 importance 卡片合并早晚各一次（编排方案 5.2），减少轮询载荷与打扰 | [规划，P1] |

### 2.2 上行（端 → 云）

| 措施 | 说明 | 状态 |
|------|------|------|
| 事件批处理 | 前台变化 5 s 防抖合并；常规摘要 15 min 周期上报；来电/审批/反馈等关键事件即时上报 | [规划，P1] |
| 幂等键 | 事件带 `event_id`、消息带 `message_id`（契约已有），云端按键去重——重试不产生重复记忆/台账 | [已测试]（契约）|
| 弱网重放 | 端侧 append-only 缓存队列，恢复后重放；云端按幂等键吸收重复 | [规划，P1] |
| 转写不占上行 | 通话转写在云侧桥内异步入库，不经手机回传 | [已测试] |

### 2.3 会话与记忆

阶段 A（单宿主机）同库直连、无同步流量；阶段 B REST API 用 **seq cursor 增量拉取**，端侧只读缓存（见编排方案 3.2）。轮询档位与增量 cursor 共用同一版本序列，避免两套一致性机制。[规划，P2]

## 3. shell 权限分级（本次已落地策略层）

### 3.1 档位

env `WHALE_SHELL_TIER`，缺省与未知取值一律回落 **managed**（最保守）。实现在 [`whale_core/cloud/shell_policy.py`](../whale-core/src/whale_core/cloud/shell_policy.py)，闭环接入 [`cloud/loop.py`](../whale-core/src/whale_core/cloud/loop.py)。[已测试]

| 档 | 能力 | 适用 |
|----|------|------|
| **managed**（默认） | 仅固定白名单工具：`what_time` / `bridge_status` / `ring_fake_call` / `list_workspace` / `tail_bridge_log` / `restart_bridge`（与 call-bridge `BRIDGE_TOOLS` 对齐）；**无自由命令** | 任何部署形态的安全底线 |
| **safe** | managed + 只读检索工具：`web_search` / `web_fetch` / `weather_now` / `calendar_today`；**仍无自由命令** | 「只允许网页搜索之类」的日常档 |
| **personal** | safe + **非高危自由命令**（`cmd:` 前缀），仅认证 owner 触发 | 完全个人使用的单 owner 宿主机 |

**不存在更高档**：没有「无限制自由命令」；高危清单对 personal 档同样硬拒绝。

### 3.2 高危硬拒绝（约 30 类模式，全档生效）

破坏性文件系统（`rm -rf /`、`mkfs`、`dd of=/dev/*`）· 电源/init（`shutdown`、`reboot`、`kill 1`）· fork bomb · 提权（`sudo`/`su`/`chmod 777 /`）· 凭据材料（`/etc/shadow`、`~/.ssh`、`authorized_keys`、`printenv`、`$*API_KEY*` 变量展开）· 远程代码管道执行（`curl | sh`）· 容器/命名空间引擎（`docker`、`kubectl`、`nsenter`——等价 root）· 自身服务与审计破坏（`systemctl stop/disable`、`crontab -r`、`history -c`、journal vacuum）· 防火墙削弱（`iptables -F`、`ufw disable`）· shell rc 持久化写入 · git 不可逆远程操作（force push / delete）。清单是代码内 canonical（`HIGH_RISK_PATTERNS`），测试逐条覆盖。[已测试]

### 3.3 前置条件与审计

- **身份门先行**：自由命令仅当入站事件带 `meta.owner_authenticated=True`（来源为 `Incoming.transport_authenticated` + `OwnerChannelRegistry` 显式配对，见编排方案 7.1）；群聊/未配对用户在任何档位都不触发执行。[已测试]
- **紧急不可自升**：事件文本中的紧急标记不能提升 shell 档位（沿用 dual-brain 约定）。
- **逐条台账**：`ShellPolicy.audit()` 记录 subject（截断 2000 字）、tier、verdict、risk、source、时间——**不记命令输出**（输出可能含密钥）；回给 owner 的执行结果须经脱敏（`bridge_tools._redact` 同款手法）。[已测试]
- **闭环可观测**：被拒命令回一张 `shell_denied` 卡片（含拒绝原因与当前档位），不发静默丢弃。[已测试]

### 3.4 诚实边界：护栏 ≠ 沙箱

字符串拒绝清单**可被绕过**（编码、变量拼接、自写脚本间接执行）。personal 档的成立前提是「**单一受信 owner + 已认证渠道**」，此时拒绝清单的作用是防手滑与防提示注入后的顺手破坏，而不是对抗恶意 owner。真正的隔离边界必须在宿主机执行器：

1. 非 root 专用用户 + `NoNewPrivileges`；
2. systemd 沙箱：`ProtectSystem=strict`、`ReadWritePaths` 限工作区、`PrivateTmp`、`ProtectHome`、`RestrictSUIDSGID`；
3. 独立 cgroup + 超时强杀（`bridge_tools` 的 10 s 超时先例沿用）；
4. 执行器不读入含密钥的 env 子集（最小化凭据面）。

**策略层 [已测试]，执行器 [规划，P1]**——两层必须一起部署，缺执行器沙箱不得开 personal 档。

### 3.5 执行侧契约

智能层只产意图：`shell_whitelist`（executor `bridge_tools_whitelist`，落地在 call-bridge）与 `shell_command`（executor `cloud_host_sandbox`，落地在云宿主机执行器）；`MockHttpActionGateway` 拟 POST `/v1/actions/shell_command`，不真连。[已测试] 真实执行器对齐 `bridge_tools` 的 argv 风格：白名单工具**永不拼接模型文本进 `bash -c`**；personal 自由命令必然经 shell 解释，因此只能进沙箱执行。[规划，P1]

## 4. 分阶段与验收

### P0（本 PR 已含 / 本周可做）

| # | 事项 | 验收 | 状态 |
|---|------|------|------|
| 1 | 分档策略 + `cmd:` 意图闭环 + 高危清单测试 | 111 tests 全绿；默认档拒绝一切自由命令且有台账/拒绝卡 | [已测试] |
| 2 | 云宿主机部署缺省 `WHALE_SHELL_TIER=managed`；升 safe/personal 需显式改 env | 部署清单写明；personal 仅单 owner 宿主机 | [规划] |

### P1（联调期）

1. cloud_host_sandbox 执行器 + systemd 沙箱单元模板（3.4 的 1–4 全部启用）；
2. 单进程 supervisor 整合（bridge + ring_hub + loop + outbox），或 socket activation；
3. 条件轮询（ETag/304）+ 轮询档位 + 上行批处理/重放队列；
4. safe 档检索工具的真实适配器（web_search 外置执行，密钥 env）。

### P2

1. 阶段 B 记忆 API 的 seq cursor 增量与轮询档位共用版本序列；
2. 占用周报（RSS/CPU/轮询/通话分钟）自动进 owner 周报。

**验收口径**：默认部署下自由命令拒绝率 100% 且可见；`rm -rf /` 类在任何档位、任何配置组合下被拒；personal 档命令 100% 有台账（无输出内容）；云端自有常驻进程 ≤ 2、空闲 RSS 合计 < 512 MB（目标值，实测修正）；通话链路延迟不因进程整合上升。

## 5. 相关文档

[cloud-model-orchestration.md](cloud-model-orchestration.md)（模型编排与记忆/人格/频控） · [dual-brain.md](dual-brain.md)（延迟红线） · [framework-status.md](framework-status.md)（三层状态） · [security.md](security.md)（密钥与占位符） · [voip-bridge.md](voip-bridge.md) 与 [audio-bridge README](../infra/call-bridge/audio-bridge/README.md)（BRIDGE_TOOLS 白名单实现） · [contracts.md](../whale-core/docs/contracts.md)（意图契约）
