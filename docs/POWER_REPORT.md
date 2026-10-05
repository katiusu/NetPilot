# NetPilot 后台耗电优化报告（1.2.0）

> 版本：`versionName = "1.2.0"`，`versionCode = 2026100501`（对比基线 1.1.0 / 2026100500）
> 机型与运行环境：见 §1.1（由用户自测表补齐整机口径）
> 本报告的全部命令都在仓库根目录执行，可直接复制复现。

## 0. 先讲清楚：这份报告证明了什么、没证明什么

本次优化的判断标准是「一件一件量，量不出来就不算数」。**结论分两类，请不要混着看**：

| 类别 | 状态 | 说明 |
|---|---|---|
| **确定性开销**（每小时发起多少次探测、多少次通知、多少次落盘、多少次唤醒） | ✅ **已量化**，代码级可复现 | 用「代码推算 + 原样命令输出」给出前后对照，见 §1.3 与 §2 |
| **真机内存占用**（PSS、残留特权进程） | ✅ **已量化**，设备实测 | `dumpsys meminfo` 前后实测，见 §1.2 |
| **整机待机 1 小时耗电百分比** | ⚠️ **未由本次采集**，需用户按 §4.2 自测 | 采集手段被环境策略拦截（见 §1.1 说明）；**因此本报告不宣称任何「省了百分之多少电」的数字** |
| **降级判定语义未变** | ✅ **已证明**，真值表 + 逐分支核对 | 见 §3，这是本次改动里风险最高的一项，证明写得最细 |

「确定性开销」为什么算数：待机场景下这些开销就是耗电的全部来源（射频尾时间、定时唤醒、SharedPreferences 写入、binder 调用）。把每一项的**次数**降下来，是可以在不测整机电流的前提下成立的推理；但它**不等于**电池百分比一定下降同样的比例，最终数字仍要用户实测。

---

## 1. 基线（改动前）

### 1.1 采集条件与限制（先说清楚，后面所有数字都受它约束）

| 项 | 情况 |
|---|---|
| 设备 | 用户的小米/Android 14+ 真机，`adb-shell` 身份 `uid=0(root)`，`context=u:r.ksu:s0` |
| 基线应用 | 已安装的 **1.1.0（2026100500）** |
| 采集命令 | `/root/dsh-bin/adb-shell`（不是裸 `adb`，那是守卫包装脚本） |
| ❌ 被拦截 | `dumpsys alarm`、`dumpsys batterystats`、`dumpsys deviceidle` → `[POLICY_BLOCKED]`；`pm install`（**所以本次无法由 agent 装机跑前后对比**） |
| ✅ 可用 | `dumpsys meminfo com.katiusu.netpilot`（**只接受包名，不接受 pid**）、`ps -A`、`logcat` |
| 结论 | **整机耗电/唤醒次数由系统侧采集不可行**，这两种口径只能靠用户自测（§4.2）；本次把能采集的都采了，把采不到的明确标出来 |

### 1.2 实测基线（设备侧原始输出）

**残留的 Shizuku 用户服务进程**（`adb-shell ps -A` + `dumpsys meminfo`）：

| 进程 | PPID | 已存活 | PSS |
|---|---|---|---|
| `com.katiusu.netpilot:np_service` #1 | 1 | 21.5 h | 52.7 MB |
| `com.katiusu.netpilot:np_service` #2 | 1 | 20.8 h | 46.9 MB |
| `com.katiusu.netpilot:np_service` #3 | 1 | 19.6 h | 49.9 MB |
| `com.katiusu.netpilot:np_service` #4 | 1 | 19.0 h | 51.8 MB |
| **合计** | | | **≈201 MB** |

**主进程 PSS**：`16 120 K`（`dumpsys meminfo com.katiusu.netpilot`）。

> PPID=1 说明这四个进程的父进程已经没了——它们是应用被系统杀掉时来不及 `unbindUserService(remove = true)` 留下的孤儿，**不属于任何正在工作的功能**，纯占内存。

### 1.3 由代码推算的确定性开销（改动前）

| 机制 | 位置 | 优化前每小时的次数 |
|---|---|---|
| 引擎主循环探测 | `core/monitor/MonitorEngine.kt` `while (isActive)` + `delay(interval)`，`DEFAULT_INTERVAL = 60` | **60 次**（正常；最坏每轮重试 4 个目标 = 240 次） |
| 监控页快速采样 | `ui/screen/monitor/MonitorPage.kt` `FAST_SAMPLE_INTERVAL_MS = 5_000L` | **最多 720 次**（最坏 2880 次）——且**退到后台也不会停**，见下 |
| 前台服务通知重绘 | `core/monitor/MonitorService.kt` `collectLatest { updateNotification(it) }` | **60 次**（每轮必 `notify`，无「文本是否变化」判断） |
| 日志落盘 | `core/monitor/LogStore.kt` `PERSIST_INTERVAL_MS = 4_000L` | **最多 900 次**（120 条日志序列化成 40+ KB JSON 后整体重写） |
| 保活心跳唤醒 | `core/keepalive/KeepAliveScheduler.kt` `HEARTBEAT_INTERVAL_MS = 15 min` | **96 次/天**，且每次心跳都会重新注册重复闹钟 |
| 被杀后重启 | `MonitorService.onTaskRemoved` / `onDestroy` → `scheduleRestartSoon` | 固定 10 秒（服务反复崩 → 10 秒一次重启风暴） |

**「5 秒快采退到后台也不停」是怎么确认的**（这是本次最大的单项发现）：

1. `MainActivity.kt` 的 `HorizontalPager(beyondViewportPageCount = 1)` 会把当前页**和相邻 1 页**一起组合出来；页面 `when (page)` 分支没有 `if (pagerState.currentPage == page)` 判断 ⇒ 切到相邻标签页时 `MonitorPage` 只是移出视口，**`LaunchedEffect` 不会取消**。
2. 应用退到后台时，Compose 不会销毁组合（View 没 detach），而 `LaunchedEffect` 里的 `delay()` 恢复靠的是 `DefaultDelay`，**不受「窗口是否可见 / 是否有帧」约束** ⇒ `while (isActive) { NetPilot.sampleNow(context); delay(5_000) }` 继续跑。
3. `NetPilot.sampleNow()` → `MonitorEngine.sampleOnce()` → `e.tick()` → `SignalReader.read()` ⇒ **每 5 秒一次真实 HTTP 探测**，每次探测都会把蜂窝射频拉进高功率态。
4. 唯一的上游护栏是 `MonitorPage.kt` 里「降级进行中的阶段跳过注入式采样」，它只覆盖态机的少数阶段，正常状态下不生效。

⇒ 只要用户打开过监控页，之后**每小时最多 720 次探测**会持续发生，是引擎主循环（60 次）的 **12 倍**。待机场景下这是头号耗电源。

---

## 2. 改动清单

七个主题，**每个主题单独改、单独编译、单独验证**（不混做，才能把数据归因到具体一处）。

### C1 界面 5 秒快采：只在「前台 + 当前页」时运行

| 项 | 内容 |
|---|---|
| 级别 | 一级（可证明零行为变化） |
| 文件 | `MainActivity.kt`（`isForeground` 状态 + `onPause` + 传参）、`MainScreen` 签名、`ui/screen/monitor/MonitorPage.kt`（每轮屏幕状态护栏） |
| 省电原理 | 去掉待机时每小时最多 720 次真实 HTTP 探测（连带蜂窝射频尾时间）；用户停留在监控页且应用在前台时行为**完全不变** |
| 改动 | ① `MainActivity` 新增 `private var isForeground by mutableStateOf(false)`；`onResume` 置 `true`、新增 `onPause` 置 `false`；② 分页内容传入 `liveSampling = isForeground && pagerState.currentPage == page`；③ `MainScreen(` 顶层 Composable 增加 `isForeground: Boolean` 形参（它是文件级顶层函数，看不到 Activity 的成员）；④ `MonitorPage.kt` 采样循环里加每轮护栏 `isScreenInteractive(context)`（读 `PowerManager.isInteractive`，读不到时保守返回 `true`）——**为什么非加不可**：`liveSampling` 是宿主通过重组传进来的，而 Compose 的重组要等下一帧（`withFrameNanos` 等 VSYNC），屏幕一关系统就不再投递 VSYNC，传进来的 `false` 迟迟不生效，而循环里的 `delay()` 走 `DefaultDelay` 不受帧约束 ⇒ 会继续每 5 秒发起一次真实探测。这条路径只靠开关盖不住，必须让循环自己每轮问一次系统 |
| 前 | 应用退到后台后仍每 5 秒一次 HTTP 探测（≤720 次/h） |
| 后 | 后台 **0 次/h**（含屏幕关闭：由每轮护栏保证，不依赖重组）；仅「应用在前台 + 当前标签页就是监控页 + 屏幕处于交互状态」时保持 5 秒快采（原行为） |
| 验证 | 代码：`MainActivity.kt` 的 `liveSampling` 表达式 + `MonitorPage.kt:156` 的 `!countingPhase && isScreenInteractive(context)`；回归：应用退到后台、**以及关屏 1 分钟**，日志页都不再出现 5 秒一条的采样记录（§4.2 步骤 4、`docs/TESTING.md` §16.2 步骤 4） |
| 回滚 | 把 `liveSampling = isForeground && pagerState.currentPage == page` 改回默认（删掉该实参即可回落 `MonitorPageView` 的 `liveSampling: Boolean = true`） |

### C2 探测前短路：屏幕关闭 + 未降级 + 信号非强时，跳过整轮 HTTP 探测

| 项 | 内容 |
|---|---|
| 级别 | 一级（**可证明零行为变化**，证明见 §3） |
| 文件 | `core/monitor/MonitorModels.kt`、`core/monitor/SignalReader.kt`、`core/monitor/AutoDowngradeEngine.kt`、`core/monitor/MonitorEngine.kt`、`ui/screen/monitor/MonitorPage.kt`、`core/monitor/MonitorService.kt`、`core/NetPilot.kt`、两个 `strings_monitor.xml` |
| 省电原理 | 判定真正会用到 Ping 的只有一条路径（强信号分支），而该分支只在屏幕亮着/可能降级时才有意义。屏幕关、状态机空闲、且信号**不强**时，Ping 结果对判定没有任何影响 ⇒ 整轮不联网，省掉一次 DNS+TCP+TTFB 与随之而来的射频尾时间 |
| 改动 | ① `SignalSnapshot` 新增 `val probeSkipped: Boolean = false`（把「没测」与「测了没通」分开，默认 false 保证既有构造点行为不变）；② `SignalReader.read(...)` 新增 `allowProbeSkip: Boolean` 与 `strongRsrpThreshold: Int` 两个**无默认值**参数（强制调用方显式表态）；③ `val skipProbe = allowProbeSkip && !(rsrp != null && rsrp > strongRsrpThreshold)`，跳过时用 `PingResult(ms = null, error = null, target = pingTarget)`；④ `AutoDowngradeEngine.tick(allowProbeSkip: Boolean = false)`，其中 `maySkipProbe = allowProbeSkip && !_state.value.active && !isScreenInteractive()`；⑤ `noResponse = snapshot.pingMs == null && !snapshot.isWifi && !snapshot.probeSkipped`（**关键**：跳过不能让 `noNetFailCount` 计数）；⑥ `MonitorEngine` 主循环用 `e.tick(allowProbeSkip = true)`，界面/手动采样仍走默认 `false`；⑦ 三处显示文案加 `monitor_value_ping_skipped`（中文「已跳过探测（屏幕关闭）」/英文 "Probe skipped (screen off)"） |
| 前 | 屏幕关、信号非强时照样每 60 秒一次 HTTP 探测 |
| 后 | 该场景 **0 次/h**；屏幕亮、或信号强（`rsrp > -85`）、或已处于降级态（要判恢复/回滚）→ **仍然探测**，行为不变 |
| 验证 | §3 真值表 + 逐分支核对；§4.2 步骤 3 真机复核 |
| 回滚 | `MonitorEngine` 主循环把 `e.tick(allowProbeSkip = true)` 改回 `e.tick()` 即可完全关闭短路（其余参数与文案留着也无害） |

### C3 保活心跳去重 + 重启指数退避

| 项 | 内容 |
|---|---|
| 级别 | 一级 |
| 文件 | `core/keepalive/KeepAliveReceiver.kt`、`core/keepalive/KeepAliveScheduler.kt`、`core/keepalive/KeepAliveState.kt`、`core/monitor/MonitorService.kt` |
| 省电原理（心跳去重） | 原实现每次心跳都执行 `KeepAliveScheduler.schedule(app)` 重新注册 `setInexactRepeating`：这是**一次多余的 binder 调用**，而且会把下次触发推回 `now + 15 min`——闹钟相位被不断重置，Doze 的批处理窗口被反复打乱。而 `setInexactRepeating` 本身就是系统自续期的，只有在「闹钟已被系统清掉」时才有必要重排；**可闹钟真被清掉时接收器根本不会被执行**，所以站在心跳里重排既救不了它、又白付代价。 |
| 省电原理（退避） | 服务反复崩溃时固定 10 秒重启是「重启风暴」，一次崩溃会连累 10 秒一次的前台服务启动 + 通知重建 |
| 改动 | ① `KeepAliveReceiver` 的 `ACTION_HEARTBEAT` 分支删掉 `if (ServicesGate.enabled(app)) { KeepAliveScheduler.schedule(app) }`，只留 `ensureMonitorRunning(app, "保活心跳")`；② `KeepAliveState` 新增 `KEY_RESTART_DELAY_MS` / `KEY_LAST_START_AT`（都带 `runtime_` 前缀，`ConfigBackup` 会跳过）与四个读写方法；③ `KeepAliveScheduler.scheduleRestartSoon(context, delayMs = -1L)`（负数=按退避自算）+ `nextBackoffDelay()`：上次启动已稳定超过 2 分钟 ⇒ 10 秒（**与优化前完全一致**），否则翻倍，上限 15 分钟；④ `MonitorService.onCreate` 记录 `noteServiceStarted` |
| 前 | 每次心跳 1 次多余 binder（重排闹钟）+ 1 条 DEBUG 日志（会触发一次日志落盘）；崩溃时固定 10 秒重启 |
| 后 | 每次心跳只剩「一次进程内的 `MonitorEngine.running.value` 判断」；崩溃重启 10 s → 20 s → 40 s → … → 15 min |
| 验证 | `adb logcat -s NetPilot` 观察心跳时刻不再出现重排动作；正常「从最近任务划掉」仍是 10 秒拉回（§4.2 步骤 5） |
| 回滚 | 把删掉的三行 `schedule` 调用还原；`scheduleRestartSoon` 默认值改回 `DEFAULT_RESTART_DELAY_MS` |

> **刻意没做的事（重要）**：**没有**取消那个 15 分钟重复闹钟。它是「应用进程被系统在后台悄悄杀掉」之后唯一的自动拉回手段（进程死了就没有任何代码能再排闹钟，而 `AlarmManager` 的闹钟由系统持有、跨进程死亡依然会触发）。取消它 = 砍掉保活兜底，属于「会改变用户可见行为」的二级候选，**必须用户确认才能做，本次没做**。所以**唤醒次数维持 96 次/天不变**，降低的是每次唤醒的代价。

### C4 日志落盘：节流 4 s → 30 s，序列化移出调用线程

| 项 | 内容 |
|---|---|
| 级别 | 一级 |
| 文件 | `core/monitor/LogStore.kt` |
| 省电原理 | 原实现每次落盘都要在**调用者线程**（可能是主线程）把 120 条日志拼成 JSON 再 `SharedPreferences.apply()` 整体重写（含 fsync）。降到 30 秒一次后，写次数上限从 900 次/h 降到 120 次/h，且序列化与磁盘 I/O 移到单线程 daemon 执行器，不再占用主线程/监控线程 |
| 改动 | ① `PERSIST_INTERVAL_MS` `4_000L → 30_000L`；② 新增 `private val writer = Executors.newSingleThreadExecutor { … "NetPilot-LogWriter" … isDaemon = true }`；③ `persist(force, blocking)` 先**在当前线程取快照**（`SnapshotStateList` 不能跨线程延迟读），再交给执行器；④ `writeEntries()` 用 `commit()` 而非 `apply()`（已在后台线程，且能拿到成功与否，失败重新标脏）；⑤ `clear()` 改成同步落盘（用户主动清空必须写完再返回），`flush()` 仍异步并在 KDoc 里写明 |
| 前 | ≤900 次/h 整体重写 + 40+ KB 序列化，可能发生在主线程 |
| 后 | ≤120 次/h，全部在后台线程，失败可重试 |
| 验证 | 日志页仍显示最多 400 条；杀进程重开仍恢复约 120 条；单条仍截断 2000 字符（§4.2 步骤 6） |
| 回滚 | `PERSIST_INTERVAL_MS` 改回 `4_000L`；`persist` 内部退化为原来的同步路径 |

### C5 通知去重 + Shizuku 孤儿进程清扫

