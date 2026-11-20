# TVP2C · 雷鸟 iO 自定义固件模块源码（611 工作线）

本目录包含两部分，请按标签区分：

1. **`src/`（611 工作线源码）**：21 个运行时模块的 C/Thumb-2 源码（菜单轮播/九宫格、显示页 TDP 运行时、问候开页、动画播放器、推送策略、相机、音乐、阅读、情景英语、诊断、传输压测等），外加构建胶水（`trampolines.S`、`ImageWireFormat.h`、`include/`、`assets/`）。这是当前在眼镜上运行的功能线。
2. **TVP2（1.0.5.5 基线）参考管线**：`build.py`、`build-inputs.json`、`link.ld`、`verification.json`。它们是 **TVP2 参考管线的原样副本**，其钉值清单锁定的是 2026-10-23 的 TVP2 候选构建态，**与本目录 `src/` 的 611 线源码不是同一棵树**，对 611 线源码会拒绝通过（这是设计行为：钉值不匹配即停）。

## 验证状态（诚实口径）

- **611 线源码**：经私有构建链出包，并于 **2026-11-20 通过真机六步验收**；遥测实验 exp-export4 显示 running=1、16ms tick、帧跳过率约 2%。更早的模块级验证为 unicorn 仿真 + 主机端编译测试。
- **TVP2 参考管线**：其 golden 重建记录（钉值、AP/ZIP 字节级一致断言）见 [verification.json](verification.json)，对应公开发布在 [Turbo1123/Turbo-IO](https://github.com/Turbo1123/Turbo-IO) 的 TVP2 版本（1.0.5.5 基线）。**想复现字节级 golden 构建，请使用该仓的 TVP2 源码树 + 本管线。**
- **为什么 611 线的 golden 链不在本仓公开复现**：611 线的构建底包含有合规敏感的前置处理步骤，不适合公开；因此本仓不提供 611 线的钉值清单。611 线的功能正确性以真机验收与仿真/主机测试为证，不以字节级重建为证。

## 构建（611 线源码的自洽编译）

每个模块翻译单元可用钉版 LLVM 独立编译（与参考管线相同的 flags）：

```sh
clang --target=arm-none-eabi -mcpu=cortex-m33 -mthumb -mfloat-abi=hard -mfpu=fpv5-sp-d16 \
  -ffreestanding -fno-builtin -Os -Wall -Wextra -Werror \
  -I src -I src/app-runtime-v1/arm-include -I include -include src/firmware_profile.h \
  -DTIO_IMAGE_RX_WIDE=1 -DTIO_DISPLAY_RUNTIME=1 -DTDP_FREESTANDING -DTIO_ANIMATION_EXPERIMENT=1 \
  -DTIO_MUSIC_RUNTIME=1 -DTD_DIAGNOSTICS_RUNTIME=1 -DTD_FREESTANDING=1 -DTD_STOCK_1055=1 \
  -c src/<module>/<file>.c -o out.o
```

`link.ld` 以绝对地址形式提供全部原厂 ABI 符号（`stream_*`/`nav_*`/`tdp_rnlink_send` 等），模块在无原厂包的情况下即可完成编译与符号解析。注意：`src/` 内含按产品线互斥的变体实现（同名钩子的多份定义），整树直接全链接会出现重复符号——正式构建的模块清单由产品线各自选定。

## 校验（TVP2 参考管线）

| 文件 | SHA-256 |
| --- | --- |
| `StrixOS-1.0.5.5-TurboIO-TVP2-CANDIDATE-NOT-APPROVED.zip` | `154f0f8e68a69b8fb93f869e423081ee43969bf09c5174e8dbeac192c1047c11` |
| `nuttx_ap-TVP2.bin` | `66a2d04faa751c87f830ff324c1d9f9eb96a969ba2c65b37170cc82585970586` |

TVP2 参考管线用法：`python3 build.py --stock <1.0.5.5 原厂 ZIP> --llvm-bin <钉版 LLVM>/bin --out build/reproduced`；`--audit` 对已出包做字节级审计。编译不连接设备、不下载任何私密资料、不刷写。

## 安全边界

非官方、高风险实验固件。完整 OTA 即便只改 AP 仍可能写其他原厂分区；可能无法启动、失去 OTA 或无法恢复。新手请使用官方固件，不要尝试。安装、签名、账号及刷机排错需自理，风险自行承担，不作救砖保证；法律允许范围内的免责不排除依法不能排除的责任。恢复不等于硬件救砖保证。不得跳过候选校验、软件就绪核对、单次授权或刷后保护释放。

原厂代码/资源版权不变；这里只许可本项目有权授权的原创扩展，不能把原厂二进制当作原创源码。
