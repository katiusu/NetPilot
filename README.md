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
| 版本 | **1.5.1**（`versionName = "1.5.1"`，`versionCode = 2026100505`） |
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

### 10. 应用图标与运行时内存（1.0.1）

- **自适应图标**：黑色背景层 + 白色「信号格 + 加号」前景层 + **单色层**（Android 13+ 的「主题图标」用它跟随壁纸着色）。因为 `minSdk 34`，只保留 `mipmap-anydpi-v26/ic_launcher.xml` 一套 XML + 5 档密度 PNG；`roundIcon` 也一并提供（部分系统/启动器要圆形图标）。
- **回收残留的 Shizuku 特权进程**：Shizuku 的用户服务是本应用之外的独立进程（名字是 `<包名>:np_service`，权限 root/shell）。应用被系统杀掉时来不及 `unbindUserService(remove = true)`，它就会变成 PPID=1 的孤儿长期驻留——实测一台机器上累积了 **4 个、约 180 MB**。现在绑定成功后会扫一次 `/proc`，把「同包名 + `:np_service`」且不是自己的进程回收掉，每个应用进程只做一次，失败静默（只省内存，不影响通道）。
- **日志落盘减量**：内存里仍然保留 400 条（日志页看得到），但**落盘只写最近 120 条**、单条消息最长 2000 字符。原先每 4 秒要把 400 条拼成一个 40+ KB 的 JSON 串再交给 SharedPreferences，是应用里最稳定的分配来源。
- **关于页版本信息 `remember`**：不再每次重组都走一遍 `PackageManager` 并新建字符串。

### 11. 许可证改为 Apache-2.0（1.1.0）

- **起因**：1.0.1 及更早版本里，`core/priv/TelephonyReflection.kt` 与上游 GPL-3.0 项目 NetworkSwitch 几乎逐字相同
  （实测相似度 0.761、上游 98.2% 的 token 落在长度 ≥12 的公共串里），因此那些版本整体按 GPL-3.0 分发。
- **处理**：该文件已 clean-room 重写（相似度 0.291、最长公共串 25 token；剩余重合只有 `HiddenApiBypass` 豁免前缀清单、
  AOSP 常量表与接口签名这类必须一致的事实），`LICENSE` 换成 **Apache-2.0**，README / 关于页 / 开源许可页文案同步更新。
- **不可撤回**：1.0.1 及更早的已分发副本（含 Releases 里的 1.0.1 APK）仍然是 GPL-3.0。
- **可复现**：`python3 tools/check_provenance.py` 打印逐文件相似度报告；逐条结论见
  [`docs/PROVENANCE.md`](docs/PROVENANCE.md)，验收步骤见 [`docs/TESTING.md`](docs/TESTING.md) 第 15 节。

### 12. 默认值调整（1.1.0）

- **网络质量降级总开关默认打开**：以前功能页的开关硬编码默认 `false`、引擎侧默认 `true`，界面显示关着其实已经在生效；现在两处都读 `MonitorSettings.DEFAULT_ENABLED`。
- **冷却期默认 60 s**（原 300 s），滑条下限 **30 s**（原 60 s）、步进 30 s。
- **恢复正常轮数默认 2**（原 3）。
- **采样间隔默认 60 s**（原 120 s）。
- 无网回滚默认 2 轮不变。
- 只影响**没写过该键**的用户：老用户存在 `SharedPreferences` 里的值照旧生效。

### 13. 后台耗电优化 + 自动更新（1.2.0）

完整的前后数据、真值表证明与复现命令见 **[`docs/POWER_REPORT.md`](docs/POWER_REPORT.md)**。这里只讲「改了什么、你会感觉到什么」。

**改了什么**（每一处都为了减少待机时的固定开销）：

