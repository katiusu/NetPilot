# 2.0.0-rc.3 计划（重排版）

> 基线：`2.0.0-rc.2` / `versionCode 21` / 包名 `io.github.katiusu.netpilot` / 分支 `main` / HEAD `76ef33c` / 122 个 .kt。
> 本文替代旧的 rc.1 计划。上一版计划里的 **UI 迁移部分已由 rc.1–rc.2 实际完成，本版不再涉及 UI**；`docs/UI_MIGRATION_MIUIXEXAMPLE.md` 一并作废（保留仅供查证）。

## 0 为什么重排

rc.1 的 24 条 bug 清单从未进入实施：那段时间实际做的是**包名更换 + UI 全量迁移**（rc.1）和**关于页闪退/弹层宿主/连续曲率**（rc.2）。旧的 `docs/CHANGELOG_2.0.0-rc.1.md` 已被改写成 rc.1 的真实发布说明（5 条 UI 条目），原 364 行的 bug 计划只存在于历史对话里。

重排后要解决三件事：

1. **行号全部作废** —— 包名和目录整体变过，旧计划里的 `FakeSignalDetector.kt:69` 这类定位已无法用。本文一律用「文件 + 函数名」定位。
2. **剔除已随重写失效的条目** —— UI 层被整套换掉，原本针对旧 `Scaffold` 写法的两条 P2 自然消失。
3. **补 UI 迁移带来的新风险** —— 全局搜索、弹层宿主、玻璃底栏是新引入的面，需要单列回归项。

## 1 复核结论（按 rc.2 实测）

逐条 grep + 读函数体核对，结论：**P0 仍然成立，P1 十四条里十三条仍存在，P2 有两条随 UI 重写失效**。

### 1.1 P0（1 条，全部仍存在）

| # | 问题 | 新定位 | 状态 |
|---|---|---|---|
| 1 | `TaskerCommandReceiver` 导出且零调用方校验，任何 App 可发命令广播改网络制式 | `core/tasker/TaskerCommandReceiver.kt`；`AndroidManifest.xml` 该 receiver 的 `exported=true` | 仍在。全工程 grep 无 `getCallingUid` / `checkCallingPermission` / `callingPackage` |

同族对照：`core/tasker/LocalePluginReceiver.kt` 有较严的入口，二者策略不一致本身就是线索。

### 1.2 P1（14 条）

