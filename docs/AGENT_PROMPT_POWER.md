# NetPilot 后台耗电与资源占用优化 —— Agent 提示词

> **用法**：把下面「提示词正文」整段复制给一个能读写本仓库的 AI 编码 agent（DSH 会话 / 子代理 / 其他编码助手）。
> 正文里所有文件:行号都是**已核实**的，直接照着查即可；`[方括号]` 是需要执行者自己实测或与用户确认后再填的内容。
> 想省事可以直接用**附录 B 的极简版**。

---

# 提示词正文（从下一行开始复制）

## 0. 你的身份与目标

你是 NetPilot（Android / Kotlin / Jetpack Compose）的性能工程 agent。

**目标**：在**不改变用户可见行为、不改变降级判定语义、不改变默认值**的前提下，降低 NetPilot 的后台耗电与资源占用（CPU / 内存 / 网络 / 唤醒次数）。

**交付物**（缺一不可）：
1. 代码改动（每处都有「为什么」注释）；
2. 一份前后量化对比报告 `docs/POWER_REPORT.md`（模板见 §11）；
3. 可安装的 APK + 用户自测步骤清单（本环境禁止 agent 自行安装到手机）；
4. 文档同步（`README.md` / `README_EN.md` / `docs/TESTING.md` / `docs/PROVENANCE.md`）。

**前提**：先测量，再改。没有前后数据的「更省电」不算结论。

## 1. 项目事实（已核实，不要再猜）

- 仓库：`/sdcard/Project/NetPilot`，包名 `com.katiusu.netpilot`；Kotlin + Compose + **Miuix 0.9.4**（`top.yukonga.miuix.kmp`）；minSdk / targetSdk 34，compileSdk 37；当前 `versionName = "1.1.0"` / `versionCode = 2026100500`（`app/build.gradle.kts:29,32`）。
- 源码根：`app/src/main/java/com/katiusu/netpilot/`（约 104 个 .kt/.aidl 文件）；后台相关集中在 `core/monitor/`、`core/keepalive/`、`core/priv/`、`core/qs/`、`core/tasker/`。
- 构建：`bash _build.sh :app:assembleDebug`（或 `:app:assembleRelease`）。
  - **debug 与 release 必须分两次单独构建**，同一次调用会 `java.lang.OutOfMemoryError: Metaspace`；
  - Gradle 原始输出在 `/tmp/np_build.log`；脚本自身的 `=== EXIT=` 与 `APK_CHECK: … manifest=True arsc=True -> OK` 标记在 `/tmp/np_run.log`（**轮询看这个文件**）；
  - `_build.sh` 不接受带空格的参数（`-Dorg.gradle.jvmargs='-Xmx… -XX:…'` 会被拆开报 `Unknown command-line option '-X'`）；
  - release 签名走 `release.keystore` + `keystore.properties`（均 gitignored），证书 SHA-256 `34100875b45d7c4dc9928030b3329b5490869a236155f9ce08b1dc70c7434c4c`（与 1.0.1 / 1.1.0 同一把 key）。
- `/sdcard` 是 **sdcardfs**：`write` / `edit` 文件工具会报 `EINVAL: 无法原子发布文件`。**所有文件改动必须走 `bash` + `python3`/heredoc + `os.replace`**。
- 设备能力限制（若你运行在 DSH 容器里）：禁止 `pm install`、禁止 `dumpsys` 未知参数（会 `[POLICY_BLOCKED]`）、禁止块设备/`settings put` 之外的系统改动；`dumpsys meminfo` **只接受包名、不接受 pid**。命令被拦截时换个形式或让用户导出，**不要反复重试同一条**。
- git 身份 `katiusu <315837822+katiusu@users.noreply.github.com>`；token 在 `/root/.gh_token`。**未经用户明确要求，不要 commit / push / 改版本号 / 发 Release。**

## 2. 硬约束（违反即返工）

