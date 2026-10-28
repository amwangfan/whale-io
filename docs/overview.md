# 架构一页图

整包分两条主通路：**卡片云推送上镜**（正式 HTTPS），以及旁路的 **VoIP 来电音频桥**（本机 / USB reverse / 自配中继）。两者端口与职责隔离。

## 主通路：手机 App ↔ HTTPS dashboard ↔ 眼镜

```mermaid
flowchart LR
  subgraph cloud [自托管云]
    W[业务 / Writer] -->|Bearer writer| D[dashboard-service]
    NG[HTTPS 入口<br/>rayneo.wangfanlab.com/turbo] -->|反代| D
  end
  subgraph phone [Magic8 手机]
    APP[Turbo-IO 宿主 / 插件<br/>PhoneBridge · HUD]
    APP -->|HTTPS 443<br/>phone Token 短轮询| NG
    APP -->|approve 后 CardTransport| BT[蓝牙 / 厂商链路]
  end
  subgraph glass [雷鸟眼镜]
    HUD[轻量 HUD / 卡片显示]
  end
  BT --> HUD
  W -.->|本机写卡片 publish| D
```

要点：

1. 手机 **只出站 HTTPS:443**，不向公网开入站端口。
2. 服务端仍强制 `approve`；「自动上镜」是 **客户端** 静默 approve（`phoneAutoApprove=client`），不是跳过鉴权。
3. APK **不**直连本机 reminder / dashboard 明文端口；卡片一律经 HTTPS 入口。

## 旁路：VoIP 来电 + 音频桥

```mermaid
flowchart LR
  subgraph phone2 [Magic8]
    FC[VoIP 来电 App<br/>自管来电 UI]
  end
  subgraph local [开发机 / 桥主机]
    BR[音频桥默认<br/>127.0.0.1:18799]
    AI[AI / TTS 等]
  end
  FC <-->|PCM 双向 TCP| BR
  BR <--> AI
```

- 默认桥地址写 **`127.0.0.1:18799`**（USB `adb reverse` 或本机中继时）。
- 随身 / 尾网场景用占位主机，**不要**把真实设备 IP 写进文档或提交。
- 与 dashboard **18796**、reminder **18797** 等口隔离，勿混用。

## 模块边界（给新人）

| 你想…… | 先看 |
|--------|------|
| 把提醒 / 卡片推到眼镜 | [cloud-push.md](cloud-push.md) |
| 装实验签名宿主 | [apk-install.md](apk-install.md) |
| VoIP 来电 / AI 通话 | [voip-bridge.md](voip-bridge.md) |
| 什么不能提交 | [security.md](security.md) |

三层优先级与阻塞见 [`framework-status.md`](framework-status.md)。