| # | 问题 | 新定位 | 状态 |
|---|---|---|---|
| 2 | 降级判定未排除 `probeSkipped`，息屏跳过探测可能被当成 Ping 失败 | `core/monitor/FakeSignalDetector.kt` `judge()` 的 `pingMs == null` 分支 | 仍在：文件内无任何 `probeSkipped` 引用 |
| 3 | 恢复写入失败也清空 `active`/`savedMode`，降级态永久丢失 | `core/monitor/AutoDowngradeEngine.kt` `finish(current, ok, event)` | 仍在：`copy(active=false, savedMode=-1, …)` 与 `ok` 无关，`ok` 只影响 `lastEvent` 文案 |
| 4 | 关闭自动降级后仍被保活链拉回前台服务 | `core/keepalive/KeepAliveReceiver.kt:79` 直接 `startForegroundService(MonitorService)`；`MonitorService.onCreate` 不复核开关 | 仍在 |
| 5 | `MonitorEngine.sampleOnce()` 与循环内 `tick()` 可并发采样，状态机无锁 | `core/monitor/MonitorEngine.kt` `startLoop()` / `sampleOnce()` | 仍在：`sampleOnce` 只有 `withContext(IO)`，无 Mutex |
| 6 | 开机接收器收厂商 `QUICKBOOT_POWERON` 广播 | `core/BootReceiver.kt:26-27` | **需复核**：现已与 `BOOT_COMPLETED` 同分支处理并一路往下执行（装内核 + 重排闹钟），若某些机型快速开机时会重复触发，会与 `MY_PACKAGE_REPLACED` 叠加。优先级降为 P2 |
| 7 | `ConfigState.reload()` 不通知 observers，导入配置后 TaskerGate 等系统侧状态不同步 | `prefs/ConfigState.kt` `reload()` | 仍在：只 `values.clear()` + 回填，未遍历 `observers` |
| 8 | 亮灭屏都无条件 `KeepAliveScheduler.schedule()`，频繁亮灭屏可让心跳饿死 | `core/keepalive/ScreenStateGate.kt` 的 `receiver.onReceive` | 仍在 |
| 9 | 同值 `EXTRA_PAGE` 走 `onNewIntent` 不触发跳页 | `MainActivity.kt:166-167` `if (page >= 0) requestedPage = page` | 仍在：`requestedPage` 是 state，赋同值不触发重组 |
| 10 | `PrivilegedCli` 的 `settings` 兜底只写 slot0 键，副卡写错键仍报成功 | `core/priv/PrivilegedCli.kt` 约 130 行 `settings put` 兜底分支 | 仍在（`slots` / `setslot` 的反射路径正常，兜底路径未按 subId 选键） |
| 11 | `readText()` 读到 EOF，5 s 超时形同死代码 | `core/priv/PrivilegedCli.kt:247`、`:269` | 仍在：两处都是 `.bufferedReader().use { it.readText() }` |
| 12 | `homeSubId` 缺省回落到「当前默认卡」，导致离开 Wi-Fi 后回切恒不执行 | `core/datacard/DataCardStore.kt` `homeSubId()` | 仍在：`getInt(KEY_HOME_SUB, SimReader.defaultDataSubId())` |
| 13 | 先 `remove` 后 `setMode`，还原失败即永久丢记录 | `core/datacard/DataCardEngine.kt:255` | 仍在 |
| 14 | `lastApplyAtMs` 全局单值 + 每规则冷却，多规则互相压制 | `core/datacard/DataCardEngine.kt:55/151/172` | 仍在：`@Volatile private var lastApplyAtMs = 0L` |
| 15 | `modeMemo` 三元组锁外读改写 | `core/priv/ControlManager.kt:67-73`、`:226-232` | 仍在 |

### 1.3 P2（9 条）

| # | 问题 | 新定位 | 状态 |
|---|---|---|---|
| 16 | `LogStore` 跨线程写 Compose 容器 | `core/monitor/LogStore.kt` | **需复核**：UI 重写后日志页数据源已换，需确认是否仍直接暴露 Compose state |
| 17 | 通知文本先写缓存，前台服务首帧可能显示旧值 | `MonitorService` 通知构建 | **已随重写失效**（通知走新格式化） |
| 18 | `onCreate` 与 `onNewIntent` 重复读 `EXTRA_PAGE` | `MainActivity.kt:166/173` | 仍在，与第 9 条同修 |
| 19 | `ConfigBackup` 的 `policies`/`rules` 语义不对称（导出含、导入丢） | `prefs/ConfigBackup.kt` | 仍在 |
| 20 | QS 磁贴回调跑在 Default 线程做阻塞 IO | `core/qs/*TileService.kt` | 仍在 |
| 21 | `AuthStore` 列名单复数拼接 | `core/priv/AuthStore.kt` | 仍在 |
| 22 | `SubscriptionSwitcher.buildArgs` 参数错位 | `core/priv/SubscriptionSwitcher.kt` | 仍在 |
| 23 | `TelephonyReflection` 只 catch `Exception`，且豁免失败结果被 `lazy` 永久缓存 | `core/priv/TelephonyReflection.kt` | 仍在 |
| 24 | Shizuku 同步 binder 无超时，`acquire()` 在锁内做 probe | `core/priv/shizuku/ShizukuController.kt`、`core/priv/ControlManager.kt` `acquire()` | 仍在 |

