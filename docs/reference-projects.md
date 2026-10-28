# 参考项目与借鉴边界（2026-10-28）

> **仅研究公开设计，不复制第三方代码。** 以下是阅读公开 README/项目文档得到的设计灵感，不是它们的完整安全或可靠性审计。借鉴之前须复核 GitHub 当前版本、许可、具体 API 和依赖要求。

| 项目 | 可核验入口 | 我们借鉴的概念 | whale-io 的对应点 | 不直接照搬的部分 |
| --- | --- | --- | --- | --- |
| MaiBot | https://github.com/Mai-with-u/MaiBot | 社交对话不必每条必回；发言时机、人格与记忆关联 | W03/W06/W09，群聊只进可信入口 | 整套 QQ 机器人及其消息执行权限 |
| AstrBot | https://github.com/AstrBotDevs/AstrBot | 把消息平台接入、插件和 AI 主体拆开 | W09 的外部渠道适配器 | 不依赖它的运行时作为 Whale Core 核心 |
| 鱼的记忆 | https://github.com/Yuuyuko-uu/dsh-fish-memory | 记忆重要度、时间衰减、有据可查的召回、可删除 | `MemoryCurator` | 不复制实现；原型仍需语义检索与冲突检测 |
| 主动搭话 | https://github.com/Yuuyuko-uu/dsh-speak-up | 无消息时低成本决定是否唤醒模型 | W06 的候选层、时间/配额门控 | 不把随机唤醒当成值得打扰的理由 |
| 鱼的心情 | https://github.com/Yuuyuko-uu/dsh-feiyu-mood | 可见、可延续的角色表达状态 | `InnerStateStore` | 不宣称 AI 有主观情绪，也不让心情提升权限 |
| 小九九 Runtime | https://github.com/bomomoQWQ/xiaojiujiu | “想做 → 提议 → 实际送达 → 回应”状态分离；未尽话题 | `ProactiveOutbox`、W05/W08 | 不直接合并源码；逐项核对其开源许可 |
| B站生活 | https://github.com/Alterssss/dsh-bili-whale | 探索窗口、兴趣、克制、带证据的分享、日记 | `DiscoveryPlanner` + W06 门控 | 不导入账号 Cookie、公开评论和自动发私信 |
| QQ 桥 | https://github.com/Yuuyuko-uu/dsh-qq-bridge | 消息接入适配、双向通道 | `OwnerChannelRegistry` 与后续 OneBot/官方 QQ 适配 | 不让 QQ 聊天消息变成系统指令 |

## 设计取舍

1. **逻辑参考**优先于依赖耦合：Whale Core 不安装 DSH 插件、AstrBot 或 MaiBot；它们可以作为未来可替换的外围桥。
2. **长期记忆不是全文检索日志**：用户许可、来源证据、去重/冲突/删除要优先，不能把误听自动升级为用户事实。
3. **自主生活不等于背地里使用账号**：B站浏览、阅读和公开回复是不同权限，最初仅做提供来源的私有发现草稿。
4. **主动联系并非越多越好**：打扰预算、单次电话审批和投递结果账本是每个自主模块的共同出口。
5. **版权与许可**：未复制这些仓库任何源码。未来直接复用模块时另建依赖登记，记录 license、commit、NOTICE、修改范围和 AGPL 等条件。

## 后续研究问题

- 探索计划如何做到不查看敏感网页、不过度抓取、不绕过账号风控？
- 怎样区分未回复是没看到、忙、拒绝、网络失败？不可把这几种情况混作情绪判断。
- 长期事实冲突如何确认，如何防止第三方文本向记忆与权限注入恶意提示？
- QQ 官方机器人接口与 OneBot 适配的授权、风控、隐私范围如何择优？
