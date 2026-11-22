# NetPilot 2.0.0 (23) 实施计划 — UI 照搬事例 + 玻璃菜单 + 更新卡 + 预测性返回 + 繁体中文

> 状态：待批准。只计划，不含代码。批准后按第 7 节分工执行，全部完成后才走 CI release 构建（不本地构建）。
> 依据：用户 m00745、m00867 与 6 张截图（m00740–m00744、m00866）。

## 1. 目标终态

| 项 | 现状 | 2.0.0 改动 |
|---|---|---|
| 设置页界面卡 | 主题模式 + 悬浮导航栏开关 + 条件液态玻璃开关 + 背景模糊 | 主题模式 + **底栏样式**下拉（柔光玻璃 / 液态玻璃，默认柔光玻璃）+ **侧栏样式**下拉（不使用 / 顶部折叠 / 左侧展开）+ 背景模糊 + **预测性返回**开关 |
| 日志页溢出菜单 | WindowIconDropdownMenu + 两组 DropdownEntry（图 6：白底圆角卡、无图标） | 照搬 miuix 最新 example：**GlassIconDropdownMenu**（玻璃菜单）+ 图标锚点 + TopLayer；玻璃不可用时回退现有窗口菜单 |
| 更新卡 | 只有自动检查更新 | 自动检查更新 + **检查更新** + **查看当前版本更新日志**（均指向 katiusu/NetPilot） |
| About 页 | 有检查更新 / 当前版本 xxx 一行（图 5 灰底） | **删除该行**，连同 state 与 UpdateDialog |
| 返回手势 | 无开关 | 可开关的预测性返回动画（照搬 miuix example 的 ApplicationInfo 反射） |
| 语言 | 默认 / 简体 / English | 默认 / 简体中文 / **繁體中文** / English |
| 版本 | 2.0.0-rc.3 (22) | **2.0.0 (23)** |

## 2. 硬约束（用户原话）

1. 新增代码**必须出于事例**（/sdcard/Project/MiuixGui = katiusu/MiuixExample），不许自己另写一套。
2. 事例的一整块搬过来，不重新设计。
3. 预测性返回与**日志菜单**从**最新的 miuix 仓库**抄（compose-miuix-ui/miuix main 的 example/）。
4. 全部改完再 CI release 构建；不本地构建；可静态分析。
5. 可以分工。

## 3. 逐文件改动清单

### W1 底栏 / 侧栏下拉（删两个开关）

**AppSettings.kt**
- 照抄事例 AppSettings.kt:9-49 两个枚举：BottomBarStyle(val value: Int) { HyperOS(1), IOS(2) }（fromValue 兜底 HyperOS）、RailStyle(val value: Int) { None(0), Top(1), Left(2) }（val Default = Left、val isEnabled get() = this != None、fromValue）。
- **枚举名保留 HyperOS/IOS**（事例原样），只把**展示文案**改成「柔光玻璃」「液态玻璃」，默认 HyperOS = 柔光玻璃。这样既满足改文案，又不改事例代码。
- data class 字段：删 isFloatingNavbar、isLiquidGlass，加 bottomBarStyle: BottomBarStyle = BottomBarStyle.HyperOS、railStyle: RailStyle = RailStyle.Default。
- 键：新增 'bottom_bar_style'、'rail_style'（getInt/setInt）；旧键 'floating_navbar'、'liquid_glass' 只用于迁移。
- **旧值迁移**：无 bottom_bar_style 时读旧布尔 —— liquid_glass==true → IOS，否则 HyperOS；railStyle 缺省 Left；写完 remove 两个旧键。