### 1.4 本版新增回归项（UI 迁移引入，不属于 bug）

| # | 项 |
|---|---|
| R1 | 全局搜索：底栏搜索钮 → 浮层 → 结果跳转，在 5 个 tab 下都不丢焦点、不误触 Pager 翻页 |
| R2 | 弹层宿主：所有 `AppDialog` 盖在玻璃底栏/侧栏之上，不被裁切 |
| R3 | 玻璃顶栏在滚动折叠、下拉回弹、二次进入页面三种时机都有正确模糊源 |
| R4 | 连续曲率圆角在浅色/深色/跟随系统三档主题下不发灰、不出现接缝 |
| R5 | 宽屏（平板）下 rail 折叠 ↔ 展开切换时页面内容随 inset 让位 |

## 2 本版范围与批次

**范围**：第 1–15 条全修 + 第 16/18/19/20/21/22/23/24 条修 + 第 6/16 条复核后处理 + 基带第 25 条（只读探测）。**不含任何 UI 改造**。

| 批次 | 内容 | 判据 |
|---|---|---|
| B1 安全 | 第 1 条：`TaskerCommandReceiver` 加调用方校验；顺带对齐 `LocalePluginReceiver` 的入口策略 | 第三方 App 发命令广播被拒，Tasker/Locale 正常 |
| B2 状态机 | 第 2、3、5、9 条 | 息屏跳过探测不再触发降级；写回失败不再清态；并发 `sampleOnce` 与循环 tick 串行；同值跳页生效 |
| B3 生命周期 | 第 4、7、8 条 | 关自动降级后服务不再被拉回；导入配置后 TaskerGate 同步；连续亮灭屏 50 次心跳仍准时 |
| B4 特权与双卡 | 第 10、11、12、13、14、15、22、23、24 条 | 副卡写入走对键；5 s 超时真实生效；Wi-Fi 回切真的执行；写回失败保留记录；多规则各自冷却；`modeMemo` 加锁 |
| B5 杂项 | 第 18、19、20、21 条 + 第 6/16 条复核结论 | 导入导出字段对称；磁贴不阻塞主线程 |
| B6 基带 | 第 25 条：`BasebandDetector` 只读探测（属性 → 特征文件 → 能力探测三档） | 设置页兼容性卡片显示「基带：高通/联发科 · 通道 · 置信度」；开发机上必须打印真实探测日志 |
| B7 回归 | 第 1.4 节 R1–R5 | 见下 |

第 26 条（`PhoneExAtBackend` / `TtyAtBackend` 真实写入）与第 27 条（`MediatekRatMapper` 回读校验）**不在本版**：它们依赖 B6 在 MTK 真机上的探测结果，开发机是高通 K40，本版拿不到验证样本。第 28 条（频段/SA-NSA/双卡 slot）继续挂起。

## 3 基带第 25 条的范围界定

只做「检测」，不做写入，能力集直接返回空：

- 新增 `core/radio/BasebandVendor.kt`（枚举 + `Confidence`）、`BasebandDetector.kt`（三点判据：`ro.board.platform` 等属性 → `/dev/radio/atci1` 等特征文件 → `phoneEx` binder / `AT+ERAT?` 回包）、`BasebandProfile.kt`（`vendor/confidence/evidence/atNodes/capabilities`）。
- `NetPilot.install()` 里初始化，结果落 Preference 键 `np_baseband_profile`，只冷启动 + 手动「重新检测」重跑；探测走 `Dispatchers.IO` + 3 s 总超时。
- 设置页兼容性卡片加一行「基带：… · 通道：… · 已确认/可能」。
- 探测原文（属性值、命中的节点路径、`phoneEx` 是否存在、`AT+ERAT?` 原始回包）全部进日志页，并提供导出。
- **`capabilities` 本版恒为空集**，UI 明确显示「检测到联发科基带，但当前版本不支持切换」，不伪造成功。