| 项 | 内容 |
|---|---|
| 级别 | 一级 |
| 文件 | `core/monitor/MonitorService.kt`、`core/priv/shizuku/ShizukuController.kt`、`core/priv/ControlManager.kt`、`TemplateApp.kt` |
| 省电原理（通知） | `MonitorEngine.publish()` 每轮都会写 `_lastTickAt` ⇒ `StateFlow` 每轮都发射 ⇒ `collectLatest { updateNotification(it) }` 每轮调用一次 `NotificationManager.notify`（一次跨进程 binder + SystemUI 重绘）。而通知文本只由「网络类型 / RSRP / ping」三项决定，信号稳定时**整串文本根本没变** |
| 省电原理（孤儿） | 应用被系统杀掉时来不及 `unbindUserService(remove = true)`，Shizuku 用户服务进程会变成 PPID=1 的孤儿长期驻留（实测累积 4 个、≈201 MB）。原清扫只在「真的需要特权通道时」才触发——而网络健康时根本走不到那条路径，所以孤儿一直在攒 |
| 改动 | ① `MonitorService` 新增 `@Volatile private var lastNotificationText: String? = null`，`updateNotification()` 开头 `if (text == lastNotificationText) return`；② `ShizukuController` 新增 `suspend fun pruneOrphanedServices(): Int`（绑定 → 调 `pruneStaleProcesses()` → **`finally { destroy() }`**，清完不留自己这个新孤儿）；③ `ControlManager` 加门面；④ `TemplateApp.onCreate` 末尾在 daemon 线程 `runBlocking` 调一次 |
| 前 | 每轮 1 次 `notify`（60 次/h）；特权进程累积到 4 个 / ≈201 MB |
| 后 | 只在文本真变化时 `notify`（信号稳定时 0 次）；应用每次启动清扫一次，稳定在 0–1 个 |
| 验证 | §4.2 步骤 7（通知不闪、不重复弹）；`adb logcat -s NetPilot` 出现 `pruned N stale Shizuku user service process(es)`、`ps -A \| grep np_service` 只剩当前绑定的一个 |
| 回滚 | 删掉 `lastNotificationText` 判断；删掉 `pruneOrphanedServices` 三处与 `TemplateApp` 的调用 |

> 清扫安全性：`StaleProcessPruner` 只杀 cmdline 含 `<包名>:np_service` **整串**的 pid，并显式跳过 `Process.myPid()` 与 `pid <= 1`，全程 `runCatching` ⇒ 临时绑定后调用它不会杀掉自己。

### C6 targetSdk 34 → 36（compileSdk 保持 37）+ 版本号 1.2.0

| 项 | 内容 |
|---|---|
| 级别 | 合规（不影响省电，但属于用户明确要求） |
| 文件 | `app/build.gradle.kts` |
| 改动 | `targetSdk = 36`、`versionCode = 2026100501`、`versionName = "1.2.0"`（`compileSdk` 保持 37；`buildToolsVersion` 保持 36.0.0） |
| 为什么现在就升 | Google Play 对 targetSdk 36 的提交要求已于 2026-08-31 生效（可延期至 2026-11-01） |
| 逐条核对（本次实测/查证） | ① **edge-to-edge**：5 个 Activity 均已 `enableEdgeToEdge()`，且 manifest 没有 `windowOptOutEdgeToEdgeEnforcement` ✅；② **预测性返回**：manifest 已 `android:enableOnBackInvokedCallback="true"`，全工程无 `onBackPressed` / `BackHandler` ✅；③ **FGS**：本应用是 `specialUse`，Android 15 的 6 小时超时只针对 dataSync/mediaProcessing，`BOOT_COMPLETED` 禁启的 6 类也不含 `specialUse`，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 已声明 ✅；④ **16 KB 页**：APK 里的 4 个 `libandroidx.graphics.path.so` 全部 LOAD 段 `p_align = 16384`，`zipalign -c -P 16 -v 4` 通过 ✅；⑤ Android 16 的 targetSdk 36 清单里**没有**通知类行为变更；⑥ 大屏方向限制、有序广播 `priority`、`elegantTextHeight`：本应用均未使用 ✅ |
| 已知陷阱（已规避） | compileSdk ≥35 且 minSdk ≤34 时，`removeFirst()` / `removeLast()` / `getFirst()` / `reversed()` 这类 JDK 21 集合方法会在 Android 14 上 `NoSuchMethodError`。全工程 grep 无使用，**后续新增代码也不要使用，用 `removeAt(0)` / `removeAt(lastIndex)`** |
| 验证 | `aapt2 dump badging` 显示 `targetSdkVersion:'36'`、`versionName='1.2.0'`、`versionCode='2026100501'` |
| 回滚 | 三个数字改回去 |

### C7 新增自动更新模组（只查、只提示，不静默安装）

| 项 | 内容 |
|---|---|
| 级别 | 新功能（用户明确要求） |
| 文件 | **新增** `core/update/UpdateChecker.kt`（111 行）、**新增** `ui/component/UpdateDialog.kt`（86 行）；接线 `ui/screen/about/AboutPage.kt`、`MainActivity.kt` |
| 检查地址 | GitHub Releases API：`https://api.github.com/repos/katiusu/NetPilot/releases/latest`（即本项目的 Releases 页，人工下载页 `https://github.com/katiusu/NetPilot/releases`） |
| 行为 | ① 启动时按设置项 `check_update_on_launch`（默认开）检查一次；② 关于页新增「检查更新」入口（`ArrowPreference`），手动触发；③ 有新版 → `WindowDialog` 展示版本号与 release notes，点「立即更新」用系统浏览器打开 Releases 页；④ **不静默下载、不自动安装、不后台轮询** |
| 省电影响 | 每次检查就一次 HTTPS 请求（15 秒超时），且**只在应用启动时或用户主动点击时**发生 ⇒ 后台零开销。这与本报告的整体方向一致：不引入任何常驻轮询 |
| 文案 | 复用工程里早已存在但从未被引用的 `strings.xml:173-184` 一组（`check_update` / `check_update_summary` / `update_available_title` / `update_later` / `update_now` / `update_latest` / `update_check_failed`）——**本次没有新增任何文案资源** |
| 验证 | §4.2 步骤 8 |
| 回滚 | 删除两个新文件 + `AboutPage` 的 `ArrowPreference` 与状态块 + `MainActivity` 的 `LaunchedEffect` 与对话框块（共 4 处） |

---

## 3. 正确性证明：跳过探测为什么不改变判定语义

这是本次风险最高的改动，证明要写到「逐分支核对」。**结论：在 `rsrp != null && rsrp > rsrpThreshold`（即 `isStrong`）之外的所有情况下，`judge()` 的返回值与状态机走向都不依赖 `pingMs`。**

### 3.1 第一层：全仓 `pingMs` 消费点普查（14 个语义点，只有 2 个能影响行为）

| # | 位置 | 用途 | `pingMs == null` 的后果 | 能改变行为？ |
|---|---|---|---|---|
| D1 | `core/monitor/AutoDowngradeEngine.kt` `noResponse` 计算 | `无响应` 计数 → 无网回退 | 计数 +1，2 轮后回滚制式 | ⚠️ **能**（见 3.2） |
| D2 | `AutoDowngradeEngine` → `SignalReader.read(...)` | 传入 ping 目标与超时 | 配置入参 | 否 |
| D3 | `core/monitor/FakeSignalDetector.kt` `judge()` | 判定 | 仅强信号 + `downgradeOnPingFail=true` 时翻盘 | ⚠️ **能**（见 3.3） |
| D4 | `ui/screen/monitor/MonitorPage.kt` Ping 行 | 逐级回退显示 | 显示下一级文案 | 否（显示） |
| D5 | `MonitorPage` 的 ping 异常详情行 | 显示错误 | 该行不渲染 | 否（显示） |
| D6 | `MonitorPage` 的 `downgradeOnPingFail` 开关 | 显示设置 | 无关 | 否 |
| D7 | `MonitorPage` 的判定结果区 | 显示 judge 输出 | 无关 | 否 |
| D8 | `ui/screen/monitor/MonitorCriteria.kt` | 拼「判定标准」人话 | **不读 pingMs**，只用阈值 | 否 |
| D9 | `ui/screen/home/HomePage.kt` 延迟卡片 | 显示 | 显示 idle 文案 | 否（显示） |
| D10 | `core/monitor/MonitorService.kt` 通知文本 | 显示 | 显示「无响应」 | 否（显示） |
| D11 | `core/NetPilot.kt` `statusText()` | 磁贴 / Tasker 状态串 | 显示「无响应」 | 否（显示） |
| D12 | `core/tasker/TaskerEventSender.kt` | 事件 extra | **不写** `netpilot.ping_ms` | 否（刻意区分「读不到」与 0） |
| D13 | `core/tasker/TaskerContract.kt` | extra 名定义 | — | 否 |
| D14 | `core/tasker/TaskerCommandReceiver.kt` | `SAMPLE_NOW` / `GET_STATUS` | 无 extra | 否 |
| D15-17 | `LogStore` / `LogPage` / `DowngradeTileService` | — | **零消费** | 否 |

⇒ 只有 **D1**（状态机计数）与 **D3**（judge 判定）需要证明。显示类只需保证「不显示成『失败』」，本次用新增的「已跳过探测（屏幕关闭）」文案覆盖。

### 3.2 第二层：D1 路径——`noResponse` 只在「已降级」分支被消费

`applyTransition()` 里的完整路径：

```
val hasCellular = snapshot.rawNetworkType != 0
val noResponse  = snapshot.pingMs == null && !snapshot.isWifi      // ← 本次改为 && !snapshot.probeSkipped
...
未降级分支（L72-134）：完全不读 noResponse  ← 关键事实
...
已降级分支（L137 起）：if (noResponse || !hasCellular) { noNetFailCount++ … 达阈值则 rollback }
```

⇒ `noResponse` 只有在**状态机已经处于降级态**时才有意义。而短路条件里包含 `!_state.value.active`（未降级），**两个条件互斥** ⇒ 跳过探测的那些轮次，`noResponse` 无论如何都不会被读到。

那为什么还要加 `&& !snapshot.probeSkipped`？**为了让「未测量」在任何路径下都不被当成「测量失败」**。这是防御性的一行：即使将来有人改了分支结构、或与并发 tick 撞上（见 3.5），也不会出现「因为没测所以判定断网」的荒谬结果——而这正是「省电做成漏检」的典型翻车方式。

### 3.3 第三层：D3 路径——`judge()` 逐分支真值表

`judge(snapshot, thresholds)` 的分支结构与「是否读取 `pingMs`」：

| 行 | 分支条件 | 是否读 `pingMs` | 结果依据 |
|---|---|---|---|
| 1 | `isWifi && rawNetworkType == 0`（Wi-Fi 且未驻留蜂窝） | **完全不读**（该 return 在所有 ping 读取之前） | `fake = false`，detail「当前在 Wi-Fi 且未驻留蜂窝，跳过降级判定」 |
| 2 | `isStrong`（`rsrp != null && rsrp > rsrpThreshold`），`sinr >= sinrThreshold` | **读**（`ping > pingThresholdMs` → 加一条 reason → `fake = true`） | 假满格规则 |
| 3 | `isStrong`，`sinr < sinrThreshold` | 结果已定 | SINR 那条 reason 已经让 `reasons` 非空 ⇒ 必 `fake = true`，ping 只改 detail 文案 |
| 4 | `isStrong`，`pingMs == null && downgradeOnPingFail` | **读**（null 会翻盘成 `fake = true`） | 默认 `downgradeOnPingFail = false` ⇒ 不触发 |
| 5 | `!isStrong`，`downgradeOnWeakSignal && rsrp < weakRsrpThreshold` | **不读** | `fake = true`，WEAK_SIGNAL |
| 6 | `!isStrong`，兜底（含 `rsrp == null`） | **不读** | `fake = false` |

**把 2/3/4 合并**，得到一条干净的等价命题：

> `judge()` 读取 `pingMs` ⟺ `isStrong`（`rsrp != null && rsrp > rsrpThreshold`）

而短路条件正是它的取反：

```kotlin
val skipProbe = allowProbeSkip && !(rsrp != null && rsrp > strongRsrpThreshold)
```

⇒ **`skipProbe == true` 时，`judge()` 一定落在「不读 ping」的分支上，判定结果与真实探测结果无关。** 证毕。

（第 1 行的 Wi-Fi 早退分支在 `isStrong` 之后、与 ping 无关，且本次改动没有触碰 Wi-Fi 分支：`isWifi && rawNetworkType == 0` 时 `rsrp` 通常为 null ⇒ 不满足 isStrong ⇒ 本来就会走短路，判定恒为 `fake = false`，与探测与否无关。）

### 3.4 第四层：短路的完整触发条件（三个条件同时成立才跳过）

```kotlin
val maySkipProbe = allowProbeSkip          // ① 只有引擎主循环传 true；界面/手动/Tasker 采样传 false ⇒ 永远真探测
        && !_state.value.active            // ② 状态机当前未降级（已降级要判恢复/回滚，必须测）
        && !isScreenInteractive()          // ③ 屏幕关闭；读不到 PowerManager 时保守返回 true（=不跳过）
```

- **屏幕亮** → 完全按原行为（包括监控页显示实时 Ping）。
- **已降级** → 完全按原行为（恢复计数、无网回滚都依赖 ping）。
- **界面/手动采样 / Tasker `SAMPLE_NOW`** → `allowProbeSkip = false`，永远真探测。
- **强信号（`rsrp > -85`）** → 不跳过（因为按 3.3 命题，此时 ping 会参与判定）。

⇒ 省电只发生在「**屏幕关 + 状态机空闲 + 信号非强**」这一种场景——也就是「手机放兜里/桌上、信号不太好但也没到降级线、屏幕黑着」的待机态。这正是待机耗电的主场景。

### 3.5 已知的唯一竞态（如实记录）

`_state.value.active` 在 `read()` **之前**读一次，`applyTransition()` 在同一轮 tick 末尾用它。若此时另一个 tick 并发把状态推进到降级态（只有 Tasker `SAMPLE_NOW` / 界面采样会并发触发，且两者都不跳过探测），这一轮的 `noResponse` 会被 `probeSkipped` 抑制、少记一次「无响应」。下一轮 `_state.value.active` 已为 `true` ⇒ 恢复真探测，计数照常。

- 影响面：仅在「屏幕关 + 恰好有并发手动采样并触发降级」时，最多少记 1 轮 `noNetFailCount`（回滚阈值默认 2 轮 ⇒ 最多晚一轮回滚）。
- 方向：保守（**偏向不误判断网**，不会造成「漏检降级」）。
- 该并发在优化前就存在（原代码同样允许两个 tick 交叠），本次没有新增并发。

---

## 4. 量化对照与验证

### 4.1 前后对照汇总表（确定性开销）

「优化前」按 1.1.0 的代码推算，「优化后」按 1.2.0 的代码推算。**场景：手机待机（屏幕关、静止）、用户此前打开过监控页** —— 这是耗电最不利的场景，也是本次优化收益最大的场景。

| 机制 | 优化前 | 优化后 | 怎么量的 | 依据 |
|---|---|---|---|---|
| HTTP 探测：引擎主循环 | 60 次/h（最坏 240） | **信号非强时 0 次/h**；强信号时仍 60 次/h | 代码推算：`delay(interval)`，`DEFAULT_INTERVAL = 60` | `MonitorEngine.kt` 主循环 + `SignalReader.kt` 的 `skipProbe` |
| HTTP 探测：监控页 5 秒快采 | **最多 720 次/h（最坏 2880）且后台不停、关屏也不停** | **0 次/h**（后台 / 非当前页 / 屏幕关闭） | 代码推算：`FAST_SAMPLE_INTERVAL_MS = 5_000L`；`3600/5 = 720` | `MainActivity.kt` 的 `liveSampling` 表达式 + `MonitorPage.kt` 的 `isScreenInteractive` 每轮护栏 |
| 两项合计 | 60 + 720 = **最多 780 次/h** | **0 次/h**（待机、信号非强）；强信号时 60 次/h | — | 同上 |
| 前台服务 `notify` | 60 次/h（每轮必发） | 仅文本变化时（信号稳定 ⇒ **0 次/h**） | 代码推算：每轮 `publish()` 必写 `_lastTickAt` ⇒ StateFlow 必发射 | `MonitorService.kt` 的 `lastNotificationText` |
| 日志落盘（SharedPreferences 整体重写 + fsync） | 最多 900 次/h | **最多 120 次/h**（且移出调用线程） | 代码推算：`3600/4 = 900`、`3600/30 = 120` | `LogStore.kt` 的 `PERSIST_INTERVAL_MS` |
| 保活心跳唤醒 | 96 次/天 | **96 次/天（刻意不变）** | `24 h / 15 min = 96` | 见 §2 C3 的「刻意没做的事」 |
| 心跳单次代价 | 重排重复闹钟（binder）+ 写一条会触发落盘的 DEBUG 日志 | 一次进程内 `MonitorEngine.running.value` 判断 | 代码比对 | `KeepAliveReceiver.kt` |
| 崩溃重启间隔 | 固定 10 秒 | 10 s → 20 s → 40 s → … → **15 min** 上限 | 代码比对 | `KeepAliveScheduler.nextBackoffDelay()` |
| Shizuku 残留特权进程 | **实测 4 个 / ≈201 MB**（PPID=1，存活 19–21.5 h） | 启动时清扫一次，稳定在 0–1 个 | 设备实测：`ps -A` + `dumpsys meminfo <包名>` | §1.2 / §2 C5 |
| 主进程 PSS | 16 120 K（实测，1.1.0 长期运行后） | 待用户实测（预期不升高；本次未新增常驻线程，唯一新线程是 `NetPilot-LogWriter` daemon + 启动时一次性的 `NetPilot-OrphanPrune`） | `dumpsys meminfo com.katiusu.netpilot` | — |

