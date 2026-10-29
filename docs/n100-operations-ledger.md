# N100 运维交接账本 — whale-core × WeQ 插件化部署（2026-10-30 凌晨立账）

> 读者：N100 上的执行 agent（冷启动可读）。前置部署过程见 `deploy-n100-handoff.md`
> （任务 A–F 已全部完成并通过端到端验收）。本账本回答三件事：**现在是什么版本、
> 两边怎么拆、各自怎么更新**。
>
> 纪律（全篇适用）：secrets 永不回显不进日志；weq-web 只绑 127.0.0.1；机器共享，
> 不动别人的 unit；风控信号（安全中心提示/连续发送失败）→ `systemctl stop qq-bot` 并报告。

## 1. 版本账本（当前锚点，2026-10-30 01:56 端到端验收通过）

| 组件 | 锚点 | 备注 |
| --- | --- | --- |
| whale-core 代码 | `amwangfan/whale-io` main @ `ae0b5d6` | 含插件层 `1e013e2`、加固 `b7f5c58`（#12–#15）、QQ/WeQ channel 插件 `ae0b5d6`（PR #18） |
| N100 测试基线 | **274 passed**（`python3 -m pytest tests/ -q`） | 任何更新后必须复跑 |
| weq-web 运行件 | 官方 2.3.2 linux-x64 包 + whaleio overlay | overlay 构建自 `whaleio/bot-engine` 旧尖（`efd27883` 时代）；分支现已重基到 `cf8051eb`（dev@93dd2e53 + 分桶落盘 + 桌面剥离 + 免 Go 打包链），**功能等价，不急重建**（差异=上游 tasks 特性与测试打磨，不触及生产路径） |
| WeQ 上游 | v2.3.2（dev@`93dd2e53`，含已合并的 #118/#119 性能 PR） | 上游无新 release 前不动 |
| nt_helper | 锚定 `nt-helper-20261027-c6df5e3`，**2026-11-26 过期** | 过期后完整模式（attach/发送）失效，离线收不受影响；Windows 侧会话已挂 11-19 提醒 |
| QQ Linux | 3.2.34-53644（官方 deb，sha512 已验） | AUR 跟踪的最新版；升级前必须先确认 WeQ 兼容（见 §5） |
| node | /opt/node-v24.21.0（weq-web 专用） | 系统 node 20 太旧，勿混用 |
| 生产验证 | 01:56:46 收 → 01:56:49 `plugin:qq_weq:send_message:ok:accepted`（3 秒全链路，静默时段被动豁免生效） | inbox `succeeded=1 failed=0` |

## 2. 拆分边界：whale-core = 核心，WeQ = 功能插件

架构依据：`/opt/whale/docs/plugin-architecture.md`（§1.1 channel 类、§5 外置进程档）。
**两者是独立进程、独立升级周期，唯一耦合是三个接口契约点。**

### 2.1 归属表

| | whale-core（核心/宿主） | WeQ（channel 插件/外置进程） |
| --- | --- | --- |
| 职责 | 智能层全部：配对身份门、记忆/情节、DeepSeek 调用、草稿/审批账本、风控预算、插件注册表与网关 | QQ 协议侧全部：本地库解密读取、SSE 消息推送、tRPC 发送（完整模式 attach）、Web UI（扫码/账号管理） |
| 进程 | `qq-bot.service`（python3，512M–2G） | `weq-web.service`（node 24，MemoryMax 2G）+ `qq.service`/`xvfb-qq.service`（QQ 客户端，2G） |
| 代码 | `/opt/whale/whale-core/`（git 快照） | `/opt/whale/weq-web/`（官方包+overlay，无 git） |
| 数据 | `/opt/whale/state/qq_workflow.sqlite3`（记忆+账本）、`qq_inbox.sqlite3`（持久化收件队列） | `/opt/whale/weq-data/`（**config.json 在此**：ssePush/provider/autoAttachQq；换包不丢）、`/opt/whale/weq-exports/` |
| 密钥 | `/opt/whale/secrets/qq_workflow.env` | `/opt/whale/secrets/weq-web.env`（WEQ_TOKEN 固定，不随重启轮换） |
| 插件注册件 | `life/qq_weq_plugin.py`：manifest（`qq.message` 入站 + `send_message`=communicate）、adapter、executor、网关工厂 | ——（WeQ 不自知是插件；适配全在核心侧） |

### 2.2 三个接口契约点（两侧唯一需要对齐的东西）

