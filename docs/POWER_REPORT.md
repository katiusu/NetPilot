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

## 附录 A：改动文件与回滚

**新增文件（2 个）**

```
app/src/main/java/com/katiusu/netpilot/core/update/UpdateChecker.kt
app/src/main/java/com/katiusu/netpilot/ui/component/UpdateDialog.kt
```

**修改文件（17 个）**

```
app/build.gradle.kts                                            C6
MainActivity.kt                                                 C1 / C7
TemplateApp.kt                                                  C5
core/NetPilot.kt                                                C2
core/keepalive/KeepAliveReceiver.kt                             C3
core/keepalive/KeepAliveScheduler.kt                            C3
core/keepalive/KeepAliveState.kt                                C3
core/monitor/AutoDowngradeEngine.kt                             C2
core/monitor/LogStore.kt                                        C4
core/monitor/MonitorEngine.kt                                   C2
core/monitor/MonitorModels.kt                                   C2
core/monitor/MonitorService.kt                                  C2 / C3 / C5
core/monitor/SignalReader.kt                                    C2
core/priv/ControlManager.kt                                     C5
core/priv/shizuku/ShizukuController.kt                          C5
ui/screen/about/AboutPage.kt                                    C7
ui/screen/monitor/MonitorPage.kt                                C2
res/values/strings_monitor.xml                                  C2
res/values-en/strings_monitor.xml                               C2
```

**回滚方式**

| 粒度 | 做法 |
|---|---|
| 全部回滚 | `git checkout -- .` + 删除两个新增的 `.kt` 文件（本次改动**全部未提交**，`git status` 即可看到） |
| 只回滚某一项 | 按 §2 每行的「回滚」列操作，都是 1–3 处的定点还原；其中 C2 有一键开关：把 `MonitorEngine` 主循环的 `e.tick(allowProbeSkip = true)` 改回 `e.tick()` |
| 真机回滚 | 用 1.2.0 覆盖安装前先导出配置；要退回 1.1.0 直接装回 `NetPilot-1.1.0-2026100500-{debug,release}.apk`（同一把签名 key，可覆盖安装，`SharedPreferences` 不会被清） |

## 附录 B：报告里每张表的复现命令

```bash
# 版本 / targetSdk / 原生库
/opt/android-sdk/build-tools/36.0.0/aapt2 dump badging NetPilot-1.2.0-2026100501-release.apk

# 签名（注意：36.0.0 里没有 apksigner，用 35.0.0 的）
/opt/android-sdk/build-tools/35.0.0/apksigner verify --print-certs NetPilot-1.2.0-2026100501-release.apk

# 16 KB 页对齐
/opt/android-sdk/build-tools/36.0.0/zipalign -c -P 16 -v 4 NetPilot-1.2.0-2026100501-release.apk

# 来源 / 许可复核
python3 tools/check_provenance.py

# 设备侧（注意：dumpsys alarm / batterystats / deviceidle 被策略拦截，用不了）
adb shell dumpsys meminfo com.katiusu.netpilot      # 只接受包名，不接受 pid
adb shell ps -A | grep np_service
adb logcat -s NetPilot

# 构建（debug 与 release 必须分两次跑）
bash _build.sh :app:assembleDebug
bash _build.sh :app:assembleRelease
```