## 4 验收

1. `grep -rn 'getCallingUid\|checkCallingPermission' app/src/main/java` 至少命中 `TaskerCommandReceiver`。
2. `AutoDowngradeEngine.finish()` 内 `active=false` 只在 `ok` 为真时执行。
3. `bash _build.sh :app:assembleRelease` 输出 `APK_CHECK: … manifest=True arsc=True -> OK`。
4. 真机（Redmi K40，高通）走一遍：设置页基带行显示「高通 · 已确认」；触发一次降级与恢复，日志里 `finish` 路径与恢复计数正确。
5. R1–R5 五项 UI 回归全过（只验证不改造）。
6. 未验证项如实写进发布说明：B6 的 MTK 判据在高通机上无法证实，只验证了「不误判为 MTK」这条负向结论。

## 5 明确不做

- 不做任何 UI 调整、样式微调、主题改动。
- 不实施基带第 26–28 条。
- 不动版本号策略：`versionCode` 由构建号递增，`versionName` 待定（rc.3 或直接 2.0.0）。

---

## 6 评审修订（v2，2026-11-21）

两份独立评审（代码事实核对 / 工程风险）推翻了本文第 1 节的若干判定，以下**以本节为准**，第 1 节保留作为过程记录。

### 6.1 判定被推翻的条目

| # | 原判定 | 修订后 |
|---|---|---|
| 16 | 需复核 | **成立**：`LogStore.kt:84` `mutableStateListOf`，`:111` 原样暴露，`:141-148` 在调用线程（含 IO）直接写 |
| 17 | 已随重写失效 | **仍在**：`MonitorService.kt:181-184` 先写 `lastNotificationText` 再 `runCatching{ notify }`，失败被吞后同文本永不重发 |
| 21 | AuthStore 列名单复数拼接 | **不成立，删除**。`content query --projection` 的正确语法就是冒号分隔 |
| 22 | buildArgs 参数错位 | **不成立**。`:230-241` 按下标对齐无误；真实问题是 `:80-83` 把非 Boolean 返回一律当成功（见 C3） |
| 23 | 只 catch Exception | **部分成立**：多数已是 `Throwable`，仅 `:201` 一处 `Exception`；保留「lazy 永久缓存失败」 |
| 24 | 同步 binder 无超时 | **部分成立**：`BIND_TIMEOUT_MS`/`CALL_TIMEOUT_MS` 已有两层 `withTimeoutOrNull`；保留「`acquire()` 在 mutex 内做 probe」 |
| 4 | KeepAliveReceiver 无门 | **部分成立**：`KeepAliveReceiver.kt:55/61/67` 有三道门；真正无门的是 `MonitorService.onCreate:47/51` 与 `onStartCommand:76`（见 C1） |
| 10 | 只写 slot0 键 | **部分成立**：先写 `preferred_network_mode` 且成功即 return（与 subId 无关），`preferred_network_mode1` 只在主卡键失败且卡数 >1 时才写 |
| 19 | 导出含导入丢 | **部分成立**：字段已对称；不对称在空表语义（policies 空数组被跳过 `:164`，rules 空数组会清空 `:184`） |
| 20 | 回调阻塞主线程 | **部分成立**：回调立即返回；问题是把阻塞 IO 放进 `Dispatchers.Default`（CPU 池） |
| 1 | 装完即暴露 | **前提修正**：manifest `enabled="false"`，由 `TaskerGate` 在用户开启接口时 `setComponentEnabledSetting` 打开；风险窗口是开启之后。`LocaleFireReceiver` 同样无校验，不能当作「较严入口」的对照 |
| 6 | 需复核 | **低风险**：四个 action 走同一分支但全链路幂等；不纳入修复，仅补注释 |

### 6.2 新增条目