1. **入站 SSE**：weq-web 推 `POST http://127.0.0.1:18797/whale`，`Authorization: Bearer <WEQ_SSE_TOKEN>`，payload `{events:[…]}`，至少一次投递（runner 侧 msgId 幂等 + inbox 持久化）。
   两侧对齐项：weq-web `config.json → settings.ssePush`（pushUrl+accessToken）↔ `qq_workflow.env → WEQ_SSE_TOKEN`。
2. **出站发送**：runner → `POST /_auth/login {token}` 取 cookie → `POST /trpc/account.sendElements`（superjson `{"json":{peerType:"c2c",targetId:<OWNER_UID>,elements:[{kind:"text",textContent:…}]}}`）。
   两侧对齐项：`weq-web.env → WEQ_TOKEN` ↔ `qq_workflow.env → WEQ_WEB_TOKEN`（必须一致）；`WEQ_WEB_URL=http://127.0.0.1:7690`。
   前置：weq-web 完整模式（autoAttachQq=true）+ QQ 在线，否则发送被拒 → 账本 unknown（不自动重试，语义正确）。
3. **插件契约**（核心内部，WeQ 无感知）：`qq_weq` manifest 声明的 kind/风险级由 PluginGateway 强制；身份/审批只查宿主台账，意图 payload 自declare无效。改契约 = 改 whale-core 代码 + 测试，与 WeQ 无关。

**推论**：whale-core 更新永远不需要动 weq-web；weq-web 更新只要三个契约点不变就无需动 whale-core。

## 3. 更新 SOP A — whale-core（核心）

当前无待拉更新（N100 已 = main@`ae0b5d6`，274 绿）。未来更新流程：

1. **拉取**（二选一）：
   - 临时通道（现状）：请 Windows 侧会话 scp `src/ examples/ tests/ pyproject.toml` 到 `/opt/whale/whale-core/`；
   - 直连通道（建议尽早建立，一次性）：给 N100 配 whale-io 仓的**只读 deploy key**
     （GitHub → amwangfan/whale-io → Settings → Deploy keys），之后
     `cd /opt/whale && git clone git@github.com:amwangfan/whale-io.git repo && rsync -a repo/whale-core/ /opt/whale/whale-core/`（或直接把仓库克隆为 /opt/whale/whale-core 并 `git pull`）。
2. **门禁**：`cd /opt/whale/whale-core && find . -name __pycache__ -type d -exec rm -rf {} + ; python3 -m pytest tests/ -q` → 必须全绿（基线 274，只许变多不许变红）。
3. **重启**：`systemctl restart qq-bot` → `journalctl -u qq-bot -n 15` 核对横幅五件套：`[配对]`、`[策略]`、`[出站] 地址校验通过`、`[插件] qq_weq channel 已注册`、`[inbox] …收回过期租约`。
4. **回归**：请用户主账号发一条测试消息 → 日志应现 `← [owner]` → `[AI 草稿]` → `[插件网关] plugin:qq_weq:send_message:ok:accepted`。
5. **失败回退**：代码是纯文件覆盖——保留上一份快照（建议每次更新前 `cp -a /opt/whale/whale-core /opt/whale/.whale-core.bak-$(date +%s)`），回退 = 换回目录 + restart。state/inbox/secrets 均在代码目录之外，更新永不触碰。

## 4. 更新 SOP B — weq-web（插件本体）

两种触发场景：

**B1. whaleio 分支代码变更（自建 overlay 更新）**——下次维护窗口建议做一次，顺便获得 N100 本地重建能力：
```bash
# 一次性准备（约 1.5GB 磁盘 + 15 分钟）
cd /opt/whale && git clone -b whaleio/bot-engine https://github.com/amwangfan/WeQ.git weq-src
cd weq-src && corepack pnpm install --frozen-lockfile --ignore-scripts
corepack pnpm native:fetch --no-authorization   # 只为本机平台取 native（web build 需要）
# 每次重建：
git pull && corepack pnpm --filter @weq/web build
# 覆盖运行件（保留官方 native/ node_modules/ package.json / start.sh）：
cd /opt/whale/weq-web
rm -rf public resources/bot-runtime
cp -a /opt/whale/weq-src/apps/web/dist/public public
cp /opt/whale/weq-src/apps/web/dist/server.mjs /opt/whale/weq-src/apps/web/dist/*Worker.mjs .
cp -a /opt/whale/weq-src/resources/bot-runtime resources/
systemctl restart weq-web
```
验证：登录 → `GET /trpc/bootstrap.getAgentLabCatalog` 应返回 `['agentscope','openai-compatible']`（补丁指纹）；`GET /trpc/bootstrap.getSettings` 确认 ssePush/provider 配置仍在（config 在 weq-data，换件不丢）。