**换算成「待机 1 小时的联网次数」**：从最多 780 次降到 0 次（信号非强）——每次探测都意味着一次 DNS 解析 + TCP 建连 + HTTP 首字节，以及把蜂窝射频拉进高功率态（通常 10–20 秒才回落）。这是本次最主要的省电来源。

> ⚠️ **本报告不把「探测次数降 100%」直接换算成「耗电降 x%」**——那需要整机实测。请按 4.2 的步骤采集前后数据，把结果填回本表最后一列。

### 4.2 用户自测步骤（agent 不能装包，必须由你来跑）

**准备**：两个 APK 都在仓库根目录——

| 文件 | 用途 |
|---|---|
| `NetPilot-1.2.0-2026100501-debug.apk` | 自测用（debug 签名，可随意覆盖安装） |
| `NetPilot-1.2.0-2026100501-release.apk` | 正式用（与 1.0.1 / 1.1.0 **同一把签名 key**，SHA-256 `34100875…4c4c`，可直接覆盖升级 1.1.0） |

> ⚠️ 覆盖安装前请先**导出配置**（设置页 → 配置导入导出），以便随时回退。
> 从 1.1.0 覆盖升级到 1.2.0 时，`SharedPreferences` 会保留：所有阈值、默认值都不会被重置。

**步骤 1｜升级与自检（5 分钟）**

1. 安装 `NetPilot-1.2.0-2026100501-debug.apk`（或 release）。
2. 打开应用 → 关于页：版本行应为 `1.2.0 (2026100501)`。
3. 关于页第一张卡里应多出一行「**检查更新**」（摘要显示「当前版本 1.2.0」）→ 点一下，应弹出「**已是最新版本**」或「**检查更新失败**」（无网络时）Toast，**不能崩页**。
4. 设置页 → 高级/关于：`check_update_on_launch` 开关若关闭，启动时不再自动检查（默认开）。

**步骤 2｜功能回归（15 分钟，逐项打勾）**

| # | 项目 | 怎么看 | 期望 |
|---|---|---|---|
| 1 | 自动降级触发 | 监控页看判定；或用手动压低信号的方式（如进入弱信号环境） | 「假满格/信号过差」判定与 1.1.0 一致，能降到预设制式 |
| 2 | 自动恢复 | 恢复良好信号，等待恢复轮数 | 按 `recoveryCount` 轮后恢复原制式 |
| 3 | 冷却期 | 触发降级后再手动触发 | 冷却期内不重复执行 |
| 4 | 无网回退 | 降级态下断网 | 2 轮后回滚完整制式 |
| 5 | 日志页 | 打开日志页 | 最多 400 条；内容与 1.1.0 一致；可清空 |
| 6 | 日志导出 | 日志页 → 导出 | 内容完整、不截断崩溃 |
| 7 | 数据卡规则 | 切 Wi-Fi / 换卡 | 按规则切换默认数据卡 |
| 8 | Tasker 广播/插件 | 发 `com.katiusu.netpilot.action.GET_STATUS` 等 | 有回执，`netpilot.ping_ms` extra 只在真探测成功时存在 |
| 9 | 两个 QS 磁贴 | 下拉快捷设置 | 点按切换正常，状态显示正确 |
| 10 | 开机自启 | 重启手机 | 服务自动起来，通知出现 |
| 11 | 划掉后拉回 | 从最近任务划掉 | **10 秒内**被拉回（退避只在崩溃循环时生效） |
| 12 | 保活状态行 | 设置页「后台服务」卡 | 状态显示正确（与是否已排闹钟一致） |
| 13 | 失败注入（可选） | 连续快速划掉同一应用 3 次 | 第 2、3 次拉起间隔应变为 20 s、40 s（退避生效） |

**步骤 3｜本次改动的专项验证（10 分钟）**

| # | 验证什么 | 怎么验 | 期望 |
|---|---|---|---|
| 1 | **屏幕关 + 信号非强 → 不探测** | 屏幕关闭、手机静置 2 分钟以上；再打开应用看监控页/日志页 | Ping 行显示「**已跳过探测（屏幕关闭）**」；日志里对应时刻没有 ping 记录 |
| 2 | **屏幕亮 → 恢复探测** | 点亮屏幕停在监控页 | Ping 立刻恢复显示真实毫秒数 |
| 3 | **强信号时仍然探测** | 站在信号很好的地方（RSRP 好于 -85）关屏等待 | 通知里的 ping 仍会更新为毫秒数（说明没偷懒） |
| 4 | **后台不再 5 秒快采** | 打开监控页 → 按 Home 退到后台 → 等 1 分钟 → 回到应用看日志页 | 后台期间**没有**每 5 秒一条的采样记录 |
| 5 | **通知不闪** | 待机时观察通知栏 | 文本只在「网络类型/RSRP/ping」真变化时才更新，不再每 60 秒抖一下 |
| 6 | **日志落盘节流** | 日志页持续有记录时杀进程再开 | 仍能恢复约 120 条（行为不变，只是写盘频率从 4 秒降到 30 秒） |
| 7 | **Shizuku 孤儿清扫** | `adb logcat -s NetPilot` 找 `pruned N stale Shizuku user service process(es)`；`adb shell ps -A \| grep np_service` | 只剩当前绑定的 0–1 个（**基线是 4 个**） |
| 8 | **检查更新** | 见步骤 1.3 | 正常弹出对话框或 Toast |

**步骤 4｜待机耗电前后对比（这是唯一能给出「省了百分之多少电」的口径，必须同口径各跑一轮）**

1. 两轮之间**手机、SIM 卡、网络环境、屏幕状态、时长必须一致**；推荐：同一位置、同一张卡、屏幕关、手机静置、**1 小时**。
2. 每轮开始前：把电池充到 100%（或记录起始百分比），杀掉所有无关应用，关掉自动亮度。
3. 让应用处于运行状态（通知在），**保持屏幕关闭静置 1 小时**。
4. 记录四项：
   - 系统「设置 → 电池 → 电池用量 → NetPilot」的前后台耗电百分比与耗电时长；
   - `adb shell dumpsys meminfo com.katiusu.netpilot` 的 PSS（**只接受包名**）；
   - `adb shell ps -A | grep np_service` 的进程数；
   - 电池总百分比变化（100% → ?%）。
5. 填表：

| 口径 | 1.1.0（基线） | 1.2.0 | 变化 |
|---|---|---|---|
| 1 小时整机耗电 | ______ % | ______ % | ______ |
| NetPilot 耗电占比 | ______ % | ______ % | ______ |
| 探测次数（估算） | ≤780 次/h | 0–60 次/h | ↓ |
| PSS | 16 120 K | ______ K | 应不升高 |
| `np_service` 进程数 | 4 | ______ | 应为 0–1 |

> 说明：agent 侧 `dumpsys battery` / `batterystats` 被设备策略拦截（`[POLICY_BLOCKED]`），且禁止 `pm install`，所以这一步只能由你完成。**在你把数据填回来之前，本报告不对整机耗电百分比下任何结论。**

### 4.3 本次已完成的验证（可复现）

| 验证项 | 命令 | 结果 |
|---|---|---|
| debug 编译 | `bash _build.sh :app:compileDebugKotlin` | ✅ `BUILD SUCCESSFUL` |
| debug 打包 | `bash _build.sh :app:assembleDebug` | ✅ `EXIT=0` + `APK_CHECK … manifest=True arsc=True -> OK` |
| release 打包 | `bash _build.sh :app:assembleRelease` | ✅ `EXIT=0` + `APK_CHECK … OK`（debug/release **分两次跑**：同一次会 `OutOfMemoryError: Metaspace`） |
| **冻结源码后的最终重建** | 补上 C1 的每轮护栏（`MonitorPage.kt` 的 `isScreenInteractive`）后重跑 `:app:assembleDebug`（1m09s）+ `:app:assembleRelease`（1m），**src 未再变动** | ✅ 两次都 `EXIT=0` + `APK_CHECK … -> OK`；仓库根的两个 APK 就是这一轮的产物（`stat` 时间 2026-10-05 02:52:34 / 02:53:35） |
| 版本与 targetSdk | `/opt/android-sdk/build-tools/36.0.0/aapt2 dump badging NetPilot-1.2.0-2026100501-release.apk` | ✅ `versionCode='2026100501'` `versionName='1.2.0'` `targetSdkVersion:'36'` `compileSdkVersion='37'` |
| release 签名与 1.0.1/1.1.0 同一把 key | `/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs …-release.apk` | ✅ `CN=NetPilot, OU=Mobile, O=katiusu…`，SHA-256 `34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c` |
| 16 KB 页对齐 | `/opt/android-sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 …-release.apk` | ✅ `Verification successful` |
| 来源/许可复核 | `python3 tools/check_provenance.py` | ✅ `checked 7 pair(s), worst duplicated share 27.2% (threshold 25%)`，唯一 REVIEW 项是既有的 `ShizukuControllerService.kt`（已标注 reviewed：AIDL 接口签名） |
| 结构自检 | 架构自检（root = `app/src/main/java/com/katiusu/netpilot`） | ✅ `scannedFiles: 106`、`cycles: []`（未新增环）；超大模块 5 个**均为既有**（`MonitorPage.kt` 684 → 710、`MainActivity.kt` 644、`TaskerEditActivity.kt` 547、`SettingsPage.kt` 540、`LiquidGlassNavigationBar.kt` 533；`MonitorPage.kt` 那 26 行增量全部是 C1 的注释与 `isScreenInteractive()` 辅助函数）。⚠️ 该分析器本次未解析出 Kotlin 包导入（`importEdges: 0`），所以「无环」只作为**未新增环**的弱证据，不作为「全仓无环」的强证明 |
| 依赖检查 | `git diff app/build.gradle.kts` | ✅ **未新增任何依赖**（只改了 3 个数字 + 注释），与「不加依赖、不引 WorkManager」的既有约束一致 |

**未由 agent 验证的项**：功能回归（§4.2 步骤 2）、专项行为（步骤 3）、整机耗电（步骤 4）。原因：环境禁止 agent 安装 APK，且设备侧 `dumpsys` 采集项被策略拦截。

---

## 5. 结论与建议下一步

### 5.1 做了什么（一句话版）

把「界面每 5 秒一次、退到后台也不停的真实网络探测」彻底关掉，把「屏幕关 + 未降级 + 信号非强」时引擎的每轮探测也跳过（附完整真值表证明判定不变），顺手把通知重绘、日志写盘、心跳重排、崩溃重启、Shizuku 孤儿这五处稳定开销降下来；同时按合规要求把 targetSdk 升到 36、补上自动更新模组，版本号 1.2.0。

### 5.2 还差什么（要你决定或执行）

| # | 事项 | 为什么 |
|---|---|---|
| 1 | **按 §4.2 步骤 4 采集整机待机耗电** | 这是唯一能把「探测次数降 100%」翻译成「电池百分比降多少」的口径。没有它，本报告只能说「确定性开销大幅下降」 |
| 2 | **按 §4.2 步骤 2/3 跑功能回归** | agent 不能装机，13 项功能 + 8 项专项验证必须你在真机上过一遍 |
| 3 | 决定是否要做下面的二级候选 | 每一项都会**改变用户可见行为**，本次一律没做 |

### 5.3 二级候选（会改变用户可见行为，**需要你确认**）

| 候选 | 省电原理 | 用户可见影响 | 建议 |
|---|---|---|---|
| **采样间隔自适应**（屏幕关时放宽到 5–15 分钟） | 待机时引擎探测从 60 次/h 降到 4–12 次/h | 降级**发现变慢**（最坏延迟一个间隔）——这是典型的「省电换灵敏度」 | ⚠️ 谨慎。当前 60 秒间隔是为了「假满格及时降级」，擅自放宽违背项目初衷。若要做，建议**只在屏幕关时放宽、且上限 5 分钟**，并在设置页给出开关 |
| **取消 15 分钟心跳，只在服务掉线时排闹钟** | 省掉 96 次/天唤醒 | **进程被系统在后台静默杀掉后，可能再也不自动拉回**（进程已死，没有任何代码能再排闹钟；`AlarmManager` 的闹钟是唯一的跨进程兜底） | ❌ 不建议。除非你接受「后台被清后需要手动打开应用」 |
| **探测目标 / 协议替换**（用系统 `connectivitycheck` 或 DNS 探测） | 可能更快、更省流 | 改变了「能不能连通」的判定口径 | ⚠️ 建议先积累 1.2.0 的实测数据，看现有 4 个目标是否够快 |
| **背景动画默认关闭或限帧** | 屏幕亮时省 GPU/CPU | 视觉变化明显 | ⚠️ 建议做成设置项默认开，不要改默认 |
| **引入 WorkManager / JobScheduler** | 更规范的调度 | 需要新增依赖（当前刻意为零依赖） | ❌ 不建议，违背工程既有约束 |

### 5.4 风险与已知边界（如实记录）

1. **短路条件严格**：只有「屏幕关 + 未降级 + 信号非强」三个条件同时成立才跳过。**强信号时仍然每 60 秒探测**——这不是遗漏，而是 §3.3 证明的必然结果：信号强时 `ping` 会参与「假满格」判定，跳过就会漏检。想让强信号也省电，只能动采样间隔（二级候选 1）。
2. **通知栏会显示「已跳过探测（屏幕关闭）」**：这是本次**唯一有意的可见文案差异**（任务规格里已预期：「屏幕关时……或显示『已跳过探测』」）。它比原来的「无响应」更准确——原来的文案把「没测」和「测了没通」混为一谈。若要完全不变，可把该分支改回显示「无响应」，但会重新把两种含义混在一起。
3. **心跳唤醒次数没降**（仍是 96 次/天），原因见 §2 C3 与 §5.3。降低的是每次唤醒的代价。
4. **架构自检的「无环」是弱证据**（分析器没解析出 Kotlin 包导入）。
5. **`removeFirst()` / `removeLast()` 陷阱**：compileSdk 37 + minSdk 34 下这组 JDK 21 集合方法会在 Android 14 上 `NoSuchMethodError`。当前代码未使用，后续新增代码请用 `removeAt(0)` / `removeAt(lastIndex)`。

---

## 6. 1.3.0 增补（自动化接口开关 + 自适应采样间隔）

> 本节记录 1.3.0 相对 1.2.0 的**增量**。§1–§5 的结论**没有被推翻**，基线数字仍然有效。

### 6.1 改动清单

| # | 改动（文件） | 级别 | 省电原理 | 前 | 后 | 验证方式 | 回滚 |
|---|---|---|---|---|---|---|---|
| C8 | `app/build.gradle.kts` | 版本 | — | 1.2.0 / 2026100501 | **1.3.0 / 2026100502** | `aapt2 dump badging` | 改回两行 |
| C9 | 新增 `core/tasker/TaskerGate.kt`；改 `AndroidManifest.xml`（三处 `android:enabled="false"`）、`prefs/ConfigState.kt`（`observe`）、`TemplateApp.kt`、`ui/screen/features/FeaturesPage.kt` | **零行为变化**（不改判定、不改既有默认值） | Tasker / Locale 广播不再冷启动应用进程 | 三个 exported 组件常驻启用，任何 Tasker 命令都会拉起进程（冷启动 + 读配置 + 写日志） | 默认禁用；开关一关就由**系统层面**禁用组件，广播**不派发** | 见 §6.4 自测 17.1；`dumpsys package com.katiusu.netpilot` 看组件 Enabled 状态 | 删掉三行 `android:enabled="false"` + 去掉 `TaskerGate.install()` |
| C10 | `core/monitor/MonitorModels.kt`（`adaptiveIntervalEnabled` / `adaptiveMarginDbm` + `isNearThreshold`）、`MonitorSettings.kt`、`MonitorEngine.kt`（`adaptiveIntervalMs`）、`FeaturesPage.kt` | **零行为变化**（不改判定） | 只在读数靠近门限时变快；远离时零额外开销 | 固定间隔 | 靠近门限时 60s→48s→38.4s→30s，下限 50% 且不低于 15s；**一轮**远离立刻回到设置值 | §6.4 自测 17.2 | 关掉「自适应采样间隔」开关即回到固定间隔 |
| C11-① | `core/keepalive/KeepAliveReceiver.kt` | 零行为变化（只改日志） | 把「静默失效」变成可诊断（不是省电，是排障能力） | 被系统拒绝时只记一句笼统的「保活广播处理失败」，且**无条件**跟着记一条「已拉起」 | 单独识别 `ForegroundServiceStartNotAllowedException`，ERROR 写明原因与下一步；只有真起来了才记「已拉起」 | §6.4 自测 17.3 | 还原成原来的 `runCatching` 包法 |
| C11-② | **查证后判定不安全，未实施** | — | — | — | — | 见 §6.2 | — |
| C11-③ | 跑一次 `:app:lintDebug` 排查 `NewApi` 误用 | 验证 | — | 从未跑过 | **`NewApi` 0 条** | 见 §6.5 | — |

