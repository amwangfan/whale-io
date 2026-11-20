# TVP2C · 雷鸟 iO 自定义固件与配套软件（源码版）

> ⚠️ 适用对象：雷鸟 iO 眼镜（Strix OS 1.0.6.11 基线）+ Android 手机。其他型号/版本需自行重定位。
> ⚠️ 免责声明：固件修改有变砖风险。本项目仅作技术分享，一切操作后果自负。请在你拥有完全处分权的设备上进行。
> 本仓库**不含**任何固件包、APK、签名密钥或打包产物；也不含厂商协议的获取性细节与任何真实设备标识。

---

## 这套东西能做什么

在**不改变任何原厂功能**的前提下，给眼镜加上：

| 能力 | 说明 |
|---|---|
| **自定义菜单** | 原厂 8 项之外挂自建模块应用，旋钮翻页、点击打开，与原厂项共存 |
| **自定义弹卡/提示** | 手机推一帧 512×128 灰度画面到眼镜，镜片自动弹出显示——文字/图片/排版完全自定义 |
| **云端智能体终端** | 对眼镜说话 → 云端 AI 思考回复 → 结果显示在镜片；可执行任务（带两阶段确认门控） |
| **语音快捷回复** | 对眼镜说「回复一下，说……」直接代发消息给绑定联系人 |
| **传输加速与自愈** | 分包大小五档可调（512B→16K）+ 有界重试/失败隔离/断路器，大帧传输提速 2-4 倍 |

## 仓库地图

| 目录 | 内容 |
|---|---|
| [firmware-tvp2c/](firmware-tvp2c/) | 眼镜侧固件模块源码（C/Thumb-2，21 个运行时模块）+ 可复现重建管线（`build.py` + `build-inputs.json` 钉死全部编译单元与补丁点） |
| [android-addon/](android-addon/) | 手机侧宿主插件源码与离线测试（Java，无秘密、无预填 Key） |
| [whale-mobile/](whale-mobile/) | Android 配套 App（Flutter + Kotlin）与端云协议契约 `contracts/v2` |
| [docs/](docs/) | 平台机制文档：架构、模块开发、TDP 显示协议、可靠性设计、受门禁刷写、AI 复现路线 |

推荐阅读顺序：[docs/01-architecture.md](docs/01-architecture.md) → [docs/03-tdp-protocol.md](docs/03-tdp-protocol.md) → [docs/06-reproduce-with-ai.md](docs/06-reproduce-with-ai.md)。

## 三句话原理

1. **模块注入**：官方固件解包 → 在精确计算的补丁点写跳转钩 → 嵌入自己编译的 Thumb-2 模块 → 蹦床复刻被覆盖的原厂序言后跳回——原厂行为逐字节不变。
2. **显示通道**：手机与眼镜之间跑一套自定义的 TDP 会话协议（QUERY/BEGIN/CHUNK/COMMIT/CLOSE + 回执），镜片侧有一个 LVGL 显示运行时负责把帧画出来。
3. **验证纪律**：所有逻辑在真机之前必须过仿真（Unicorn 跑真实编译产物）+ 主机端单元测试；字节级审计保证补丁落点正确。

## 重建与验证

- 固件：见 [firmware-tvp2c/README.md](firmware-tvp2c/README.md)——`python3 build.py --stock <原厂包> --llvm-bin <钉版 LLVM> --out build/reproduced`，重建产物与发布件字节一致才放行；概要验证结果见 [firmware-tvp2c/verification.json](firmware-tvp2c/verification.json)。
- 插件与 App：分别见 [android-addon/README.md](android-addon/README.md) 与 [whale-mobile/README.md](whale-mobile/README.md)。两者均为自带离线测试的源码树。

## 边界

- 文中涉及的厂商固件/应用仅用于**自用设备的互操作性研究**；请遵守你所在辖区的法律与厂商条款。
- 本仓不含、也不提供获取原厂固件包与宿主 APK 的途径；补丁点与构建清单只适用于文内标注的基线版本。
- 这不依附于、也不代表雷鸟官方。

## 许可证

本仓库原创代码与文档以 [Apache License 2.0](LICENSE) 发布（见 LICENSE）。原厂固件/应用中的既有资源版权归原厂商所有，本仓仅包含项目有权授权的部分。
