<div align="center">

# NetPilot

**5G 假满格？信号过差？让它自动退回 4G。**

双卡各管各的 · Wi-Fi 规则库 · 后台保活 · Tasker 接口 · Root 优先 / Shizuku 兜底

![Android](https://img.shields.io/badge/Android-14%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-Miuix%200.9.4-4285F4)
![License](https://img.shields.io/badge/License-Apache--2.0-blue)

[English](README_EN.md) · [测试指南](docs/TESTING.md) · [代码来源追溯](docs/PROVENANCE.md) · [Tasker 接口](docs/TASKER.md) · [快捷磁贴](docs/QS_TILE.md)

</div>

---

## 这是什么

同一个位置，5G 图标明明满格，网速却像断网——手动切回 4G 立刻能用。这是「假满格」：**信号强度够，但质量不行**（SINR 掉到负数、Ping 超时）。

NetPilot 把这个判断和切换自动化：

1. 定期采样 RSRP / SINR / Ping；
2. 命中「假满格」或「信号过差」→ 把当前默认数据卡的制式降回 4G；
3. 冷却期结束后连续若干轮恢复正常 → 自动切回 5G；
4. 写制式需要特权：**优先 Root**（`su` + `app_process` 调隐藏 API），没 Root 就用 **Shizuku** 兜底，两条路都走不通时退回 `settings put global preferred_network_mode[1]`。

除此之外它还是个完整的双卡网络管家：手动制式切换、快捷设置磁贴、每张卡独立策略、连上指定 Wi-Fi 自动换默认数据卡、运行日志、Tasker/Locale 自动化接口。

| | |
|---|---|
| 包名 | `com.katiusu.netpilot` |
| 版本 | **1.5.4**（`versionName = "1.5.4"`，`versionCode = 2026100700`） |
| 系统要求 | Android 14+（minSdk 34 / targetSdk 36） |
| 界面 | Jetpack Compose + [Miuix](https://github.com/YuKongA/Miuix) 0.9.4 |
| 语言 | 简体中文 / English |
| 许可证 | [Apache-2.0](LICENSE) |

> **安装**：从 [Releases](../../releases) 下载 APK 直接安装。发布包用项目自有 release key 签名；`debug` 构建是 Android Debug 签名，两者签名不同、不能互相覆盖安装（换签名需先卸载）。

---

## 功能

### 1. 网络质量自动降级（核心）

5G 图标满格但实际跑不动时（RSRP 够强、SINR 掉到负数 / Ping 超时），**或者信号过差时**，自动把制式降回 4G，冷却结束后再尝试恢复 5G。

「启用网络质量降级」总开关**默认开启**（装完即生效）。两条规则**互相独立**，判定标准**全部可在「功能」页调整**，监控页的「判定标准（当前生效值）」会把生效值实时拼出来显示（改一个数立刻跟着变，绝不写死）：

```
规则一「假满格」（服务「网络质量自动降级」）：
  ① RSRP ≥ 强信号阈值（默认 -85 dBm）      ← 信号「看起来」很强
  ② 且满足以下任意一条：
       Ping > Ping 阈值（默认 200 ms）
       SINR < SINR 阈值（默认 0 dB）
       Ping 完全失败（独立开关，默认关）
  → 需要降级

规则二「信号过差」（独立开关，默认开）：
  RSRP 读得到、且 < 弱信号阈值（默认 -110 dBm）
  → 需要降级（只看强度，与 Ping、SINR 无关）
```

> 两个 RSRP 阈值的语义**不一样**，别混：
> **强信号阈值**（`np_fake5g_rsrp`，默认 `-85`）=「RSRP 够高才算假满格」的门槛；
> **弱信号阈值**（`np_fake5g_weak_rsrp`，默认 `-110`）=「差到这个地步就直接降级」的独立门槛。
> 若把弱阈值调到比强阈值还高（逻辑上矛盾），生效值会自动收敛为「强阈值 − 1」，监控页会显示收敛后的值并给出提示（用户存的值不动）。

状态机：

- 降级后进入**冷却期**（默认 60 s，可调，下限 30 s），期间不重复降级
- 冷却结束后累计 **2 轮**（`recoveryCount`）判定为正常 → 恢复到原制式
- 降级后连续 **2 轮**（`noNetRollbackCount`）完全无网 → 立即回滚，并**退出自动模式**
- 检测间隔默认 **60 s**（可调，下限 15 s）

相对参考实现的两处**有意改进**（代码注释里已写明理由）：

1. **状态全持久化 + 启动自愈**：进程被杀重启后，若发现「上次降级未恢复」，无条件把制式写回完整模式并解除自动模式。参考脚本会把 `preferred_network_mode` 永久卡在 9 上出不来。
2. **无网回滚后直接退出自动模式**（参考脚本仍保留 `FAKE_5G_ACTIVE=1`）。

Ping 走 **HTTP 首字节时间**，默认目标是**必应的 `http://www.bing.com/`**（国内可直连、响应稳定，且它返回固定的 30x 跳转、只取状态行所以一次请求就够），失败时按 `cn.bing.com` → 百度 → 厂商校验地址回退，并在界面显示**完整精确的失败原因**。想复现「假满格」，把「强信号 RSRP 门槛」临时调到 `-140 dBm`、Ping 阈值调到 `1 ms` 即可，完整步骤见 [`docs/TESTING.md`](docs/TESTING.md) 第 3 节。

> **为什么放行明文 HTTP**：Android 9+ 默认禁止明文流量，因此项目里有
> `app/src/main/res/xml/network_security_config.xml`（`base-config cleartextTrafficPermitted="true"`）并在 Manifest 注册。
> 全仓**唯一**的网络访问点就是这一处 Ping 探测（暴露面为零）；改用 HTTPS 会多一个 TLS 往返、系统性抬高 Ping 值污染 `200 ms` 阈值；
> 用 `base-config` 而非域名白名单，是因为 Ping 目标在功能页里由用户自由修改。

### 2. 手动制式切换 + 快捷磁贴

内置 34 种网络制式（`core/mode/NetworkMode.kt`，对应 Android 的 `NETWORK_MODE_*`）。主页提供快捷预设：自动 5G / 仅 5G / 5G+4G / 自动 4G / 仅 4G / 3G / 2G。下拉快捷设置里有**两个磁贴**：制式循环切换、自动降级总开关。

### 3. 双卡独立策略：内置预设 + 自定义多选

每张卡一张卡片，**卡片头部是这张卡独立的总开关**（「启用此卡策略」），下面是模式下拉：

| 模式 | 含义 |
|---|---|
| `跟随系统` | 这张卡不参与任何自动降级 |
| `网络质量自动降级` | 这张卡启用「假满格 / 信号过差」降级 |
| `连 Wi-Fi 时降为 4G` | 这张卡连上 Wi-Fi 时降到 4G |
| `自定义` | 上面两项**各自一个独立开关**，随便组合 |

选「自定义」时展开两行独立开关（默认「网络质量自动降级」开、「连 Wi-Fi 时降为 4G」关）；总开关关掉时整块淡出，单个子项关掉时只有该行淡出。

> **每卡策略是真的生效的**，不只是界面状态。监控侧的降级引擎是全局单实例、只作用于「当前默认数据卡」，
> 所以通过 `core/NetPilotEvents.kt` 的 `qualityDowngradeAllowed(subId)` 回调把「这张卡有没有资格降级」反查回数据卡策略
> —— 依赖倒置：`monitor` 不反向 import `datacard`，依赖图不成环。未接线时默认放行，避免出现「功能开着却不降级」的假状态。
> **门控只拦「进入降级」，不拦「恢复」**——否则用户中途关掉策略会把这张卡永久锁在 4G。

### 4. Wi-Fi 规则库：连上指定 Wi-Fi 时换默认数据卡

按 Wi-Fi 的 SSID / BSSID 自动切**默认数据卡**。匹配优先级：**BSSID 精确 > SSID 相等（忽略大小写）> 空 SSID = 任意 Wi-Fi**。规则自带「切到哪张卡」（可选「任意卡」= 只切制式不换卡）、优先级排序、冷却时间（默认 120 s）、「离开 Wi-Fi 回切」。字段设计参考了 [TrafficSIM](https://github.com/L-aros/TrafficSIM)。

页面顶部有说明卡，明确「规则管理的是默认数据卡，不是制式」。

### 5. 首次启动引导：权限说明 + 自启动 / 省电策略

首次启动按顺序弹两个说明框，**不直接甩系统权限框**：

1. **权限理由框**：逐条列出 `READ_PHONE_STATE`（读信号/制式/双卡）、`ACCESS_FINE_LOCATION`（Android 10+ 把蜂窝信号强度含 **SINR** 归为位置数据，不给则 SINR 长期「未知」）、`POST_NOTIFICATIONS`（监控是前台服务，Android 13+ 缺它会被系统拒绝常驻），**每一项都注明原因**；拒绝后另弹一个框说明失去哪些功能并给「去设置」。
2. **自启动 + 省电策略框**：说明「不开自启动 → 重启后监控不恢复」「省电策略不设为无限制 → 息屏后可能被冻结、降级后无法恢复原制式」；按钮按 **左「取消」/ 右「去设置」** 排布，「去设置」优先跳 MIUI/HyperOS 自启动管理页、不可用再回落应用程式详情，另有「申请忽略电池优化」快捷入口。

判定与跳转收在 `core/PermissionGuide.kt`，弹窗在 `ui/component/PermissionDialogs.kt`。

### 6. 后台保活 + 「关闭所有服务」总开关

保活分三层：`START_STICKY`（系统回收后自启，最可靠）→ `onTaskRemoved` 后用 `AlarmManager` 拉回 → **15 分钟不精确心跳**兜底自修复。

被拉回的延迟是**指数退避**的（1.2.0）：正常情况（服务已经稳稳跑了 2 分钟以上）仍然是 **10 秒**拉回，与以前完全一致；只有在「刚起来就又没了」的连续失败场景才 10 s → 20 s → 40 s → … 一路退到 15 分钟上限，避免服务反复崩时变成 10 秒一次的重启风暴。
心跳本身**不再重复注册闹钟**（1.2.0）：`setInexactRepeating` 由系统自己续期，原来每次心跳都重排一次会不断重置闹钟相位、打乱 Doze 批处理，而真被系统清掉时那个接收器根本不会被执行——重排既救不了它，又白付一次 binder。

设置页「后台服务」卡片有一个**总开关**，关掉会同时停掉：监控前台服务、心跳、被杀后重启、开机自启；Tasker / QS 磁贴入口也会明确失败并写日志。
副作用统一收口在 `core/NetPilot.setServicesEnabled`；`core/ServicesGate.kt` 只读写配置、不依赖任何服务（叶子节点，依赖图不成环）。

> 已知边界：Doze 会推迟/合并不精确闹钟；少数 OEM 不回调 `onTaskRemoved`；系统里点「强行停止」后不投递任何闹钟。
> 用户主动停（`ACTION_STOP`）会清掉「希望运行」标记，心跳**不会**再拉回来——否则用户永远关不掉。

### 7. 监控仪表盘 + 运行日志

- **监控页**：实时 RSRP / SINR / Ping / 制式 / 运营商 / Wi-Fi SSID / 当前特权通道 / 降级状态机阶段；顶层有 5 秒快速采样（检测到降级进行中时会跳过采样，避免把分钟级判定压成十几秒）。
  1.2.0 起这层快采**只在「应用在前台 + 当前标签页就是监控页」时运行**：以前它退到后台也不会停，会每 5 秒发起一次真实 HTTP 探测（每小时最多 720 次），是待机耗电的头号来源。页面本身的表现没有任何变化。
  采样循环内部还会**每轮独立确认一次屏幕状态**（`PowerManager.isInteractive`）：Compose 的重组要等下一帧，屏幕关闭后系统不再投递 VSYNC，只靠上面那个开关盖不住「关屏仍在探测」这条路径。
- **屏幕关闭且信号非强时不再做无用探测**（1.2.0）：判定只在信号强于阈值时才会用到 Ping，所以在「屏幕关 + 状态机空闲 + 信号非强」时整轮跳过网络探测，监控页/通知会显示「已跳过探测（屏幕关闭）」而不是「无响应」。屏幕亮起、进入降级态、或信号变强都会立刻恢复真实探测。逐分支证明见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §3。
- **假满格只在 5G / 5G+ 上判定（1.4.0，默认开）**：在 4G / 3G / 2G 上不再用 Ping / SINR 判「满格没质量」—— 那时降级目标就是 4G，写下去射频侧什么都不变，判据却一直成立，恢复计数永远攒不够，设备会被永久锁在 4G。判定依据里还会写明「当时是什么制式」。开关与解释见 §15。
- **自适应采样间隔（1.3.0 起，默认开）**：读数靠近任一判定门限时，采样间隔逐轮按「自适应缩短倍率」缩短（1.4.0 起默认 0.85，即每轮缩 15%，可在功能页调；示例 60s → 51s → 43s → 37s），最多缩到设置值的一半；一旦远离就立刻恢复成设置值。它只决定「多久采一次」，**不参与判定** —— 门限比较仍然用原始读数，所以自适应不可能改变降级/恢复结果（证明见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §6）。远离门限时不产生任何额外开销。
- **SINR 读不到时不再是干巴巴的「未知」**：显示结构化原因（未插卡 / 缺 `READ_PHONE_STATE` / 缺定位权限 / 定位服务未开 / 当前制式不上报 SINR 等），并且 SINR 先试 `signalStrength`、再回退 `allCellInfo`。
- **「判定标准（当前生效值）」区**：把两条规则用当前生效阈值拼成人话，含「过差规则已关闭」这种状态。
- **日志页**：最近 400 条运行记录，可清空。**1.5.0 修了一个真 bug：日志此前从不落盘，冷启动后日志页必然空白**（`LogStore.init()` 全工程零处调用，详见 §16）；现在重开应用能看到上次退出前约 120 条。
- **写入诊断（1.5.0，开关默认关）**：设置页「系统兼容性」里新增「写入详细诊断日志」。打开后，「开关拨了但网络没变」这类问题能一路查到：命中了哪个反射重载、调制解调器返回了什么、`settings put` 的退出码、回读读到什么、哪一步抛了什么异常。

### 8. 系统兼容性：全部实测，**刻意不做厂商分支**

参考项目里有大量按厂商分支的定制系统兼容代码（小米 `nr_sa_mode`→`nr_mode`、vivo 跳过、三星/华为 PNM 写入可能被忽略等）。**本项目一律没有移植**，理由：我们写的是 `settings put global preferred_network_mode[1]`（不是 `nr_sa_mode`），那些分支的前提在这里不成立；而各厂商定制系统的底细在没有真机验证时无法确认，硬猜分支只会引入新 bug。

只保留「防 bug 的那半边」——设置页「系统兼容性」卡片，**没有一个写死的展示值**：

- **写入后回读校验**开关（默认开）：写完 setting 后 `settings get` 读回比对；给「某些机型回读本身不准、校验反而误判失败」留后路，关掉即写完返回。
- **只读详情（实测）**：厂商 / 型号（直接显示系统报出的值，不做拼接）/ Android 版本 / OS 版本（`ro.miui.ui.version.name` → `ro.mi.os.version.name` → HyperOS 属性 → `ro.build.version.incremental` → `Build.VERSION.RELEASE`，都取不到才显示「未知」）。
- **真实探测**（`SystemCompatInfo.probe`，跑在 `Dispatchers.IO`）：真探测当前特权通道与原因；真跑一次 `getmode` 判断走 `app_process` 还是 `settings` 兜底；**写入命令串由通道 + 真实 subId/卡槽 + `context.packageCodePath` 现场拼出**；再真执行一次读，给出四态 `OK（读回 <原始值>）/ FAILED（失败：<原始输出>）/ SKIPPED（没有可用通道，未执行）/ NO_TARGET（没能确定写入目标）`。
- **脚注**：明确「未知 = 探测失败或没取到，不代表没问题」。若某机型写入被系统忽略，回读校验会把它标为失败并写进日志页。

### 9. 自动化接口（Tasker / 广播）

- **安全广播**：发命令 `com.katiusu.netpilot.action.*`，收事件 `com.katiusu.netpilot.event.*`
- **Locale 插件**：Tasker 的「插件 → NetPilot」里直接配置动作与条件

- **总开关（1.3.0，默认关）**：功能页 →「自动化接口」→「启用 Tasker / Locale 接口」。关着时命令接收器、Locale 插件与插件配置界面被**系统层面禁用**，Tasker 广播连派发都不会发生（不是「收到再忽略」，而是彻底的零唤醒）。**从 1.2.0 升级上来的用户需要重新打开一次。**

详见 [`docs/TASKER.md`](docs/TASKER.md) 与 [`docs/QS_TILE.md`](docs/QS_TILE.md)。

### 10. 版本历史

| 版本 | versionCode | 主要内容 |
|---|---|---|
| 1.0.0 | 2026100400 | 首个公开版本：假满格自动降级、双卡独立策略、快捷磁贴、Tasker 接口。 |
| 1.0.1 | 2026100401 | 自适应图标；回收残留的 Shizuku 特权进程；日志落盘减量。 |
| 1.1.0 | 2026100500 | 许可证改为 Apache-2.0（`TelephonyReflection.kt` clean-room 重写）；默认值调整（降级总开关默认开、冷却 60 s、恢复 2 轮、采样 60 s）。 |
| 1.2.0 | 2026100501 | 后台耗电优化；新增自动更新检查。 |
| 1.3.0 | 2026100502 | 自动化接口总开关（默认关闭）；自适应采样间隔。 |
| 1.4.0 | 2026100503 | 假满格只在 5G / 5G+ 判定；判定参数默认值调整。 |
| 1.5.0 | 2026100504 | 日志落盘修复；写入诊断；桌面图标清理；写入链路修掉六处「看得见的假成功」；运营商默认值修正；Tasker 事件门控。 |
| 1.5.1 | 2026100505 | 权威存储三类「读不到」分开说明；日志结论带原因、两档日志模式；日志页倒序；导出日志文件。 |
| 1.5.2 | 2026100600 | Shizuku 通道日志补全；日志页「正序 / 倒序」切换（默认正序）；界面内直接切换全部 34 种制式；修掉 Shizuku 孤儿用户服务进程清扫失效（每个约 40 MB）；日志导出改流式写；release 按 MiuixGuiExample 开启 R8（APK 33.3 MB → 4.15 MB）。 |
| 1.5.3 | 2026100601 | 后台耗电：息屏 HTTP 探测降到最多 5 分钟一次；制式读带 5 分钟记忆（不再每个采样周期 fork 一次特权进程）；降级状态落盘与日志落盘加脏检查 / 分档窗口；功能页制式列表每项间距 6 dp、按标签去重同名项（0/3、10/22），并在 5G/4G/3G/2G 每组各加一行「自动适配运营商」（按本机 SIM 解析，4G/3G/2G 用逐级去掉更高代际的位掩码表，全部条目保留）；修掉 Shizuku 孤儿用户服务进程清扫永久失效（门控顺序 + 启动 4 次有界重试 + 回收数写进日志页）；不再把「页面离开组合导致的协程取消」误报成 binder 调用失败（日志页的 a80/b80 假 WARN）。 |
| 1.5.4 | 2026100700 | 深睡眠：锁屏期间保活心跳与「尽快拉回」闹钟改用**不唤醒**类型（`ELAPSED_REALTIME` + `set`，亮屏瞬间自动切回唤醒型；本应用从不持有 wake lock）；制式写入新增**系统回读确认** —— 写完读回真值并按位掩码比对，写入被系统收下但没生效时日志与 Toast 如实提示（判定语义未改：modem 返回 false 仍不算失败）；Shizuku 孤儿用户服务清扫重试扩到 7 次（最远 60 分钟），并在**首次选用 Shizuku 之前**补清一次。 |

> 逐版细节不再堆在 README 里：前后量化数据见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md)，验收步骤见 [`docs/TESTING.md`](docs/TESTING.md)。
> 许可：1.0.1 及更早的已分发副本（含 Releases 里的 1.0.1 APK）按 GPL-3.0 授权且不可撤回；自 1.1.0 起以 Apache-2.0 发布，方法、实测相似度与复现命令见 [`docs/PROVENANCE.md`](docs/PROVENANCE.md)。
## 权限一览

| 权限 | 用途 |
|---|---|
| `READ_PHONE_STATE` / `READ_BASIC_PHONE_STATE` | 读信号强度（RSRP/SINR）、当前制式、双卡信息 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Android 10+ 把蜂窝信号强度（含 SINR）归为位置数据，不给则 SINR 读不到 |
| `POST_NOTIFICATIONS` | 监控是前台服务，Android 13+ 缺此权限会被系统拒绝常驻 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 运行前台监控服务 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Ping 探测；全应用**只**访问 Ping 目标 |
| `ACCESS_WIFI_STATE` | 读当前 Wi-Fi 的 SSID / BSSID，用于规则匹配 |
| `RECEIVE_BOOT_COMPLETED` | 开机恢复监控（受「后台服务」总开关约束） |
| `WAKE_LOCK` | Doze 下唤醒心跳闹钟 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 引导用户把省电策略设为「无限制」 |
| `QUERY_ALL_PACKAGES` | 探测 MIUI/HyperOS 自启动管理页等系统设置组件是否可跳转 |

应用**不联网上传任何数据**（唯一网络请求是 Ping 探测），不采集位置，不读通讯录/短信/相册。

---

## 特权通道：Root 优先，Shizuku 兜底

| 通道 | 实现 | 备注 |
|---|---|---|
| **Root** | `su` 起 `app_process` 跑 `PrivilegedCli`，走隐藏 API `setPreferredNetworkTypeBitmask` | 首选。`app_process` 跑不起来时**自动退化**为 `settings put global preferred_network_mode[1]` |
| **Shizuku** | `Shizuku.bindUserService` + AIDL（`IShizukuController`） | 兜底，不需要每次授权 |

探测与选择都在 `core/priv/ControlManager.kt`，结果缓存 60 s（避免每轮都跑 `su -c id`）。

> 通道**只影响写制式 / 换卡，不影响 Ping 探测**。

---

## 自己构建

```bash
git clone https://github.com/katiusu/NetPilot.git
cd NetPilot
bash _build.sh :app:assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
```

标准 Android Gradle 工程（Kotlin 2.2 + AGP 8.x + Compose）。用 Android Studio 直接打开也可以，`minSdk 34`。

<details>
<summary>开发环境备注（低内存容器 / 国内网络）</summary>

1. **项目级 `gradle.properties` 里的 `org.gradle.jvmargs` 对 Gradle 无效**——JVM 在读取工程配置之前就已启动，真正生效的是 `$GRADLE_USER_HOME/gradle.properties`。在只有 7.5 G 内存的容器里用默认 `-Xmx1536m` 会反复 `Gradle build daemon disappeared unexpectedly`。`_build.sh` 用命令行显式 `-Dorg.gradle.jvmargs="-Xmx1152m …"` 解决，并配了 `kotlin.compiler.execution.strategy=in-process`（避免再起第二个 Kotlin 守护进程）。
2. 若 `dl.google.com` / `repo.maven.apache.org` 不可达，可用 `init.gradle` 把仓库替换为镜像；`local.properties` 里的 `sdk.dir` 指向本机 SDK。
3. 判断构建成败请看 `_build.sh` 的 `=== EXIT=…` 行或 `/tmp/np_build.log`，**不要用 `| tail` 接脚本**，那会吞掉退出码。

</details>

---

## 目录结构

```
app/src/main/java/com/katiusu/netpilot/
├── core/
│   ├── NetPilot.kt              # 门面：UI / Tasker / QS 磁贴的唯一入口
│   ├── NetPilotEvents.kt        # 事件总线 + 每卡降级门控回调（切断 monitor ↔ datacard 包循环）
│   ├── BootReceiver.kt          # 开机自启（受服务总开关约束）
│   ├── ServicesGate.kt          # 后台服务总开关（只读写配置，无副作用）
│   ├── PermissionGuide.kt       # 权限 / 自启动 / 省电引导的判定与跳转
│   ├── mode/                    # NetworkMode（34 制式）+ 位掩码映射
│   ├── priv/                    # 特权层：ControlManager / Root / Shizuku / 反射 / WriteCompat
│   ├── monitor/                 # 采样（SignalReader）/ 判定 / 状态机 / 前台服务 / 日志
│   ├── keepalive/               # 心跳闹钟 + 被杀后重启接收器
│   ├── datacard/                # 双卡策略（SimPolicyMode）+ Wi-Fi 规则引擎
│   ├── qs/                      # 两个 QS 磁贴
│   ├── tasker/                  # 广播 + Locale 插件 + 桥接 + TaskerGate（接口总开关）
│   └── update/                  # 检查 GitHub Releases 更新（只查、只提示、不静默安装）
├── ui/
│   ├── screen/                  # 首页 / 功能 / 监控 / 日志 / 设置 / 数据卡 / 关于
│   └── component/               # 权限弹窗、液态玻璃导航栏等
└── tasker/TaskerEditActivity.kt # Locale 插件配置界面
```

---

## 已知边界 / **未验证项**（请务必读）

**这个 APK 编译通过、组件齐全、签名有效、静态审查无循环依赖——但没有任何一条真机写入行为被验证过。**
原因：开发机（Redmi K40 / HyperOS / Android 15）的设备策略拦截了 `app_process`、`su`、`settings put`、`dumpsys telephony.registry`，无法做端到端实测。

因此以下假设**仍是假设**：

- `CLASSPATH=<apk> app_process /system/bin …` 在 HyperOS 上能否真的跑起来——**这正是 Root 通道加 `settings put` 兜底的理由**（该兜底方案成立于 Network_Enhance 已验证 `settings put global preferred_network_mode` 会被电话进程的 ContentObserver 捕获并重新下发制式）
- Shizuku UserService 的绑定链路（AIDL 回调、15 s 轮询）能否成功
- Tasker / Locale 的协议常量（开发机**未安装 Tasker**，无法验证）
- QS 磁贴的实际渲染与点击
- **SINR 能否在具体机型 modem 上读到**：代码已做「`signalStrength` → `allCellInfo`」两级回退，并在读不到时把原因显示出来；但无法预知哪一级能命中
- 载波聚合（CA）无法禁用——这与免 Root 方案是同一个边界，非本应用缺陷
- **1.4.0 的「假满格只在 5G / 5G+ 判定」依赖 `sawNr`**：`5G` 用 `dataNetworkType == NETWORK_TYPE_NR` 判，`5G+`（NSA / EN-DC）用 `allCellInfo` 里是否出现过 NR 小区判。部分机型在缺少「精确位置」权限时读不到小区信息 ⇒ `sawNr` 恒为 false，那类机型在 NSA 下会退化成「只在 SA 上判」——**真 5G 也可能不再触发假满格降级**。这一条在开发机上无法验证，遇到就关掉功能页的「假满格只在 5G / 5G+ 判定」。
- **`sawNr` 与 `isOnNr()` 只在 `FakeSignalDetector` 里被读**：弱信号规则完全不看制式，所以「信号过差降级」在任何制式下行为都不变。

其他已知偏差：

- **SINR 的失败原因只显示互斥的一条**：例如「当前制式不上报 SINR」与「定位服务未开」同时成立时，只显示前者。
- **UI 里手动切制式 / 换卡不会发 `MODE_CHANGED` / `DATA_SIM_CHANGED` 广播**，只有广播命令与 Locale 插件路径会发（Tasker 自动化若依赖这两个事件需注意）。
- 降级磁贴通过通道显示名（`"Root"` / `"Shizuku"`）判断是否有特权通道，改名会一起改。

---

## 来源与许可

本项目以 **Apache-2.0** 发布（见 [`LICENSE`](LICENSE)）。逐文件的借鉴明细见 [`docs/PROVENANCE.md`](docs/PROVENANCE.md)。

| 来源 | 许可证 | 借鉴内容 |
|---|---|---|
| [Network_Enhance](https://github.com/ScarletHanami/Network_Enhance) | MIT | 假 5G 判定阈值与状态机、运营商 PNM 修正表、`settings put` 可行性论证（**未移植**其按厂商分支的定制系统兼容代码，理由见 §功能 8） |
| [TrafficSIM](https://github.com/L-aros/TrafficSIM) | MIT | Wi-Fi SSID/BSSID 规则的字段设计、优先级/冷却/回切语义 |
| [NetworkSwitch](https://github.com/aunchagaonkar/NetworkSwitch) | GPL-3.0（**仅作行为参考**） | 隐藏 API 目标清单与调用形状的参考；对应文件已 clean-room 重写，实测相似度见 [`docs/PROVENANCE.md`](docs/PROVENANCE.md)。**1.0.1 及更早版本移植过其中代码，那些版本以 GPL-3.0 分发且授权不可撤回** |
| [Miuix](https://github.com/YuKongA/Miuix) | Apache-2.0 | UI 组件库 |
| Miuix 示例工程（MiuixGui） | — | 项目骨架、偏好组件、模糊导航栏；检查更新模组（`core/update/UpdateChecker.kt` 与 `ui/component/UpdateDialog.kt` 是同一作者工程里同形实现的移植，**非第三方代码**） |

> **许可变更**：1.0.1 及更早的版本移植过 GPL-3.0 的 NetworkSwitch 代码，因此那些版本以 GPL-3.0 授权（不可撤回，含 Releases 里的 1.0.1 APK）。相关文件已 clean-room 重写——方法、实测数字与复现命令见 [`docs/PROVENANCE.md`](docs/PROVENANCE.md)——自本次提交起本项目以 **Apache-2.0** 发布。

---

## 反馈

欢迎到 [Issues](../../issues) 反馈机型适配情况——尤其是「写入被系统忽略」「SINR 读不到」「保活失效」这三类，带上「设置 → 系统兼容性」里显示的实测值会非常有帮助。
