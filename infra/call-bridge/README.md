# infra/call-bridge — VoIP 来电 + 音频桥

> whale-io 基础设施之一（原独立实验仓已并入本目录）。Android 包名 `com.nova.fakecall` 及设备 / 服务器部署路径为代码标识，保持不变。

> **归档兼容目录，不是新部署入口。** 手机 / VoIP / 音频桥唯一维护与部署入口为 [`whale-mobile`](https://github.com/amwangfan/whale-mobile)。下述脚本、构建与运维步骤仅作历史参考；本仓仅保留兼容与必要安全回补。

> **实验项目，非官方。** 凭据与密钥请自行准备；本目录不含 Token、keystore、APK 或录音。

自管 Telecom `ConnectionService` + 全屏来电 UI + 接通后 PCM 双向桥（默认端口 **18799**）。不插卡、不刷机。

## 安全与凭据

- `secrets/` 不入库（仅 `.gitkeep` + 说明）；用环境变量 `SIGNAL_TOKEN` / `DASHSCOPE_API_KEY` / `AGENTSCOPE_API_KEY`，或本机自建 token 文件。
- `debug.keystore` 不入库；构建前自行生成（见下方）。
- 两个历史 ring hub 入口使用相同鉴权：仅接受 `Authorization: Bearer <SIGNAL_TOKEN>` 或 `X-Ring-Token`，不接受 URL query / JSON body 中的 token。空 token 无法授权来电、挂断和设备查询；非 loopback 监听未配 token 时拒绝启动。loopback 空 token 仅保留历史 poll / wait / WS 兼容行为，不应对外暴露。
- 文档中的主机地址均为占位符：`<PUBLIC_IP>`、`<TAILSCALE_IP>`、`<SSH_PORT>`。保留本机默认端口号（如 18799）。

## 设备路径（机上）

- APK 部署路径示例: `/data/local/tmp/nova-fake-call/`
- 包名: `com.nova.fakecall`
- 脚本: `ring.sh` / `answer.sh` / `hangup.sh` / `stop.sh` / `sim-cosmetics.sh`

## 来电演示

```bash
ssh -o BatchMode=yes -i ~/.ssh/<ssh_key> root@<TAILSCALE_IP> \
  "bash -lc '/data/local/tmp/nova-fake-call/ring.sh \"***\" \"***\"'"
# 接听 / 挂断
/data/local/tmp/nova-fake-call/answer.sh
/data/local/tmp/nova-fake-call/hangup.sh
```

`ring.sh` 参数：`number` `name` `[bridge_host]` `[bridge_port]`  
默认桥主机请用环境/参数覆盖（仓库默认回退 `127.0.0.1:18799`）。

## Box 音频桥

```bash
./audio-bridge/start.sh
./audio-bridge/stop.sh
```

详见 `audio-bridge/README.md`。

## 生成 debug keystore

```bash
keytool -genkeypair -v -keystore debug.keystore -storepass android \
  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

## 运维速查

| 项 | 值 |
|----|----|
| 信令/媒体 TCP | **18799**（桥监听 `0.0.0.0`；手机连 `<PUBLIC_IP>` 或 `<TAILSCALE_IP>`） |
| leaf5 mock | **18798**（勿动） |
| bridge PID / 日志 | `audio-bridge/bridge.pid` / `audio-bridge/bridge.log`（均 gitignore） |

## 原理摘要

- `PhoneAccount.CAPABILITY_SELF_MANAGED` + `addNewIncomingCall`
- 接通后 `AudioBridge`：TCP 帧传 PCM s16le mono 16kHz
- 拨 `1` → AI（Nova AI CallProvider）路径见 `docs/`

## 已知限制

- 回环无 AEC，会有回声；延迟受 Tailscale/TCP 影响
- 正式部署请自行配置公网/安全组与 `SIGNAL_TOKEN`