| 改动 | 以前 | 现在 | 你能感觉到的差异 |
|---|---|---|---|
| 监控页 5 秒快采 | 打开过监控页后**退到后台也不停**，每小时最多 720 次真实 HTTP 探测 | 只在「应用在前台 + 当前页就是监控页」时运行 | 无（页面表现不变） |
| 屏幕关 + 信号非强时的探测 | 照常每 60 秒探测一次 | **整轮跳过**，不联网 | 通知/监控页显示「已跳过探测（屏幕关闭）」 |
| 通知重绘 | 每 60 秒重发一次通知（哪怕文本一个字都没变） | 只在文本真变化时发 | 通知不再每分钟抖一下 |
| 日志落盘 | 每 4 秒把 120 条日志整体重写进 `SharedPreferences` | 每 30 秒一次，且移出调用线程 | 无（日志页仍是 400 条、重开恢复约 120 条） |
| 保活心跳 | 每次心跳都重新注册一遍闹钟 + 写一条会触发落盘的日志 | 一次进程内的状态判断 | 无 |
| 被杀后重启 | 固定 10 秒 | 正常仍 10 秒；连续失败时指数退避到 15 分钟 | 只有在服务反复崩时才会感觉变了 |
| Shizuku 残留进程 | 累积不清（实测一台机器上 4 个、约 201 MB） | 应用启动时清扫一次 | 后台内存更稳 |

**降级判定语义没有变**——「跳过探测」只在「屏幕关 + 状态机空闲 + 信号不强于阈值」三个条件同时成立时才发生；信号强时 `Ping` 会参与假满格判定，此时**照常探测**。逐分支的真值表证明见报告 §3。

**自动更新（新）**：关于页新增「检查更新」入口，启动时也会按设置项（默认开）检查一次 [Releases](../../releases)。**只查、只提示**：有新版会弹出对话框显示版本号与更新说明，点「立即更新」用浏览器打开 Releases 页——不静默下载、不自动安装、不做任何后台轮询。**设置页「更新」小节有「自动检查更新」开关（默认开）**；这个偏好从 1.2.0 起就存在，但到 1.5.0 才有可点的 UI 入口。

**合规（1.2.0）**：`targetSdk` 34 → **36**（`compileSdk` 保持 37）。已逐条核对 edge-to-edge、预测性返回、前台服务 `specialUse`、BOOT_COMPLETED 限制、16 KB 页对齐（`zipalign -c -P 16` 实测通过），本应用均无需额外改动。

### 14. 自动化接口开关 + 自适应采样间隔（1.3.0）

| 改动 | 以前 | 现在 | 你能感觉到的差异 |
|---|---|---|---|
| Tasker / Locale 接口 | 三个组件常驻启用，Tasker 每发一次命令都会拉起本应用进程 | **默认关闭**；开关一关就由系统禁用这三个组件，广播不派发、进程不被唤醒 | 不用自动化的用户不再被 Tasker 广播唤醒；用自动化的人需要去功能页打开一次 |
| 采样间隔 | 固定等于设置值 | 读数靠近判定门限时逐轮缩短 20%（最多到一半），远离立刻恢复 | 临界信号下判定反应更快；功能页多出「自适应采样间隔」开关和「自适应灵敏度」滑块 |
| 保活拉起失败 | 被系统拒绝时只有一句笼统的「保活广播处理失败」，而且日志还会跟着写一条「已拉起」 | 单独识别 `ForegroundServiceStartNotAllowedException`，写明原因与下一步操作 | 排查「保活没生效」时日志能说清是电池优化没关 |

**自适应只改采样节奏，不改判定**：门限比较仍然使用原始读数，`isNearThreshold()` 怎么变都不可能改变降级/恢复结果 —— 它在代码里没有任何判定路径的调用者。逐条说明、以及一项「查证后判定不安全、因此没做」的候选，见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §6。

**新增配置项（1.3.0）**：`np_tasker_enabled` 默认**关**、`np_fake5g_adaptive_interval` 默认**开**、`np_fake5g_adaptive_margin` 默认 **10 dBm**。既有阈值与采样间隔的默认值**一个都没动**。

### 15. 假满格只在 5G / 5G+ 判定 + 判定参数默认值调整（1.4.0）

