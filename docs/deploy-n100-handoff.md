# N100 部署交接书 — whale-io QQ 全栈（weq-web 补丁版 + QQ Linux + runner）

> 交给 N100 上的执行 agent。目标：在学校 N100（Debian 13 / 4C / 16G / root）上把
> 「QQ 小号 → weq-web → whale-core runner → DeepSeek → 自动回复实发」全链路跑通。
> Windows 端（现有运行系统）由另一侧会话管理，**本机任务不要碰 Windows**。

## 0. 背景一句话

whale-io 的 QQ 陪伴管道已在 Windows 上全链路验证（收：WeQ SSE 推送；脑：DeepSeek
via AgentScope；发：WeQ Web tRPC `account.sendElements`；审批账本全走通）。现迁移到
N100 长期运行。weq-web 用的是**官方 2.3.2 linux-x64 发布包 + whaleio 补丁覆盖层**
（自构建 server.mjs/public/bot.mjs，官方 native/node_modules），补丁内容：LLM 厂商
目录收敛为 AgentScope 专用（已冒烟验证）、bot 引擎分桶落盘（性能优化）、Tailscale
熔断（web 独立版本身无此组件，覆盖层保持一致性）。

## 1. 工作区地图（现状，均已就绪，勿重复做）

| 路径 | 内容 |
| --- | --- |
| `/opt/whale/weq-web/` | weq-web 2.3.2 补丁版（server.mjs 11,640,938B；resources/bot-runtime/bot.mjs 462,143B；public/ 362 文件；native/ 与 node_modules/ 为官方 linux-x64 原件） |
| `/etc/systemd/system/weq-web.service` | **active**：`/opt/node-v24.21.0/bin/node server.mjs`，绑 `127.0.0.1:7690`（勿改监听范围），`WEQ_DATA_DIR=/opt/whale/weq-data`，MemoryMax=2G |
| `/opt/whale/secrets/weq-web.env` | `WEQ_TOKEN=<固定令牌>`（600；登录 weq-web 用它，**任何输出/日志不得回显**） |
| `/opt/whale/secrets/qq_workflow.env` | runner 密钥（600）：DEEPSEEK_API_KEY/BASE_URL/MODEL、OWNER_QQ=3563425362（主账号）、QQ_SELF_UIN=3899713055（小号=本机 QQ）、OWNER_UID、PASSIVE_DAILY_LIMIT=500（被动回复保险丝；主动 DAILY_LIMIT 默认 10）、QQ_LIVE_AUTOSEND=1（无人值守真实发送双开关之一，配 `--live-auto-send`，#12）等。**其中 WEQ_SSE_TOKEN 与 WEQ_WEB_TOKEN 两行是 Windows 旧值，任务 C 中要更新** |
| `/opt/whale/whale-core/` | whale-core 代码（src/examples/tests/docs；纯 Python stdlib，无第三方依赖；python3=3.13 可直接跑） |
| `/opt/whale/downloads/` | weq-web.tar.gz（官方原包 90MB）、weq-web-overlay.tar.gz（补丁层 6.6MB）；**linuxqq.deb 下载失败不存在**（qqdl.gtimg.cn 超时，见任务 A） |
| `/opt/whale/weq-data/`、`/opt/whale/weq-exports/` | weq-web 数据/导出目录（配置 config.json 会出现在 weq-data） |

已验证冒烟（可复测）：`POST /_auth/login {"token":...}` → 200 + weq_session cookie；
`GET /trpc/bootstrap.getVersionInfo` → app 2.3.2 / node 24.21.0；
`GET /trpc/bootstrap.getAgentLabCatalog` → `['agentscope','openai-compatible']`（补丁生效标志）；
`getSettings` → 基线为空（ssePush 0 服务器、providers 0、autoAttachQq=false）。

## 2. tRPC 调用模式（配置 weq-web 全靠它，不用手改 config.json）

```bash
TOKEN=$(cut -d= -f2 /opt/whale/secrets/weq-web.env)
curl -s -c /tmp/ck -X POST http://127.0.0.1:7690/_auth/login \
  -H 'Content-Type: application/json' -d "{\"token\":\"$TOKEN\"}"
# 查询（无入参）：GET /trpc/<router>.<procedure>
curl -s -b /tmp/ck http://127.0.0.1:7690/trpc/bootstrap.getSettings
# 变更（有入参）：POST /trpc/<router>.<procedure>，superjson 包裹：
curl -s -b /tmp/ck -X POST http://127.0.0.1:7690/trpc/bootstrap.setAgentLabDefaultModel \
  -H 'Content-Type: application/json' \
  -d '{"json":{"ref":{"providerId":"agentscope","model":"deepseek-v4.1-flash-1017"}}}'
```

