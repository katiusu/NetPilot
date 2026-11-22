# NetPilot 2.0.2（25）省电方案

> 目标：在不削弱保活与降级判定的前提下，压低息屏静止态的唤醒次数、CPU 时间与显示功耗。
> 现状基线 = 2.0.1（versionCode 24）。本文只定方案，不改代码；实施时再提 versionCode 25 / versionName "2.0.2"。

---

## 一、已经做过的（不要重复优化）

这些在 2.0.0 / 2.0.1 里已经落地，不要再挖一遍：

- 息屏跳过 HTTP 探测：SignalReader.kt:233-239 的 skipProbe —— 屏幕关闭 + 未降级时，信号不强就直接不 ping，快照带 probeSkipped。
- 通知去重：MonitorService.kt:187 文本不变直接 return，只有变了才走 NotificationManager binder。
- 日志落盘节流：LogStore.kt:68 PERSIST_INTERVAL_MS = 30s，LogStore.kt:80 PERSIST_DEBUG_INTERVAL_MS = 300s，LogStore.kt:193 按「本轮是否有重要日志」二选一。
- 心跳闹钟息屏不用唤醒型：KeepAliveScheduler.kt:93-96 —— 亮屏 ELAPSED_REALTIME_WAKEUP、息屏 ELAPSED_REALTIME。
- 亮灭屏重排节流：ScreenStateGate.kt:42 MIN_RESCHEDULE_INTERVAL_MS = 30s，ScreenStateGate.kt:125 内不重排。
- 数据卡写制式节流：DataCardEngine.kt:43 60s、:49 5s 防抖、:52-53 恢复重试 60s 到 15min；InactiveSimLte.kt:34 5 分钟。
- 后台探测退避：AutoDowngradeEngine.kt:503 BACKGROUND_PROBE_BACKOFF_MS = 5min。

## 二、还剩的四个耗电源（按收益排序）

| # | 耗电源 | 位置 | 现状 |
|---|---|---|---|
| A | 息屏静止态仍按 60s 全量采样 | MonitorEngine.kt:101-146 | 自适应只缩短（:129-132），永不放大；凌晨 8h 约 480 轮 |
| B | 没有任何省电模式感知 | 全仓 grep isPowerSaveMode = 空 | 系统进省电模式时行为不变 |
| C | 息屏仍每轮读 modem / 制式 | SignalReader.tick、MtkAtChannel.kt:93 | 息屏没有轻量采样路径 |
| D | 息屏日志仍按 5 分钟 fsync | LogStore.kt:80 | 与 A 叠加，整夜持续写盘 |

## 三、措施

### P0-1 息屏静止态退避（收益最大，改动最小）

- 现状：MonitorEngine.kt:144 delay(intervalMs)，intervalMs 由 adaptiveIntervalMs 决定，只会变短。
- 改法：循环里加 stillStreak。判据 = 屏幕关闭 + 未降级 + 快照三元组（networkType / RSRP 档位 / ping 状态）与本轮前完全一致。连续 3 轮静止后间隔按 1.5 倍递增，封顶 1800s；任何一项变化、亮屏、进入 RECOVERING/ROLLBACK 立刻归零。
- 与自适应共存：近门限缩短优先 —— near 为真时静止退避作废，仍走原来的缩短逻辑。
- 代价：MonitorEngine.kt 约 25 行 + 一个可关的开关（默认开）。判定阈值不变；静止时读数本来就不会变，不牺牲准确性。
- 预期：夜间 480 轮降到约 30 轮；具体数字待量测。

### P0-2 省电模式感知

- 现状：全仓无 isPowerSaveMode 引用。
- 改法：进程内动态注册 ACTION_POWER_SAVE_MODE_CHANGED（Context.RECEIVER_NOT_EXPORTED），命中时：
    1. 动态背景锁 60fps（不关闭；BgEffectModifier.kt 的节流上限按省电状态取 刷新率 或 60），模糊走 isBlurEnabled=false；
    2. 监控间隔下限翻倍（技术下限 15s 改 30s，配置值乘 2，封顶 3600s）；
    3. 日志落盘改走 PERSIST_DEBUG_INTERVAL_MS 档。