**修的是什么**：在 4G 上，只要信号格是满的（RSRP 强于 −85 dBm）而 Ping 偏高，旧版就会判「假 5G 满格」并降级 —— 可那时的降级目标**就是 4G**，写下去射频侧毫无变化，唯一效果是把 5G 门关死。更糟的是降级之后判据**依然成立**，恢复计数永远攒不够，设备被永久锁在 4G，只有断网两轮才可能出来。这就是「回到 4G 后一直触发假满格」的完整成因链。

| 改动 | 以前 | 现在 | 你能感觉到的差异 |
|---|---|---|---|
| 假满格的适用范围 | 任何制式下都按 Ping / SINR 判 | **只在当前驻留 5G / 5G+（NSA 双连接）时判**；4G / 3G / 2G 上不再判 | 4G 上不会再被莫名降级，也不会再被锁在 4G；真 5G 上的判定完全照旧 |
| Ping 上限默认值 | 200 ms | **300 ms** | 这个读数是含 DNS 与建连的完整首字节耗时，不是无线 RTT；200 ms 对 4G 尾段偏紧 |
| 降级冷却默认值 | 60 秒 | **120 秒** | 单次误判的影响窗口减半，5G⇄4G 更不容易反复横跳 |
| 自适应灵敏度默认值 | 10 dBm | **20 dBm** | 更早开始加密采样 |
| 自适应缩短倍率 | 硬编码 20% | **默认 15%（0.85 倍），可在功能页调** | 想更灵敏就往小调，想更省电就往大调 |
| 判定依据展示 | 只说「RSRP 满格但 Ping 高」 | 说清**当时是什么制式**；被门控挡住时明确写「本轮不检查 Ping 与 SINR」 | 排障时一眼看出「这轮是在 4G 还是 5G 上判的」 |

**「5G / 5G+」是怎么认的**：`5G` = 数据网络直接上报 NR；`5G+` = 数据网络仍报 LTE，但小区列表里已经见到 NR 小区（NSA / EN-DC 双连接）。**`4G+`（LTE 载波聚合）不算 5G。**

**这个开关默认开着，也可以关掉**：功能页 →「假满格只在 5G / 5G+ 判定」。关掉即完全退回 1.3.0 的行为。需要关掉它的典型场景：部分机型在 NSA 下读不到 NR 小区（缺「精确位置」权限），会连真 5G 一起被挡在门外。

**默认值只对「没调过这一项」的用户生效**：这几个滑块读的是「当前值 ?: 默认值」，只有你真的拖过才会被记住。所以从没动过它们的用户升级后立刻用上新默认值，调过的用户保留自己的数字 —— 升级不会悄悄改掉你设过的参数。

**没有做的事（如实说明）**：弱信号规则（RSRP 低于 −110 dBm 降级）**一位都没改**，它与假满格规则互斥（一个要求 RSRP > −85，一个要求 < −110），判定结果逐位不变。逐条证明与时序分析见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §7。

**新增配置项（1.4.0）**：`np_fake5g_nr_only` 默认**开**、`np_fake5g_adaptive_step` 默认 **0.85**；`np_fake5g_ping` 默认 **300 ms**、`np_fake5g_cooldown` 默认 **120 秒**、`np_fake5g_adaptive_margin` 默认 **20 dBm**。

---

### 16. 1.5.0（一）日志落盘修复 + 写入诊断 + 桌面图标清理 + 自动更新开关

这三件都是「代码在那儿但没接上」的缺陷，不是新功能。

**16.1 日志从不落盘（真 bug）**

`LogStore` 的 `appContext` 只在 `LogStore.init(context)` 里赋值，而这个调用**全工程零处引用** —— 每次 `persist()` 都在第一句 `val ctx = appContext ?: return` 直接返回。后果：日志页只有本进程内存里的记录，**冷启动后必然空白**，`load()` 也从没执行过。1.5.0 在 `core/NetPilot.kt` 的 `Application.onCreate` 里补上 `LogStore.init(app)`，与已有的 `PrefsStore` / `ConfigState` / `ControlManager` / `MonitorEngine` 初始化并列。

**16.2 写入链路只有 logcat 看得见**

