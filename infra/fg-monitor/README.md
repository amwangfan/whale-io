# infra/fg-monitor — 前台监测 + 截图

> whale-io 基础设施之一（原独立实验仓已并入本目录）。Android 包名 `com.nova.appmonitor.*` 为代码标识，保持不变。

> **实验项目，非官方。** 凭据与 debug keystore 请自行准备；本目录不含 APK、keystore、`.gradle` 缓存。


实时查看当前前台 / 最近打开应用，并触发截图。面向 Root Nova（Android 9 Phh-Treble / NetHunter），同时准备非 Root 变体。

包名：`com.nova.appmonitor.root` / `com.nova.appmonitor.user`  
minSdk 28（Android 9）

## 能力矩阵

| 能力 | Root flavor | User（非 Root）flavor |
|------|-------------|----------------------|
| 前台应用（包名+标签+时间） | UsageStats + Accessibility；可选 `su dumpsys window` | UsageStats + Accessibility |
| 最近列表 | 内存环形缓冲（约 100 条） | 同左 |
| 截图 | `su -c screencap -p` | MediaProjection（每次会话需用户同意） |
| 权限 | Usage Access；建议开无障碍；Root | Usage Access；无障碍；截图授权弹窗 |
| 本地 HTTP 桩 | `LocalFeedStub`（默认未启动，端口 18796，`GET /v1/fg`） | 同左 |

与 leaf5 `/v1/devices` 遥测无关；本应用独立，HTTP 桩仅预留日后云端喂数。

## 架构要点

### Root 路径
- **前台**：`ForegroundAppMonitor` 轮询 `UsageStatsManager`；有 su 时额外解析 `dumpsys window` 的 `mCurrentFocus` / `mResumedActivity`。
- **更快推送**：可选开启 `FgAccessibilityService`（`TYPE_WINDOW_STATE_CHANGED`）。
- **截图**：`ScreenshotHelper` → `su -c 'screencap -p <path>'`，写入应用私有 `Pictures/`。

### 非 Root 路径
- **前台**：必须授予 **PACKAGE_USAGE_STATS**（设置 → 使用情况访问）；强烈建议开无障碍服务以降低轮询延迟。
- **截图**：`MediaProjection` 用户同意后，`MediaProjectionService` 单次抓帧保存 PNG。

### UI
- 顶部：当前前台（应用名、包名、时间戳、来源）
- 按钮：开始/停止监测、截图、跳转权限设置
- 列表：最近切换历史

## 构建

```bash
cd infra/fg-monitor
# 勿走代理（本机若设了 proxy 先 unset）
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY

export JAVA_HOME=<JDK17_PATH>
export ANDROID_HOME=<ANDROID_SDK_PATH>

./gradlew :app:assembleRootDebug :app:assembleUserDebug
```

产物：
- `dist/nova-app-monitor-root-debug.apk`（包名 `com.nova.appmonitor.root`）
- `dist/nova-app-monitor-user-debug.apk`（包名 `com.nova.appmonitor.user`）
- 原始：`app/build/outputs/apk/{root,user}/debug/`

## 安装（成功构建后再做；勿盲目推）

Root Nova 示例（Tailscale，密钥本机已配置时）：

```bash
scp -i ~/.ssh/<ssh_key> \
  app/build/outputs/apk/root/debug/app-root-debug.apk \
  root@<TAILSCALE_IP>:/data/local/tmp/nova-app-monitor.apk
ssh -i ~/.ssh/<ssh_key> root@<TAILSCALE_IP> \
  '/system/bin/pm install -r -g /data/local/tmp/nova-app-monitor.apk'
```

首次打开后：
1. 授予「使用情况访问」
2. （建议）开启无障碍「Nova Monitor」
3. 点「开始监测」；Root 版可直接「截图」

## 目录

```
app/src/main/          共用 Kotlin / 资源
app/src/root/          Root flavor 标记
app/src/user/          User flavor 标记
BuildConfig.IS_ROOT    运行时分支截图与 dumpsys
```