已知过程（都在 bootstrap/account 路由）：`saveAgentLabProvider`、`setAgentLabDefaultModel`、
`testAgentLabProvider`、`getSettings`；SSE 推送配置的 mutation 与 autoAttachQq 开关的
过程名请自行探测：`grep -oE '"[a-zA-Z]*[Ss]sePush[a-zA-Z]*"' /opt/whale/weq-web/server.mjs | sort -u`
（同理 grep `autoAttach` / `openAccount` / `dbkey` / `qrcode`）。响应形状
`{"result":{"data":{"json":…}}}`；错误时 `{"error":…}`。

## 3. 任务清单（按序执行）

### A. 安装 QQ Linux 官方版（不用 NapCat）

1. 下载 deb（上一轮 `https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_amd64.deb` 超时）。依次试：
   - `https://dldir1.qq.com/qqfile/qq/QQNT/Linux/linuxqq_3.2.34-53644_amd64.deb`
   - 原 qqdl.gtimg.cn 地址重试（`--retry 8 -C -`，走 tmux 后台防断）
   - 都失败 → 停下报告（Windows 侧会代下后 scp 过来）
2. 校验（必须通过才安装）：
   `echo "774e45cd7238dc51b31c02ee494f9f86e773c487a90851790a806f776e130af125110045844e90dd68d0b073dc53bdd4d1c6d8fb7225b8f214fc0e35d1a20e32  linuxqq.deb" | sha512sum -c -`
3. 安装：`apt-get update && apt-get install -y ./linuxqq.deb`（自动解依赖：nss、
   libasound2t64、libgtk-3-0t64、gjs、at-spi2-core、libopenjp2-7、libopenslide0、krb5 等，
   Debian 13 名称由 apt 解析；缺什么补什么）。
4. 验证：`ls -la /opt/QQ/qq && /opt/QQ/qq --version || true`（weq-web 自动探测 `/opt/QQ/qq`，
   装到别处才需要 `WEQ_QQ_EXE`）。

### B. Xvfb 无头跑 QQ（systemd 两个 unit）

`/etc/systemd/system/xvfb-qq.service`：
```ini
[Unit]
Description=Xvfb display :99 for QQ
After=network.target

[Service]
ExecStart=/usr/bin/Xvfb :99 -screen 0 1280x800x24 -nolisten tcp
Restart=always

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/qq-bot.service`：
```ini
[Unit]
Description=QQ Linux (小号 3899713055, headless)
After=xvfb-qq.service
Requires=xvfb-qq.service

[Service]
Environment=DISPLAY=:99
Environment=HOME=/root
ExecStart=/opt/QQ/qq --no-sandbox
Restart=on-failure
RestartSec=10
MemoryMax=2G

[Install]
WantedBy=multi-user.target
```
启动后验证：`systemctl is-active xvfb-qq qq-bot`、`ps aux | grep -c [q]q`。
（root 跑 Electron 需要 `--no-sandbox`；QQ 首启可能弹登录窗——正常，扫码在任务 D。）
可选排障工具：机器上已有 x11vnc（`x11vnc -display :99 -localhost -rfbport 5900` +
ssh -L 看画面）。

### C. weq-web 业务配置（tRPC）

1. **AgentScope provider**（key 从 secrets 文件读，不回显）：
   ```bash
   KEY=$(grep '^DEEPSEEK_API_KEY=' /opt/whale/secrets/qq_workflow.env | cut -d= -f2-)
   BASE=$(grep '^DEEPSEEK_BASE_URL=' /opt/whale/secrets/qq_workflow.env | cut -d= -f2-)
   # saveAgentLabProvider，models 8 个全 capabilities:["chat"]：
   # deepseek-v4.1-flash-1017 / deepseek-v4.1-flash / deepseek-v4-pro-0813 /
   # glm-5.2 / kimi-k3 / qwen3.7-plus / qwen3.8-flash / qwen3.8-max
   # payload: {"json":{"id":"agentscope","name":"AgentScope（whale-io 专用）",
   #   "vendor":"openai-compatible","baseUrl":"'$BASE'","apiKey":"'$KEY'",
   #   "models":[{"id":"…","label":"…","capabilities":["chat"]}, …]}}
   ```
   然后 `setAgentLabDefaultModel` → `{"json":{"ref":{"providerId":"agentscope","model":"deepseek-v4.1-flash-1017"}}}`；
   `testAgentLabProvider`（同 baseUrl/key + model）应返回 ok=true（平台偶发 503
   RESOURCE_EXHAUSTED 属瞬时，隔分钟重试）。