### 6.2 C11-② 为什么没做：冷却期内跳过探测**会**改变判定

原设想是「已进入降级态、且正处于冷却期时，这一轮探测没有消费者，可以跳过」。逐行核对
`core/monitor/AutoDowngradeEngine.kt` 的 `applyTransition()` 后否掉：

- 已降级分支里，`noResponse` 的消费点在**冷却闸门之前**：先 `if (noResponse || !hasCellular) { noNetFailCount += 1 … }`，
  之后才是 `cooldownRemainingMs > 0 → return`。
- 所以在冷却期内跳过探测 ⇒ `pingMs == null` ⇒ `noResponse == true` ⇒ `noNetFailCount` 被凭空 +1
  ⇒ 两轮之后触发一次**本不该发生**的 rollback。（`probeSkipped` 只挡 C2 那条路径，而 C2 只在「未降级」时生效。）

要安全地做，就得让 `noResponse` 认识「因为冷却期而跳过」这第四种含义 —— 那已经是在改状态机的输入语义，
不是在省电。按「漏检比多耗电糟糕得多」的原则，**不做**。

> 这条记录本身是本次的产出之一：它把「想当然能省」和「算过才知道不能」区分开了。

### 6.3 C10 的正确性边界：`isNearThreshold()` 没有任何判定路径的调用者

`SignalSnapshot.isNearThreshold(thresholds)` 的返回值只进 `MonitorEngine.adaptiveIntervalMs()` → `delay()`；
全仓调用点只有 `core/monitor/MonitorEngine.kt` 里那一处，**没有**任何 `judge()` / `applyTransition()` /
`isStrongSignal()` 路径读它。因此：

- 它无论返回什么，降级/恢复的结果都不可能改变；
- 它只改变「下一次采样在**什么时候**」，不改变「每一次采样得到**什么**」；
- 关掉开关（`adaptiveIntervalEnabled = false`）时 `adaptiveIntervalMs` 走
  `base.coerceIn(15, 3600) * 1000L`，与 1.2.0 的表达式**逐字符相同**。

「靠近门限」的三条带宽由 `margin`（默认 10 dBm）按 1 : 1/3 : 5 推导（10 dBm / 3 dB / 50 ms）。
读不到的项一律**不算靠近**：读不到就无法判断远近，按「不靠近」处理能干净地退化回固定间隔，
不会因为「读不到」就长期贴着快采跑。

### 6.4 量化对照（1.2.0 → 1.3.0）

| 项 | 1.2.0 | 1.3.0 |
|---|---|---|
| Tasker 广播唤醒应用进程次数 | 每条命令 1 次冷启动 | **0**（接口关闭时；打开后与 1.2.0 相同） |
| 空闲且远离门限时的采样节奏 | 60 秒 | 60 秒（**不变** —— 这是「远离就恢复」的直接结果） |
| 靠近门限时的判定滞后 | 最坏 60 秒 | 最坏 30 秒（间隔缩到一半后），即**反应快一倍** |
| 靠近门限时的探测开销 | 60 次/小时 | 最多 120 次/小时（**本版唯一一处开销上升**，只在读数贴着门限时发生，且上限 2 倍） |
| 判定语义 | — | **未变**（见 §6.3） |

如实说明：C10 **不是**省电改动，它是一处「用可选的探测密度换反应速度」的权衡，而且默认开启。
它之所以符合本轮要求，是因为它**不改变判定结果**，且远离门限时开销严格不变（零额外开销）。
更在意待机耗电的话，直接关掉「自适应采样间隔」就能回到 1.2.0 的节奏。

### 6.5 lint 结果（C11-③）

`lint { checkReleaseBuilds = false }` 意味着 release 构建不会拦 `NewApi`；`compileSdk 37` + `minSdk 34`
的组合下，误用高版本 API 会在 Android 14 设备上直接崩。本次单独跑了一次：

```bash
cd /sdcard/Project/NetPilot
./gradlew :app:lintDebug --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
  -Dorg.gradle.configuration-cache.parallel=false
```

> 必须手工放大 Metaspace：`_build.sh` 固定用 `-XX:MaxMetaspaceSize=320m`，lint 的 work action 会以
> `> Metaspace` 失败 —— 和项目里 `lint { checkReleaseBuilds = false }` 要绕开的是同一个坑。

报告：`app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`

| issue | 条数 | 与本轮的关系 |
|---|---|---|
| **`NewApi`** | **0** | **本次要查的就是它：1.3.0 新增代码没有误用高版本 API** |
| `UnusedResources` | 112 | 既有（模板遗留资源）；**本次新增的 7 条文案一条都没被判为未使用** |
| `UseKtx` / `PluralsCandidate` | 16 / 13 | 既有，风格类 |
| `ObsoleteSdkInt` | 5 | 既有（minSdk 34 下的冗余版本判断） |
| `PrivateApi` | 1 | 既有且刻意（`TelephonyReflection` 读隐藏 API） |
| `StaticFieldLeak` | 1 | **误报**：`MonitorEngine.engine` 是 object 字段，`AutoDowngradeEngine` 持有的是 `applicationContext`，不泄漏 Activity |
| `MissingPermission` | 1（Error） | **误报**：`SubscriptionSwitcher.activeSlotList()` 整段包在 `try { … } catch (e: Throwable)` 里，`SecurityException` 已被处理 |
| `BatteryLife` | 1 | 既有且刻意（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，保活需要） |
| `HighAppVersionCode` | 1（Error） | 既有约定（`2026100502` 式构建号，离 `Integer.MAX_VALUE` 还很远） |
| `OldTargetApi` | 1 | `targetSdk 36 < compileSdk 37`，符合预期（Play 当前只要求 36） |

两条 `Error` 都是既有误报/既有约定，**没有一条是 1.3.0 引入的**。新增代码引入的新 issue 数为 **0**
（唯一打在新文案上的是 `TypographyFractions`「用 ⅓ 代替 1/3」，已改文案，现在也消失了）。

### 6.6 产物与验收（1.3.0）

构建（debug 与 release **分两次**跑，同一次会 `java.lang.OutOfMemoryError: Metaspace`）：

```bash
cd /sdcard/Project/NetPilot
bash _build.sh :app:assembleDebug   --no-configuration-cache   # === EXIT=0 ===  APK_CHECK … -> OK
bash _build.sh :app:assembleRelease --no-configuration-cache   # === EXIT=0 ===  APK_CHECK … -> OK
```

> `--no-configuration-cache` 在本容器不是可选项：Gradle 配置缓存命中时，APK 会沿用**上一版**的
> `versionCode` / `versionName` —— 构建全绿、APK 时间戳也是新的，值却还是旧的（本次首次构建就实测到
> `versionCode='2026100501' versionName='1.2.0'`）。核对方法是 `aapt2 dump badging … | head -1`，
> 详见 [`TESTING.md` §14.3 第 3 条](TESTING.md)。

| 项 | 值 |
|---|---|
| `NetPilot-1.3.0-2026100502-debug.apk` | 43,757,363 B · MD5 `6c12a5ab0e6cb959871430fb6ecbcbcc` |
| `NetPilot-1.4.0-2026100503-release.apk` | 33,146,557 B · MD5 `0e528d52fef4390ca48fd5e6cd3069e8` · SHA-256 `744b44e10f1bf8ade85f5035560ca41aa67a214d0646c1feed7987b51841b84a` |
| `aapt2 dump badging` | `versionCode='2026100502' versionName='1.3.0'` · `targetSdkVersion:'36'` · `compileSdkVersion='37'` |
| `native-code` | `arm64-v8a` `armeabi-v7a` `x86` `x86_64` |
| release 签名 | `CN=NetPilot, OU=Mobile, O=katiusu, L=Beijing, ST=Beijing, C=CN`，SHA-256 `34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c`（与 1.0.1 / 1.1.0 / 1.2.0 同一把） |
| debug 签名 | `C=US, O=Android, CN=Android Debug`，SHA-256 `eae3bb7895cd29ddfda1deaae6a4177c4a00f429916c2a6427f13109c208675c` |
| 16 KB 页 | `zipalign -c -P 16 -v 4` 打在 release 包上 → **Verification successful** |
| APK 内的 Tasker 组件 | 三个组件都是 `android:enabled="false"`（`aapt2 dump xmltree --file AndroidManifest.xml` 命中 3 次）—— 证明 C9 的「默认关」是打进包里的，不只是源码里写着 |
| 架构自检 | `scannedFiles: 107`、`cycles: []`、超大模块 5 个（与 1.2.0 相同，`MonitorPage.kt` 710 行）。注意该分析器 `moduleCount: 0` / `importEdges: 0`，**解析不出 Kotlin 包导入**，「无环」只能算弱证据 |
| 来源核查 | `python3 tools/check_provenance.py` → `checked 7 pair(s), worst duplicated share 27.2% (threshold 25%)`，唯一 REVIEW 项是既有的 `ShizukuControllerService.kt`（已标注 reviewed：AIDL 接口签名 + 类声明）；1.3.0 新增的 `TaskerGate.kt` 不复制任何第三方代码 |

交付物（放在仓库根，`.apk` 已被 `.gitignore` 排除，不会进版本库）：
`NetPilot-1.3.0-2026100502-debug.apk`、`NetPilot-1.4.0-2026100503-release.apk`。

## 7. 1.4.0 增补（假满格的 5G / 5G+ 门控 + 判定参数默认值调整）

### 7.1 起因：真 4G 上会「一直」触发假满格

用户报告（m00947）：「在回到 4g 时，可能 ping 太大一直触发假 5g 满格」。排查后发现三条互相咬合的成因，**缺任何一条都不会是「一直」**：

| # | 成因 | 1.3.0 里的位置 |
|---|---|---|
| 1 | 判定规则**没有制式门控**：蜂窝路径只读 `rsrp` / `sinr` / `pingMs`，从不看当前驻留在几 G。所以一条真 4G 链路只要 RSRP 强于 −85 dBm 且 ping > 上限，就会被判成「假 5G 满格」 | `core/monitor/FakeSignalDetector.kt` 的 `judge()` |
| 2 | **降级目标就是 4G**：在 4G 上写「4G 优先」，射频侧什么都不会变，唯一效果是把 5G 门关死 | `DowngradeThresholds.downgradeMode`（默认 9） |
| 3 | **降级后永久卡死**：落到 4G 后判据依然成立 ⇒ `judgement.fake` 恒真 ⇒ `recoveryCount` 永远涨不到 `recoveryCount`（默认 2）；唯一逃生口是「连续 2 轮彻底无网」触发 rollback，之后立刻又被判回去 | `core/monitor/AutoDowngradeEngine.kt` 冷却闸门之后的 `if (!judgement.fake)` 分支 |

成因 3 是关键，也是最严重的一条：`recoveryCount` 在「仍满足降级条件」时**既不加也不清零**。所以这不是「偶尔误判」，而是**单次误判之后无法自愈**——设备被锁在降级制式上，只有断网两轮才可能出来。

### 7.2 改动清单（C12 / C13）

| # | 改动（文件） | 级别 | 省电 / 正确性原理 | 前 | 后 | 回滚 |
|---|---|---|---|---|---|---|
| C12-1 | `core/monitor/MonitorModels.kt`：`SignalSnapshot` 新增 `sawNr: Boolean = false`；新增 `fun isOnNr()` | 判定语义（按用户批准） | NSA / EN-DC 下数据网络仍可能上报 LTE，但 `allCellInfo` 已经见到 NR 小区。`sawNr` 原本只内联在 `sinrUnavailableReason()` 里算过一次，**没有进快照**；提取成字段后判定层才拿得到 | 无此字段 | `isOnNr() = rawNetworkType == NETWORK_TYPE_NR \|\| sawNr` | 删除字段与函数 |
| C12-2 | `core/monitor/FakeSignalDetector.kt`：`if (isStrong)` 分支新增 `nrOnlyBlocked = thresholds.fakeFullBarOnNrOnly && !snapshot.isOnNr()`，用 `if (!nrOnlyBlocked)` 包住 Ping / SINR / Ping 失败三段 | 判定语义（按用户批准） | 非 5G 时这三条 reason 一律不产生 ⇒ `fake` 只能由弱信号规则给出 | 任何制式都判 | 只在 5G / 5G+ 判 | 设置里关掉「假满格只在 5G / 5G+ 判定」 |
| C12-3 | `core/monitor/MonitorModels.kt`：新增 `val fakeFullBarOnNrOnly: Boolean = true` | 新开关（用户要求默认开） | 机型在 NSA 上读不到 NR 小区（缺「精确位置」权限）时，门控会误伤真 5G ⇒ 必须留逃生口 | — | 默认 true | 同上 |
| C12-4 | `core/monitor/MonitorSettings.kt` + `ui/screen/features/FeaturesPage.kt`：`KEY_NR_ONLY = "np_fake5g_nr_only"`，SWITCH，`dependsOn = KEY_ENABLED` | 接线 | 走 `ConfigState`，带 `np_` 前缀 ⇒ 会被配置导出/导入带上 | — | — | 删除 spec |
| C12-5 | `core/monitor/SignalReader.kt`：把内联的 `strengthList.any { it is CellSignalStrengthNr }` 提取为局部 `val sawNr`，两处复用（`sinrUnavailableReason` 与快照组装） | 数据采集 | 一次遍历、两处用途；快照新增 `sawNr = sawNr` | 只用于 SINR 原因 | 同时进快照 | 还原内联 |
| C12-6 | **可观测性（用户选定方案 D）**：`FakeJudgement.detail` 三处带上制式——假满格「判定为假满格（4G LTE）：…」、弱信号「判定为信号过差（5G NR）：…」、正常但被门控挡住时明确写「当前是 4G LTE，假满格只在 5G / 5G+ 上判定，本轮不检查 Ping 与 SINR（…）」 | 零行为变化 | 用户看到「一直触发」时第一件要回答的事就是「当时到底在 4G 还是 5G」；必须把「没判」和「判了正常」区分开，否则读数是强信号、Ping 也照样显示，用户会以为规则坏了 | 无制式信息 | 有 | 还原字符串 |
| C12-7 | `ui/screen/monitor/MonitorCriteria.kt` + `strings_quality.xml`：规则主句加第 5 个占位符 `%5$s`（`（仅 5G / 5G+）`），门控开启时额外渲染一条 `q_criteria_nr_only_rule` 解释 | 零行为变化（只读页） | 门控是**判定前提**的一部分，不写进主句就会让人以为后面那半句在任何制式下都生效；解释只在门控开着时出现，关掉后不渲染，避免误导 | 无 | 有 | 还原 |
| C13-1 | `MonitorModels.kt`：`pingThresholdMs` **200 → 300** | 默认值（用户指定） | 这个读数是「DNS + TCP 建连 + HTTP 首字节」的**冷路径**耗时（`SignalReader.httpProbe()` 每次 `Connection: close` + `disconnect()`），量级本就比无线 RTT 大一截；200 ms 对 4G 尾段偏紧 | 200 | 300 | 功能页滑块 |
| C13-2 | `MonitorModels.kt`：`cooldownSec` **60 → 120** | 默认值（用户指定） | 冷却期内不尝试恢复 ⇒ 单次误判的影响窗口减半，也避免 5G⇄4G 反复横跳（每次切制式都掉一次数据连接） | 60 | 120 | 功能页滑块 |
| C13-3 | `MonitorModels.kt`：`adaptiveMarginDbm` **10 → 20** | 默认值（用户指定） | 带宽更宽 ⇒ 更早开始加密采样 | 10 | 20 | 功能页滑块 |
| C13-4 | `MonitorModels.kt` + `MonitorSettings.kt` + `MonitorEngine.kt` + `FeaturesPage.kt`：自适应倍率从硬编码常量 `ADAPTIVE_STEP_FACTOR = 0.8` 迁到 `DowngradeThresholds.adaptiveStepFactor = 0.85f`，并做成可调滑块（`KEY_ADAPTIVE_STEP`，`0.50f..0.95f`，步进 `0.01f`，两位小数） | 默认值 + 新开关 | 倍率越大越省电、反应越慢 | 0.8 硬编码 | 0.85 可调 | 功能页滑块 |