1. **不加新依赖**。工程刻意不用 WorkManager，见 `core/keepalive/KeepAliveScheduler.kt:11`（「只用系统 `AlarmManager`，不引 WorkManager（本工程不加依赖）」）。确有必要引入依赖 → 先让用户批准。
2. **不改判定语义**。`core/monitor/FakeSignalDetector.kt` 的两条规则（假满格 / 信号过差）与阈值方向（严格比较）是行为契约。任何「跳过某步测量」的优化，**必须先写出真值表证明判定结果不变**（见 §7.1）。
3. **不改默认值**（`core/monitor/MonitorSettings.kt` 的 `DEFAULT_*`、`MonitorModels.kt:105-150` 的 `DowngradeThresholds` 默认）。当前默认：冷却 60 s（下限 30 s）、恢复 2 轮、无网回退 2 轮、采样间隔 60 s、ping 阈值 200 ms、ping 超时 2500 ms、强信号阈值 `rsrpThreshold = -85`（`MonitorModels.kt:101`，严格大于才算强）、SINR 阈值 `sinrThreshold = 0`（`:103`）、弱信号门槛 `weakRsrpThreshold = -110`、`downgradeOnPingFail = false`（`:153`）。需要改 → 先问用户。
4. **不改 UI 行为 / 文案**，除非改动本身要求；Miuix API 只能按已用过的写法（参考 `miuixguiexample` 模板的 modifier 顺序），**不可臆造 API**。
5. **不引入唤醒锁、不申请精确闹钟权限、不新增前台服务通知**。
6. **不碰许可与 clean-room 结论**（`LICENSE` / `docs/PROVENANCE.md` / `tools/check_provenance.py` 的结论），那是上一轮的交付结论。
7. **不提交 `.apk` 与 `app/build/`**。
8. 每处改动都要有**前后量化证据 + 验证命令**，否则不算完成。

## 3. 当前后台执行面清单（排查起点，全部已核实）