一次制式切换要穿过「反射拿 ITelephony → 三条写入策略 → `settings put` 兜底 → 回读」四层，而这条链路上几乎只用 `android.util.Log`（只有 logcat 可见）；`TelephonyReflection.dispatch` 更是把每个候选组合抛出的异常**直接吞掉**，连 logcat 里都没有失败原因。所以从外面只能看到「开关拨了、网络没变」。

1.5.0 新增 `core/priv/WriteDiag.kt` 作为这条链路的日志出口，并把四层的关键节点接进去：反射失败原因、命中的是哪条策略、**调制解调器返回了什么**、`settings put` 的键与退出码、回读值。

**16.3 详细诊断开关（默认关）**

设置页「系统兼容性」新增「写入详细诊断日志」（配置键 `np_verbose_log`，默认**关**），打开后额外记录逐候选的反射尝试与异常原文。Root 通道下诊断要从 `app_process` 子进程搬回应用进程（子进程 stdout 加 `DIAG ` 前缀，由 `RootController` 逐行转发），属于纯诊断开销，所以默认关。

**判定语义没有任何变化**：`setAllowedNetworkTypesForReason` 的返回值就是调制解调器接不接受这个模式，1.5.0 只把它**记进日志**，不改判定（把它当成「写入失败」会改变降级/恢复语义，那一步留给你决定，见报告 §8.3）。

**16.4 桌面上有两个图标**

`AndroidManifest.xml` 里 `MainActivity` 与 `activity-alias .LauncherAlias` **各带一份** MAIN/LAUNCHER 过滤器 ⇒ 装完未启动前桌面上就是两个图标；而「隐藏桌面图标」只禁用别名，对 `MainActivity` 自己的入口无效 —— 所以那个开关既藏不住图标、也从来没接到任何 UI 上。1.5.0 删掉别名整块、`LauncherIconController.kt`、`AppSettings.hideLauncherIcon` 及其持久化字段（旧导出 JSON 里的该键会被忽略）。

**16.5 自动更新开关的 UI 入口**

`AppSettings.checkUpdateOnLaunch`（默认开）从 1.2.0 起就存在并生效，但**没有任何 UI 开关**。1.5.0 在设置页新增「更新」小节与「自动检查更新」开关。同时修掉一个会被这个开关立刻暴露出来的旧缺陷：`MainActivity.persistState()` 原本直接构造新的 `AppSettings`，会把界面上没暴露的字段（`checkUpdateOnLaunch`）**重置成默认值**；现在改为 `AppSettings.load(this).copy(...)`。

验收步骤见 [`docs/TESTING.md`](docs/TESTING.md) §19，量化与代码级论证见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §8。

### 17. 1.5.0（二）写入链路：修掉六处「看得见的假成功」

第 16 节让写入链路**看得见**了；这里修链路里**真正错的地方**。所有改动只落在
「观测」与「真 bug」两件事上，**不动**降级 / 恢复 / 冷却 / 无网回退的任何阈值与默认值。

**17.1 「允许的网络类型」的权威存储不是 `settings`**

Android 11 起，一张卡允许哪些制式由 TelephonyProvider 的 `siminfo.allowed_network_types` 决定，
`Settings.Global.preferred_network_mode` 在多数 ROM 上只是遗留兼容字段。旧代码的
「写入 → 回读 settings → 一致 → 认为成功」是一个**闭环**：写的是它、读的也是它，
因此在结构上永远发现不了「设置对象变了、系统根本没读」。本版新增
`core/priv/AuthStore.kt`，让两条通道都能读**另一个源**，并在写入后做跨源回读；
写入顺序改为 **ITelephony → 权威存储（siminfo）→ settings**。

**17.2 表外模式不再被当成「放开全部制式」**

`NetworkModeBitmaskMapper.toBitmask()` 以前对映射表之外（含负数）的 RIL mode 一律返回
`ALL_NETWORK_TYPES = (1 shl 31) - 1` —— 也就是**把这张卡放开到所有制式**。于是一次「锁 5G」
可能变成「不限制任何制式」，方向与用户意图相反且没有任何提示。本版改为返回 `null`，
由调用方拒绝写入，并在日志页写明「模式 N 不在本机位掩码表内，已拒绝写入」。