- 代价：约 60 行 + 设置页一个「省电模式下自动降配」开关（默认开）。
- 预期：省电模式本来就是低电量场景，收益直接体现在剩余续航；待量测。

### P1-1 息屏轻量采样（跳过 modem / 制式读取）

- 现状：tick 每轮都读 networkType、通道制式。
- 改法：给 SignalReader 加 lightweight 参数，屏幕关闭 + 未降级 + 静止态时为真：只读 RSRP/RSRQ（降级判定唯一依赖）+ 缓存里的网络类型；通道制式与 channelLabel 不刷新，也不打开 MtkAtChannel。
- 兜底：RSRP 档位变化、亮屏、界面主动 sampleNow 一律立刻走全量。
- 代价：SignalReader 约 30 行，需回归「假满格在 5G/5G+ 上判定」不受影响（fakeFullBarOnNrOnly 依赖 rawType，静止态缓存值不变）。
- 风险：中等（判定路径），必须过 TESTING.md 的判定回归。

### P1-2 息屏日志更狠地节流

- 改法：LogStore.kt:80 的 300s 在息屏时改 900s；行环形缓冲从当前值降到 200 行（内存 + GC 双收益）。
- 代价：约 10 行。风险：出问题时息屏日志跨度变粗 —— 重要日志立刻落盘的行为不变。

### P2 低优先（记入清单，不一定进 2.0.2）

- 页内快采 FAST_SAMPLE_INTERVAL_MS 改为 min(5s, 配置间隔/4)，把 15s 配置下的 5s 快采也压下去。
- 息屏用 ConnectivityManager.NetworkCallback 做通断粗判，替代部分 ping。
- 心跳 15min 在连续 N 次重启失败时退避到 30min（KeepAliveScheduler.kt:38 / :54 已有 MAX_RESTART_DELAY_MS 骨架）。

## 四、不做的事

- 不加 WorkManager、不加新依赖（KeepAliveScheduler.kt:11 的既定约束）。
- 不用 setExact*（要 SCHEDULE_EXACT_ALARM，KeepAliveScheduler.kt:13 明确拒绝）。
- 不引入持有 PARTIAL_WAKE_LOCK —— 现在全仓零 wakelock，这是耗电口碑的底线。
- 不把监控间隔下限抬到 15s 以上作为默认（判定灵敏度是产品核心）。

## 五、验收（每个 P0 都要有数）

息屏 8 小时，同机同卡同一充电周期内对比 2.0.1 与 2.0.2：

    adb shell dumpsys batterystats --charged io.github.katiusu.netpilot
    adb shell dumpsys alarm | grep -i netpilot
    adb shell dumpsys power | grep -iE "wake locks|mWakefulness"
    adb shell dumpsys batterystats --charged | grep -A5 netpilot

判定标准（三条全过才算达标）：

1. Uid CPU 时间下降 20% 以上；
2. 息屏 wakeup 次数不增加（允许持平）；
3. 保活效果不退化：kill 服务后 15 分钟内拉起，成功率不低于 2.0.1。

## 六、版本与 changelog

- versionCode 25、versionName "2.0.2"（app/build.gradle.kts:47 / :66）。
- app/src/main/assets/changelog.md 顶部加一段 2.0.2：以两个井号加空格加版本号起头，每条一行、短横线加空格起头、不加编号、不用反引号（UpdateChecker.currentChangelog 按标题逐字匹配）。
- 拟定条目（实施后定稿）：
    - 息屏静止时自动拉长采样间隔，最长 30 分钟，亮屏或读数变化立即恢复
    - 新增省电模式感知：进入系统省电模式时动态背景锁 60 帧、关闭模糊、采样下限翻倍
    - 息屏采样不再读取通道制式，减少 modem 查询
    - 息屏日志落盘间隔放宽至 15 分钟

## 七、开工顺序

P0-1 与 P0-2（都是小改动，可同一次提交）→ 跑一次 8h 息屏量测 → 视数据决定 P1-1 是否进 2.0.2，否则推 2.0.3。