| # | 机制 | 位置 | 现状参数 | 代价来源 |
|---|---|---|---|---|
| 1 | 监控主循环 | `core/monitor/MonitorEngine.kt:77-107`（`while (isActive)`） | `delay(interval)`，`MonitorSettings.DEFAULT_INTERVAL = 60`（`MonitorSettings.kt:70`），下限 `coerceIn(15, 3600)`（`MonitorEngine.kt:105`） | 每轮一次完整采样 + 网络探测 |
| 2 | 前台服务 | `core/monitor/MonitorService.kt:45-47`（`startForeground`，`foregroundServiceType="specialUse"`）、`:68` `stopSelf`、`:97` 总开关 | 常驻通知 | 常驻进程 + 通知 |
| 3 | 每轮采样 | `core/monitor/AutoDowngradeEngine.kt:44-58` `tick()` = `SignalReader.read()` + `FakeSignalDetector.judge()` + `applyTransition()` | 每 60 s | 见 4/5/6 |
| 4 | **HTTP 探测** | `core/monitor/SignalReader.kt:272-289` `httpProbe()`；目标列表 `:302-307`（bing → cn.bing → baidu → 华为校验）；`MAX_PING_ATTEMPTS = 4`（`:310`）；总预算 `TOTAL_PING_BUDGET_FACTOR = 3`（`:319`） | 每次 `Connection: close`（`:282`）+ `disconnect()`（`:287`）→ **每轮新建 TCP + DNS**；单轮最坏 3×2500 ms | **射频尾时间**（每次探测后 modem 高功率态 10–20 s）→ 60 s 周期近乎长期高功率 |
| 5 | 特权读取 | `AutoDowngradeEngine.kt:254-258` `readModeOrMinusOne()` → `ControlManager.acquire()`；`core/priv/ControlManager.kt:38-41` `cachedChannel` 缓存、`:76-79` 掉线重探 | 只在「可能进入降级」的分支调用 | Root/Shizuku 通道一次调用固定成本 |
| 6 | Shizuku 绑定轮询 | `core/priv/shizuku/ShizukuController.kt:157-170`（`delay(POLL_INTERVAL_MS)`、`BIND_TIMEOUT_MS` 上界）；`pruneStaleServices()` 一次性（`pruneOnce` CAS） | 仅绑定期 | 轮询 + 用户服务进程内存 |
| 7 | **保活心跳** | `core/keepalive/KeepAliveScheduler.kt:34` `HEARTBEAT_INTERVAL_MS = 15 min`；`:59` `setInexactRepeating(ELAPSED_REALTIME_WAKEUP, …)` | **~96 次唤醒/天** | Doze 会批处理但仍唤醒 CPU；且服务活着时收到心跳**什么都不做** |
| 8 | 重启闹钟 | `KeepAliveScheduler.kt:42` `DEFAULT_RESTART_DELAY_MS = 10 s`；`:84` `setAndAllowWhileIdle`（进 Doze 也放行） | 每次「被划掉/服务销毁」排一个 | 服务反复崩溃 → 10 s 一次重启风暴 |
| 9 | 保活接收 | `core/keepalive/KeepAliveReceiver.kt:67` `startForegroundService(MonitorService)` | 心跳/重启时 | 拉起进程 |
| 10 | 开机 | `core/BootReceiver.kt:37-45` `ServicesGate.enabled()` + `KeepAliveScheduler.schedule()` | 开机/应用更新 | 一次性 |
| 11 | **日志落盘** | `core/monitor/LogStore.kt:40` `MAX_ENTRIES = 400`、`:50` `PERSIST_ENTRIES = 120`、`:52` `PERSIST_INTERVAL_MS = 4_000`、`:55` `MAX_MESSAGE_CHARS = 2_000`、`:141-142` 整体 JSON `apply()` | 每 4 s 重写一遍 120 条日志 | 序列化 + SharedPreferences 写放大 |
| 12 | 界面快采 | `ui/screen/monitor/MonitorPage.kt:64` `FAST_SAMPLE_INTERVAL_MS = 5_000`、`:125-135` `while (isActive)` | 仅监控页可见时（**要核实**） | 与引擎 60 s 轮**叠加**的重复采样 |
| 13 | 背景动画 | `ui/component/effect/BgEffectModifier.kt:145-159` 每帧 `withFrameNanos`（≤60 fps `invalidateDraw`）；`ui/component/effect/BgEffectBackground.kt:53-68` 颜色插值循环 | 屏幕亮时 | GPU/CPU |
| 14 | QS 磁贴 | `core/qs/NetworkModeTileService.kt:55`、`core/qs/DowngradeTileService.kt:34` `onStartListening()` | SystemUI 拉时 | 需核实 `onCreate` 里有没有常驻工作 |
| 15 | 唤醒锁 | **无**（全仓只有 `core/PermissionGuide.kt:198` 读 `PowerManager` 判断电池优化白名单） | — | 保持「无唤醒锁」 |
| 16 | WorkManager / JobScheduler | **无**（刻意） | — | 若引入需用户批准 |

## 4. 耗电机制与优先级判断

优化前先用这套机制解释「为什么这里耗电」，再决定动哪个：

1. **蜂窝射频尾时间**：每次 HTTP 探测把 modem 拉进高功率态，通常 10–20 s 才回落。60 s 一轮 ⇒ 射频长期不完全空闲。**探测频率与探测次数是头号候选**。
2. **定时唤醒**：`ELAPSED_REALTIME_WAKEUP` 每 15 min 唤醒一次（~96 次/天），且服务存活时该唤醒的唯一效果是「确认服务还活着」。
3. **SharedPreferences 整体重写**：4 s 一次把 120 条日志序列化再整串写回，写放大 + fsync。
4. **每帧 invalidate 的动画**：屏幕亮时 GPU/CPU 持续工作。
5. **特权通道调用**：binder / `app_process` 每次调用都有固定成本；Shizuku 用户服务本身是第二个进程（历史上有 `:np_service` 孤儿进程泄漏，已由 `StaleProcessPruner` 处理，见 `docs/TESTING.md`）。