### 7.3 「5G+」的定义（必须讲清，否则这条改动会被误解）

代码里**本来没有** `5G+` 这个概念（`grep '5G+|NR_CA|EN-DC'` 只命中既有的 `toggleEndc` 开关）。本次给它下了一个可执行的定义：

| 显示 | 含义 | 判据 |
|---|---|---|
| **5G** | 数据网络直接上报 NR | `rawNetworkType == TelephonyManager.NETWORK_TYPE_NR` |
| **5G+** | 数据网络仍报 LTE，但小区列表里已经见到 NR 小区（NSA / EN-DC） | `sawNr == true` |
| **4G+** | LTE 载波聚合，**不算** 5G | `rawNetworkType == 19`（`NETWORK_TYPE_LTE_CA`），显示「4G+ LTE-CA」 |

`isOnNr()` 取前两者的并集。注意 `sawNr` 依赖 `allCellInfo`，而它在部分机型上需要「精确位置」权限；读不到时恒为 `false` ⇒ 那类机型在 NSA 下会退化成「只在 SA 上判」，这正是 7.2 里 C12-3 保留开关的原因，也是 §7.6 必须让用户真机验证的一条。

### 7.4 正确性证明：为什么这条改动**不会**漏检

这是本段最需要证明的部分——放宽判定的改动天然有「漏检」风险，而漏检比误判糟糕得多。逐条列：

1. **弱信号规则完全没动。** `judge()` 的另一条规则（`thresholds.downgradeOnWeakSignal && rsrp != null && rsrp < thresholds.weakRsrpThreshold`）与门控**互斥**：它在 `if (isStrong)` 之外，而 `isStrong` 是 `rsrp > rsrpThreshold`（默认 −85 dBm），弱信号是 `rsrp < weakRsrpThreshold`（默认 −110 dBm）——两者不可能同时成立。所以「信号真的差」这条路径的判定结果**逐位不变**。
2. **门控只影响「信号强」这一个分支。** `nrOnlyBlocked` 只在 `if (isStrong)` 内被求值，非强信号时它连碰都碰不到。
3. **被挡住的 reason 在 4G 上本来就不该产生。** 三条 reason（Ping 超限、SINR 过低、Ping 全失败）描述的是「5G 满格但质量差」。在 4G 上它们是**事实描述仍然成立、但结论不成立**——原因见 7.1 的成因 2/3。原脚本跑在「5G 手机上、默认信号强就是 5G」，这个假设在本工程不成立。
4. **有一个真实的行为收窄，必须如实承认。** 门控开启后，一台**驻留在 4G 且 RSRP 强、ping 高**的设备不再降级。这是**故意**的：降级目标就是 4G，写下去射频侧毫无变化，只会让 `recoveryCount` 卡死（成因 3）。换句话说，被去掉的不是「一个有效的降级动作」，而是「一个只在日志里看起来有动作、实际什么都没做的写入」。用户若认为 4G 上仍应降级，关掉开关即完全退回 1.3.0 行为。
5. **`isStrongSignal()` / `judge()` 的其他分支逐位未改**：Wi-Fi 早退、弱信号、兜底 `fake=false` 三条路径的返回结构没动，只改了 `detail` 文案（`detail` 不参与判定，只进日志与界面）。
6. **C2 的短路只会更强。** 1.4.0 起「信号强」不再意味着「假满格会看 ping」——非 NR 时 `pingMs` 连强信号分支都不会被读到。所以「探不探都不改判定」这个结论在 1.4.0 上比 1.2.0 时更宽：原来的短路条件（屏幕关 + 未降级 + 非强信号）保持不变，可以只收缩、不需要放宽。

### 7.5 默认值迁移的语义（必须让用户知道）

`HookSliderCard` / `HookCards` 读的是 `ConfigState.float(key, spec.defaultFloat)` / `ConfigState.bool(key, spec.defaultBoolean)`，**只在用户真的拖过滑条或点过开关时才写入**。所以：

- **从没动过**某个滑条 / 开关的用户，改 `DEFAULT_*` 与 `spec.default*` 对他**立即生效**；
- **动过**的用户保留自己设的值，**不会被覆盖**——这一点是有意的：升级不该悄悄改掉用户明确调过的参数。

因此 7.2 里 C13-1..4 四个默认值改动，只对「没调过这几项的用户」改变行为，其余用户维持原样。报告里把这个口径写清楚，避免把「升级后我觉得还是 200」当成 bug。

### 7.6 量化对照（1.3.0 → 1.4.0，全部为确定性推算）

| 项 | 1.3.0 | 1.4.0 | 说明 |
|---|---|---|---|
| 4G 上触发假满格的轮次 | 每轮都可能 | **0**（门控开启时） | 成因 1 被消除 |
| 4G 上「误判后永久锁死」 | 只有断网 2 轮才可能解除 | **不再进入该状态** | 成因 3 随之消失 |
| ping 落在 200–300 ms 的轮次是否判 fake | 是 | **否** | C13-1；这条同时降低 5G 上的误判 |
| 单次误判的恢复保护窗口 | 60 s | **120 s** | C13-2 |
| 靠近门限时的采样间隔（示例：基准 60 s、连续 4 轮靠近） | 60 → 48 → 38.4 → 30.7 s | 60 → 51 → 43.4 → 36.9 s | C13-3/C13-4；1.4.0 的**采样密度更低**（更省电），代价是反应略慢 |
| 开始自适应缩短的门限距离 | RSRP 10 dBm 内 | **20 dBm 内** | C13-3；更早进入、但单轮间隔更大 |

**与 1.2.0 的关系**：这一节**没有推翻** §1–§5 的任何结论。C2 的短路条件、C3 的心跳与退避、C4 的日志节流、C5 的通知去重与孤儿清扫全部保持不变；C10 的自适应间隔仍然只调采样节奏、**不参与任何判定**（§6.3 的结论继续成立，`isNearThreshold()` 依旧没有判定路径的调用者）。

### 7.7 产物与验收（1.4.0）

### 7.7.1 实测产物（2026-10-05 构建，全部命令已在本文档内复现）

| 项 | 值 |
|---|---|
| debug APK | `NetPilot-1.4.0-2026100503-debug.apk` · 43 763 247 B · MD5 `30394f9eb8b5e366cee1aa8fd372247a` |
| release APK | `NetPilot-1.4.0-2026100503-release.apk` · 33 152 441 B · MD5 `36bf9793e1637cc66c5bafe00def252a` · SHA-256 `2196f71e3ab30b8e618ab69b37be06bc956302fb8180f73db1c58f2e7463ea31` |
| `versionCode` / `versionName` | `2026100503` / `1.4.0` |
| `targetSdk` / `compileSdk` | `36` / `37`（`platformBuildVersionName='17'`） |
| 原生库 | `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` |
| release 签名 | `CN=NetPilot, OU=Mobile, O=katiusu, L=Beijing, ST=Beijing, C=CN`，SHA-256 `34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c`（与 1.0.1 / 1.1.0 / 1.2.0 / 1.3.0 同一把 key） |
| 16 KB 页对齐 | `Verification successful` |
| 组件默认禁用（C9 回归） | 清单里 `enabled=false` 命中 **3** 次（Tasker 命令接收器 / Locale 插件 / 插件配置界面） |
| 构建 | `bash _build.sh :app:assembleDebug --no-configuration-cache` → `EXIT=0`（3m30s）；`:app:assembleRelease` → `EXIT=0`（7m54s）。两次 `APK_CHECK: manifest=True arsc=True -> OK` |
| 来源复核 | `python3 tools/check_provenance.py` → `checked 7 pair(s), worst duplicated share 27.2%`，唯一 REVIEW 项是既有的 `ShizukuControllerService.kt`（已标注 reviewed） |
| 架构自检 | `scannedFiles: 107`、`cycles: []`、超大模块 5 个（`MonitorPage.kt` 710 / `MainActivity.kt` 644 / `TaskerEditActivity.kt` 547 / `SettingsPage.kt` 540 / `LiquidGlassNavigationBar.kt` 533，均为既有）。**注意 `moduleCount: 0` / `importEdges: 0` —— 该分析器解析不出 Kotlin 包导入，「无环」只能作为「未新增环」的弱证据**，不能当成依赖图已核验 |

```bash
/opt/android-sdk/build-tools/36.0.0/aapt2 dump badging NetPilot-1.4.0-2026100503-release.apk | head -1
# 期望：versionCode='2026100503' versionName='1.4.0' targetSdkVersion:'36' compileSdkVersion:'37'

/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs NetPilot-1.4.0-2026100503-release.apk
# 期望：SHA-256 34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c

/opt/android-sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 NetPilot-1.4.0-2026100503-release.apk
# 期望：Verification successful
```

真机必验三条（agent 不能装包，需你操作）：
1. **插 4G 卡 / 锁 4G，确认不再触发假满格降级**；此时监控页判定区的「假满格降级（仅 5G / 5G+）」后面应出现那条解释。
2. **5G 上仍能触发**：把 ping 上限调到很低（例如 50 ms）制造判定，确认假满格照常降级。
3. **关掉「假满格只在 5G / 5G+ 判定」后回到旧行为**：4G 上应重新能触发，且解释行消失。

## 8. 1.5.0 增补（日志落盘 + 写入链路修复与权威存储 + 桌面图标清理 + 自动更新开关 + 运营商表修正 + Tasker 事件门控）

> 先把与省电主题的关系说清楚：**这一版几乎没有省电收益**，它的价值是把「看不见」变成「看得见」。
> 1.5.0 之前用户问的是「回读检验通过了为什么还是切不动制式」—— 这个问题之所以难回答，正是因为
> 写入链路在应用内日志里完全不可见。§8.3 是这个问题本身的代码级结论，§8.4 逐项说明本版对
> 耗电与资源的实际影响（其中**含一处会如实增加 I/O 的项**）。

### 8.1 起因：三处「代码在那儿但没接上」

| # | 现象 | 根因（已核实） |
|---|---|---|
| 1 | 日志页冷启动后必然空白，重启应用看不到上次记录 | `LogStore.init(context)` 全工程零处调用 ⇒ `appContext` 永远为 null ⇒ `persist()` 第一句 `val ctx = appContext ?: return` 直接返回，`load()` 也从未执行 |
| 2 | 「开关拨了但网络没变」时无法判断卡在哪一步 | `core/priv/**` 几乎只用 `android.util.Log`（只有 logcat 可见）；`TelephonyReflection.dispatch` 把每个候选组合抛出的异常**静默吞掉** |
| 3 | 装完未启动前桌面有两个图标，且「隐藏图标」开关无效 | `AndroidManifest.xml` 的 `MainActivity` 与 `activity-alias .LauncherAlias` 各带一份 MAIN/LAUNCHER 过滤器；隐藏逻辑只禁用别名，`MainActivity` 自己的入口仍在 |

另有一处尚未被触发的缺陷（C17，见 §8.2）：`MainActivity.persistState()` 直接构造新的 `AppSettings`，
会把界面上没有暴露的字段重置成 data class 默认值。

### 8.2 改动清单（C14 – C17）

| # | 改动文件 | 级别 | 为什么 | 验证方式 | 回滚 |
|---|---|---|---|---|---|
| C14 | `core/NetPilot.kt`、新建 `core/priv/WriteDiag.kt` | 修复 | 补 `LogStore.init(app)`；新增诊断出口 | 冷启动后日志页非空（§8.5） | 删掉那一行 init + 删新文件 |
| C15 | `core/priv/TelephonyReflection.kt`、`core/priv/PrivilegedCli.kt`、`core/priv/root/RootController.kt` | 可观测性 | 把写入链路四层的关键节点接进日志页 | TESTING §19.2 | 逐处还原；诊断默认关，不改变任何判定 |
| C16 | `app/src/main/AndroidManifest.xml`、删 `LauncherIconController.kt`、`MainActivity.kt`、`AppSettings.kt` | 修复 | 删掉重复的 LAUNCHER 入口 | 装完未启动前只有一个图标 | 还原别名与控制器 |
| C17 | `MainActivity.kt`（`persistState`）、`ui/screen/settings/SettingsPage.kt`、两个 `strings_keepalive.xml` | 修复 + 新开关 | `persistState` 改 `AppSettings.load(this).copy(...)`；新增「写入详细诊断日志」（`np_verbose_log`，默认关）与「自动检查更新」两个开关 | TESTING §19.3 / §19.4 | 还原 `persistState` 的构造式；删两个开关 |

C15 接进去的节点共 4 层：

| 层 | 改前 | 改后 |
|---|---|---|
| 反射拿 ITelephony | 只有 logcat 一行 | 同时进日志页（`WriteDiag.warn`） |
| 三条写入策略 | 只记「走了哪条」，**调制解调器返回值被丢弃** | 记「走了哪条」+ **`CallResult.Hit.value`（modem 的答案）** |
| `settings put` 兜底 | 只有 logcat | 键、值、命令退出码、回读值 |
| 回读校验 | 只有「一致/不一致」 | 「回读一致」时明确标注**这只证明设置对象存下了该值，不等于调制解调器已接受** |

### 8.3 回读检验的工作流程与「通过了仍切不动」的六条机制

先分清两件被混称「回读检验」的东西 —— 用户困惑的根源就在这里：

| | ① 写后回读校验 | ② 设置页「系统兼容性」的读取探测 |
|---|---|---|
| 入口 | 开关 `np_verify_write`（默认 true），文案「写入后回读校验」 | 同卡片下方的只读信息行，文案「成功，读回 %1$s」 |
| 实现 | `core/priv/root/RootController.kt:200-212 settingsSetMode` | `core/priv/WriteCompat.kt:177-299 SystemCompatInfo.probe` |
| 会写吗 | 会（`settings put` 后 `settings get` 比对） | **一个字节都不写**（纯只读探测） |

② 的 `ReadState.OK` 只证明「读得到当前值」，与「写下去能不能生效」**没有因果关系**。① 之所以能
「通过」，有六条互相独立的机制：

1. **闭环里没有电话进程 / RIL / 调制解调器**：写是 `SettingsProvider.update()`、读是 `SettingsProvider.get()`，同一个 provider、同一个字段。而且 Android 11+ 起权威存储已搬到 TelephonyProvider 的 `allowed_network_types`，`preferred_network_mode` 在不少 ROM 上只是遗留兼容字段，可能根本没人读。
2. **真正该回读的那条路没有回读**：`core/priv/TelephonyReflection.kt:327-356 setNetworkMode` 只判断 `dispatch` 有没有抛异常就 `return true`；而 AOSP Android 14 的 `setAllowedNetworkTypesForReason` **本身就是返回 modem 是否接受的 boolean** —— 我们拿到了这个返回值却丢掉了。（1.5.0 起改为写进日志，但**仍不参与判定**，理由见 §8.4。）
3. **三条策略在 Android 14 上实际只剩一条**：`ITelephony` 上 `setAllowedNetworkTypes(long)` 与 `setPreferredNetworkType(int)` **已不存在**（AOSP `android14-release` 与 main 分支都只有 `setAllowedNetworkTypesForReason`；`grep` 结果为空），后两条是死代码，没有真正的降级余地。
4. **生效值是多个 reason 的合成结果**：`getAllowedNetworkTypesForReason(subId, REASON_USER)` 只回读 USER 一维。能读合成值的 `getAllowedNetworkTypesBitmask(subId)` 要求 `READ_PRIVILEGED_PHONE_STATE`（signature-only），普通应用拿不到。
5. **值未变时提前 `return true`**：AOSP 实现里有 `if (allowedNetworkTypes == phone.getAllowedNetworkTypes(reason)) return true;`。我们的 `core/mode/NetworkModeBitmaskMapper.kt:56-59 toBitmask` 用自己的表算掩码，与 ROM 取值对不上时可能算出与当前相同的掩码 ⇒ 「成功」但零动作。
6. **无权限与「回读一致」可以同时成立**：`setAllowedNetworkTypesForReason` 第一句就是 `TelephonyPermissions.enforceCallingOrSelfModifyPermissionOrCarrierPrivilege(...)`，`MODIFY_PHONE_STATE` 是 `signature|privileged`，`com.android.shell`（Shizuku 无线调试的 uid 2000）不持有；而 `settings put` 只需要 shell/root 的 `WRITE_SECURE_SETTINGS` ⇒ **制式没切，写入与回读却一路绿灯**。

**结论**：① 的成功条件与「制式真的换了」之间没有因果关系，它在结构上就无法回答那个问题。
1.5.0 没有改这个判定（改成「modem 说 false 就算失败」会改变降级/恢复语义：外层会因此回落到
`settings` 写入，或把这次切换标记为失败），而是把链路每一步都记进日志，让真验证成为可能：

