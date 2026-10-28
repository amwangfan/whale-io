# 安全：禁止入库与扫描习惯

适用于整个 whale-io 仓（`docs/`、`whale-core/`、`infra/`）。下列内容 **一律不得** 出现在提交、PR、截图说明或 CI 日志中。

## 禁止入库

| 类别 | 示例（概念） | 正确做法 |
|------|--------------|----------|
| Token / 密钥 | phone Token、信令 Token、云厂商 API Key、GitHub PAT | 本机文件或钥匙串；环境变量 |
| 证书与签名 | `*.keystore`、`*.jks`、debug keystore | 本机构建机自备 |
| 安装包 | `*.apk`、`*.aab` | 私有制品区 / 本机路径 |
| 真实网络地址 | 公网 IP、Tailscale IP、本机 Windows 用户目录 | 用 `<PUBLIC_IP>` / `<TAILSCALE_IP>` / `127.0.0.1` |
| 私密身份串 | 真实全名路径、未脱敏设备序列号（若政策要求） | 占位符 `<serial>` |

**允许保留的示例域名：** `rayneo.wangfanlab.com`（及 path `/turbo`）。

## 文档写法

- 主机：优先 `127.0.0.1`，否则 `<BRIDGE_HOST>` / `<PUBLIC_IP>` / `<TAILSCALE_IP>`。  
- Token：只写「本机自备」「Bearer \<token\>」，不写真值、不写可定位到个人的全文路径。  
- 设备 id：可用占位 `<device>`；若必须举例，确认不是密钥。

## 提交前自扫

在仓根执行（按需增补模式）：

```bash
rg -n '47\.108\.|8\.211\.|100\.82\.|100\.127\.|100\.100\.|100\.92\.|100\.102\.' .
rg -n 'phone-.*\.token|Bearer [A-Za-z0-9_\-]{20,}' .
rg -n -i 'C:[\\/]+Users' .
```

有命中 → **先改再 commit**。说明性文字（「不要把 Token 真值写进仓」）可保留，但不要出现赋值真值。

## .gitignore

仓根 `.gitignore` 已覆盖常见 `*.token`、`*.keystore`、`*.apk`、`secrets/`、`.env` 等。新增敏感后缀时同步更新 ignore 与本页。