**MainActivity.kt**
- :216-217 两个 state → bottomBarStyle / railStyle。
- :460 删 navBarMode，:463 改为照抄事例 :302 的 val useRail = isWideScreen && railStyle.isEnabled。
- :465-469 换成照抄事例 :304-318：expandSidebar、rememberGlassNavigationRailState(initialExpanded)、LaunchedEffect 同步、sidebarWidth。
- :565-610 NavigationRail 整块换成事例 :512-521 的 GlassNavigationRail(items, selectedIndex, onSelect, backdrop, state = sidebarState)。pager inset 保留现有 PaddingValues(start = railWidth)，railWidth 用 animateDpAsState(sidebarState.progress * sidebarWidth)（见 R1）。
- :531-543 SettingsPageView 传参：删 4 个旧参数，加 bottomBarStyle / onBottomBarStyleChange / railStyle / onRailStyleChange / predictiveBack 回调。
- :651-721 BottomNavigationBar(mode: Int) → BottomNavigationBar(style: BottomBarStyle)，函数体改成照抄事例的 when (style) { HyperOS -> GlassNavigationBar; IOS -> IosLiquidGlassNavigationBar }。

**ui/util/BlurUtils.kt**：删 :290 shouldExpandNavigationRail()（不再被引用）。

### W2 更新卡（检查更新 + 当前版本更新日志）

**core/update/UpdateChecker.kt**
- 加 fun currentChangelog(context, version): String，**照抄**事例 UpdateChecker.kt:64-73（读 assets/changelog.md，正则 ^##[ \t]+v?(\S+)[ \t]*\r?$，命中返回该段，未命中空串）。
- REPO 已是 katiusu/NetPilot（:28），不改 —— 这就是「链接到我们的仓库」。

**新增 app/src/main/assets/changelog.md**：格式照事例，标题 ## 2.0.0；内容 = 本版改动 + rc.1–rc.3 简条汇总。工程当前无 assets 目录，新建即可。

**SettingsPage.kt 更新卡（:558-577）**：照抄事例 SettingsPage.kt:242-273 整块 —— SwitchPreference(自动检查更新) + AppArrowPreference(检查更新，summary 随 isCheckingUpdate) + AppArrowPreference(当前更新日志 → showChangelog) + if (showChangelog) { CurrentChangelogDialog(appVersion, notes) { showChangelog = false } }。state 与 checkUpdate() 照抄 AboutPage.kt:200-232；appVersion 用 UpdateChecker.currentVersion；notes 用 currentChangelog。末尾补一条 AppArrowPreference 直跳 GITHUB_RELEASES_URL（复用 AboutPage 常量与 openExternalUrl）。

### W3 删除 About 页检查更新行（图 5）

AboutPage.kt：删 :416-423 的 ArrowPreference(title = check_update) 整块；连带删 :200 updateScope、:202 updateInfo、:203 isCheckingUpdate、:205-221 checkUpdate()、:226-233 UpdateDialog 调用块及相关 import；保留 :405-415 三条（仓库 / Release / 反馈）。

### W4 预测性返回手势（可设置）

来源：compose-miuix-ui/miuix main，example/android/.../MainActivity.kt:27-89、146-160（已核实）：
- private var predictiveBackEnabled = true，savedInstanceState 读写 KEY_PREDICTIVE_BACK；
- 反射 ApplicationInfo.setEnableOnBackInvokedCallback(Boolean)：getDeclaredMethod → isAccessible = true → invoke，整段 runCatching，@SuppressLint("PrivateApi")；
- 开关 UI 照 ThemeSettingsPage.kt:426-436。

改动：AppSettings 加 enablePredictiveBack = true（键 'predictive_back'，JSON 同步）；MainActivity 加字段 + onCreate 调一次 + onSaveInstanceState + 回调重设 + 照抄 setEnableOnBackInvokedCallback（companion object）；**调用前先 TelephonyReflection.ensureHiddenApiExempted()**（core/priv/TelephonyReflection.kt:86，现有机制）；SettingsPage 界面卡加 SwitchPreference。manifest 不用改（targetSdk 36 默认启用）。

### W5 繁体中文

- LocaleHelper：加 ZH_TW("zh-TW")，顺序 SYSTEM, ZH_CN, ZH_TW, EN。
- SettingsPage 语言卡（:305-331）：languageNames / languageValues 各加一项。
- 新增 res/values-zh-rTW/strings.xml：values/strings.xml 全量 216 条繁体（台湾用语：設定/網路/檔案/資訊/訊號/檢查/存取/連線…）。
- 补 values-en 新键；三份 strings 的 name 集合与占位符必须一致（脚本校验）。