**17.3 权限被拒不再只是一句「写入失败」**

`MODIFY_PHONE_STATE` 是 `signature|privileged` 权限，`com.android.shell`（Shizuku 无线调试的
uid 2000）**不持有**；而 `settings put` 只需要 shell/root 的 `WRITE_SECURE_SETTINGS`。
这正是「制式完全没切，写入与回读却一路绿灯」的成因。本版让
`TelephonyReflection.dispatch` 把 `SecurityException` 单独挑出来，**无条件**记进日志页
（不受「详细诊断」开关影响），并带上当前 `Process.myUid()`，直接写明缺的是哪个权限。

**17.4 「三条写入策略」到底还剩几条，由设备自己说**

`setAllowedNetworkTypes(long)` 与 `setPreferredNetworkType(int)` 在 Android 14 的 `ITelephony`
上**已经不存在**，旧代码里那两条是死代码 —— 所谓「三条策略」在新机上是**一条**。
本版新增 `TelephonyReflection.describeWriteMethods()`，运行时枚举本机 `ITelephony` 上的写入方法
及其重载形参列表（只做方法枚举、不需要任何权限），显示在设置页「系统兼容性」卡片里；
三条全失败时的日志也会把这份结果一起打出来。

**17.5 设置页新增两行只读事实**

「系统兼容性」卡片新增「本机可用的写入方法」与「权威存储（TelephonyProvider）」两行。
后者会写明这个值是**经哪条通道**读到的；读不到时把「通道侧的原因」与「应用进程侧的原因」分别列出 ——
「读不到」和「读到但是空的（未设置）」是两件事，不能合并成一句「未知」。

**17.6 新增直接写权威存储的写入路径**

- **Root 通道**：`su -c content update --uri content://telephony/siminfo --where sub_id=<id> --bind allowed_network_types:l:<掩码>`，
  随后 `content query` 回读比对（`content` 的退出码只说明命令跑通，不代表那一列真的变了）。
- **Shizuku 通道**：在 user service 进程（uid 是 shell/root）里用 `ContentResolver.update` 写同一列。
- **`app_process` CLI 侧**：走同一条 `content` 命令，所以「有 Root 且 `app_process` 可用」的机器上，
  一次 `setmode` 就能完成 ITelephony → siminfo → settings 三级尝试。

> **如实说明**：`siminfo.allowed_network_types` 是权威存储，但**写进去不等于调制解调器立刻接受** ——
> 是否重新下发取决于电话进程有没有观察这张表，部分 ROM 要到重启才生效。所以本版日志会明确写
> 「已写入权威存储；是否下发到调制解调器仍取决于电话进程」，不做任何超出证据的承诺。