```bash
# 1) 切一次制式，看 radio 日志里到底有没有 RIL 请求（决定性证据）
adb logcat -b radio | grep -iE "SET_ALLOWED_NETWORK|setAllowedNetworkTypes|PREFERRED_NETWORK"
#    若只有 settings 写入、没有任何 RIL 请求 ⇒「写进了 SettingsProvider 但没人理」当场成立
# 2) 看数据网络制式是否真的变了
adb shell dumpsys telephony.registry | grep -i mDataConnectionState
# 3) 对照系统设置里的「启用 5G」开关（vivo 等 ROM 用它做自有布尔门，不在这套 reason 维度里）
```

### 8.4 与耗电 / 资源的关系（逐项，含一处如实增加的开销）

| 项 | 影响 | 说明 |
|---|---|---|
| C14 日志落盘 | **增加**（如实记录） | 修好之前落盘等于没发生；修好之后「每 30 秒最多一次、最多 120 条的整体写入」回来了。这正是 1.2.0「日志落盘减量」的**既有设计**，本版只是让它真正生效 —— 净效果是「回到 1.2.0 设计的那条基线」，不是新增设计。不需要历史日志的用户可在日志页清空，落盘随即只剩空数组 |
| C15 诊断日志（`always` / `warn`） | **常态零开销** | 只在切换制式时写入，而切换制式本来就要起一次 shell；不新增任何定时任务或轮询 |
| C15 诊断日志（`detail`） | **默认关 ⇒ 0** | 逐候选 trace 用 `val trace: ((String) -> Unit)? = if (WriteDiag.isVerbose) { { … } } else null` 构造，**未开启时连 lambda 都不创建**，反射调用路径与 1.4.0 完全一致 |
| C15 子进程 `DIAG ` 行 | **仅详细模式** | 只有 `setmode` 才追加 `--verbose`；`getMode` 每个采样周期都会调用，刻意不带该标志，避免逐轮跨进程输出 |
| C15 modem 返回值只记日志 | 0 | 判定分支、shell 调用次数、返回的 Boolean 与 1.4.0 逐字节一致 |
| C16 删除 `activity-alias` | 无（略减） | 少一个组件声明，少一条 `setComponentEnabledSetting` 调用 |
| C17 `persistState` 改 `load().copy()` | 可忽略 | 改主题 / 导航栏 / 玻璃时才多读一次 SharedPreferences |

**本版仍然没有引入唤醒锁、精确闹钟、第二个前台服务通知，也没有新增任何依赖。**

### 8.5 量化对照（1.4.0 → 1.5.0）

| 项 | 1.4.0 | 1.5.0 | 说明 |
|---|---|---|---|
| 冷启动后日志页条目数 | **0** | 约 120（上次落盘内容） | C14 的直接可观测结果 |
| 写入链路在应用内日志的可见节点数 | 0 | 4 层 / 8 类事件 | 仅在真的切换制式时产生 |
| 详细诊断开启时每次 `setmode` 的额外日志行数 | — | 约 5–15 行 | 默认关 ⇒ 0 |
| 桌面图标数（干净安装、未启动） | 2 | **1** | C16 |
| 设置页开关数 | 6 | 8 | 新增「写入详细诊断日志」「自动检查更新」 |
| `AndroidManifest.xml` 组件数 | — | −1（`activity-alias`） | C16 |
| 新增依赖 | — | **0** | |
| `versionCode` / `versionName` | 2026100503 / 1.4.0 | **2026100504 / 1.5.0** | 为什么不是需求的「20261004」：它比上一版**小**，会被 `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝，见 TESTING §19.0 |
| 判定语义（降级 / 恢复 / 冷却 / 无网回退 / 假满格门控） | — | **逐字节不变** | 本版只加日志、只加 UI 开关 |

### 8.6 写入链路修复：起因（六条代码级结论）

**与耗电的关系先说清楚**：这一版对耗电与资源**几乎没有影响**（见 §9.4），它修的是正确性与可观测性。
之所以仍然写进这份报告，是因为它决定了「之前那些省电改动到底有没有真的生效」能不能被验证 ——
一个写不进制式的开关，讨论它的省电收益没有意义。

### 8.6.1 六条结论（原文保留）

用户把 1.5.0 之前那轮诊断的六条结论逐条带回来，要求逐条修复。六条按「证据强度」排列：

| # | 结论 | 证据 |
|---|---|---|
| 1 | 回读是「自己写自己读」，闭环里没有电话进程 / RIL / 调制解调器 | Android 11 起权威存储从 `Settings.Global` 搬到 TelephonyProvider；写 `settings` 与读 `settings` 是同一个 provider、同一行 |
| 2 | 真正该被回读的返回值拿到了却丢掉 | AOSP Android 14 `PhoneInterfaceManager.setAllowedNetworkTypesForReason` 结尾 `return success;`，`success` 即调制解调器是否接受；旧代码只看 `invoke` 有没有抛异常 |
| 3 | 三条写入策略在 Android 14 实际只剩一条 | `setAllowedNetworkTypes(long)` 与 `setPreferredNetworkType(int)` 在 `ITelephony` 上已不存在（AOSP `android14-release` 与 main 都只有 `setAllowedNetworkTypesForReason`） |
| 4 | 允许的网络类型是 `REASON_USER/CARRIER/MODEM` 多维求交，只回读 USER 一维 | 能读合成值的 `getAllowedNetworkTypesBitmask` 要求 `READ_PRIVILEGED_PHONE_STATE`（signature-only） |
| 5 | 值未变时提前短路 | AOSP 实现里有 `if (allowedNetworkTypes == phone.getAllowedNetworkTypes(reason)) return true;`；而 `toBitmask()` 用自建 `MODE_FAMILIES` 表，表外落到 `ALL_NETWORK_TYPES` 时可能正好等于当前值 |
| 6 | 无权限与「回读一致」可以同时成立 | `MODIFY_PHONE_STATE` 是 `signature\|privileged`，`com.android.shell`（uid 2000）不持有；而 `settings put` 只需 `WRITE_SECURE_SETTINGS` |

本版**只修观测层与真 bug**（用户选定的范围）：第 1、2、3、6 条变成日志与设置页里的事实，
第 5 条按「表外即拒绝」修掉，**第 2 条的返回值仍然只记录、不改判定** ——
把调制解调器的 `false` 当成「写入失败」会改变降级 / 恢复语义，那不在本版授权范围内。

### 8.7 改动清单（C18 – C23）

| # | 改动文件 | 级别 | 为什么 | 验证方式 | 回滚 |
|---|---|---|---|---|---|
| C18 | `core/mode/NetworkModeBitmaskMapper.kt`、`core/priv/TelephonyReflection.kt` | 真 bug | `toBitmask` 表外返回 `null`，`setNetworkMode` 拒绝写入 —— 旧行为会把「锁 5G」变成「放开全部制式」 | TESTING §20.2 | 恢复 `?: return ALL_NETWORK_TYPES` 与该调用点 |
| C19 | `core/priv/TelephonyReflection.kt` | 观测 | `dispatch` 新增 `onDenied`，`SecurityException` 单独挑出并带上 `Process.myUid()`，无条件记录 | TESTING §20.3 | 删 `permissionDenial` 与三处 `onDenied` 实参 |
| C20 | `core/priv/TelephonyReflection.kt`、`core/priv/WriteCompat.kt` | 观测 | `describeWriteMethods()` 运行时枚举 `ITelephony` 写入方法，三条全失败时一并报出 | TESTING §20.1 | 删方法与该调用点 |
| C21 | 新建 `core/priv/AuthStore.kt`、`core/priv/NetworkControlChannel.kt` | 观测 | 权威存储的读写与结果分类；接口加两个带默认实现的方法（未实现就如实说未实现） | TESTING §20.1 / §20.4 | 删新文件与接口两方法 |
| C22 | `core/priv/root/RootController.kt`、`core/priv/PrivilegedCli.kt`、`core/priv/shizuku/ShizukuController(Service).kt`、`IShizukuController.aidl` | 新增路径 | 直接写 `siminfo.allowed_network_types`；写入顺序 ITelephony → siminfo → settings | TESTING §20.4 | 逐处还原；AIDL 删两条 |
| C23 | `ui/screen/settings/SettingsPage.kt`、两个 `strings_keepalive.xml` | 观测 | 设置页「系统兼容性」加两行只读事实 | TESTING §20.1 | 删两行与两条文案 |

### 8.8 权威存储到底是什么（本版的事实基础）

- **它是一张小表里的一列，不是独立 provider**：AOSP main
  `packages/providers/TelephonyProvider/src/com/android/providers/telephony/TelephonyProvider.java`
  的建表语句里，`allowed_network_types` 与 `allowed_network_types_for_reasons` 是 **`siminfo` 表的列**
  （`COLUMN_ALLOWED_NETWORK_TYPES + " BIGINT DEFAULT -1,"`）；URI 由
  `s_urlMatcher.addURI("telephony", "siminfo", URL_SIMINFO)` 与
  `addURI("telephony", "siminfo/#", URL_SIMINFO_USING_SUBID)` 注册（`#` 是 subId）。
- **provider 自己不做权限检查**：同一目录的 `AndroidManifest.xml` 里
  `<provider android:name="TelephonyProvider" android:authorities="telephony" android:exported="true" ... />`
  **没有声明 `readPermission` / `writePermission`**（对比同文件的 `SmsProvider` / `MmsSmsProvider`
  都写了 `android:readPermission="android.permission.READ_SMS"`；
  `CarrierProvider` 写了 `writePermission="android.permission.MODIFY_PHONE_STATE"`）。
  也就是说**门槛来自调用方**（`PhoneInterfaceManager` 的 `enforceCallingOrSelfModifyPermissionOrCarrierPrivilege`），
  而不是 provider 自己 —— 这解释了为什么 root 渠道的 `content update` 能写进去，而应用进程反射
  `ITelephony` 会被拒。
- **列名字面量已在真机核实**：Redmi K40 / Android 15 的
  `/system/framework/framework.jar` 的 dex 字符串池里能直接 grep 到 `allowed_network_types`、
  `allowed_network_types_for_reasons`、`NETWORK_TYPE_BITMASK_NR`、`MODIFY_PHONE_STATE`
  （`adb-shell grep -a -o -m 1 '<字面量>' /system/framework/framework.jar` 全部命中），
  不需要反编译。
- **取不到的东西也如实记下**：`/system/framework/telephony-common.jar` 在 Android 15 上被剥离，
  自写 DEX 解析器读出它**不含 `Lcom/android/internal/telephony/ITelephony;`**，
  因此「本机 `ITelephony` 到底有哪些方法」不可能靠静态分析回答 ——
  这正是 C20 改成**运行时反射枚举**的原因。

### 8.9 与耗电 / 资源的关系（写入链路部分）

| 项 | 影响 | 说明 |
|---|---|---|
| C21 / C23 读取权威存储 | **设置页打开时 1 次** | 只在用户进「系统兼容性」卡片、或打开详细诊断时执行一次 `content query` / 一次 `ContentResolver.query`，不在主循环、不在采样周期 |
| C22 写权威存储 | **仅制式切换时**（低频） | 一次 `content update` + 一次 `content query` 回读；这是用户主动操作触发的路径，与 1.5.0 的 `settings put` 同级 |
| C19 / C20 日志 | 常态零开销 | `onDenied` 只在真的被权限拦下时触发；`describeWriteMethods()` 只在「三条全失败」或设置页探测时调用，**不在**每次写入路径上 |
| C18 | 无 | 一次 null 判断 |
| 新增唤醒锁 / 闹钟 / 服务 / 依赖 | **无** | 本版没有新增任何后台组件、权限或第三方依赖 |

**净结论**：本版对空闲耗电没有可测量的影响，对「切换制式」这一次操作的耗时增加约一次 `content`
进程启动（与已有的 `settings put` 同量级）。这是为了让「写没写进去」第一次真的有据可查。

### 8.10 运营商识别与默认制式表：按公开来源重新核对（本轮新增）

**起因**：`core/mode/NetworkMode.kt` 的 `OPERATOR_DEFAULTS`（MNC → 默认制式）决定「解除锁 5G 时回落到哪个制式」
—— `core/NetPilot.kt` 的 `lockLte(off)` 在用户选「跟随运营商」时取的就是它，`core/monitor/AutoDowngradeEngine.kt:299`
的恢复路径也取它。旧表把 `46010` 记作联通、`46027` 记作电信，而这两个 MNC 在任何公开来源里都查不到；
反过来 `46005`（电信 CDMA）与 `46020`（铁通，2008 并入移动）确实存在却漏了 ——
前者的后果是**这张卡落到兜底 26（联通档）**，静默地回错了制式。

**四个独立来源（MCC 460）的核对结果**：

| 来源 | 覆盖的 460 号段 |
|---|---|
| `musalbas/mcc-mnc-table` | 00 移动、01 联通、02 移动、03 电信、04 卫星、05 电信、06 联通、07 移动（共 9 条） |
| `pbakondy/mcc-mnc-list` | 在上面基础上另有 08 移动、09 联通、11 电信、**15 广电（在用）**、**20 铁通（在用）**（共 13 条） |
| `mcc-mnc.org/mcc/460` | 00/02/04/07/08 移动、01/06/09 联通、03/05/11 电信、20 铁通（**不含 15**） |
| ITU-T E.212 公报 OB 1280（2023） | 只登记 `460 00` China Mobile、`460 01` China Unicom、`460 03` China Unicom CDMA、`460 04` China Satellite Global Star |

**没有任何一个来源出现 `46010` 或 `46027`**；`46015` 只出现在 pbakondy 的在用列表里（与 192 号段 2022 年商用的事实吻合）
—— 说明「只查一个来源就会漏」在两个方向上都成立。

**改了什么**：

| 运营商 | 去前导零后的 MNC 键 | 默认制式 |
|---|---|---|
| 中国电信 | `3` / **`5`（新增）** / `11` | 27 `NR/LTE/CDMA/EVDO/GSM/WCDMA` |
| 中国移动 | `""`（46000）/ `2` / `4` / `7` / `8` / **`20`（新增）** | 32 `NR/LTE/TDSCDMA/GSM/WCDMA` |
| 中国联通 | `1` / `6` / `9`（**删掉 `10`**） | 26 `NR/LTE/GSM/WCDMA` |
| 中国广电 | `15` | 33 `NR/LTE/TDSCDMA/CDMA/EVDO/GSM/WCDMA` |

- 上表的制式数值逐个对应 AOSP `RILConstants.java` 的 `NETWORK_MODE_*`（权威定义，未改）；
- **删掉查不到的键的代价如实写在这里**：万一 `46027` 真的存在于某张电信卡上，它现在落到兜底 26 而不是 27 ——
  与「它本来就没有权威归属」一致，因此不算回归；
- 非国内卡、或国内号段不在表内时仍取兜底 26（原有语义不变）。

**识别能力（新增，并且能被看见）**：新增 `Carrier` 枚举（中国移动 / 联通 / 电信 / 广电）、
`carrierOf(mcc, mnc)`、`carrierName(mcc, mnc)`；`CarrierInfo`（`core/monitor/MonitorSettings.kt:207`）新增
`activeCarrierName(context, subId)` 与 `activeCarrierSummary(context, subId)`；设置页「系统兼容性」卡片新增只读行
「运营商识别」，值形如 `中国移动（46000）→ 32 NR/LTE/TDSCDMA/GSM`，表里没有的号段会明确显示
`不在内置运营商表中（460xx）→ 回落 26 NR/LTE/GSM/WCDMA`。

**为什么要显示出来**：这张表本质是猜，猜错不会报错、只会安静地回到错的制式。摊在设置页后，换一张卡就能立刻验证。

### 8.11 Tasker 事件出口：接口关闭时不再外发（本轮新增）

**起因**：`TaskerGate` 只管**组件启用状态**，而五条事件发送路径（`signalSampled` / `downgraded` / `recovered` /
`modeChanged` / `dataSimChanged`）全部汇到 `TaskerEventSender.broadcast()`，那里**没有任何开关判断**；
再加上 `TemplateApp.kt:28` 无条件 `TaskerBridge.init(this)`，于是「Tasker 接口关着」时快照与降级事件照样
`sendBroadcast` 出去 —— 开关形同虚设，而且每轮信号采样都要白构造一次 `Intent` 再广播（纯浪费）。

**改法**（三处，都在 `core/tasker`）：

| 位置 | 改动 | 为什么选这里 |
|---|---|---|
| `TaskerEventSender.broadcast()` | 首行 `if (!TaskerGate.isEnabled(context)) return` | 五条路径唯一的收口点，一次覆盖全部事件；将来新增事件也不会漏 |
| `TaskerGate` | 新增 `isEnabled(context)`；`sync(context)` 末尾 `if (enabled) TaskerBridge.init(app)` | 开关打开时才接线；`ConfigState.observe(KEY_ENABLED)` 本来就会在开关变化时回调 `sync` |
| `TemplateApp.onCreate` | 删掉无条件的 `TaskerBridge.init(this)` | 无条件接线正是「关着也发」的第二个入口 |