### W6 日志页溢出菜单改为 miuix 玻璃菜单（图 6）

来源（已核实全部在 miuix 0.9.5-beta02 内，GlassIconDropdownMenu 自 2026-11-07 存在）：
- example/shared/src/commonMain/kotlin/MainPage.kt:93-96（状态与 anchor）、:224-265（按钮锚点）、:367-388（TopLayer 里的菜单）；
- example/shared/src/commonMain/kotlin/utils/TopLayer.kt（TopLayer / TopLayerHost / LocalTopLayer，整文件搬）；
- miuix-glass 的 GlassIconDropdownMenu(show, onDismissRequest, anchor, anchorContent, entries, backdrop, collapseOnSelection)。

**改动**：
1. 新增 ui/util/TopLayer.kt —— **整文件照抄** example 的 TopLayer.kt（Apache-2.0，同作者），包名改本工程。
2. MainActivity：`val topLayerState = remember { TopLayerState() }`，`CompositionLocalProvider(LocalTopLayer provides topLayerState)` 包住内容，最外层 Box 末尾放 `TopLayerHost(state = topLayerState, modifier = Modifier.fillMaxSize())`（在 Scaffold/BottomBar 之后，保证菜单压在底栏之上）。
3. LogPage.kt:305-322 菜单按钮：照抄 MainPage.kt:224-265 —— `GlassIconButton(onClick = { showMoreMenu = !showMoreMenu }, modifier = Modifier.glassPopupAnchor(moreAnchor, cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2, floating = true)) { Icon(MiuixIcons.Glass.More, ..., Modifier.size(28.dp).glassPopupAnchorContent(moreAnchor)) }`；`val moreAnchor = rememberGlassPopupAnchor()`、`var showMoreMenu by remember { mutableStateOf(false) }`。
4. LogPage 页面根 Box 之后加：`TopLayer { GlassIconDropdownMenu(show = showMoreMenu, onDismissRequest = { showMoreMenu = false }, anchor = moreAnchor, anchorContent = { Icon(MiuixIcons.Glass.More, null, Modifier.size(28.dp)) }, entries = menuEntries, backdrop = backdrop, collapseOnSelection = false) }`（collapseOnSelection = false：排序与复制/分享/导出/清空是两组，不因选中一次就收起）。
5. **回退**：`val glassMenu = blurActive && backdrop != null`（对应 example 的 isRuntimeShaderSupported 判定）；为 false 时保留现有 `WindowIconDropdownMenu` 分支与 `MiuixIcons.More` 图标，菜单条目共用同一份 menuEntries。
6. menuEntries 结构照 example 保持不变（两组 DropdownEntry：排序 ASC/DESC 带 selected；复制/分享/导出/清空），只把文字与 onClick 用现有字符串与函数。

### W7 版本与构建

- app/build.gradle.kts:43 versionCode = 23、:56 versionName = "2.0.0"；新增 docs/CHANGELOG_2.0.0.md。
- 静态检查通过后 commit + push origin main → GitHub Actions（release 签名 secrets 已配）。
- CI 出包后 gh run download → apksigner verify --print-certs 核对 SHA-256 仍为 34:10:08:75:B4:5D:7C:4D:C9:92:80:30:B3:32:9B:54:90:86:9A:23:61:55:F9:CE:08:B1:DC:70:C7:43:4C:4C。

## 4. 关键决策

**D1 删掉旧普通贴底底栏**。事例只有悬浮两种样式。旧默认 isFloatingNavbar=false 的用户升级后变成柔光玻璃胶囊（视觉变化），changelog 写明。

**D2 底栏文案「柔光玻璃 / 液态玻璃」，默认柔光玻璃**。枚举值仍用事例的 HyperOS(1) / IOS(2)：柔光玻璃 = GlassNavigationBar，液态玻璃 = IosLiquidGlassNavigationBar。

**D3 侧栏默认 Left**（事例默认，宽屏进来是左侧展开面板）。

