# 调研：DeepSeek 接入 QQ —— WeQ 路径评估（2026-10-29）

> 调研对象：[WeQ](https://github.com/weq-devs/WeQ)（weq-devs，v2.3.2，dev 分支 3c3b98ff）。
> 关联产出：已向 WeQ 提交内存优化 PR <https://github.com/weq-devs/WeQ/pull/118>（本文 §5）。
> 本文是调研笔记，不改变 [companion-intents.md](companion-intents.md) 与
> [reference-projects.md](reference-projects.md) 的既有结论；是否收录由维护者决定。

## 1. 结论摘要

1. **WeQ 不是"QQ 机器人框架"，而是 NTQQ 本地数据工具 + 克隆体引擎**。它接入 DeepSeek 的正路是：
   桌面端用 DeepSeek 训练"好友克隆"（AgentLab），再**导出为自包含 OneBot bot 产物**（独立 Node
   进程，单文件 `bot.mjs`，零运行时依赖），连 NapCat/SnowLuma 后以该人格在真实 QQ 上收发消息。
2. **DeepSeek 是 WeQ 的一等内置 provider**（`packages/agentlab/src/catalog.ts`：deepseek-chat /
   deepseek-reasoner / deepseek-v4-flash / deepseek-v4-pro；另推荐硅基流动一个 key 覆盖
   聊天+向量+视觉，含 `deepseek-ai/DeepSeek-V3`）。接入本身零开发量，纯配置。
3. **导出 bot 的消息热路径存在严重内存/CPU 浪费**（每条消息整库深拷贝 2–3 次），已实测量化并
   提交 PR #118：单条消息存储耗时 1633ms→674ms，20 条消息峰值堆 211MB→3.6MB，RSS 不再增长。
4. 对 whale-core 而言，WeQ 的最佳定位是**"QQ 人格化分身"的现成实现 + 聊天语料/记忆素材来源**，
   而不是 W09 多渠道适配器的传输层。传输层建议仍是 OneBot 直连（NapCat）+ 自己的薄适配器，
   与 README"不自动替用户操作 QQ"的默认边界一致。
5. **许可注意**：WeQ 为 CC BY-NC-SA 4.0（README；根 package.json 写 GPL-3.0-or-later，以 LICENSE
   为准需再核）。非商业 + 相同方式共享 ⇒ 只做**进程隔离集成**（运行导出产物、读导出 JSON），
   不要把 WeQ 代码拷入本仓。

## 2. WeQ 的 AI/机器人架构（与本调研相关部分）

```
WeQ 桌面端 (Electron)                     导出产物（独立部署，长跑 Node 进程）
├─ NTQQ 本地库解密/解析（nt_helper 原生）    <outDir>/
├─ AgentLab：好友克隆训练                   ├─ bot.mjs      esbuild 单文件引擎（@weq/bot+@weq/agentlab+ws 内联，442KB）
│   ├─ 画像/风格/口头禅抽取（LLM）          ├─ index.mjs    入口：读 config.json → startBot
│   ├─ 语料 pairs + 向量检索（embedding）   ├─ config.json  adapter(wsUrl/token)+selfId+llmProviders+features+webui
│   └─ 记忆/关系/意愿闸/错别字/语音         ├─ persona/     <id>.json(画像+语料) + stickers/ + voice/
├─ WeQ 助手（任务型 agent，沙箱/任意权限）   └─ data/        conversations/memories/notes/relations/stats.json（运行时生成）
├─ MCP 服务器（只读，127.0.0.1+Bearer）
└─ 导出 OneBot bot ────────────────────────►  ws 客户端连 NapCat/SnowLuma（OneBot v11 正向 ws）
```

要点：

- **模型配置**：设置 → AI 功能 → API 配置（LLM/TTS 分块，多 provider，默认模型，能力自动检测）。
  克隆训练选聊天模型（必选）+ 向量模型（可选，语义检索）+ 视觉模型（可选，解析表情包）。
- **导出即冻结**：导出 bot 固定"导出时实际引用的模型与服务商"，不随桌面全局默认漂移；
  `config.json.llmProviders[].timeoutMs` 可调（默认请求总期限 3600s）。
- **bot 运行时行为**：私聊默认回；群聊需 `features.groupChat`，回复意愿有 `llm`（拟人判断，
  每条群消息多一次 LLM 调用）/`heuristic`（启发式，省 token）两档；记忆按（bot 账号×克隆体×对端）
  隔离；每会话串行、默认 4 会话并发、队列 256/32 上限；自带 WebUI 控制台（127.0.0.1:8090，
  密钥鉴权，看 token 用量/收发统计，可改配置热重载）。
- **隐私默认**：普通日志不落正文；`logging.debugContentUntil` 最多开 30 分钟调试窗。
- **MCP 方向**：WeQ 对外提供只读 MCP（外部 AI 客户端查当前账号聊天数据）；助手侧可接外部
  MCP（仅任意权限模式）。两者都不提供"外部程序驱动 WeQ 发消息"的口子——**发消息的自动化
  路径只有导出 bot（OneBot）或桌面助手任意权限模式（人工场景）**。
- **部署形态**：桌面版（Win x64/mac/linux）；WeQ Web（NAS/服务器，Node≥22，复用桌面业务后端）。
  导出 bot 与两者无关，可单独跑在任何有 Node 的机器。

## 3. DeepSeek×QQ 接入方案对比

| 方案 | 组成 | 优点 | 缺点/风险 | 适用 |
| --- | --- | --- | --- | --- |
| **A. WeQ 导出 bot**（本调研主线） | WeQ 桌面训练 → 导出产物 + NapCat/SnowLuma + DeepSeek API | 人格来自真实聊天记录；记忆/关系/意愿闸/语音/表情齐备；产物自包含零依赖；DeepSeek 内置支持 | 需一台常开 QQ 的协议端（NapCat）；克隆语义=扮演好友而非"助手"；CC BY-NC-SA 许可边界 | 个人分身、陪伴型 bot、离线语料驱动的拟人回复 |
| **B. NapCat/LLOneBot + 通用 bot 框架**（MaiBot/AstrBot/Koishi/LangBot + DeepSeek 插件） | 协议端 + 框架插件 | 生态活跃、插件多、助手语义（而非克隆）；社区文档全 | 人格/记忆要自己搭或依赖框架实现；与 whale-core 能力重叠 | 通用 QQ 助手、群管、命令型 bot |
| **C. QQ 官方机器人 API**（q.qq.com） | 官方开放平台 | 合规、无风控封号风险 | 腾讯已收敛"主动消息推送"（[ithome 报道](https://www.ithome.com/0/846/651.htm)），仅被动回复且有模板/频率限制；个人开发者能力更窄；无本地语料 | 面向公众的服务型 bot、合规硬需求 |
| **D. WeQ MCP / 助手**（只读分析） | WeQ 桌面 + 外部 DeepSeek 客户端（Claude/DSH 等）经 MCP 读聊天数据 | 零风控增量（不发消息）；适合"对聊天记录做智能分析" | 不能收发消息，不是"接入 QQ"；令牌=账号聊天数据读权限 | 聊天分析、年度报告式挖掘、whale-core 记忆素材提取 |

**协议端说明**（A/B 共用）：NapCat（NapNeko 团队，活跃维护，支持 Docker 无头部署）实现
OneBot v11，本质是魔改 QQ NT 客户端——**任何协议端方案都有账号风控风险**；WeQ 桌面自身也
有因 attach/凭证采集触发设备风险提醒的公开反馈（[weq-devs/WeQ#112](https://github.com/weq-devs/WeQ/issues/112)）。
建议：小号或可承受封禁的账号、低频被动回复、不开主动群发。

**内存量级参考**（部署侧真正的内存大头是协议端）：NapCat≈完整 QQ NT 客户端（Electron 基座，
数百 MB～1GB+）；WeQ 导出 bot≈Node 基线+数据（数十 MB，PR #118 后消息路径不再随消息量
锯齿增长）；DeepSeek 为云 API 无本地占用。

## 4. whale-core 对接评估（W09 多渠道）

现有契约（`whale-core/src/whale_core/life/channels.py`）：`Incoming(channel, account,
conversation, message_id, text, transport_authenticated)` → `OwnerChannelRegistry.accept()`
（仅显式配对 + 传输已鉴权）→ `OwnerEvent`；出站 `OutboundPort.send(draft)->bool`（受理≠送达）。

映射建议（与 companion-roadmap"最后接 QQ，通过外部适配器做账号映射"一致）：

- **传输适配器自建，不复用 WeQ 代码**：OneBot v11 正向 ws（NapCat）→ `Incoming(channel="qq",
  account=str(user_id), transport_authenticated=token 校验)`；`OutboundPort` → `send_private_msg`。
  Python 侧可用现成 OneBot SDK 或百行内 ws 客户端。WeQ 导出 bot 的 adapter/orchestrator
  （`packages/bot/src/adapter/onebot.ts`）可作**实现参考**（协议字段、重连、echo 匹配、队列上限），
  参考思路不抄代码，规避 SA 条款。
- **WeQ 作为语料/记忆素材来源（可选，人工触发）**：导出的聊天记录 JSON / MCP 只读查询 →
  whale-core 记忆提炼管线的输入。属于"用户显式操作产物"，不违反"不擅自录音截图/不自动操作 QQ"。
- **WeQ 导出 bot 作为并行形态（可选）**：如果目标是"鲸鱼在 QQ 上有人格化分身"，导出 bot 是
  现成的完整实现；whale-core 不驱动它，两者边界清晰。不建议把 whale-core 伪装成它的
  LLM provider（OpenAI 兼容代理转发）来"注入"策略——耦合隐蔽、故障面大，如需要再单独评估。
- **默认边界重申**：只做被动回复；主动搭话/主动发消息沿用 companion-intents 的授权模型
  （每个渠道独立开关 + 静默时段 + 当日上限），群聊内容永远视为不可信输入。

## 5. WeQ 内存精简：已提交的 PR #118

**问题**（实测，合成夹具 3.2MB 对话 + 12.1MB 含 1024 维向量记忆，Windows/Node 22）：
导出 bot 每条消息对整库做 2–3 次 structuredClone 深拷贝 + 全量校验 + 整文件重写；
一条命中记忆的回复 ≈ 300–1600ms 同步阻塞、数十 MB 瞬时分配，长跑进程 RSS 锯齿增长
（20 条消息 +248MB）。

**修复**（`packages/bot`，不动文件格式/原子写契约/失败语义）：
`BotJsonFile.replace` 改所有权转移 + 发布时深冻结（增量冻结跳过未变更子树）；读取返回
"新数组+逐条浅拷贝"（标量修改仍无害，嵌套向量改写显式抛错）；touch/append 只构造最小
新鲜副本；校验对冻结条目跳过字段级扫描。

**效果**：单条消息循环 1633→674ms；峰值堆增长 211→3.6MB（≈1/59）；RSS 20 条后不再增长；
GC 后滞留 +30.5→+0.1MB；热路径 structuredClone 归零。新增 13 项回归测试；既有 40 项
bot 契约测试全绿。PR：<https://github.com/weq-devs/WeQ/pull/118>（base=dev，待审）。

**继续可精简的方向**（已写进 PR 的开发笔记"本轮不做"，供后续跟进）：
- `AgentLabStore.getPersona` 每条消息全量重读 persona 文件（1.1MB≈10ms/条）→ bot 侧 mtime+size 读穿缓存；
- 剩余耗时是整文件 stringify+fsync（单文件 JSON 下限）→ 按 scope 分桶落盘或 200ms 防抖合并写（涉及耐久性语义，需维护者对齐）；
- `BotOrchestrator.histories`（每对端 40 轮）与 `StatsStore.byDay` 无上限 → LRU/保留期；
- 桌面端（Electron）渲染进程内存是另一战场：维护者已有完整诊断体系（dev-memory-monitor、
  renderer-resource-lifecycle 等专项），外部随手优化空间小、验证门槛高，未动。

## 6. 收发全走 WeQ 的可行性与「按需在线」提案（2026-10-29 补充）

> 背景：whale-core README 边界已改为「QQ 允许操作（可代用户收发消息与互动，走账号授权与既有审批链路）」。
> 本节结论基于 v2.3.2 代码审阅 + 本机实测（离线模式已验证：零 attach / 零凭据采集，缓存密钥解密正常）。

### 6.1 现状盘点（代码证据）

| 能力 | 状态 | 证据 |
| --- | --- | --- |
| 收消息 → 外部系统 | ✅ 现成：**SSE 推送**（`sse_push.ts`），本地库监听、离线模式可用、Bearer 鉴权、去抖/合并/重试不丢消息 | 设置 → WeQ 服务 → SSE 推送 |
| 发消息（应用内） | ✅ 现成：`MessageSendService`（PbSendMsg + highway 富媒体 + 文件），MCP 工具 `send_text_message` 等已实现 | `docs/develop/send-message.md`、`mcp/tools.ts:4009` |
| 发消息（外部程序） | ❌ 无接口：发送工具 `assistantOnly`，外部 MCP **刻意只读**（2.3.x 安全收紧）；`onlinePid()` 要求 QQ 在线 + 完整模式 | `mcp/server.ts:54`、changelog |
| attach 频度 | 每 QQ 进程**一次**：物料持久化、跨 WeQ 重启复用；SSO 丢失用存储物料重注册、不再读内存 | `monitor.ts` attachHook 注释、`storeSsoSession` |
| 常驻周期动作 | 完整模式下 `harvestIfStale`（5s pid poll 驱动）：rkey/clientkey 到期前 5min 重采（clientkey TTL 30min → **约每 25min 一轮** CGI + 首页装扮抓取）；web 凭据按功能使用时才取 | `monitor.ts:699-729` |
| 纯文字发送依赖 | **只需 SSO 会话物料**（a2/d2/d2key）；rkey=媒体下载、clientkey=web 登录、装扮/web 凭据均不在发送链路 | send-message.md 协议分析 |

### 6.2 「仅发送按需在线」模式提案（供上游讨论，非既定实现）

维护者 3 天前的 `credential-request-safety-20261026.md` 立场保守（明确"保留正常的自动刷新"、
只合并在途请求、不猜 TTL），且 issue #112 报告人请求过"按需执行 + 安全模式"。因此提案定位为
**新增一个 opt-in 在线档位**，不改默认行为：

```
onlineMode: full（现状默认） | offline（现状，完全离线） | on-demand（新增：发送窗口）
```

on-demand 档语义（全部用已有原语拼装，无新协议面）：
1. **不做周期 harvest**：monitor 跳过 `harvestIfStale` 的 rkey/clientkey/装扮轮询（省掉每 ~25min 的 CGI 与装扮抓取）；
2. **SSO 会话惰性注册**：首次发送/在线操作时用已存物料 `storeSsoSession`（不重新 attach），空闲 N 分钟（默认 5–10min，可配）后 `forgetSsoSession` 释放；
3. **凭据惰性化**：rkey 走既有 `harvestRkeysNow()` 按需路径（媒体功能用到才取；导出补媒体已是这个先例）；web 凭据维持"用时取 + TTL 缓存"现状；
4. **attach 不变**：仍是每 QQ 进程一次、需用户知情同意（现有欢迎框/横幅流程）。

风控暴露面对比（诚实标注：腾讯检测规则未知，#112 亦未证实因果，此表只比较行为面大小）：

| 形态 | 内存读取 | 周期 CGI | 发送身份 | 适用 |
| --- | --- | --- | --- | --- |
| offline + SSE 收（**今天已可用**） | 一次/进程（首次取密钥） | 零 | 不发 | 感知/记忆/监听 |
| on-demand 发送窗口（提案） | 一次/进程 | 仅发送窗口内 | **主账号** | 低频、审批制代发 |
| full 常开（现状默认） | 一次/进程 | 每 ~25min 一轮 + 功能触发 | 主账号 | 重度在线功能用户 |
| NapCat + 小号 | 零（独立协议端） | 协议端自身心跳 | **小号** | 高频自动回复 bot |

### 6.3 外部发送接口（第二个提案，安全决策更重）

若要 whale-core/bot **自动化**走 WeQ 发送，需要受控外部接口。最小可行形态：外部 MCP 增加
opt-in 的 `send_text_message` 子集，带**会话白名单 + 限频 + 独立令牌 + 审计日志**，默认关。
这与"外部 MCP 只读"的既有安全决策相抵，必须由维护者拍板；不建议任何绕过手段
（fork 自签、复用 assistantOnly 令牌、UI 自动化点按都属破坏安全边界）。

### 6.4 今天就能落地的组合（零上游改动）

- **收**：离线模式 + SSE 推送 → whale-core HTTP 接收器（B1 适配器补一个 listener，纯 stdlib）；
- **发（低频、人工审批）**：需要时 UI 一键开完整模式（或受限模式横幅的会话级"完整功能"按钮——不改配置、账号会话结束自动失效）→ 助手/UI 发送 → 用完即关；短窗口只有一次初始凭据突发，无 25min 常驻轮询；
- **发（自动、高频）**：仍走 NapCat + 小号（风险隔离），待 6.2/6.3 上游落地再评估迁移。

## 7. 双线维护（上游 PR 线 vs whale-io 部署线）

WeQ 本地工作克隆：`E:\WhaleIO\tmp\WeQ`（已 gitignore；remote：origin=weq-devs/WeQ，fork=amwangfan/WeQ）。

| 线 | 分支 | 用途 | 纪律 |
| --- | --- | --- | --- |
| **上游 PR 线** | `perf/bot-store-hotpath`（PR #118）、`perf/bot-persona-cache`（PR #119） | 向 weq-devs/WeQ:dev 提 PR | 从 `origin/dev` 拉出；一个 PR 一个主题；**绝不出现 whale-io 相关内容**；改动仅限对上游自身有价值的部分 |
| **whale-io 部署线** | `whaleio/bot-engine`（= dev + #118 + #119 合并） | 构建本机部署的 `bot.mjs`（已换入安装的 WeQ `resources\resources\bot-runtime\`，原件备份 `bot.mjs.orig-2.3.2`） | whale-io 专用的 WeQ 定制（发送接口实验、按需在线、SSE 加固等）只进 `whaleio/*` 命名空间；不向 upstream 提这些分支的 PR |

维护动作：
- 上游合并 #118/#119 后：`whaleio/bot-engine` rebase 到新 `origin/dev`，删除重复实现
  （persona_cache 的本地 `freezeDeep` 换成 `json_file` 的 `deepFreeze`），重跑测试并重建 `bot.mjs` 换入；
- WeQ 官方升级（安装器覆盖 `bot.mjs`）后：从新 tag 重建 `whaleio/bot-engine` 并重新换入，或等上游合并后直接用官方版；
- whale-io 侧对接件（SSE 桥、适配器、运行器）全部在 whale-io 仓，不 fork WeQ 代码（许可：CC BY-NC-SA，进程隔离集成）。

## 8. 参考

- WeQ 仓库/文档：<https://github.com/weq-devs/WeQ>（guide：assistant / agentlab / mcp-server /
  model-resources / remote-web；develop：bot-store-hotpath-20261029 等）
- NapCat：<https://github.com/NapNeko/NapCatQQ>（FAQ：<https://mintlify.wiki/NapNeko/NapCatQQ/resources/faq>）
- 官方机器人消息收发：<https://bot.qq.com/wiki/develop/api-v2/server-inter/message/overview.html>；
  主动推送收敛报道：<https://www.ithome.com/0/846/651.htm>
- 本仓相关：[companion-intents.md](companion-intents.md)（W09）、[companion-pipelines.md](companion-pipelines.md)
  （出入口契约）、[reference-projects.md](reference-projects.md)（MaiBot/AstrBot/dsh-qq-bridge 与许可边界）
