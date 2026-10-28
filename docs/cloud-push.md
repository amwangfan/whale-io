# 云推送操作指导

面向新人联调：从 HTTPS 入口到手机自动上镜。不含真实 Token、公网 IP、本机用户路径。

## HTTPS 入口约定

| 项 | 约定 |
|----|------|
| 正式 origin（示例） | `https://rayneo.wangfanlab.com/turbo` |
| 协议 / 端口 | **仅 HTTPS，且必须 443** |
| 反代目标（源站本机） | dashboard-service，例如 `127.0.0.1:18796` |
| 鉴权 | `Authorization: Bearer <token>`；**禁止** query / URL 内嵌 Token |
| 禁止 | 明文 HTTP；非 443 HTTPS；把 SSH / 运维口当 API；公网裸露 dashboard 端口 |

手机侧 BoundedHTTP 应拒绝：明文、重定向、非 443、以及任意「看起来像运维口」的 URL。

健康检查示例（可不带 Token）：

```bash
curl -fsS "https://rayneo.wangfanlab.com/turbo/health"
```

## Phone Token：本机自备

1. 在 dashboard / 运维侧为 **phone** 角色签发 Token（role=phone，绑定你的 `device` id）。
2. 只保存在 **本机**（钥匙串、加密 SharedPreferences、或本机 ssh 目录下的私有文件）。
3. **不要**写入本仓、聊天、截图、CI 日志。
4. App 启动后用 `GET /v1/identity` 校验 role 与 device，再开始轮询。

Token 文件命名可用占位习惯，例如 `phone-<device>.token`——路径自行决定，文档与仓库只写占位符。

## 自动上镜（client approve）

服务端 API **不变**：仍须

`POST /v1/phone/jobs/{id}/approve` + 精确 `hash`。

客户端行为（`GET /v1/capabilities` 中 `phoneAutoApprove = "client"`）：

1. 短轮询 `GET /v1/jobs`，筛 `awaiting_phone_approval`。
2. `GET /v1/jobs/{id}` 取 document + hash。
3. **静默** `approve`（不弹每次确认 UI）→ 宿主 `CardTransport.publish` → 诚实 `receipt`。
4. 同 job 不二次批；遇 `sending` / `outcome_unknown` / 409 → **冻结，禁止盲重试**。

Writer 侧（本机或同机 sidecar）典型顺序：写卡片 → `publish` → job 进入 `awaiting_phone_approval`。

## 联调检查清单

- [ ] `https://rayneo.wangfanlab.com/turbo/health` 返回正常（TLS 与反代已通）。
- [ ] 手机数据源 Base 设为上述 origin（无多余明文端口）。
- [ ] phone Token 已在本机填入；`GET /v1/identity` 角色正确。
- [ ] Writer 发出一张测试卡；手机能拉到 `awaiting_phone_approval`。
- [ ] 静默 approve → publish → receipt 闭环；无盲重试。
- [ ] **不声称**镜片必显：无宿主卡片桥 / 未连眼镜时，只验收到 approve/receipt。
- [ ] 文档与提交中无 Token、无真实服务器 IP、无本机 Windows 用户路径。

## 常见停手条件

- `/turbo` 尚未反代到 dashboard → 不要宣称远端卡片已通。
- 缺 phone Token 或 identity 失败 → 先本机签发与校验，勿把 Token 贴进 issue。
- 眼镜固件 / 宿主能力不足 → 只验手机侧闭环，不刷机除非另说。