2. **SSE 推送**：`SSETOK=$(openssl rand -hex 24)`；用探测到的 ssePush mutation 添加
   server：name=whale-runner、pushUrl=`http://127.0.0.1:18797/whale`、accessToken=$SSETOK，
   并设为 enabledServerId。然后把 `/opt/whale/secrets/qq_workflow.env` 的
   `WEQ_SSE_TOKEN=` 行更新为 $SSETOK。
3. **完整模式**：autoAttachQq 开关过程置 true（发送必需；attach 需要 QQ 进程在线，
   所以此步在任务 D 扫码登录后生效验证）。
4. **runner 令牌对齐**：把 `/opt/whale/secrets/qq_workflow.env` 的 `WEQ_WEB_TOKEN=`
   行更新为 weq-web.env 里的 WEQ_TOKEN 值（`WEQ_WEB_URL=http://127.0.0.1:7690` 不变）。

### D0. 先诊断「未找到 QQ 的 major.node」（2026-10-29 夜新增）

现象：WeQ UI 报「QQ 安装可能已损坏或版本不受支持。未找到 QQ 的 major.node，无法确定 subAppId。」
但实测 `/opt/QQ/resources/app/major.node` **存在**。WeQ 探测逻辑（packages/platform/src/linux/paths.ts）：
`findQqExe()` 取 `WEQ_QQ_EXE`（存在即用）否则候选表第一个存在的（/opt/QQ/qq …），
然后 major.node = **`join(exe, '..', 'resources', 'app', 'major.node')`**（词法归一）。
因此报错仅两种可能：**取密钥时 QQ 尚未装好/正在重启（瞬时态）**，或 **WEQ_QQ_EXE 指向了
/opt/QQ 目录之外的包装器**（如 /usr/local/bin/qq-headless → 相对路径解析到错误目录）。

诊断步骤：
```bash
# 1) 三件套都在？（qq-headless 必须在 /opt/QQ/ 里面）
ls -la /opt/QQ/qq /opt/QQ/qq-headless /opt/QQ/resources/app/major.node
# 2) weq-web 运行进程的真实 env（不是 unit 文件，是进程）
pid=$(systemctl show -p MainPID --value weq-web)
tr '\0' '\n' < /proc/$pid/environ | grep -E 'WEQ_QQ_EXE|WEQ_HOST'
# 3) 用 WeQ 同款逻辑复现解析
/opt/node-v24.21.0/bin/node -e '
const {existsSync}=require("fs"), {join}=require("path");
const c=[process.env.WEQ_QQ_EXE,"/opt/QQ/qq","/usr/share/QQ/qq","/usr/lib/QQ/qq"].filter(Boolean);
for(const p of c){ if(existsSync(p)){ const m=join(p,"..","resources","app","major.node");
  console.log("exe:",p,"-> major:",m,existsSync(m)); break; } }' 
# （在 weq-web 同款环境下跑：env WEQ_QQ_EXE=<第2步的值> /opt/node-v24.21.0/bin/node -e …）
```
修复分支：
- 若 qq-headless 不在 /opt/QQ/ 内 → 把包装器**移到 /opt/QQ/qq-headless**（unit 已指这里），
  `systemctl daemon-reload && systemctl restart weq-web`；
- 若三件套与 env 都正确 → 是瞬时态：确认 `systemctl is-active qq` 后直接在 UI/tRPC 重试
  「取密钥」，应不再报错；
- 修好后 `grep -iE 'major|subAppId|attach' /opt/whale/weq-data/logs/*.log | tail -20` 复核。

### D. 扫码登录（⚠ 需要用户配合，到这步先暂停向用户要人）

weq-web 绑定 127.0.0.1，用户笔记本走 SSH 隧道访问 UI：
`ssh -L 7690:127.0.0.1:7690 root@100.114.93.90` → 浏览器开 `http://127.0.0.1:7690`
→ 粘贴 weq-web.env 的令牌（用户自己有副本，或经安全渠道给用户）→ UI 里走
「取密钥 / 打开账号」流程，用**小号 3899713055 的手机 QQ 扫码**。
注意：N100 登录会把 Windows 上的同号 QQ 顶下线——预期行为；**扫码成功后让用户通知
Windows 侧停掉旧 runner/WeQ**（避免双端抢会话）。
登录后验证：getSettings 里账号在线状态；`grep -i attach /opt/whale/weq-data/logs/*.log | tail`
应出现 attach/harvest 成功记录（ptrace_scope=0 + root，免密直连）。

### E. whale-core runner（systemd）