## 5. 测量：先基线，后对比

### 5.1 设备侧（先试，被拦就换形式）

```bash
# 应用电池数据（用包名；被拦就改让用户导出）
adb shell dumpsys batterystats --charged com.katiusu.netpilot > /sdcard/np_batt_before.txt
# 闹钟与唤醒（数一下 netpilot 有几个待发闹钟、间隔多少）
adb shell dumpsys alarm | grep -i netpilot
# Doze / 电源
adb shell dumpsys power | grep -iE "mWakefulness|wakelock"
adb shell dumpsys deviceidle | head -40
# 内存（只接受包名）
adb shell dumpsys meminfo com.katiusu.netpilot
# 应用自身日志（界面「日志」页同源；debug 包可 run-as）
adb shell run-as com.katiusu.netpilot ls shared_prefs
adb logcat -s NetPilot
```

- 若在 DSH 里：优先用 `/app/*` 通道（`T=$(cat /root/.dsh/.bridge_token); curl -s "http://127.0.0.1:3090/app/help?token=$T"` 查端点），设备 shell 走 `adb-shell` 命令；**被 `[POLICY_BLOCKED]` 就停下换方案**。
- 你自己**不能装包**（禁止 `pm install`）：基线/对比的整机耗电数据需要**用户**配合（系统「电池用量」页 / AccuBattery）。

### 5.2 代码级（必做，不依赖手机）

- 数清**每轮 tick 的确定性开销**：HTTP 请求数（最多 4 次，最坏 3×timeout）、特权调用次数、序列化字节数。
- 数清**每天固定唤醒源**：心跳 96 次 + 重启闹钟触发条件。
- 统计所有 `while (isActive)` / `delay(` / `Handler(` 的位置，逐条确认「不可见/不需要时是否为零开销」。

### 5.3 基线记录与对比口径

- 基线写入 `docs/POWER_REPORT.md` 的「基线」表：机型、系统版本、网络（Wi-Fi/5G）、屏幕状态、时长、口径（怎么量的）。
- **对比必须同口径**：同一台设备、同一网络、屏幕关、静止、≥30 min（推荐 1 h），前后各一轮。记录：电池百分比、应用 CPU 时间、wakeup 次数、HTTP 请求数、PSS。

## 6. 优化候选（分两级；先做第一级）

### 6.1 一级：**可证明零行为变化**（优先）

1. **探测连接复用**：`SignalReader.kt:282` 的 `Connection: close` + `:287` `disconnect()` 改成 keep-alive 复用 / 去掉强制关闭，同一个目标在 TTL 内缓存 DNS 结果。要求：超时与总预算语义（`:319`）不变，探测结果（RTT 读数）不受影响。
2. **探测前短路（有证明要求）**：`FakeSignalDetector.judge()` 只有在 `isStrong`（`MonitorModels.kt:49`：`rsrp != null && rsrp > rsrpThreshold`）为真时才看 `pingMs`；`downgradeOnPingFail` 默认 `false`（`MonitorModels.kt` 的 KDoc：「默认关掉以保持行为等价」）。
   ⇒ 因此 **「屏幕关闭 且 非强信号（`rsrp == null` 或 `rsrp <= rsrpThreshold`）」时跳过 HTTP 探测，判定结果不可能改变**（`fake` 只能由弱信号分支给出，而该分支只读 RSRP）。落地前必须：
   - 从代码里逐分支核对该真值表并写进报告（含 Wi-Fi 早退分支 `FakeSignalDetector.kt:41-50`）；
   - 处理「界面显示 Ping」的差异：屏幕关时无 UI 观察者，可不探测；屏幕亮时保持原行为（或显示「已跳过探测」）；
   - 不改 `pingThresholdMs` / `pingTimeoutMs` 默认值。
