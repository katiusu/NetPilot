# NetPilot 流畅度与帧率方案（2.0.2 并行项）

> 现象：界面"有点卡"。但"卡"至少是三种不同的病，改法不同，必须先量再动刀。
> 本文只定方案，不改代码。与 docs/PLAN_2.0.2.md（省电）统筹：两份方案里「动态背景帧率」是同一个开关的两面，见第六节。

---

## 一、先把"卡"拆成三种

| 类型 | 表现 | 主要嫌疑 |
|---|---|---|
| T1 动画帧率不足 | 动效本身在 120Hz 屏上像 60Hz，节奏不匀 | BgEffectModifier.kt:148 的手动 60fps 节流 |
| T2 滚动掉帧 | 上下滑动时一顿一顿，跟手滞后 | 模糊 drawBackdrop（BlurUtils.kt:87 radius 25）+ 每帧分配 |
| T3 页面切换/首屏掉帧 | 切页、首次进页面白一下 | 无 baseline profile、大面积重组 |

三种混在一起时，用户统称"卡"。所以第一步不是改代码，是量。

## 二、量测（用现在已装的 2.0.1 包就能做，不用重编）

    # 1) 重置，然后手动滚动监控页 10 秒，再读数
    adb shell dumpsys gfxinfo io.github.katiusu.netpilot reset
    adb shell dumpsys gfxinfo io.github.katiusu.netpilot

  看两个数：Janky frames 百分比、90th/95th/99th percentile 帧时间。
  判定：120Hz 屏 p95 超过 8.3ms 就有肉眼感；60Hz 屏 p95 超过 16ms。

    # 2) 逐帧数据（要更细时）
    adb shell dumpsys gfxinfo io.github.katiusu.netpilot framestats

    # 3) 确认是不是模糊拖的：设置里关掉模糊，重复上面两步对比
    #    同一台机、同一页面、同样滚动距离。

    # 4) 要归因到具体线程用 Perfetto
    #    trace 里看 RenderThread 的 DrawFrame 是否被 GPU 占满、UI 线程是否有 doFrame 超时

对比基线记在 docs 下方「量测记录」，2.0.2 验收要拿它对。

## 三、已定位的嫌疑（按把握程度排序）

### S1 背景动画被手动锁在 60fps（把握最高）

- 位置：BgEffectModifier.kt:144-158，minDeltaNanos = 1_000_000_000L / 60L，靠 now - lastEmit 跳帧。
- 问题：屏幕 120Hz 时每帧都来回调，但只在"攒够 16.6ms"时 invalidateDraw，实际节奏是 跳-画-跳-画，帧间距 8/8/16ms 交替，肉眼就是"不顺"。这是"看起来卡"最直接的来源。
- 附带问题：BgEffectBackground.kt:70-86 每帧 invalidateDraw 会让整个背景层重绘，包含 fillMaxSize 的 Spacer。

### S2 模糊是全屏逐帧重采样（滚动掉帧的头号嫌疑，需量测确认）

- 位置：BlurUtils.kt:86-88 TopBarBlurConfig.BlurRadius = 25f；BlurUtils.kt:194-208 uniformBlurBackdrop 走 drawBackdrop + blur(radius)。
- 问题：miuix 的 LayerBackdrop 在内容变化时重采样 backdrop，滚动＝内容每帧都在变，于是每帧一次 radius 25 的模糊。而模糊默认开（AppSettings.kt:62 isBlurEnabled = true）。
- 待确认：关模糊前后 gfxinfo 的对比差值，就是它的成本。

### S3 绘制期分配

- 位置：BlurUtils.kt:172-188 在 drawBehind 里构造 Brush.verticalGradient（每帧一个新对象），BgEffectModifier.kt:168-173 每帧 updateResolution/updateBoundIfNeeded/updateColors/updatePointsAnim（painter 内部有缓存，但仍在绘制期做分支与函数调用）。
- 问题：滚动期间每帧分配 → GC → 卡顿尖峰。painter 侧的 FloatArray 已经复用（BgEffectPainter.kt:25-28），这块是好的，不要动。

### S4 页面级重组