验收步骤见 [`docs/TESTING.md`](docs/TESTING.md) §19，六条缺陷的逐条代码级论证见
[`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §8。

### 18. 1.5.0（三）运营商默认值修正与识别 + Tasker 事件门控 + 详细日志扩展 + 权威存储失败分类修正

**18.1 运营商默认值表按公开来源重核**

`NetworkMode.OPERATOR_DEFAULTS` 决定「解除锁 5G 时回落到哪个制式」（功能页把该项设成「跟随运营商」时生效）。
旧表有三处不对：

- 漏了 **46005（中国电信 CDMA）** —— 这张卡会落到兜底 26（联通档），**静默地回错制式**；
- 漏了 **46020（中国铁通，2008 年并入移动）**；
- 多出 `46010`（旧表记作联通）与 `46027`（旧表记作电信）—— 这两个 MNC 在四个独立来源
  （`musalbas/mcc-mnc-table`、`pbakondy/mcc-mnc-list`、`mcc-mnc.org`、ITU-T E.212 公报 OB 1280）里**一个都查不到**。

现在：电信 `3/5/11` → 27、移动 `""`(46000)/`2/4/7/8/20` → 32、联通 `1/6/9` → 26、广电 `15` → 33，
制式数值逐个对应 AOSP `RILConstants.java` 的 `NETWORK_MODE_*`。
非国内卡或表外号段仍取兜底 26（语义未变）；删掉查不到的键的代价（万一 `46027` 真在某张卡上，现在落到 26 而不是 27）
已如实写在 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §8.10。

**18.2 新增运营商识别，而且在设置页看得到**

新增 `Carrier` 枚举（中国移动 / 联通 / 电信 / 广电）、`NetworkMode.carrierOf(mcc, mnc)`、
`carrierName(mcc, mnc)`，以及 `CarrierInfo.activeCarrierName()` / `activeCarrierSummary()`。
设置页「系统兼容性」卡片新增只读行**「运营商识别」**，值形如 `中国移动（46000）→ 32 NR/LTE/TDSCDMA/GSM`；
号段不在表内时明确写 `不在内置运营商表中（460xx）→ 回落 26 NR/LTE/GSM/WCDMA`。
**这张表本质是猜，猜错不会报错、只会安静地回到错的制式** —— 摊在设置页，换一张卡就能立刻验证。

**18.3 Tasker 接口关闭时，不再外发任何事件**

`TaskerGate` 只管**组件启用状态**（`pm.setComponentEnabledSetting`），而五条事件发送路径
（信号采样 / 降级 / 恢复 / 制式变化 / 数据卡变化）全部汇到 `TaskerEventSender.broadcast()`，
那里**没有开关判断**；再叠加 `TemplateApp.onCreate` 里无条件的 `TaskerBridge.init(this)`，
于是「接口关着」时快照与降级事件照样广播出去 —— 开关形同虚设，而且每轮采样都要白构造一次 `Intent`。

本版把门控加在**唯一的收口点** `broadcast()` 首行（`if (!TaskerGate.isEnabled(context)) return`），
`TaskerGate.sync()` 改成「开关打开才 `TaskerBridge.init`」，并删掉 `TemplateApp` 里的无条件接线。
`np_tasker_enabled` 默认 false（既有默认值，未改），打开后行为与之前**完全一致**。

**18.4 详细诊断日志的覆盖面扩展**

「写入详细诊断日志」开关（`np_verbose_log`，默认关）控制的 `WriteDiag.detail()`，
在本版新增的写入路径上**一处都没有**（`core/priv/AuthStore.kt` 与 `core/priv/shizuku/ShizukuController.kt` 各 0 条）。
本版补齐：`content query` / `content update` 的**命令原文**、结果分类、`content update` 的原始
exit/stdout/stderr、逐列试探（`allowed_network_types` → `allowed_network_type`）、AIDL 返回的编码字符串、
「三条 ITelephony 都没成、转写权威存储」等。全部走 `detail()`：**开关关闭时一行都不写**；
`always()` / `warn()`（例如权限被拒）仍然无条件记录，语义未变。

**18.5 同一版本的补丁：Shizuku 通道读权威存储的失败原因不再是「被拒绝」**

真机日志里出现过 `被拒绝：… Unable to find app for caller … when getting content provider telephony`。
这句 `SecurityException` **与权限无关**：`ContentResolver` 会先让 AMS 按调用方 pid 找一条**应用进程记录**，
而 Shizuku 用户服务进程由守护进程用 `app_process` 拉起、从未 `attachApplication`，AMS 侧没有它的记录 ——
补多少权限都过不去。旧版把它和 `Permission Denial` 一起归成「被拒绝」，等于把人引向「去授权」这条死路。
本补丁新增 `AuthStore.isNoAppRecord()`，把它单独归成 `Read.Unavailable` / `Write.Failed`，
文案写明「补授权无效；读写权威存储只能用 Root 通道」；真缺权限时仍然是「被拒绝」。只改分类与文案，
不改判定、不改写入顺序（详见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §8.13）。

> **1.5.1 又改了一次**：单一判据 `isNoAppRecord()` 换成三条判据的 `AuthStore.callerHint()` ——
> 「身份名单」（`Access SIMINFO table from not phone/system UID`）、「缺 SIMINFO 库权限」
> （`No permission to access SIMINFO table`）、「AMS 无调用方记录」（`Unable to find app for caller`）
> 分别给不同说明。见 §19.1 与 [`docs/TESTING.md`](docs/TESTING.md) §20.4。

验收步骤见 [`docs/TESTING.md`](docs/TESTING.md) §19.12–§19.14 与 §19.16，来源对照与代价见
[`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §8.10–§8.13。

### 19. 1.5.1 权威存储读取修复 + 日志可分析性 + 日志页倒序

1.5.0 发布后，真机上出现了三种「读不到」，但它们其实是三个完全不同的原因。本版把原因拆开、把日志改成能分析的形式。

**19.1 权威存储：三种「读不到」分别说清**

- **Root 通道**：`siminfo` 表里没有目标 `sub_id` 的那一行时，不再只说「没有这个 subId 的行」，而是**枚举整张表**并报出现有几行（`sub_id` / `sim_id` / `allowed_network_types`）与框架给出的候选 subId；整张表为空时会说明这是「本机没插卡，或 TelephonyProvider 还没登记任何卡」。
- **应用进程 / Shizuku**：`Access SIMINFO table from not phone/system UID` 与 `Unable to find app for caller …` 是**两种不同的原因**，现在各有各的说明 —— 前者是 TelephonyProvider 里的**身份名单**（只放行 system / phone / root，AOSP 源码注释写明 root 是特意放行、方便测试），后者是 AMS 找不到调用方进程记录（Shizuku 用户服务由 `app_process` 拉起、从未 `attachApplication`）。**两者都与权限无关，补授权永远不会通过**（`ACCESS_TELEPHONY_SIMINFO_DB` 是 signature|privileged，第三方拿不到）。
- 1.5.0 把这后一种的说明错套在前一种上，本版改准 —— 分类函数由单一判据 `isNoAppRecord()` 换成三条判据的 `AuthStore.callerHint()`。
- 另外：系统没给出默认数据卡 `subId`（返回 `-1`）时，现在会回落到「活动卡 / 默认语音卡」的首个 subId，不再让整条链路卡在 `-1`。**但绝不改写目标 subId** —— 写入与回读必须盯着同一张卡。
- 验收见 [`docs/TESTING.md`](docs/TESTING.md) §20.4–§20.5，依据与代价见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §9.1。

**19.2 日志：结论带原因，逐步问题归开关**

- 失败结论行现在**带上原因**，不需要打开任何开关：`卡 1 切换 NR/LTE 失败：…`，而不是光秃秃的「切换失败」。原因由写入链路最深一层提供（`WriteDiag` 把最近一条原因暂存起来，`NetPilot.setMode` 取走并清空，避免下次带上过期原因）。
- 每一步的过程细节（逐策略返回值、命令原文、`exit`/`stdout`、回读原文、`ContentResolver` 就绪情况）统一归「**写入详细诊断日志**」开关管；关掉开关后这些行不再出现，但**结论行里的原因不受影响**。1.5.0 有 6 处过程行误放在无条件级别，本版降级。
- 日志页改为**倒序**（最新一条在最上面）；「复制全部 / 分享」导出仍是**时间顺序**（旧→新），方便顺着读。
- 验收见 [`docs/TESTING.md`](docs/TESTING.md) §20.1–§20.3，分级设计见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §9.3。

**19.3 版本与产物**：`versionName = 1.5.1`、`versionCode = 2026100505`。这个 versionCode 曾被一个**从未发布**的 1.6.0 构建用过（同一把 release key），所以可以直接覆盖安装那个包；正常覆盖安装 1.5.0 即可。产物与量化数据见 [`docs/POWER_REPORT.md`](docs/POWER_REPORT.md) §9.6。

**19.4 本版不改什么**：判定语义、默认值、用户可见行为一律不变；`siminfo` 的写入顺序（ITelephony → 权威存储 → settings）与严格回读校验不变。

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
