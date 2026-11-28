# NetPilot 2.0.3

版本号：versionCode 26 / versionName 2.0.3

本版只修一个崩溃：**Android 17 上应用一启动就闪退**。没有新增功能入口，不改变监控判定与降级策略。

## 现象

在 Xiaomi M2012K11AC / HyperOS / Android 17（SDK 37）上，打开应用即退出。logcat crash buffer 反复出现：

```
java.lang.RuntimeException: Unable to create service io.github.katiusu.netpilot.core.monitor.MonitorService
    at android.app.ActivityThread.handleCreateService(ActivityThread.java:...)
Caused by: java.lang.SecurityException: Starting FGS with type specialUse
    callerApp=ProcessRecord{... io.github.katiusu.netpilot/u0a379} targetSDK=36
    requires permissions: all of the permissions
    [android.permission.FOREGROUND_SERVICE_SPECIAL_USE]
    at android.app.Service.startForeground(Service.java:775)
    at io.github.katiusu.netpilot.core.monitor.MonitorService.onCreate(MonitorService.kt:47)
    at android.app.ActivityThread.handleCreateService(...)
    at android.app.ActivityThread.-$$Nest$mhandleCreateService(...)
    at android.app.ActiveServices.validateForegroundServiceType(ActiveServices.java:3197)
```

## 根因

不是少写权限。manifest 里 `FOREGROUND_SERVICE` 与 `FOREGROUND_SERVICE_SPECIAL_USE` 都在，`dumpsys package` 也显示两者 granted=true，`MonitorService` 的 `foregroundServiceType="specialUse"` 与 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 也都在。

Android 17 把 specialUse 的校验改成了**权限 + app-op 两道门**：该权限的保护级别是 `normal|appop|instant`，AMS 在 `validateForegroundServiceType` 里除了查权限位，还会查同名 app-op。本机 ROM 把这条 app-op 的默认值设成了 `ignore`：

```
$ appops get io.github.katiusu.netpilot
...
FOREGROUND_SERVICE_SPECIAL_USE: ignore
```

于是 `Service.startForeground()` 抛 SecurityException；它发生在 `onCreate` 里，没有任何 try/catch 接住，服务创建失败 → 进程级崩溃 → 应用闪退。

实测确认：`appops set io.github.katiusu.netpilot FOREGROUND_SERVICE_SPECIAL_USE allow` 之后 force-stop 再启动，crash buffer 为空，`dumpsys activity services` 显示 MonitorService `isForeground=true types=0x40000000`（SPECIAL_USE）。同机还有其它应用（如 com.dergoogler.mmrl）也是 `ignore`，可见是 ROM 的默认值，不是本应用写坏了配置。

## 修法（三层，任一层成立都不会再崩）

1. **服务内兜底**（`MonitorService.kt`）：`onCreate` 改为调用 `startForegroundSafe()`，把 `RuntimeException`（SecurityException / MissingForegroundServiceTypeException / ForegroundServiceStartNotAllowedException）全部接住；失败时在 IO 线程跑一次自愈（root 下 `appops set ... allow`），回读确认后重试 startForeground；仍失败则 `degradeToNotice()`：记一条 error 日志 + 推一条「监控未启动」通知 + `stopSelf()`，**进程不崩、界面可用**。
2. **启动前自检**（`MonitorEngine.setEnabled`）：总开关 / 磁贴 / Tasker / 开机自启最终都走这里。起服务前先查 app-op，被拒就先把授权补好再起，避免「起一次、崩一次」的抖动。
3. **可点的修复入口**（`FgsRepairReceiver`）：通知带「立即修复」按钮；点它跑一次 appops 修复（`goAsync` + 独立线程，不占主线程），成功就撤通知并把监控拉回来（仅当总开关开着、用户没点过停止）。
4. `onDestroy` 在降级态不再排「10 秒后拉回」的闹钟：授权没修好之前反复重启只会刷日志、烧电。兜底交给心跳（≤15 分钟）和用户点通知。

关键实现细节：

- app-op 的字符串必须是 `android:foreground_service_special_use`。`appops` 命令行认的写法是 `FOREGROUND_SERVICE_SPECIAL_USE`（下划线）；写成短横线形式会报 `Unknown operation string`。
- `AppOpsManager.unsafeCheckOpNoThrow` 只有在返回 `MODE_IGNORED` / `MODE_ERRORED` 时才判定为拒绝；`MODE_DEFAULT` 视为「未知、交给 startForeground 去试」，避免在没有问题的 ROM 上白跑 root。
- 查询本身抛异常（老 ROM 没有这个 op 字符串）时同样返回「放行」，让 startForeground 决定。
- 降级时服务的 `onStartCommand` 若发现 `foregroundReady == false` 会跳过 `MonitorEngine.startLoop`，避免「界面显示在监控、实际没跑」的假状态。