- 位置：MonitorPage.kt:88-118 同时 collect 6 个 StateFlow，并有 5 秒快采循环，每轮更新 autoEnabled 等 state。
- 问题：监控页开着时，每 5 秒整棵页面重组一次；若此时在滚动，就会撞在滚动帧上。

### S5 没有 baseline profile

- 现状：grep profileinstaller / baseline-prof.txt 均为空，app/build.gradle.kts 无相关配置。
- 影响：首屏与冷启动靠 JIT，切页首次进入的掉帧主要来自这里。收益确定但要生成 profile（要跑构建 + macrobenchmark），成本最高。

## 四、措施

### P0-1 背景动画跟随屏幕刷新率（改 3 行，收益最直接）

- 删掉手动 60fps 节流：withFrameNanos 每帧推进 animTime，不再靠 now - lastEmit 跳帧。
- 反向降级只留给省电：省电模式/低电量时锁 30fps（见第六节），普通模式不再人为设上限。
- 代价：BgEffectModifier.kt 约 5 行。风险：GPU 占用上升，需与 S2 的模糊预算一起调。

### P0-2 背景绘制隔离成独立图层

- 用 drawWithCache + graphicsLayer 把背景固定成一层，只在该层 invalidate，不触发上层内容重绘。
- 代价：BgEffectBackground.kt / BgEffectModifier.kt 约 15 行。

### P0-3 模糊预算（滚动期降级）

- 两档做法，择一或叠加：
    1. 滚动进行中把 radius 从 25 降到 16（视觉差异小，GPU 成本明显下降），滚动停止 200ms 后恢复；
    2. 设置页加「高画质模糊」开关，默认开；低端机（如 < 8 核或系统报告低内存）自动关。
- 触发源：已有的 TopAppBar scroll behavior state 与 pageScrollModifiers 里的 scrollGate 都能判断"正在滚动"，不需要新监听。
- 代价：约 40 行。风险：观感变化需肉眼确认。

### P1-1 绘制期零分配

- Brush 提升为 remember 或在 drawWithCache 里缓存；drawBehind 内禁止构造对象。
- 代价：约 20 行。

### P1-2 重组收敛

- MonitorPage：状态文字用 derivedStateOf 或独立 TextStyle 的 Composable 包一层，让 5 秒一次的变化只重组那一小块；collectAsState 改 collectAsStateWithLifecycle（页面不在前台不收集）。
- 采样与 UI 解耦：采样结果只更新 engine 的 StateFlow，UI 不参与调度。
- 代价：约 30 行。

### P1-3 baseline profile

- 加 androidx.profileinstaller，生成 baseline-prof.txt（首屏、监控页、设置页的关键路径）。
- 代价：高（要跑一次 release 构建 + 至少一轮 macrobenchmark），建议排在 P0 全部验证之后。

### P2-1 帧率可观测性

- 只在 debug 构建接 JankStats，把每帧 jank 原因写进 LogStore；release 不引入（避免省电方案里的额外开销）。
- 收益：以后再有"卡"不用猜。

## 五、验收

1. gfxinfo Janky frames 从 2.0.1 基线下降到 5% 以内；
2. 滚动期 p95 帧时间：120Hz 屏不超过 8.3ms，60Hz 屏不超过 16ms；
3. 连续滚动 10 秒，不出现连续 2 帧以上掉帧；
4. 动态背景动画在 120Hz 屏上帧间距均匀（Perfetto 里看间隔是否恒定为 8.3ms）。

## 六、与省电方案的冲突裁决

- 帧率与耗电是同一条轴的兩端。裁决：**默认跟刷新率、省电模式降 30fps**。
- 具体落点：省电方案 P0-2 的省电模式感知里，把"关闭动态背景"细化为"动态背景锁 30fps 或直接停"，两者都符合用户预期。
- 不采用"默认把背景锁 60fps 省电"——用户明确要顺，省电留给省电模式。

## 七、开工顺序

先量（第二节，不改代码）→ 按量测结果排序改 → P0-1 → P0-3 → P0-2 → P1-1 → P1-2 → 复测 → 视情况排 P1-3。

量测记录（待填）：2.0.1 基线 Janky frames = ?，p95 = ?，关模糊后 = ?