**语义**：`np_tasker_enabled` 默认 false（既有默认值，未改）⇒ 默认状态下不再有任何 Tasker 广播；
打开开关后行为与之前**完全一致**（`TaskerGate.install` 会立即 `sync` 一次并接线）。
`TaskerBridge.init` 内部的 `started` 幂等保护仍然在，重复调用不会重复订阅。

### 8.12 详细诊断日志：覆盖面扩展（本轮新增）

`WriteDiag.detail()` 是唯一受「写入诊断日志」开关（`np_verbose_log`，默认**关**）控制的级别；
`always()` / `warn()` 无条件记录（原有语义，未改）。本轮把**一处日志都没有**的两个文件补上，
并把已有几处补到「能据此定位」的粒度：

| 文件 | 补了什么 |
|---|---|
| `core/priv/AuthStore.kt` | 原来 0 处。现在记：`content query` / `content update` 的**完整命令原文**、分类结果（`Read` / `Write` 的判定）、未定论时附 stdout/stderr、ContentResolver 读写结果 —— 这是本版新增的写入路径，之前出问题只能靠猜 |
| `core/priv/root/RootController.kt` | `authStoreSetMode` 记「命令原文 + `content update` 的原始 exit/stdout/stderr」；`readAuthStore` 记**逐列试探**（`allowed_network_types` → `allowed_network_type`）与每列原始输出；`writeAuthStore` 记原始结果 |
| `core/priv/PrivilegedCli.kt` | `writeAuthStore` 记「目标 mode → 位掩码」「被拒时的原始输出」「回读原文 + 解析值」 |
| `core/priv/shizuku/ShizukuControllerService.kt` | 记「三条 ITelephony 都没成、转写权威存储」「ContentResolver 是否就绪」「写入失败原因」 |
| `core/priv/shizuku/ShizukuController.kt` | 记 AIDL 调用返回的编码字符串（区分「用户服务没绑上」与「provider 拒绝」） |

**代价**：全部走 `detail()`，开关关闭时**一行都不写**；打开后单次切换最多新增十几行，
日志页仍是 `MAX_ENTRIES = 400` / `MAX_MESSAGE_CHARS = 2000` / 落盘 120 条的既有上限。
**未验证**：真机上这些行的实际内容见 [`TESTING.md`](TESTING.md) §19.14。

### 8.13 补丁（同一版本内，未改版本号）：把「AMS 不认调用方进程」与「缺权限」分开

**起因**：真机（Android 15 / Shizuku 通道）日志页出现

`shizuku 权威存储读取：subId=1 -> 被拒绝：allowed_network_types: Unable to find app for caller android.app.IApplicationThread$Stub$Proxy@a67e245 (pid=28246) when getting content provider telephony`

**为什么不只是文案问题**：这句 `SecurityException` 与权限无关。`ContentResolver.acquireProvider` 会先让 AMS
按**调用方 pid** 找一条应用进程记录（`getRecordForApp`），找不到就直接抛异常 —— Shizuku 用户服务进程由
Shizuku 守护进程用 `app_process` 拉起，从未 `attachApplication`，AMS 侧没有它的记录，**给多少权限都过不去**。
旧版把这一句与 `Permission Denial` 一起归成「被拒绝」，等于把用户引向「去授权」这条走不通的路。

