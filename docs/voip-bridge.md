# VoIP 来电 + 音频桥

概念说明与本地默认值。主机一律写 `127.0.0.1` 或 `<BRIDGE_HOST>` 占位符，**不写真实设备 IP**。

## 做什么

1. **VoIP 来电 App**（Magic8 非 Root 路径）：自管 Telecom / 全屏来电 UI，可模拟来电、接听、挂断。
2. **音频桥**：接通后在手机与桥主机之间传 PCM（常见 s16le / mono / 16 kHz），再接到 AI / TTS 等。
3. **不插卡、不刷机**；开发期优先 USB `adb reverse`，随身再自配中继。

源码在本仓 [`infra/call-bridge/`](../infra/call-bridge/)；本页只给操作概念。

## 默认端口与主机

| 项 | 默认说明 |
|----|----------|
| 桥端口 | **18799**（本地默认） |
| 桥主机（App 默认） | `127.0.0.1` |
| 文档占位 | `<BRIDGE_HOST>`、`<TAILSCALE_IP>`、`<PUBLIC_IP>` |

其它相关口（勿混用）：

| 端口 | 用途（概念） |
|------|----------------|
| 18796 | dashboard（经 HTTPS 反代，不给 VoIP 来电直连） |
| 18797 | 本机 reminder（勿作公网入口） |
| 18798 | 其它 lab mock（勿占） |
| **18799** | VoIP 来电音频桥 |

## 开发期（USB）示意

```bash
# 桥进程在开发机监听（示例）
# (cd infra/call-bridge && ./audio-bridge/start.sh)

# 手机把 18799 转到电脑
adb reverse tcp:18799 tcp:18799
```

App 内桥地址保持 `127.0.0.1:18799` 即可。

若电脑与桥主机不是同一台，可在电脑上跑 **TCP 中继** 到 `<BRIDGE_HOST>:18799`，手机仍只连本机 `127.0.0.1`（经 reverse）。

## 信令触发（概念）

桥侧可提供 HTTP 信令（路径以 `infra/call-bridge/` 代码为准），例如「响铃 / 接听 / 挂断」。  
调用时主机用占位符：

```bash
curl -X POST "http://127.0.0.1:18799/v1/ring?device_id=<device>"
```

生产或随身场景若暴露公网，须自备鉴权（如环境变量中的信令 Token），**Token 不入库、不写本文**。

## 联调最短路径

1. 启动音频桥（确认监听在约定地址）。  
2. `adb reverse tcp:18799 tcp:18799`。  
3. 安装 VoIP 来电 user 包，授予麦克风 / 电话相关权限。  
4. App 内确认桥为 `127.0.0.1:18799`。  
5. 模拟来电 → 接听 → 说话 / 听回传。  

## 已知限制（预期）

- 回环无 AEC，可能有回声。  
- 经尾网 / 公网中继时延迟上升。  
- 与雷鸟卡片推送通路独立：VoIP 来电通了 ≠ 云推送已通。


## Omni realtime 路径（低延迟）

详表与环境变量见 [`infra/call-bridge/audio-bridge/README.md`](../infra/call-bridge/audio-bridge/README.md)。此处只留可执行要点：

| 项 | 约定 |
|----|------|
| 模式 | `BRIDGE_MODE=omni`（默认仍是 `agent` 级联） |
| 模型 | `qwen3.8-omni-flash-realtime`（可用 `BRIDGE_OMNI_MODEL` 覆盖） |
| 接入 | `wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=…`（无需 WorkspaceId） |
| 鉴权 | 仅 `DASHSCOPE_API_KEY`（**env only**，不入库、不打印） |
| 音频 | 上行 pcm16/16 kHz；下行 pcm24→16 kHz 回手机 |

```bash
BRIDGE_MODE=omni ./infra/call-bridge/audio-bridge/start.sh
```

不部署到公网，除非已有 systemd / 反代 / Token 流程（见 `infra/call-bridge/docs/public-aliyun-bridge.md`）。

## BRIDGE_TOOLS 白名单

设 `BRIDGE_TOOLS=1` 后启用（默认关）。**无任意 shell**，仅固定动作：

| 工具名 | 语音触发例 |
|--------|------------|
| `what_time` | 「现在几点」 |
| `bridge_status` | 「查一下桥状态」 |
| `ring_fake_call` | 「打个 VoIP 来电」（实现名保留；用户文案用 VoIP 来电） |
| `list_workspace` | 「列一下工作区」 |
| `tail_bridge_log` | 「看桥日志」 |
| `restart_bridge` | 「重启桥」 |

路径：Omni 原生 tools（`session.update`）+ ASR 关键词路由。实现：`infra/call-bridge/audio-bridge/bridge_tools.py`。

```bash
BRIDGE_MODE=omni BRIDGE_TOOLS=1 ./infra/call-bridge/audio-bridge/start.sh
```

云端智能层只发 `shell_whitelist` 意图（见 `whale_core.cloud`），不在 whale-core 内执行这些工具。