3. **日志落盘节流**：仅在内容变化时写（dirty flag），或「攒够 N 条 / M 秒且已变化」才落盘；序列化移出主线程；必要时改追加写文件而不是整体重写。要求：界面「日志」页与导出（`LogPage.kt:69` `MAX_EXPORT_CHARS = 200_000`）行为不变，保留条数上限语义。
4. **保活心跳去重**：收到心跳且 `MonitorService` 正在运行 → **不再重复排**心跳（只在服务不在时排下一个），把每天 96 次「无效果唤醒」变成「服务掉线才唤醒」。要求：`KeepAliveScheduler.isScheduled()`（`:118`）仍能正确反映状态（设置页状态行依赖它），「划掉后自动拉回」行为不变。
5. **重启闹钟退避**：`KeepAliveScheduler.kt:78-94` 加指数退避 + 上限（例如 10 s → 30 s → 60 s → … → 15 min），避免服务反复崩溃时的重启风暴。
6. **UI 生命周期核对**：确认 `MonitorPage.kt:125` 与两个 `BgEffect*` 的 `while (isActive)` 都随「页面离开 / 组合销毁」取消；若监控页在后台仍快采，则改为仅在可见时。前台服务通知只在状态**变化**时 `notify`。
7. **特权通道成本**：确认 `ControlManager` 的掉线检测不产生每轮重探；Shizuku 侧不用时释放用户服务（注意别重蹈 `:np_service` 孤儿进程）。
8. **QS 磁贴**：核对 `onCreate` / `onStartListening` 里没有常驻轮询或重复绑定。

### 6.2 二级：**可能改变用户可见行为 → 必须先问用户**

- 采样间隔自适应（屏幕关时放宽到 N 分钟，或仅在「疑似弱网」时提高频率）；
- 探测目标/协议替换（ICMP 不可用；改 DNS 探测或系统 `connectivitycheck`）；
- 弱化/关闭保活心跳（取决于用户是否需要「划掉后自动拉回」）；
- 背景动画默认关闭或限帧；
- 引入 WorkManager / JobScheduler（需依赖批准）。

每一个二级项都要写清：改了什么、为什么省电、**对用户可见行为的影响**、副作用、回滚方式，然后**等用户确认**。

## 7. 工作流（每一步都这么走）

1. **一个主题一次改动**（禁止把多个主题混在一起，否则没法归因）。
2. 落盘走 `bash` + `python3` + `os.replace`；改前 `read` 目标文件，锚点断言 `count == 1`。
3. 构建：`:app:assembleDebug` 与 `:app:assembleRelease` **分开各跑一次**，确认 `EXIT=0` + `APK_CHECK … OK`。
4. 自检：涉及来源/许可时跑 `python3 tools/check_provenance.py`；结构改动跑架构自检（root = `app/src/main/java/com/katiusu/netpilot`），确认无新增循环依赖、无新增超大模块。
5. **记录证据**：改动前后数据 + 复现命令（原始命令，不要只写结论）。
6. 同步文档：`README.md`（新增/追加小节）、`README_EN.md`、`docs/TESTING.md`（新增「怎么验证这次优化」）、必要时 `docs/PROVENANCE.md`。
7. 汇总进 `docs/POWER_REPORT.md`，并把 APK 路径 + 自测步骤交给用户。

## 8. 验收标准（缺一不可）

- **功能回归**：逐项自测并记录结果 —— 自动降级触发/恢复、冷却期、无网回退、日志页与导出、数据卡规则、Tasker 广播命令与 Locale 插件、两个 QS 磁贴、开机自启、划掉后拉回、设置页保活状态行。
- **量化**：待机（屏幕关、静止、同网络）1 h 电池消耗**下降 ≥ [目标，建议 ≥20%]**；应用 wakeup 次数下降；HTTP 请求数下降（现状 ≈ 60 次/h 起）；PSS 不升高。给出前后对照表。
- **代码**：构建 `EXIT=0` + `APK_CHECK OK`；无新增依赖；每处改动有「为什么」注释；架构自检无新增环。
- **文档**：四份文档同步；`docs/POWER_REPORT.md` 完整。
- **交付**：可安装 APK（debug 签名即可，除非用户要求 release）+ 自测步骤 + 回滚说明。