**B2. WeQ 上游发新 release（含 nt_helper 到期刷新，2026-11-26 前必做一次）**：
1. 下载新官方 `weq-web-<ver>-linux-x64.tar.gz`，解到新目录；
2. `weq-src` 切到新 tag 对应基线并重放 whaleio 补丁（`git fetch && git rebase --onto <新tag> <旧dev锚> whaleio/bot-engine`，冲突面：catalog.ts / remote runtime / bot stores——都有测试兜底）；
3. 按 B1 构建 overlay 覆盖到新官方包；
4. `WEQ_DATA_DIR` 不动（config/日志/账号状态全部延续），停旧起新；
5. 验证三件套：catalog 指纹、`getSettings` 配置延续、runner 回归消息；
6. **token 语义**：WEQ_TOKEN 是 env 固定的（不轮换）；若上游行为有变导致令牌失效，更新 `weq-web.env` 后同步改 `qq_workflow.env` 的 `WEQ_WEB_TOKEN`（两处必须一致）并 `systemctl restart qq-bot`。

## 5. 更新 SOP C — QQ Linux（谨慎档）

QQ 升级会更换 `major.node`（subAppId 锚）与本地库结构，**必须先确认 WeQ/nt_helper 兼容再动**：
1. 查 WeQ 上游 release notes / issues 是否声明支持目标 QQ 版本；
2. 备份：`cp -a ~/.config/QQ /opt/whale/backups/QQ-$(date +%F)`（账号目录，含登录态）；
3. 下载新 deb → sha512 校验 → `apt install ./linuxqq.deb` → `systemctl restart qq`；
4. 验证：weq-web UI 账号仍在线、attach/harvest 日志正常、跑一条回归消息；
5. 异常回退：降级装回 3.2.34-53644 deb（/opt/whale/downloads/linuxqq.deb 留着别删）+ 恢复账号目录备份。

## 6. 日常运维速查

| 事项 | 命令 |
| --- | --- |
| 服务矩阵 | `systemctl is-active weq-web xvfb-qq qq qq-bot`（四个全 active 才健康） |
| runner 日志 | `journalctl -u qq-bot -n 50 --no-pager` |
| 收件队列健康 | `cd /opt/whale/whale-core && python3 examples/qq_live_workflow.py --secrets … --store … --inbox … jobs`（failed>0 时 `jobs --requeue-failed` 人工恢复） |
| 草稿/账本 | 同上换 `show` 子命令 |
| weq-web 日志 | `/opt/whale/weq-data/logs/`（按日） |
| 内存水位 | `systemctl status` 各 unit 的 Memory 行；总预算 5G（QQ 2G + weq-web 2G + runner 512M） |
| 停机窗口 | 只停 `qq-bot` 不影响收消息落库（QQ+weq-web 还在写库）；重启后 inbox `recover_stale` 自动续处理，SSE 停机期间的消息以 weq-web 库监听语义为准（上线后新消息必达，窗口期旧消息可能不补推——停机尽量短） |

## 7. 立账时的待办与远程依赖

- [ ] **进行中（另一会话）**：whale-core 从 whale-io monorepo 拆分为独立**公开**仓库——
  落地后本账本 §3 SOP A 的拉取源改为新公开仓（N100 侧动作不变：git pull/scp → pytest 274 →
  restart → 回归）；拆分方需完成敏感标识清洗（真实 QQ 号/uid 不出现在公开代码、测试夹具与
  **git 历史**中——新仓必须从全新首提交开始，不继承 whale-io 历史）；
- [ ] （建议，非紧急）B1 完整走一遍：N100 本地获得 overlay 重建能力，摆脱对 Windows 侧的构建依赖；
- [ ] （建议）给 N100 配 whale-core 新仓的只读 deploy key，SOP A 改走 git pull；
- [ ] **2026-11-26 前必做**：SOP B2（nt_helper 到期；Windows 侧会话已挂 11-19 提醒，N100 侧也可自建 weekly timer 双保险）；
- [ ] 东京云（aliyun-tokyo）角色 = 门面/哨兵，**永不部署 QQ 协议侧**；待用户关停 ouruestc 释放内存后，另行下达看门狗+异地备份任务书（与本账本无关，勿抢跑）；
- [ ] Windows 侧已退役（runner 停止、WeQ 桌面关闭），仅保留为回退路径与 whaleio 构建机——N100 不需要维护它。
