# NetPilot 2.0.2

版本号：versionCode 25 / versionName 2.0.2

本版只做两件事：**省电**与**流畅度**。没有新增功能入口，不改变任何既有行为默认值。

## 省电

- **息屏静止退避**：屏幕关闭、未处于降级态、且连续三轮读数（制式 / 是否看到 NR 小区 / RSRP / 探测往返）完全相同时，采样间隔从第 3 轮起按 1.5 倍逐轮放大，封顶 30 分钟（1800 秒）。屏幕点亮、读数变化、或读数靠近判定门限时，stillStreak 立即清零、恢复正常节奏。
- **系统省电模式感知**（core/PowerSaveWatcher.kt）：监听 ACTION_POWER_SAVE_MODE_CHANGED，只落一个进程内 volatile 标志；三处消费方各自读取：
  - 动态背景动画锁 60 帧（不关闭）；
  - 毛玻璃的 LayerBackdrop 不再创建（逐帧离屏重采样是界面里最贵的开销）；
  - 采样间隔：配置值 x2，技术下限从 15 秒抬到 30 秒，自适应缩短仍生效但不低于 30 秒。

## 流畅度

- 动态背景动画原先**无条件**按 60 fps 节流（BgEffectModifier.startAnimation 里的 minDeltaNanos）。在 120 Hz 屏上，withFrameNanos 每 8.3 ms 回来一次，节流把它变成 8/16 ms 交替，看上去就是一顿一顿。现在默认不节流、跟随屏幕刷新率，只在省电模式下锁 60 fps。

## 未纳入本版

以下来自 docs/PLAN_SMOOTHNESS.md，本版未做（风险或收益待验证）：滚动期模糊预算（radius 25 降到 16）、背景图层隔离、drawBehind 里 Brush 的每帧构造优化、监控页重组收敛、baseline profile。

## 已知限制

- 界面已经打开时再切到省电模式：动画帧率与采样间隔立即生效，但毛玻璃要等该页面重建（重进或换页）才消失，因为 backdrop 在组合期内只求值一次。
- 息屏静止退避期间最长 30 分钟不采样；屏幕点亮后由监控页的 5 秒快采立即补一次读数。

## 验证方式

- 构建：GitHub Actions（.github/workflows/build.yml，push main 触发），产物 netpilot-release。
- 本机无法跑 dumpsys gfxinfo（HyperOS 对第三方包报 Failure while dumping the app），帧率基线要靠 perfetto 或 debug 构建的 JankStats，本版未做量化对比。
