# 实验签名包安装

基线：**TurboIO 1.0.10**，`versionCode` **258**（简称 vc258）。  
目标机：Magic8 一类真机。**不刷雷鸟眼镜固件**，除非另有明确说明。

## 为什么要先卸官方宿主

实验包使用 **实验签名**，与荣耀应用市场 / 官方渠道的宿主签名不同。  
Android 不允许「同包名、不同签名」直接覆盖安装，因此必须：

1. **先卸载** 官方签名宿主（包名以实际宿主为准，常见为雷鸟公开宿主包）。
2. 再安装实验签名的 1.0.10（vc258）包。
3. 需要回官方渠道时：卸实验包 → 再从商店重装官方包。

> APK 本体 **不入库**。构建产物放在本机或私有制品区，用 `adb install` 安装。

## 安装步骤（示意）

将 `<serial>`、`<host.apk>` 换成你的设备序列号与本机 APK 路径：

```bash
# 查看设备
adb devices

# 卸载官方 / 旧实验宿主（包名以真机为准）
adb -s <serial> uninstall <host.package.name>

# 安装实验包
adb -s <serial> install -r <host.apk>

# 核对版本
adb -s <serial> shell dumpsys package <host.package.name> | grep -E 'versionName|versionCode'
```

期望看到类似：`versionName=1.0.10`，`versionCode=258`。

## 插件 / 附属包

- PhoneBridge、轻量 HUD 等以 **插件或宿主内模块** 形式随实验包或后续分仓发布。
- 假来电、前台监测是 **独立 App**，安装流程见 [`infra/call-bridge/`](../infra/call-bridge/) 与 [`infra/fg-monitor/`](../infra/fg-monitor/)；默认不替代雷鸟宿主。

## 明确不做

| 动作 | 默认 |
|------|------|
| 刷眼镜固件 | **否**（除非另说） |
| 把 APK / keystore 提交 Git | **否** |
| 用非 vc258 ABI 盲打包注入 | **否**——先钉宿主 profile |
| 声称「商店官方包」 | **否**——本文只谈实验签名包 |

## 回滚

1. `adb uninstall` 卸下实验宿主。  
2. 从官方渠道重装对应版本。  
3. 眼镜侧保持原厂固件，无需因手机实验包而刷机。