## 9. 风险与禁忌

- **不要把「省电」做成「漏检」**：判定漏检比多耗一点电糟糕得多。任何放宽测量的改动都要有真值表证明 + 回归自测。
- 不许用「减少日志」「降低日志级别」来伪造省电结论（日志是用户排障入口，见 `docs/TESTING.md`）。
- 不引入唤醒锁 / 精确闹钟权限 / 第二个前台服务通知。
- 不动许可与 clean-room 结论（`docs/PROVENANCE.md`）。
- 未获用户明确要求：不 commit、不 push、不改版本号、不发 Release。
- 不靠注释或文档代替实测；不因为「看起来更省」就合并改动。

## 10. 报告模板（`docs/POWER_REPORT.md`）

```
# NetPilot 后台耗电优化报告（<版本>）

## 1. 基线
| 项 | 值 | 怎么量的 |
|---|---|---|
| 待机 1 h 耗电 | [ ]% | [命令/界面] |
| wakeup 次数 | [ ] | dumpsys alarm / batterystats |
| HTTP 请求数/小时 | [ ] | 代码推算 + 日志 |
| PSS | [ ] MB | dumpsys meminfo <包名> |
| 每轮 tick 确定性开销 | [ ] | 代码统计 |

## 2. 改动清单
| # | 改动（文件:行） | 级别 | 省电原理 | 前后数据 | 验证方式 | 回滚 |
|---|---|---|---|---|---|---|
| 1 | [ ] | 零行为变化 | [ ] | [ ] | [ ] | [ ] |

## 3. 正确性证明（针对跳过测量的改动）
[真值表 + 逐分支核对]

## 4. 回归自测结果
| 功能 | 结果 | 证据 |
|---|---|---|

## 5. 结论与建议下一步
[还差什么、二级候选哪些值得做、需要用户确认什么]
```

## 附录 A：并行分工（如果你用 Agent Teams）

写范围必须互不重叠，Lead 负责最终核对与合并：

- **侦察 agent（只读）**：产出后台执行面清单核对 + 基线数据 + 真值表核对，不改代码。
- **探测优化 agent**：只改 `core/monitor/SignalReader.kt` + `core/monitor/MonitorEngine.kt`。
- **保活/日志 agent**：只改 `core/keepalive/*` + `core/monitor/LogStore.kt`。
- **UI/动画 agent**：只改 `ui/`。
- **验证 agent（只读）**：独立重跑构建、重算基线、复核真值表证明与回归清单。

## 附录 B：极简版提示词（想省事用这个）

> 你是 NetPilot（`/sdcard/Project/NetPilot`，Kotlin/Compose，包名 `com.katiusu.netpilot`）的性能 agent。目标：**不改判定语义、不改默认值、不加依赖**的前提下降低后台耗电。先读 `docs/AGENT_PROMPT_POWER.md`（含已核实的后台执行面清单、硬约束、测量方法、优化候选与验收标准），按它执行：先建立基线 → 只做「可证明零行为变化」的优化（探测连接复用、非强信号+息屏时跳过 HTTP 探测、日志落盘节流、心跳去重、重启退避、UI 生命周期核对）→ 一主题一改动 → 构建 `bash _build.sh :app:assembleDebug`（debug/release 分开跑，看 `/tmp/np_run.log` 的 `=== EXIT=` 与 `APK_CHECK`）→ 每处都留前后数据与复现命令 → 汇总到 `docs/POWER_REPORT.md` + 可安装 APK + 用户自测步骤。**需要改变用户可见行为的优化一律先问我**；未经我同意不要 commit / push / 改版本号 / 发 Release。文件改动必须用 bash + python3（`/sdcard` 是 sdcardfs，write/edit 工具会 EINVAL）。

# 提示词正文结束