**D4 侧栏换 GlassNavigationRail**：RailStyle 的顶部折叠 / 左侧展开只有它能表达。

**D5 日志菜单用玻璃版**：图 6 要的就是重写，miuix 最新 example 的玻璃菜单是现成实现；玻璃不可用时保留窗口菜单回退，功能不丢。

**D6 不升 miuix 版本**：现用 0.9.5-beta02 已包含所需全部 API（GlassIconDropdownMenu / GlassNavigationRail / glassPopupAnchor / TopLayer 所需的都是 example 层代码）。

**D7 繁中机械转换 + 人工校对**。

## 5. 风险与对策

| 编号 | 风险 | 对策 |
|---|---|---|
| R1 | 换 GlassNavigationRail 后 pager inset 与 5 页 extraBottomPadding 错位 | 保持 pagerContent(navBarHeight, pagerPadding) 签名不动，只把 railWidth 换成 animateDpAsState(progress * sidebarWidth)；不搬 pageProgress 逐页方案 |
| R2 | 旧设置迁移写错会重置用户设置 | 迁移单独成函数，分支表自检：无新键 + 旧 liquid_glass=true → IOS；两旧键都缺 → HyperOS |
| R3 | 预测性返回反射在 Android 14+ 被非 SDK 限制拦 | 先 ensureHiddenApiExempted 再 runCatching；失败只记 DEBUG |
| R4 | SettingsPage.kt 多人同改 | 该文件只由一人在阶段 B 一次改完 |
| R5 | 繁中键集合漏项 | 脚本比对三份 name 集合与数量、占位符 |
| R6 | 不本地构建，编译错只能靠 CI | 静态检查清单逐项过；CI 失败读 annotations 全段 |
| R7 | 玻璃菜单 anchor 在 AppTopAppBar 的 actions 里，可能被裁切 | 照搬 TopLayer 机制（菜单提到全窗层绘制）；anchor 用 Modifier.glassPopupAnchor 标在按钮外层 |
| R8 | LogPage 有 isBlurEnabled=false 的情况 | glassMenu = blurActive && backdrop != null，否则走 WindowIconDropdownMenu 回退 |

## 6. 静态检查清单（CI 之前）

1. grep isFloatingNavbar|isLiquidGlass|navBarMode|floating_navbar|liquid_glass → 仅 AppSettings 迁移分支可留旧键名。
2. 三份 strings.xml name 集合与数量比对，占位符一致。
3. 改动文件括号平衡 + 人工复核 when 分支、data class 逗号、import 顺序。
4. 未使用 import / state 扫描（AboutPage 删行后必查；LogPage 保留回退分支时 MiuixIcons.More import 仍需）。
5. git diff --stat：预期约 11 改 + 4 新增（TopLayer.kt、assets/changelog.md、values-zh-rTW/strings.xml、docs/CHANGELOG_2.0.0.md）。
6. versionName/versionCode 已改且 CHANGELOG 已写。

## 7. 分工与顺序

**阶段 0（我）**：三份 strings.xml 统一补齐新键（底栏 2 + 侧栏 3 + 预测返回 2 + 语言 1 + 玻璃菜单用的 log_action_more 已有）。

**阶段 A（并行 3 人，写域不重叠）**
- **T1 nav-settings**：AppSettings.kt、MainActivity.kt（导航 + TopLayer host）、ui/util/BlurUtils.kt、新增 ui/util/TopLayer.kt。
- **T2 about-update**：AboutPage.kt、core/update/UpdateChecker.kt、新增 assets/changelog.md、docs/CHANGELOG_2.0.0.md。
- **T3 i18n**：LocaleHelper.kt、values-zh-rTW/strings.xml、values-en 补键。

**阶段 B（并行 2 人，文件互不重叠，依赖 T1 的类型）**
- **T4 settings-page**：SettingsPage.kt（两个下拉、删两开关、预测返回开关、更新卡三项、语言项、签名）。
- **T5 log-menu**：LogPage.kt（玻璃菜单 + 回退分支）。

**阶段 C（我）**：versionCode/versionName、静态检查、commit + push、CI release 构建 + 验签。