## 预测性返回手势（同批修复）

- 现象：设置页「预测性返回」开关怎么拨都没反应，返回手势的预览动画不跟随。
- 根因：2.0.1 改为反射 `Activity#setEnableOnBackInvokedCallback` 与 `Window#setEnableOnBackInvokedCallback`。Activity 上那个是 @SystemApi，普通应用调用必抛 `IllegalArgumentException`；Window 上根本没有这个方法（每次 NoSuchMethodException）。两个都被 `runCatching` 静默吞掉，于是什么也没发生。
- 修法（照抄 HyperLyric 的实测实现）：真正的开关是 hidden API `ApplicationInfo#setEnableOnBackInvokedCallback(boolean)`，且必须在 **Activity 创建之前**设置（系统在建 ActivityRecord 时读取）。新增 `core/PredictiveBack.kt`，用工程已有依赖 `org.lsposed.hiddenapibypass:6.1` 解除隐藏 API 限制后反射调用：
  - `TemplateApp.onCreate` → `PredictiveBack.install(this, AppSettings.load(this).predictiveBack)`，保证每次冷启动都按偏好设好；
  - 设置页切开关 → 先落偏好，再 `PredictiveBack.install(application, value)`，然后 `recreate()` 让新标志立刻生效；重建前把 `EXTRA_PAGE = PAGE_SETTINGS` 写回 intent，界面留在设置页而不是弹回首页。
- 影响面：仅返回手势的预览动画；返回功能本身不受影响。没有依赖新库（hiddenapibypass 已在用）。

## 涉及文件

- 新增 `app/src/main/java/io/github/katiusu/netpilot/core/monitor/FgsGate.kt`（app-op 查询 / root 自愈 / 告警通知）
- 新增 `app/src/main/java/io/github/katiusu/netpilot/core/monitor/FgsRepairReceiver.kt`（通知上的修复入口）
- 改 `MonitorService.kt`（安全 startForeground、降级、onStartCommand/onDestroy 守卫）
- 改 `MonitorEngine.kt`（起服务前自检 + `startMonitorService` 收敛入口）
- 改 `AndroidManifest.xml`（注册不导出的 `FgsRepairReceiver`）
- 改 `res/values{,-en,-zh-rTW}/strings_monitor.xml`（6 条文案）
- 新增 `app/src/main/java/io/github/katiusu/netpilot/core/PredictiveBack.kt`（预测性返回应用级开关）
- 改 `TemplateApp.kt`、`MainActivity.kt`（装/切预测性返回）
- 改 `app/src/main/assets/changelog.md`、`app/build.gradle.kts`（versionCode 26 / versionName 2.0.3）

## 验证方式

1. GitHub Actions（`.github/workflows/build.yml`）编译 release，产物 `netpilot-release`。
2. 装回本机后：先把 app-op 故意改回 `ignore`（`appops set io.github.katiusu.netpilot FOREGROUND_SERVICE_SPECIAL_USE ignore`），force-stop，冷启动。
   - 期待：**不崩**；日志里有「startForeground 被系统拒绝」+「已用 root 补上 specialUse 前台服务授权」；服务在前台运行（`dumpsys activity services` 里 isForeground=true）。
   - 若无 root：期待不崩、出现「监控未启动」通知，点「立即修复」提示失败原因。
3. 通知栏「停止」仍然有效；停止后冷启动不会自动拉起。
4. 预测性返回：设置页把开关关掉 → 界面重建并留在设置页；再划返回手势应无预览动画/无系统预测动画；打开后恢复。杀进程冷启动后仍按开关状态（读 SharedPreferences）。

## 已知限制

- 没有 root、也没有 shell 授权的情况下，应用自己无法写 app-op，只能降级提示 —— 这是 Android 17 的设计，不是本应用能绕过的。可让用户手动在系统设置里允许后台运行，或等 ROM 把默认值改回来。
- Shizuku 通道的 shell uid 不一定是 `shell`，写 app-op 可能被拒；本版只走 root，失败即降级，不尝试 Shizuku。
- 本机识屏未授权，本次没有跑 UI 自动化回归；验证以 logcat + dumpsys 为准。
