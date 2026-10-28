# Whale 双脑架构（P0/P1）

## 分工
- **Realtime / Omni（助手）**：持续语音流的低延迟听说与操作工具。不要将 DeepSeek 放在每轮 PCM→语音响应的同步路径中。
- **DeepSeek（陪伴好友）**：文字长对话、较深的理解、记忆整理建议及非实时任务。通过 \`DeepSeekCompanion\` 使用兼容 Chat Completions 的 HTTPS API，缺 Key 时显式失败，不自动改用其他服务。
- **统一核心**：两者共同使用 \`SqliteMemoryStore\` 的情节、偏好、人格快照；实时电话产生的转写可通过 \`RealtimeTranscriptRecorder\` 异步排队，绝不等待 DeepSeek。
- 重要限制：当前是**库级可组合能力**，实时电话还需将 Omni 的转写回调连接到统一的录制器。不能把独立的原型误认为已经在手机上跨端同步。

## 运行与配置
\`\`\`bash
cd whale-core
uv sync --extra dev
uv run pytest -q
\`\`\`
- \`DEEPSEEK_API_KEY\`：只放环境变量，勿存仓库。
- \`DEEPSEEK_BASE_URL\`：默认 \`https://api.deepseek.com\`。
- \`DEEPSEEK_MODEL\`：默认 \`deepseek-chat\`，正式服务商接口以实际账号为准。
- 数据库路径自行指定（例如私有数据目录下的 \`whale.db\`），**不上传 GitHub**；不同设备须通过受认证的同步服务访问同一记忆存储，不能直接共享 SQLite 文件。
- \`HttpsDashboardGateway\` 仅是可注入 HTTPS 发布适配器；必须确认 dashboard 实际的路径、请求体及授权后才能启用。

## 实时集成开关

`WHALE_MEMORY_DB=/private/data/whale.db`：仅在 Omni 模式为实际通话异步写入用户及助手的**最终转写文本**；默认关闭。部署时给数据库目录持久化权限，不要放进源码目录。已支持的范围仅是本机同库，不是云同步 API。

## 上线前
1. 先完成本地测试，再用隔离测试环境验证手机与云端认证；不直接覆盖正在运行的音频桥。
2. **P0**：\`BRIDGE_TOKEN\` 保护 PCM 入口；不要对互联网直暴露未加密的 TCP。使用私有隧道或 TLS 前置；不采集原始语音为默认。信令 \`SIGNAL_TOKEN\` 必配且使用 HTTPS。
3. **P1**：日程/天气/前台事件适配、云上持久存储的鉴权、用户控制的记忆删除、主动通话授权与抑制策略，仍需实机/真实数据联调。
4. 对具有副作用的意图（呼叫、手机控制、开麦）采取显式授权，紧急标记不可由不可信事件文本直接提升权限。

## 延迟
Omni 直接音频流回应；文本转写异步写入持久记忆，DeepSeek 在另一条请求链进行深度对话。测量首字音延迟、端到端回合时延、记忆队列丢弃率，并避免在回调线程调用数据库或文本 LLM。