1. 自检：`cd /opt/whale/whale-core && python3 -m pytest tests/ -q` → **应 144 全绿**（纯离线）。
2. `/etc/systemd/system/whale-runner.service`：
   ```ini
   [Unit]
   Description=whale-core QQ workflow runner
   After=weq-web.service qq-bot.service
   Wants=weq-web.service

   [Service]
   WorkingDirectory=/opt/whale/whale-core
   ExecStart=/usr/bin/python3 examples/qq_live_workflow.py run --live-auto-send \
     --secrets /opt/whale/secrets/qq_workflow.env \
     --store /opt/whale/state/qq_workflow.sqlite3 \
     --inbox /opt/whale/state/qq_inbox.sqlite3
   Restart=on-failure
   RestartSec=10
   MemoryMax=512M

   [Install]
   WantedBy=multi-user.target
   ```
   先 `mkdir -p /opt/whale/state`。
   **迁移注意（issue #12）**：`--auto-approve` 已改为纯 dry-run（不真实发送）。
   无人值守真实发送 = `--live-auto-send` **且** `qq_workflow.env` 里加
   `QQ_LIVE_AUTOSEND=1`（双开关，缺一 runner fail closed 退出码 2）；入站持久化
   队列走 `--inbox`（issue #14，崩溃恢复/受控重试，`jobs` 子命令可观测）。
3. 启动验证 `journalctl -u whale-runner -f`，横幅应含：owner 绑定 (qq,3563425362)、
   `!! 警告：无人值守真实发送已启用`、`[策略] 主动: 上限=10/日 …| 被动回复: …`、
   `[出站] WeQ Web 真实发送已启用`、`[inbox] 持久化队列：…`、`[监听] http://127.0.0.1:18797/`。
4. runner 重启后 weq-web 的 SSE 推送会开始投递（debounce 2s）。

### F. 端到端验收（需要用户发一条真实消息）

用户主账号（3563425362）给小号（3899713055）发一条消息 → 10 秒内：
`journalctl -u whale-runner -n 30` 应出现 `← [owner]` → `[AI 草稿]` →
`[账本] qq-reply:…: accepted (sent to channel)`，且用户主账号收到鲸鱼回复。
另测：`python3 examples/qq_live_workflow.py show --secrets … --store …` 列出账本。

## 4. 维护与纪律

- **native 有效期**：包内 nt_helper 锚定 `nt-helper-20261027-c6df5e3`，**2026-11-26 过期**
  （过期后 attach/发送失效，离线读库不受影响）。建议建每周 systemd timer 检查并提醒
  （读 `/opt/whale/weq-web/native/linux/x64/nt_helper.node` 的 getBuildInfo，或直接提醒
  用户到点升级：上游发新 release 后重新走「官方包+overlay」流程）。
- **重建补丁层**（未来改了 whaleio 分支代码时）：N100 可直接
  `git clone -b whaleio/bot-engine https://github.com/amwangfan/WeQ.git && pnpm install
  --frozen-lockfile && pnpm native:fetch --no-authorization && pnpm --filter @weq/web build`
  → 用 `apps/web/dist` 覆盖 server.mjs/workers/public/resources（保留官方 native/、
  node_modules/）→ `systemctl restart weq-web`。
- **风控纪律**：只回不主动发；PASSIVE_DAILY_LIMIT=500 是被动回复保险丝不是配额
  （主动 DAILY_LIMIT 默认 10/日 + 静默 22–7，#13）；出现任何风控信号
  （安全中心提示/发送连续失败）→ `systemctl stop whale-runner` 并报告用户。
- **秘密卫生**：两个 env 文件 600；令牌/密钥永不进日志、命令行历史（用文件读入变量）、
  报告文本。
- 不要改 weq-web.service 的 WEQ_HOST（保持 127.0.0.1；外部访问一律 SSH 隧道）。
- 机器是共享的（跑着 campustrust、sftpgo、nginx 等）：whale 栈内存总预算 **5G**
  （QQ 2G + weq-web 2G + runner 512M ≈ 4.5G，余量给 Xvfb），所有新 unit 都带
  MemoryMax；不动已有 unit。

## 5. 验收清单（全勾才算完）

- [ ] QQ deb sha512 校验通过并安装，/opt/QQ/qq 存在
- [ ] xvfb-qq + qq-bot active，QQ 进程稳定在线 10 分钟无重启
- [ ] weq-web：provider=agentscope + 默认模型 + testAgentLabProvider ok
- [ ] ssePush server 配置生效（weq-web 日志出现 sse push watcher 字样）
- [ ] autoAttachQq=true 且日志出现 attach/harvest 成功
- [ ] 扫码登录完成（用户确认），Windows 侧已被通知下线
- [ ] runner pytest 144 绿；whale-runner active，横幅三项齐全
- [ ] 端到端：主账号发消息 → accepted (sent to channel) → 主账号收到回复
- [ ] native 有效期每周提醒 timer 就位