| # | 问题 | 位置 |
|---|---|---|
| C1 | `onCreate` 无条件 `setMonitorWanted(true)`，把用户「停止」翻回运行态，`ACTION_STOP` 只生效到下次被拉起 | `MonitorService.kt:51` |
| C2 | Tasker 回执走隐式广播且带信号快照/通道名，任何 App 可被动收到 | `TaskerCommandReceiver.kt:251-264` |
| C3 | 反射调用把「非 Boolean 返回」当成功，void 方法静默失败也报切换成功 | `SubscriptionSwitcher.kt:80-83` |

### 6.3 方案修订

- **#1 鉴权改为 token，不用 `getCallingUid`**：广播派发路径上 `Binder.getCallingUid()` 返回自身 uid，写成校验等于假安全。开启 Tasker 接口时生成随机 token 写入 ConfigState，`SET_*`/`TOGGLE_*`/`LOCK_LTE`/`SWITCH_DATA_SIM` 必须带 `EXTRA_TOKEN` 且匹配；只读类放行。`getSentFromUid()`/`getSentFromPackage()`（API 34）仅用于日志归属。
- **#1 出口同步修**：回执 `setPackage` 到已知 Tasker 包；未匹配时不发隐式广播，或只带 `ok`/`message`，**不带** snapshot/channelLabel。
- **#3 判据反向**：不是「ok 才清 active」，而是「写回失败保留 `savedMode` + `restorePending`，退避重试 3 次后置 ERROR」。
- **#9 用自增 ticket**：`requestedPage` 同值不触发，改为 `requestedPageTicket++` 驱动 `LaunchedEffect`。
- **#12 需迁移策略**：`homeSubId` 未设置时返回 -1 哨兵，首次运行时把「当前默认卡」落盘为 home，避免升级后自动跳到用户没选过的卡。
- **#13 不只调顺序**：`savedModeBeforeWifiDowngrade` 必须落 prefs（含 subId + 时间戳），`remove` 移到写成功之后，失败保留并退避重试。
- **#14 改 per-(ruleId, subId) 冷却**，全局单值只留 5 s 级防抖。
- **#25 基带探测砍掉 AT**：只做「属性读 + `stat`/`lstat`（不 open）+ `getService` 查询」，绝不调用 `phoneEx` 任何 `invoke*`，全部 `try/finally`，日志显式声明「本次未做 AT 探测」。失败或低置信度**不落盘**或带 `expiresAt`。补显式 QCOM 判据（`ro.hardware` 前缀 qcom、`/vendor/lib64/libqmi_*`、`ro.vendor.qti.*`）。
- **UI 例外**：§5「不做 UI 调整」开一个白名单例外 —— 只允许改 `ui/screen/settings/SettingsPage.kt` 的兼容性卡片一行，以及日志页导出按钮；其余 UI 不动。

### 6.4 实施批次（修订版，合并依赖）

| 批 | 内容 | 说明 |
|---|---|---|
| B1 | #1 + C2 | 入口与出口同批，token 方案 + 回执收敛 |
| B2 | #2 #3 #5 #16 #17 + C1 | monitor 一个包内闭环 |
| B3 | #4 #7 #8 | 生命周期与配置通知 |
| B4 | #12 #13 #14 | 双卡三件套必须同批（同一文件同一状态） |
| B5 | #10 #11 #15 #23 #24 + C3 | 特权层 |
| B6 | #9 + #18 #19 #20 | 导航与杂项，`#9`/`#18` 同处必须同批 |
| B7 | #25 | 基带只读探测，唯一允许碰设置页的一批 |
| B8 | #6 | 仅补注释，不改逻辑 |
| B9 | 回归 + 发布说明 | 只做静态检查与 CI 验证，无本地构建 |

> 用户决策（2026-11-21）：**一步到位完成全部计划，不分段提交**；**不做本地构建**，全部完成后先静态检查，再走 GitHub Actions 构建验证。因此 B1–B8 作为一次提交推送，B9 只保留静态检查与 CI 结果核对。