**反证（解释 Root 通道为什么没这个问题）**：Root 通道走 `/system/bin/content`，`cmd content` 内部用的是
隐藏 API `IActivityManager.getContentProviderExternal`，那个入口不需要应用进程记录。开源先例（同结论的注释）：
[darkclad/uxspace@1d83515](https://github.com/darkclad/uxspace/commit/1d835151c6004e9daf0b4ec9704b66534cab7093) ——
「needs a registered application record for the calling pid, which only AMS-launched processes have.
Our shell-uid app_process server has none.」

**改动**（`app/src/main/java/com/katiusu/netpilot/core/priv/AuthStore.kt`，C27）：

| 判据 / 出口 | 旧 | 新 |
|---|---|---|
| 输出或异常消息含 `Unable to find app for caller` / `when getting content provider`（`isNoAppRecord`） | 与权限失败同归 `Denied` | `Read.Unavailable` / `Write.Failed`，并带上 `NO_APP_RECORD_HINT`（「…补授权无效；读写权威存储只能用 Root 通道」） |

> **1.5.1 更新**：这一条单一判据已扩展成 `AuthStore.callerHint()` 的三条判据 ——
> 身份名单（`Access SIMINFO table from not phone/system UID`）、缺 SIMINFO 库权限
> （`No permission to access SIMINFO table`）、AMS 无调用方记录（`Unable to find app for caller`），
> 分别给不同文案；并新增「读到 NoRow 时枚举整张表」。见 §9.1 / §9.2。
| 其它 `SecurityException` / `Permission Denial` | `Denied` | 不变（仍是「被拒绝：」） |
| `describeRead` / `describeWrite` | 「被拒绝：」/「失败：」混在一起 | 分别为「读不到：」与「失败：」，与「被拒绝：」在设置页和日志页一眼可分 |

**边界**：只改**分类与文案**，不改任何判定、重试或写入顺序；`encodeRead/decodeRead`、`encodeWrite/decodeWrite`
早已覆盖 `UNAVAILABLE` / `FAILED`，跨 binder 不需要新增编码分支。代价：每次读写多一次字符串 `contains`（可忽略）。

**验证**：真机复现与判读步骤见 [`TESTING.md`](TESTING.md) §19.9 第 5–6 条与 §19.16。

### 8.14 量化对照（同一版内：改动前 → 改动后）

| 项 | 改动前 | 改动后 | 说明 |
|---|---|---|---|
| 写入路径层数 | 2（ITelephony → settings） | **3**（ITelephony → siminfo → settings） | 新增权威存储层 |
| 能读到的「允许的网络类型」数据源 | 1（`settings`） | **3**（`settings` / `siminfo`（经通道）/ `siminfo`（应用进程）） | 交叉回读 |
| 表外模式的后果 | 写入 `(1 shl 31) - 1`（放开全部制式） | 拒绝写入 + 日志说明 | C18 |
| 权限被拒时的日志 | 无（被 catch 吞掉） | `SecurityException` + `Process.myUid()` + 缺失权限名 | C19 |
| 「三条策略」剩余条数 | 未知（靠读 AOSP 推断） | 由设备运行时报告 | C20 |
| 设置页「系统兼容性」只读行数 | 4 | **7** | 新增「本机可用的写入方法」「权威存储」「运营商识别」 |
| 新增依赖 | — | **0** | |
| `versionCode` / `versionName` | 2026100504 / 1.5.0 | **2026100504 / 1.5.0（不变）** | 1.5.0 从未发布过：后续几批改动并入本版，不占新版本号 |

### 8.15 产物与验收（1.5.0 / 2026100504，最终）

**8.15.1 产物**

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `NetPilot-1.5.0-2026100504-release.apk` | 33,221,389 B | `048e94ec9230df709c24b78b8a65acd42380bf2fc5459bb7a323f5e4b66f06f9` |
| `NetPilot-1.5.0-2026100504-debug.apk` | 43,848,575 B | `ea6b9a97367422172577cc1af867afa66c5613c2724cef96800737a9c8812856` |

上表是 **C27（§8.13 的失败分类补丁）之后重新构建**的值：`versionCode` / `versionName` 按要求**保持不变**，
所以这是本节唯一的最终交付值（构建时间 2026-10-05）。
新代码确实进了产物 —— 解包两个 APK 的 `classes*.dex` 后 `grep -a`：
`Unable to find app for caller` 命中 1 次、`补授权无效` 命中 2 次（release 在 `classes2.dex`，debug 在 `classes14.dex`）。

两个 APK 均由 `bash _build.sh :app:<task> --no-configuration-cache` 产出后复制到项目根目录。
项目根目录下**同名的旧 1.5.0 产物已被这两个文件覆盖** —— 1.5.0 从未发布过，不存在「两个不同的 1.5.0」。
**APK 只交付、不安装**（agent 不自行安装到任何设备）。

**8.15.2 复现命令**

```bash
bash _build.sh :app:assembleRelease --no-configuration-cache
bash _build.sh :app:assembleDebug   --no-configuration-cache
BT=/opt/android-sdk/build-tools/36.0.0
$BT/aapt2 dump badging NetPilot-1.5.0-2026100504-release.apk | head -1
$BT/aapt2 dump xmltree --file AndroidManifest.xml NetPilot-1.5.0-2026100504-release.apk | grep -c 'android.intent.action.MAIN'
$BT/aapt2 dump xmltree --file AndroidManifest.xml NetPilot-1.5.0-2026100504-release.apk | grep -c 'enabled.*false'
/opt/android-sdk/build-tools/37.0.0/apksigner verify --print-certs NetPilot-1.5.0-2026100504-release.apk
$BT/zipalign -c -P 16 -v 4 NetPilot-1.5.0-2026100504-release.apk
python3 tools/check_provenance.py
```

**8.15.3 实测结果**

| 检查项 | 结果 |
|---|---|
| `aapt2 dump badging` | `versionCode='2026100504' versionName='1.5.0'`、`targetSdkVersion:'36'`、`compileSdkVersion:'37'`、`native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'`（release 与 debug 一致） |
| 启动入口 | `launchable-activity: name='com.katiusu.netpilot.MainActivity'` |
| 清单里 `android.intent.action.MAIN` 计数 | **1**（release 与 debug 均 1；1.4.0 为 2，本版起为 1） |
| Tasker 组件 `android:enabled=false` 计数 | **3**（release 与 debug 均 3）⇒ C26 只改代码、没有动 `AndroidManifest.xml`，组件禁用机制与 1.4.0 一致 |
| 清单里 `LauncherAlias` 计数 | **0**（C16 的清理保持） |
| `apksigner verify --print-certs` | 证书 SHA-256 `34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c` —— 与 1.3.0 / 1.4.0 **同一把 key** ⇒ 可直接覆盖安装（`SharedPreferences` 不清） |
| `zipalign -c -P 16 -v 4` | `Verification successful`（release 与 debug 均通过） |
| C27 新代码是否在产物里 | 解包 `classes*.dex` 后 `grep -a "Unable to find app for caller"` = 1 次、`grep -a "补授权无效"` = 2 次（release 与 debug 均命中）⇒ 交付的 APK 含本次分类修正 |
| `python3 tools/check_provenance.py` | `EXIT=0`，`checked 7 pair(s), worst duplicated share 27.2% (threshold 25%)`；唯一 REVIEW 项仍是既有的 `ShizukuControllerService.kt`（已标注 reviewed）。逐项：`NetworkMode.kt` 0.220 / `NetworkModeBitmaskMapper.kt` 0.297（重复段 12.1%）/ `TelephonyReflection.kt` 0.231（11.0%）/ `ControlManager.kt` 0.073 / `ShizukuController.kt` 0.159 / `ShizukuControllerService.kt` 0.265（27.2%，已 reviewed）/ `IShizukuController.aidl` 0.544（签名契约）；本版新增的 `AuthStore.kt` 不在比对集内 |
| 架构自检（root=`app/src/main/java/com/katiusu/netpilot`） | `scannedFiles: 108`、`cycles: []`；超大模块 5 个：`MonitorPage.kt` 710、`MainActivity.kt` 643、`SettingsPage.kt` **607**（本轮新增「运营商识别」一行后又长了 9 行）、`TaskerEditActivity.kt` 547、`LiquidGlassNavigationBar.kt` 533 |

> 「无环」在此只能算**弱证据**：该自检工具解析不出 Kotlin 的包级导入（`moduleCount: 0` / `importEdges: 0`），
> 它报告的 `cycles: []` 不构成完整的依赖图证明。

**8.15.4 构建过程中发现并修掉的一个构建系统问题（值得留档）**

第一次 `:app:assembleDebug` 用的是**默认的 configuration cache**（`gradle.properties` 里
`org.gradle.configuration-cache=true`），结果 `processDebugManifestForPackage` 被判为 `UP-TO-DATE`，
**合并清单里还是上一批构建写下的旧 `versionCode`**：产出的 debug APK 经 `aapt2 dump badging`
如实报告旧版本号，而同一份 `app/build.gradle.kts` 产出的 release APK 却是新版本号。
即 **「构建成功」不等于「产物是这一版的」**。修法是删掉 debug 变体的清单中间产物后用
`--no-configuration-cache` 重跑：

```bash
rm -rf app/build/intermediates/merged_manifest/debug \
       app/build/intermediates/merged_manifests/debug \
       app/build/intermediates/packaged_manifests/debug \
       app/build/intermediates/manifest_merge_blame_file/debug \
       app/build/intermediates/compatible_screen_manifest/debug \
       app/build/outputs/apk/debug
bash _build.sh :app:assembleDebug --no-configuration-cache
```

重跑后 `output-metadata.json` 与 `aapt2 dump badging` 都变成了 `app/build.gradle.kts` 里的那一个版本号。
**结论：凡带版本号发布的构建，一律加 `--no-configuration-cache`，并在复制产物前核对 `versionCode`。**

**本版实战**：版本号切回 `2026100504 / 1.5.0` 之后，release 与 debug 都带 `--no-configuration-cache` 重跑
（debug 先删掉 5 个清单中间产物目录）。值得一提的是 debug 这一次 `processDebugManifestForPackage` 是
`FROM-CACHE`，**正是这个坑最容易复发的时刻** —— 所以复制产物后仍然逐个核对了 badging 第一行，
两个产物都报 `versionCode='2026100504' versionName='1.5.0'`（见 §8.15.3）。

**未做（需要真机）**：本版所有需在设备上观察的结论 —— 设置页三行只读事实（写入方法 / 权威存储 /
运营商识别）的实际取值、表外模式拒绝写入的日志、Shizuku 通道的 `SecurityException` 长什么样、
权威存储写入与回读的顺序、Tasker 开关关闭时确实没有广播、详细诊断日志的实际内容 —— 全部写在
[`TESTING.md`](TESTING.md) §19（19.1–19.16）。`siminfo` 的读写在本环境被 DSHA 策略拦截
（`content query --uri content://telephony/...` → `[POLICY_BLOCKED] 短信授权不包含其他内容提供者或 URI 参数`），
**无法在 agent 侧真机验证**。

## 9. 1.5.1 增补（权威存储读取修复 + 日志可分析性 + 日志页倒序）

> 本版 `versionName = 1.5.1`、`versionCode = 2026100505`。1.5.0 已发布，这是它的第一个补丁版本；
> 判定语义、默认值与用户可见行为**一律不变**。

### 9.1 起因：三条真机日志被同一句「读不到」概括

1.5.0 发布后，真机上出现三种**不同**的失败，但设置页与日志页把它们写成了同一个样子：

| 真机看到的原文 | 实际原因 | 发生在哪 |
|---|---|---|
| `没有这个 subId 的行` | `siminfo` 表里确实没有这一行（或整张表为空 —— 本机没插卡 / provider 未登记） | Root 通道（`content query` 单行查询） |
| `被拒绝：allowed_network_types: Access SIMINFO table from not phone/system UID` | TelephonyProvider 的**身份名单**只放行 system(1000) / phone(1001) / root(0) | 应用进程（uid 10xxx）与 Shizuku（uid 2000） |
| `Unable to find app for caller … when getting content provider telephony` | AMS 找不到调用方 pid 的**应用进程记录**（该进程由 `app_process` 启动、从未 `attachApplication`） | Shizuku 用户服务进程 |

用户指出「这个中文你肯定翻译错了」—— 1.5.0 的 `NO_APP_RECORD_HINT`（讲 AMS 记录）被套在了第二种情况上。

**AOSP 依据**（`packages/providers/TelephonyProvider`；main 与 `android-15.0.0_r1` 两份源码均已核对）：

- `checkPermissionForSimInfoTable()`：先 `ensureCallingFromSystemOrPhoneUid("Access SIMINFO table from not phone/system UID")`，再 `checkCallingOrSelfPermission("android.permission.ACCESS_TELEPHONY_SIMINFO_DB")`，否则 `SecurityException("No permission to access SIMINFO table")`。
- `isCallingFromSystemOrPhoneUid()`：`TelephonyPermissions.isSystemOrPhone(callingUid) || UserHandle.isSameApp(callingUid, Process.ROOT_UID)`，源码注释原文「Allow ROOT for testing. ROOT can access underlying DB files anyways.」⇒ **名单 = system / phone / root**。
- 结论：**第一种判据是身份判断，不是权限** —— 应用进程与 Shizuku 补任何权限都不会通过（`ACCESS_TELEPHONY_SIMINFO_DB` 是 signature|privileged，第三方拿不到）；root 是**被特意放行**的，所以 Root 通道「读不到」只可能是「表里没有那一行」，不是被拒。

### 9.2 改动清单（C28 – C31）

| # | 改动文件 | 级别 | 为什么 | 验证方式 | 回滚 |
|---|---|---|---|---|---|
| C28 | `core/priv/WriteDiag.kt`、`core/priv/TelephonyReflection.kt`、`core/priv/PrivilegedCli.kt`、`core/priv/shizuku/ShizukuController(Service).kt`、`core/NetPilot.kt` | 可分析性（真问题） | 1.5.0 的结论行只有「切换失败」四个字，原因埋在深处、且多半只在详细开关打开时才有 | TESTING §20.1 / §20.2 | 还原 `warn`/`detail` 分级与 `NetPilot.setMode` 的日志行 |
| C29 | `core/priv/AuthStore.kt`、`core/priv/root/RootController.kt`、`core/priv/ControlManager.kt`、`core/priv/WriteCompat.kt` | 真 bug（诊断错误） | 三种「读不到」被混为一谈；Root 通道只说「没有这个 subId 的行」而不说表里到底有什么；subId 为 -1 时整条链路无解 | TESTING §20.4 / §20.5 | 还原 `callerHint` 三条判据、`NoRow(detail)`、`explainNoRow()` 与 subId 候选补齐 |
| C30 | `ui/screen/log/LogPage.kt` | 可用性 | 日志页旧→新，最需要看的最新一条在最下面；导出保持旧→新 | TESTING §20.3 | 去掉 `.asReversed()` 与 `animateScrollToItem(1)` |
| C31 | `app/build.gradle.kts` | 版本 | 1.5.0 已发布，补丁必须换版本号 | TESTING §20.0 | 改回 2026100504 / 1.5.0 |

### 9.3 日志分级：结论带原因，步骤归开关

- **结论行（无条件）**：`NetPilot.setMode` 的成功/失败行、每条链路最终的失败原因、决策（拒绝写表外模式、没有可用通道、目标行不存在、权限被判身份名单拦下）。失败行现在是 `卡 N 切换 X 失败：<最深一层的失败原因>`。
- **原因怎么跨层传上来**：`WriteDiag` 增加 `@Volatile lastFailure` + `rememberFailure()` / `consumeFailure()`；`warn()` 无条件记，`detail()` **只在详细开关打开时**记。`NetPilot.setMode` 拿到的仍然只是 `Boolean`，失败时 `consumeFailure()` 取走原因并清空（避免下一次失败带上过期原因）。Shizuku 通道另在应用进程侧 `rememberFailure()` 补一句能指明方向的结论（服务侧逐条原因只在详细开关下可见）。
- **过程行（详细开关）**：逐策略返回值、`content update` / `settings put` 的原始 exit 与输出、回读原文、`ContentResolver` 是否就绪、逐列试探。1.5.1 把 1.5.0 里 **6 处**误放在 `always` 的过程行降为 `detail`（`TelephonyReflection` 的反射失败、三条策略返回值、`PrivilegedCli.writeSettings` 原始结果、`ShizukuControllerService` 的权威存储回退结果）。
- 硬要求：**关掉详细开关后，结论行必须仍然带原因**（原因来自 `warn` 与「开关打开时的 `detail`」；若确实没有更深一层原因，显示「通道没有报出具体原因」）。

### 9.4 与耗电 / 资源的关系

| 项 | 开销 |
|---|---|
| C28 原因暂存 | 一次 `String` 字段写入（内存），无 I/O；`consumeFailure()` 是一次读 + 置空 |
| C29 枚举整张表 | **只在已经确定「目标行不存在」之后**执行一次 `content query`（无 `--where`）；读得到时完全不执行 |
| C29 subId 候选补齐 | 只在系统返回 -1 时调用一次公开 API（`getActiveSubscriptionInfoList()`），且包在 `runCatching` 里 |
| C29 逐列试探 | 与 1.5.0 相同（最多两列，均只在读不到时） |
| C30 倒序 | `asReversed()` 是列表视图，无拷贝；导出时再翻一次 |

结论：本版**没有常态新增开销**，只在「已经失败」的路径上多做一次诊断查询。

### 9.5 量化对照（1.5.0 → 1.5.1）

| 项 | 1.5.0 | 1.5.1 | 说明 |
|---|---|---|---|
| `versionCode` / `versionName` | 2026100504 / 1.5.0 | **2026100505 / 1.5.1** | 补丁版本（C31） |
| 失败结论行是否带原因 | ✗（只有「切换失败」） | ✓（带最深一层原因） | C28 |
| 过程行的归属 | 6 处混在无条件级别 | 全部归「写入详细诊断日志」 | C28 |
| 三种「读不到」是否分开 | ✗（AMS 文案套在身份名单那种情况上） | ✓（三条判据各自文案） | C29 |
| Root 通道「没有这个 subId 的行」 | 只有这一句 | 追加「表里现有 N 行 / 整张表为空」+ 候选 subId | C29 |
| 默认数据卡 subId 为 -1 时 | 整条链路卡住 | 回落到活动卡 / 默认语音卡，且不改写目标 subId | C29 |
| 日志页顺序 | 旧→新 | **新→旧**（导出仍旧→新） | C30 |
| 判定语义 / 默认值 / 用户可见行为 | — | **不变** | — |

### 9.6 产物与验收（1.5.1 / 2026100505，最终）

**9.6.1 产物**

| 文件 | 大小 | SHA-256 |
| --- | --- | --- |
| `NetPilot-1.5.1-2026100505-release.apk` | 33,237,773 B | `30606dac74ca374c93b348e6fe1c4aa22904fe50c16404a820311392cbf2c8c1` |
| `NetPilot-1.5.1-2026100505-debug.apk` | 43,864,959 B | `e55e5d5ff0bed578d4a3c414c8134f21e96e400bec534e441ce6d38ee2af8d19` |

两个产物都用 `--no-configuration-cache` 构建。release 仍用项目里那把 release key（证书 SHA-256
`34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c`，与 1.3.0 起各版一致）——
所以 2026100505 这个 versionCode 与那个**从未发布**的 1.6.0 测试包相同，装过测试包的设备可以直接覆盖升级。

**9.6.2 复现命令**

```bash
# release
bash _build.sh :app:assembleRelease --no-configuration-cache
# debug：先清掉清单中间产物，避免 configuration cache 复用旧的合并清单
rm -rf app/build/intermediates/{merged_manifest,merged_manifests,packaged_manifests,manifest_merge_blame_file,compatible_screen_manifest}/debug \
       app/build/outputs/apk/debug
bash _build.sh :app:assembleDebug --no-configuration-cache

# 核验
BT=/opt/android-sdk/build-tools/36.0.0
BTS=/opt/android-sdk/build-tools/37.0.0
$BT/aapt2 dump badging NetPilot-1.5.1-2026100505-release.apk | head -1
$BT/aapt2 dump xmltree --file AndroidManifest.xml NetPilot-1.5.1-2026100505-release.apk | grep -c 'android.intent.action.MAIN'
$BT/aapt2 dump xmltree --file AndroidManifest.xml NetPilot-1.5.1-2026100505-release.apk | grep -c 'enabled.*false'
$BTS/apksigner verify --print-certs NetPilot-1.5.1-2026100505-release.apk | grep -i 'SHA-256'
$BT/zipalign -c -P 16 -v 4 NetPilot-1.5.1-2026100505-release.apk | tail -1
sha256sum NetPilot-1.5.1-2026100505-*.apk
```

**9.6.3 实测结果**

| 检查项 | 结果 |
| --- | --- |
| `aapt2 dump badging` | 两个产物都是 `versionCode='2026100505' versionName='1.5.1'`、`targetSdkVersion:'36'`、`compileSdkVersion:'37'` |
| 启动图标 | `android.intent.action.MAIN` = **1**（release 与 debug） |
| Tasker 组件默认关 | `enabled.*false` = **3**（release 与 debug） |
| `zipalign -c -P 16 -v 4` | `Verification successful`（两个产物） |
| apksigner 证书 | `34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c` |
| 新代码是否在产物里 | 解包 release 的 `classes*.dex` 后 `grep -a`：`整张表现在是空的` 1 次（C29 的 siminfo 枚举结论）、`逐策略问题` 1 次（C28 的结论行原因回传）；1.5.0 那句旧的 `该通道进程不是应用进程` 已不见（被三条判据取代） |
| `python3 tools/check_provenance.py` | `EXIT=0`，`checked 7 pair(s), worst duplicated share 27.2%`（唯一 REVIEW 仍是 `ShizukuControllerService.kt`，已 reviewed） |
| 架构自检 | `scannedFiles: 108`、`cycles: []`、超大模块仍是那 5 个（本版未新增） |

**9.6.4 与耗电 / 资源的关系**：本版新增的都是「出错时才拼字符串」与一次读操作（失败路径上多跑一条 `content query`
枚举 `siminfo`），成功路径零新增常驻开销；日志页倒序只是把同一个列表翻一次，不增加内存与 IO。唯一可感知的变化是
日志内容变长（上限仍由 `LogStore.MAX_MESSAGE_CHARS = 2_000` 与 `MAX_ENTRIES = 400` 兜住）。

**9.6.5 未做（需要真机）**：设置页那几行只读事实的实际取值、三种「读不到」在真机上是否分别显示、
日志页是否确实新在上、结论行里是否带上了每一步的原因 —— 全部写在 [TESTING.md](TESTING.md) §20（20.1–20.6）供用户自测。

## 附录 A：改动文件与回滚

**1.5.0 新增文件（2 个）**

```
app/src/main/java/com/katiusu/netpilot/core/priv/WriteDiag.kt   C15
app/src/main/java/com/katiusu/netpilot/core/priv/AuthStore.kt   C21 / C22
```

**1.5.0 删除文件（1 个）**

```
app/src/main/java/com/katiusu/netpilot/LauncherIconController.kt   C16
```

**1.3.0 新增文件（1 个）**

```
app/src/main/java/com/katiusu/netpilot/core/tasker/TaskerGate.kt   C9
```

**1.2.0 新增文件（2 个）**

```
app/src/main/java/com/katiusu/netpilot/core/update/UpdateChecker.kt
app/src/main/java/com/katiusu/netpilot/ui/component/UpdateDialog.kt
```

**1.5.0 合并批次额外改动的文件**（后续几批并入 1.5.0，故单列在这里）

```
app/build.gradle.kts                                            C24（版本号回到 1.5.0 / 2026100504）
app/src/main/aidl/.../shizuku/IShizukuController.aidl           C21 / C22
core/mode/NetworkMode.kt                                        C25（运营商表按公开来源重核）
core/mode/NetworkModeBitmaskMapper.kt                           C18（表外返回 null）
core/monitor/MonitorSettings.kt                                 C25（运营商识别 / activeCarrierSummary）
core/priv/AuthStore.kt                                          C21 / C22（新增）
core/priv/NetworkControlChannel.kt                              C21（readAuthStore / writeAuthStore 默认实现）
core/priv/PrivilegedCli.kt                                      C20 / C22
core/priv/TelephonyReflection.kt                                C18 / C19 / C20
core/priv/WriteCompat.kt                                        C19 / C20 / C25（兼容性卡片新增只读行）
core/priv/root/RootController.kt                                C20 / C21 / C22
core/priv/shizuku/ShizukuController.kt                          C21 / C22
core/priv/shizuku/ShizukuControllerService.kt                   C18–C22
core/tasker/TaskerEventSender.kt                                C26（事件出口门控）
core/tasker/TaskerGate.kt                                       C26（isEnabled + 按需接线）
TemplateApp.kt                                                  C26（去掉无条件 TaskerBridge.init）
ui/screen/settings/SettingsPage.kt                              C15 / C19 / C20 / C25
res/values/strings_keepalive.xml                                C15 / C19 / C20 / C25
res/values-en/strings_keepalive.xml                             C15 / C19 / C20 / C25
```

**1.5.1 改动的文件**（未新增文件，全部是已存在文件的修改；同版本内补丁，不占新版本号）

```
app/build.gradle.kts                                            C31（版本号 1.5.1 / 2026100505）
core/NetPilot.kt                                                C28（失败结论行带上原因）
core/priv/AuthStore.kt                                          C29（callerHint 三判据 / NoRow 带原因 / siminfo 枚举 / subId 候选）
core/priv/ControlManager.kt                                     C29（subId 为 -1 时用候选补齐）
core/priv/PrivilegedCli.kt                                      C28（writeAuthStore / writeSettings 回传原因）
core/priv/TelephonyReflection.kt                                C28（逐策略原因进结论行）
core/priv/WriteCompat.kt                                        C29（描述行带 sub_id 与三种原因）
core/priv/WriteDiag.kt                                          C28（lastFailure / consumeFailure）
core/priv/root/RootController.kt                                C29（explainNoRow 枚举整张表）
core/priv/shizuku/ShizukuController.kt                          C28
core/priv/shizuku/ShizukuControllerService.kt                   C28
ui/screen/log/LogPage.kt                                        C30（日志页倒序，导出仍按时间顺序）
README.md / README_EN.md / docs/*.md                            文档同步
```

**修改文件**

```
app/build.gradle.kts                                            C6 / C8
app/src/main/AndroidManifest.xml                                C9
MainActivity.kt                                                 C1 / C7
TemplateApp.kt                                                  C5 / C9
core/NetPilot.kt                                                C2
core/keepalive/KeepAliveReceiver.kt                             C3 / C11-①
core/keepalive/KeepAliveScheduler.kt                            C3
core/keepalive/KeepAliveState.kt                                C3
core/monitor/AutoDowngradeEngine.kt                             C2
core/monitor/LogStore.kt                                        C4
core/monitor/MonitorEngine.kt                                   C2 / C10
core/monitor/MonitorModels.kt                                   C2 / C10
core/monitor/MonitorService.kt                                  C2 / C3 / C5
core/monitor/MonitorSettings.kt                                 C10
core/monitor/SignalReader.kt                                    C2
core/priv/ControlManager.kt                                     C5
core/priv/shizuku/ShizukuController.kt                          C5
prefs/ConfigState.kt                                            C9
ui/screen/about/AboutPage.kt                                    C7
ui/screen/features/FeaturesPage.kt                              C9 / C10
ui/screen/monitor/MonitorPage.kt                                C1 / C2
res/values/strings_monitor.xml                                  C2
res/values-en/strings_monitor.xml                               C2
res/values/strings_np.xml                                       C8 / C9 / C10
res/values-en/strings_np.xml                                    C8 / C9 / C10
```

**回滚方式**

| 粒度 | 做法 |
|---|---|
| 全部回滚 | `git checkout -- .` + 删除两个新增的 `.kt` 文件（`WriteDiag.kt`、`AuthStore.kt`；`git status` 就能看到全部改动） |
| 只回滚某一项 | 按 §2 / §6.1 每行的「回滚」列操作，都是 1–3 处的定点还原 |
| 不改代码就能关掉的项 | C2：把 `MonitorEngine` 主循环的 `e.tick(allowProbeSkip = true)` 改回 `e.tick()`（唯一的代码级开关）；C9：设置里关掉「Tasker / Locale 接口」；C10：设置里关掉「自适应采样间隔」 |
| 真机回滚 | 同一把签名 key，可直接覆盖安装任一旧版 APK（`SharedPreferences` 不会被清）：`NetPilot-1.5.0-2026100504-{debug,release}.apk`、`NetPilot-1.4.0-2026100503-{debug,release}.apk`、`NetPilot-1.3.0-2026100502-{debug,release}.apk`、`NetPilot-1.2.0-2026100501-{debug,release}.apk`、`NetPilot-1.1.0-2026100500-{debug,release}.apk` |

## 附录 B：报告里每张表的复现命令

```bash
# 版本 / targetSdk / 原生库（发布前必看：核对 versionCode/versionName 与 build.gradle.kts 是否一致）
/opt/android-sdk/build-tools/36.0.0/aapt2 dump badging NetPilot-1.4.0-2026100503-release.apk | head -1

# 签名（注意：36.0.0 里没有 apksigner，用 35.0.0 的）
/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs NetPilot-1.4.0-2026100503-release.apk

# 16 KB 页对齐
/opt/android-sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 NetPilot-1.4.0-2026100503-release.apk

# Tasker 三个组件在打包后的清单里确实是 enabled=false（期望输出 3，见 §6.6）
/opt/android-sdk/build-tools/36.0.0/aapt2 dump xmltree --file AndroidManifest.xml \
  NetPilot-1.4.0-2026100503-release.apk | grep -c "enabled.*false"

# 来源 / 许可复核
python3 tools/check_provenance.py

# 设备侧（注意：dumpsys alarm / batterystats / deviceidle 被策略拦截，用不了）
adb shell dumpsys meminfo com.katiusu.netpilot      # 只接受包名，不接受 pid
adb shell ps -A | grep np_service
adb logcat -s NetPilot

# 构建（debug 与 release 必须分两次跑；--no-configuration-cache 的原因见 TESTING.md §14.3 第 3 条）
bash _build.sh :app:assembleDebug   --no-configuration-cache
bash _build.sh :app:assembleRelease --no-configuration-cache

# lint（必须手工放大 Metaspace：_build.sh 的 320m 会让 lintAnalyzeDebug 以 > Metaspace 失败）
./gradlew :app:lintDebug --console=plain \
  -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8" \
  -Dorg.gradle.configuration-cache.parallel=false
```
