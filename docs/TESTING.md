# NetPilot 测试指南

产物：`NetPilot-1.0.1-2026100401-debug.apk`
（`versionName = 1.0.1`、`versionCode = 2026100401`，debug 签名，`minSdk 34 / targetSdk 34`，包名 `com.katiusu.netpilot`）。

APK 只交付、不安装。要自己装的话：

```bash
adb install -r NetPilot-1.0.1-2026100401-debug.apk
```

---

## 0. 首次启动：权限说明 + 自启动 / 省电引导

### 0.1 权限：**每一项都会单独说明原因**，不会直接甩系统弹窗

首次进入会先弹一个理由说明框，逐条列出下面三项、各配一句原因；点「继续授权」才调起系统权限框。
若被拒绝，会再弹一个说明框列出「失去哪些功能」，并给「去设置」按钮（直达应用程式详情）。

| 权限 | 为什么需要 |
| --- | --- |
| `READ_PHONE_STATE` | 读信号强度与网络制式、识别双卡 subId。没有它 RSRP 显示「未知」、SIM 列表可能是空的、**降级判定永不触发** |
| `ACCESS_FINE_LOCATION` | Android 10+ 把蜂窝信号强度（含 **SINR**）归为位置相关数据。没有它 SINR 长期显示「未知」，假满格判定少一个维度 |
| `POST_NOTIFICATIONS` | 监控是前台服务。Android 13+ 没有通知权限时系统会直接拒绝服务常驻，后台监控会被杀 |

**验收**：三个框依次出现，文案能说清「不给会怎样」；「去设置」能跳到应用详情页。

### 0.2 自启动 + 省电策略

权限处理完之后弹第二个框，说明两件事：

- **自启动**：HyperOS/MIUI 默认禁止应用开机自启，不开的话重启手机后监控与自动降级不会恢复。
- **省电策略改「无限制」**：否则系统会在息屏后冻结应用，监控停止、切到 4G 后可能无法恢复原制式。

按钮按要求排布：**左边「取消」、右边「去设置」**。另有「申请忽略电池优化」快捷入口（已忽略时不显示）。

**验收**：左右按钮顺序正确；「去设置」优先跳 MIUI/HyperOS 自启动管理页，机型不支持时回落应用程式详情，不会崩。

---

## 1. 打通特权通道（决定能不能真的切制式）

设置页「特权通道」卡片会显示当前可用通道与原因：

- **Root**（优先级最高）：KernelSU / Magisk 里已给 NetPilot 授权即可。
- **Shizuku**（兜底）：先在 Shizuku 应用里启动服务，再回 NetPilot 点「申请 Shizuku 权限」。
- 显示「不可用」时点一次「重新探测」。

**验收点**：状态不是「不可用」，且「当前制式」能读出真实值（而不是「未知」）。

> 通道只影响**写制式 / 换卡**，不影响 Ping 探测。所以「Shizuku 模式下 Ping 一直失败」不是通道问题
> —— 原因与排查见第 6 节。

## 2. 手动制式切换

1. 首页或功能页选一个制式（如 `仅 4G/LTE` = 11、`4G/3G/2G 自动` = 9）→ 应用。
2. 交叉验证（任选）：
   ```bash
   adb shell settings get global preferred_network_mode      # SIM1
   adb shell settings get global preferred_network_mode1     # SIM2
   ```
   或 系统设置 → 关于手机 → SIM 卡状态 → 首选网络类型；或拨号盘 `*#*#4636#*#*` → 手机信息。
3. **QS 磁贴**：下拉快捷设置 → 编辑 → 把「网络制式」和「网络质量降级」拖出来。
   - 「网络制式」每次点击按 `NR_ONLY → NR_LTE → LTE_ONLY → LTE_GSM_WCDMA` 循环。
   - 「网络质量降级」是总开关，长按进监控页。

## 3. 网络质量降级（核心：假满格 / 信号过差）

两条**互相独立**的规则，任一命中都会把制式切到 4G。判定标准**全部可在功能页调整**，监控页的
「判定标准（当前生效值）」区会把生效值实时拼成人话显示出来（读一遍就知道现在按什么在判）。

| 规则 | 触发条件 | 默认值 |
| --- | --- | --- |
| **假满格** | RSRP **≥** 强信号阈值 **且**（Ping > Ping 阈值 **或** SINR < SINR 阈值 **或** Ping 全失败开关打开） | `-85 dBm` / `200 ms` / `0 dB` / 关 |
| **信号过差** | RSRP 读得到、且 **<** 弱信号阈值（**只看强度**，与 Ping、SINR 无关） | `-110 dBm`，独立开关**默认开** |

> `RSRP 读不到`（没给权限、未插卡、读数 UNKNOWN）**不算**信号差，不会触发第二条。
> 读不到 ≠ 信号弱，否则监控会无休止地把制式写成 4G。
>
> 两个 RSRP 阈值**语义不同**：强信号阈值（`np_fake5g_rsrp`）是「够强才有资格算假满格」，
> 弱信号阈值（`np_fake5g_weak_rsrp`）是「差到这个地步就直接降级」。若把弱阈值调到比强阈值还高，
> 生效值会自动收敛成「强阈值 − 1」，监控页会显示收敛后的值并给出提示（用户存的值不动）。

真实「假满格」不好复现，用**阈值调低法**在几十秒内必定触发：

1. 功能页 → 确认「启用网络质量降级」总开关是开的（**默认开启**；若之前关过就打开）。
2. 把参数改成「一定判假」的组合：**RSRP 强信号阈值 → `-140 dBm`**、**Ping 阈值 → `1 ms`**。
3. 等一个检测周期（**检测间隔**默认 120 秒，可调到 15 秒加速）。
4. 期望结果：监控页出现降级、写回 `preferred_network_mode` = 9、日志出现「检出网络质量差（假满格）」、
   `adb logcat -s NetPilot:V` 同步可见。
5. 验证「恢复」：把 RSRP 强信号阈值调回 `-50 dBm` → 连续 3 个周期后恢复。
6. 验证「信号过差」（**不用真去地下室**）：功能页打开「信号过差时也降级」（默认开），
   然后把**强信号阈值调到 `-50 dBm`**（高到没有哪张卡能达到 → 假满格规则自然失效）、
   **弱信号阈值调到 `-60 dBm`**。此时生效的弱阈值就是 `-60`（因为 `-60 < -50`，不会被收敛），
   而现实中 RSRP 通常在 `-80 ~ -110`，必然 **< -60** → 等一个周期即应命中，期望日志出现「检出信号过差」。
   > 反过来（弱阈值比强阈值还高）会被收敛成「强阈值 − 1」，永远命中不了 —— 这正是那个护栏的意义。
7. 验证「每卡门控」（见第 5 节）：把 SIM1 模式改成「跟随系统」→ 再等一轮 → 即便判定命中，**也不应再写 setting**；
   改回「网络质量自动降级」后立刻恢复可降级。**恢复方向不受门控限制**（否则会卡死在 4G）。
8. 验证「护栏」：当制式已经是降级目标（9）时，引擎不会再重复写 setting —— 日志里不该出现
   每轮都写一次的记录。

**还原**：强信号调回 `-85`、弱信号调回 `-110`、Ping 调回 `200`、间隔调回 `120`，或点首页「恢复制式」。
应用每次启动都会 `selfHeal()`，不会卡在 9。

## 4. 后台保活 + 「关闭所有服务」总开关

### 4.1 保活机制（三层，从可靠到尽力）

1. **`START_STICKY`**：系统因内存回收杀掉服务后，由系统自己重启 —— 这是最可靠的一层。
2. **`onTaskRemoved`**：用户从最近任务划掉应用时，10 秒后由 `AlarmManager` 把服务拉回。
3. **15 分钟心跳**：`setInexactRepeating` 的周期闹钟做兜底自修复。

**验收**：打开监控 → 从最近任务划掉 → 10 秒左右监控通知应重新出现，日志页出现拉回记录。

> 已知边界（系统限制，代码绕不过）：Doze 下不精确闹钟会被推迟/合并，15 分钟是目标值不是保证；
> 少数 OEM 不回调 `onTaskRemoved`；用户在系统里点「强行停止」后不会投递任何闹钟。
> 另外 `ACTION_STOP`（用户主动停）会把「希望运行」标记清掉，心跳**不会**再把服务拉回来 ——
> 否则用户永远关不掉。

### 4.2 服务总开关

设置页 → 「后台服务」卡片 → **「启用所有后台服务」**（默认开）。

关掉之后会同时停掉：**网络监控前台服务、保活心跳、被杀后自动重启、开机自启**；
Tasker / 快捷设置磁贴触发的操作也会一并失效。

**验收**：
1. 关掉总开关 → 监控通知消失；从最近任务划掉应用，10 秒后**不会**被拉回；重启手机后不会自启。
2. 再打开总开关 → 服务立刻恢复，心跳重新注册（卡片上的状态行会显示「运行中 / 心跳已注册」）。
3. QS 磁贴 / Tasker 在总开关关闭时应当**明确失败并写日志**，而不是静默无效。

## 5. 双卡策略与 Wi-Fi 规则库（本轮重做）

打开：首页 → 数据卡。

### 5.1 规则到底管什么（页面顶部有说明卡）

> 规则管理的是**默认数据卡**（哪张卡走流量）。连上指定 Wi-Fi 时，把默认数据卡切到**这条规则指定的那张卡**；
> 离开该 Wi-Fi 时按「离开回切」决定是否切回原来的卡。

匹配优先级：**BSSID 精确匹配 > SSID 相等（忽略大小写）> 空 SSID = 任意 Wi-Fi**。

### 5.2 每张卡的策略：四种模式（每张卡一个独立开关）

- 每张 SIM 一张卡片，**卡片头部是一个独立开关**（「启用此卡策略」）。
- 下面是模式下拉：

| 模式 | 含义 |
| --- | --- |
| `跟随系统` | 这张卡不参与任何自动降级 |
| `网络质量自动降级` | 这张卡启用「假满格 / 信号过差」降级（原「假 5G 自动降级」改名） |
| `连 Wi-Fi 时降为 4G` | 这张卡连上 Wi-Fi 时降到 4G |
| `自定义` | 上面两项**各自一个独立开关**，随便组合 |

- 选「自定义」时展开两行独立开关；总开关关掉时整块淡出，单个子项关掉时只有该行淡出
  （刻意不用 0.38 × 0.38 的叠乘，否则会淡到看不清）。

> **每卡策略是真的生效的，不只是界面状态**：监控侧的降级引擎是全局单实例、只作用于「当前默认数据卡」，
> 所以通过 `core/NetPilotEvents.kt` 的 `qualityDowngradeAllowed(subId)` 把「这张卡有没有资格降级」反查回
> `DataCardEngine.effectiveStrategies`（依赖倒置，不成环）。未接线时默认放行。
> **门控只拦「进入降级」，不拦「恢复」** —— 否则用户中途关策略会把这张卡永久锁在 4G。

**验收（单卡可测）**：

1. SIM1 选「网络质量自动降级」→ 按第 3 节的方法制造一次判定命中 → 应当真的降级。
2. 把 SIM1 改成「跟随系统」→ 再制造一次命中 → **不应**再降级
   （这条专门验证「每卡策略真的落地」，而不是只改了个界面状态）。
3. 选「自定义」：两个开关独立生效；只开「连 Wi-Fi 时降为 4G」时，网络质量判定不再触发降级。
4. 关掉 SIM1 的独立开关 → 模式与自定义开关的取值保留，但策略不生效。
5. 新建一条 Wi-Fi 规则：SSID 留空（任意 Wi-Fi）或填当前 Wi-Fi 名，目标卡选 SIM1，冷却 120 s。
   连上该 Wi-Fi → 等一个采样周期 → 默认数据卡应切到 SIM1；断开 → 按「离开回切」决定是否切回。
6. 规则编辑弹窗里各输入项**间距充足**、不挤在一起；触摸目标不重叠。

> 双卡说明：本机 SIM2 未插卡，双卡只能做**单卡回归**（页面不崩、策略能存能读）。
> 规则**不再由卡「订阅」**：每张卡的模式（含自定义的两个开关）决定它参与哪些降级，Wi-Fi 规则自带
> 「切到哪张卡」（`DataCardRule.targetSubId`，可选「任意卡」= 只切制式不换卡）。
> 旧版本的「卡订阅规则」数据在首次读取时自动做兼容映射，不会导致配置丢失。

## 6. Ping 探测：以前为什么「一直失败」，现在怎么排查

**根因是两个问题叠加**：

1. **旧实现用 TCP 握手**探 `223.5.5.5:443`、`119.29.29.29:443`、`114.114.114.114:53`、`8.8.8.8:443`。
   这四个在国内很容易**整片失效**：`114.114.114.114` 的 TCP 53 常被运营商拦截，前两个是 DoT 端口同样常被过滤，
   `8.8.8.8` 基本被黑洞丢弃。全灭时界面只显示「Ping 失败」，而且最坏要阻塞 `4 × timeoutMs`（默认 10 秒）。
2. **Android 9+ 默认禁止明文 HTTP**。改成 HTTP 目标之后，如果 Manifest 里没有
   `usesCleartextTraffic` / `networkSecurityConfig`，`HttpURLConnection` 会直接抛
   `java.net.UnknownServiceException: CLEARTEXT communication to ... not permitted by network security policy`
   —— 四个目标**在发出任何网络包之前**就被系统拦掉，表现就是「Ping 永远测不到」。
   本轮已新建 `app/src/main/res/xml/network_security_config.xml`（`base-config cleartextTrafficPermitted="true"`）
   并在 Manifest 的 `<application>` 上注册。
   - 为什么放行明文：全仓**唯一**的网络访问点就是这一处 Ping 探测（暴露面为零）。
   - 为什么不改成 https：TLS 握手多一个 RTT，会系统性抬高 Ping 值，污染默认 `200 ms` 阈值。
   - 为什么用 `base-config` 而不是域名白名单：Ping 目标在功能页里是**用户可改**的。

**现在**用的是 **HTTP 首字节时间**，目标按优先级：

1. `http://www.bing.com/`（**主目标**，必应；国内可直连、响应稳定）
2. `http://cn.bing.com/`（必应国内入口，同一家的备用）
3. `http://www.baidu.com/`
4. `http://connectivitycheck.platform.hicloud.com/generate_204`（厂商联网校验地址，系统自己就在轮询它）

> 本轮把「厂商校验地址优先」改成「必应优先」：hicloud 是华为的域名，非华为机型不保证解析得到。
> 必应会对 `http://` 返回一个 30x 跳转，本实现不跟随重定向，所以拿到状态行就停、不影响读数。

参数：`connectTimeout = readTimeout = 超时`、不跟随重定向、不缓存、`Connection: close`；
**总预算 = 3 × 超时**（默认 7.5 秒），目标快速失败时继续试下一个，真在超时才提前收手。

**排查**：监控页的 Ping 行标题显示探测主机名；失败时给出**精确原因**，形如
`http://www.bing.com/ 失败：java.net.SocketTimeoutException: connect timed out`。

- `SocketTimeoutException` → 网络确实不通或该地址被挡，换测试网络再看。
- `UnknownHostException` → DNS 有问题（所有目标都是域名，没有裸 IP）。
- `ConnectException` → 连接被拒。
- `UnknownServiceException` 且 message 含 `CLEARTEXT` → 明文放行配置没生效（本轮已修；界面会额外追加
  「系统禁止明文 HTTP」的提示，出现它说明 `network_security_config.xml` 没被打进包）。
- 四条目标全失败 → 说明**这台设备当前真的上不了网**，不是应用的问题。

### 6.1 SINR 读不到时怎么排查（本轮新增）

监控页的 SINR 行不再只显示「未知」，而会给出**结构化原因**：

| 显示的原因 | 含义 / 怎么办 |
| --- | --- |
| 未检测到蜂窝网络 | 没插卡或没驻网，与权限无关 |
| 缺少「电话状态」权限 | 去授权 `READ_PHONE_STATE` |
| 缺少「精确位置」权限 | Android 10+ 把信号强度含 SINR 归为位置数据，必须授权 `ACCESS_FINE_LOCATION` |
| 定位服务未开启 | 权限给了但系统定位开关关着，SINR 也读不到（去打开定位） |
| 当前制式不上报 SINR | 现在驻在只上报强度的制式（如纯 2G/3G） |
| LTE rssnr / NR SS-SINR 均未上报 | modem 本身不报（部分机型/运营商固件如此），只能在别的手机上对比确认 |
| 未上报 | 兜底：以上都不成立但就是拿不到 |

读取顺序是**两级回退**：先 `TelephonyManager.signalStrength.cellSignalStrengths`（NR 取 `ssSinr`、LTE 取 `rssnr / 10`），
失败再回退 `allCellInfo`（需要 `READ_PHONE_STATE` + `ACCESS_FINE_LOCATION` + 定位服务开启）。
**验收**：在有 5G 覆盖的地方进监控页，SINR 要么显示数值，要么显示上面某一条具体原因，**不应**只显示「未知」四个字。

## 7. 系统兼容性（为什么不做按厂商分支，且一切都是实测值）

参考项目 Network_Enhance 有一大堆「小米要 `nr_sa_mode`→`nr_mode`、vivo 跳过、三星/华为 PNM 写入可能被忽略」
的定制系统分支。**本应用没有实现这些分支**，原因有二：

1. 我们写的是 `settings put global preferred_network_mode[1]`（**不是** `nr_sa_mode`），那些分支的前提在这里不成立；
2. 各厂商定制系统的底细无法在没有真机验证的前提下确认，硬猜分支只会引入新 bug。

取而代之的是「防 bug 的那半边」——设置页 →「系统兼容性」卡片。**这里没有任何写死的展示值**：

- **「写入后回读校验」开关（默认开）**：写完 setting 后再 `settings get` 读回来比对。
  给「某些机型回读本身不准、校验反而误判失败」留后路，关掉即写一次就返回。
- **只读详情（实测）**：厂商 / 型号（**直接显示系统报出的值**，不做拼接）/ Android 版本 / **OS 版本**。
  OS 版本按 `ro.miui.ui.version.name` → `ro.mi.os.version.name` → HyperOS 属性 → `ro.build.version.incremental`
  → `Build.VERSION.RELEASE` 依次回退，**都取不到就显示「未知」**，不会编一个好看的假值。
- **真实探测**（`SystemCompatInfo.probe`，整体跑在 `Dispatchers.IO`）：
  - 真探测当前特权通道与原因（拿不到显示「未检测到」，并把 `Root：…；Shizuku：…` 的真实结论作为脚注）；
  - 真跑一次 `getmode` 来判断实际走的是 `app_process → PrivilegedCli` 还是 `settings` 兜底；
  - **写入命令串由通道 + 真实 subId/卡槽 + `context.packageCodePath` 现场拼出来**，推导不出就是空串 → 显示「未知」；
  - 再真执行一次读，给出四态：`OK（读回 <原始值>）` / `FAILED（失败：<原始输出>）` / `SKIPPED（没有可用通道，未执行）` / `NO_TARGET（没能确定写入目标）`。
- **脚注**：明确「未知 = 探测失败或没取到，不代表没问题」。
- 探测只在进入设置页时跑一次（`LaunchedEffect` 在 `LazyColumn` 之外），滚动不会反复起 shell；
  探测期间整卡显示「检测中…」，任何失败都只退化为「未知」。

**验收**：
1. 卡片显示的厂商 / 型号 / Android 版本 / **OS 版本**与「设置 → 关于手机」一致；取不到的字段显示「未知」，
   **不应**出现某个固定字符串或模板占位符。
2. 「写入命令」那条是**本机真实命令串**（含真实包路径与卡槽编号）。
3. 关闭回读校验后切制式仍然工作（只是不再校验），开关状态在卡片上实时反映。

## 8. Tasker 联动

命令（Tasker → 系统 → 发送意图）：

| 动作 | Action |
| --- | --- |
| 设制式 | `com.katiusu.netpilot.action.SET_NETWORK_MODE`（extra `netpilot.mode_value`） |
| 用预设 | `com.katiusu.netpilot.action.SET_PRESET`（extra `netpilot.preset`，如 `lte_only`） |
| 自动降级开关 | `com.katiusu.netpilot.action.SET_AUTO_DOWNGRADE` / `TOGGLE_AUTO_DOWNGRADE` |
| 换数据卡 | `com.katiusu.netpilot.action.SET_DATA_SIM` / `SWITCH_DATA_SIM` |
| 锁 LTE | `com.katiusu.netpilot.action.LOCK_LTE` |
| 立即采样 | `com.katiusu.netpilot.action.SAMPLE_NOW` |
| 取状态 | `com.katiusu.netpilot.action.GET_STATUS` |

事件（Tasker → 配置文件 → 事件 → 系统 → 意图接收）：
`com.katiusu.netpilot.event.{RESULT,DOWNGRADED,RECOVERED,MODE_CHANGED,DATA_SIM_CHANGED,SIGNAL_SAMPLED}`

Locale 插件：Tasker → 任务 → 插件 → NetPilot。完整 extra 键表见 `docs/TASKER.md`。

> 本轮只改了**用户可见文案**（「假 5G」→「网络质量」），**action / 条件 / extra 键名一个都没动**，老配置继续可用。
> 已知偏差：在 NetPilot 界面里手动切制式 / 换卡**不会**发 `MODE_CHANGED` / `DATA_SIM_CHANGED`。
> 总开关关闭时，这些入口会明确失败并写日志。

## 9. 配置导入导出

设置页 → 导出配置 → 改几项设置（主题、降级阈值、每卡模式与自定义开关、Wi-Fi 规则）→ 再导入
→ 期望**外观、阈值、双卡策略（含自定义开关）、Wi-Fi 规则一起回来**。

> v2 格式分 `prefs` / `app_settings` / `datacard` 三段。
> `datacard.policies[]` 保存的是 `mode` + `customNetworkQuality` / `customWifiDowngrade`。
> 旧备份里的 `AUTO_DOWNGRADE` 会映射成 `NETWORK_QUALITY`、`FIXED_MODE`（固定制式，功能已删除）映射成
> `FOLLOW_SYSTEM`（安全退化），所以**老备份导入不会丢卡策略**。旧的扁平 JSON 仍可导入。

## 10. 日志与排障

- 应用内：日志页（可清空）。
- 命令行：`adb logcat -s NetPilot:V`
- 无输出时先确认特权通道是否可用（切制式、换卡全部走通道）。

## 11. 一键还原

- 应用内：首页「恢复制式」+ 功能页参数调回默认 + 「重置降级状态」。
- 后台不留东西：设置页「关闭所有后台服务」。
- 彻底清干净：卸载应用。
- 网络制式不会残留：启动时的 `selfHeal()` 会把完整制式写回。

## 12. 关于页

- 版本行显示 `1.0.1 (2026100401)`（`versionName` + `longVersionCode`）。
- **GitHub 仓库**（<https://github.com/katiusu/NetPilot>）是**单独一张卡片**，在「许可证 / 开源依赖」
  那张卡**上方**，两者不混在一起；下方那张卡放许可证与依赖两个入口。
- 三个入口点击都应正常打开浏览器（没有可用浏览器时弹 Toast，**不能崩页**）。
- **验收**：GitHub 卡里**不该**出现许可证 / 依赖行；触摸目标与相邻行一致（≥48dp），点击有反应。

## 13. 应用图标与内存（1.0.1）

### 13.1 图标

1. 桌面 / 抽屉图标是**黑底白色「信号格 + 加号」**，与「应用信息」页里显示的图标一致。
2. 圆形图标场景（部分系统 / 启动器）不应出现白边或方角（`ic_launcher_round` 与 `ic_launcher` 都已提供）。
3. Android 13+ 打开「设置 → 壁纸与个性化 → 主题图标」后，图标应变成**单色跟随壁纸着色**——这就是 `ic_launcher_monochrome` 层。

### 13.2 回收残留的 Shizuku 特权进程

1. 前提：当前特权通道是 **Shizuku**（Root 通道不会产生 `np_service` 进程）。
2. 打开应用触发一次通道探测，然后 `adb logcat -s NetPilot`，应能看到
   `pruned N stale Shizuku user service process(es)`（N > 0 表示这次真的回收了残留进程）。
3. 想直接在设备上核对：`adb shell ps -A | grep np_service` —— 正常情况下**只有 1 个**（当前绑定的那个），不再是多个。
4. 一个都没有、或没有 log：说明当前没走 Shizuku 通道，或系统不允许读 `/proc`。**只影响这条优化，不影响任何功能**。

### 13.3 日志落盘减量

1. 日志页仍应能看到最多 **400 条**历史记录。
2. 杀掉应用再打开：能恢复的最近日志约 **120 条**（刻意的取舍：换更小的 SharedPreferences 与更少的 GC）。
3. 单条超长文本（异常堆栈等）会被截断到 2000 字符，不丢条目、只截内容。

---

## 14. 发布签名（release）

### 14.1 密钥库与口令

- 密钥库 `release.keystore`（PKCS12，别名 `netpilot`，RSA 4096，SHA256withRSA，有效期至 2056-09-26）。
- 证书 SHA-256 指纹：`34:10:08:75:B4:5D:7C:4D:C9:92:80:30:B3:32:9B:54:90:86:9A:23:61:55:F9:CE:08:B1:DC:70:C7:43:4C:4C`。
- 口令与别名写在 `keystore.properties`（**已被 .gitignore 排除，永远不要提交**），格式：

```properties
storeFile=release.keystore
storePassword=<口令>
keyAlias=netpilot
keyPassword=<口令>
```

- ⚠️ **务必离线备份 `release.keystore` 与口令**：两者缺一，以后就无法再发布"可覆盖安装"的更新（签名不同，用户必须先卸载旧版）。
- `app/build.gradle.kts` 里的 `signingConfigs` 仅在 `keystore.properties` 存在时启用；缺失时 release 自动回落 debug 签名，保证别人 clone 后仍能 `assembleRelease`。

### 14.2 打包与自检

```bash
bash _build.sh :app:assembleRelease --max-workers=1 --no-daemon
# 产物：app/build/outputs/apk/release/app-release.apk
```

```bash
/opt/android-sdk/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# 期望：Verifies；V2 Signer 证书 DN = CN=NetPilot, OU=Mobile, O=katiusu…，SHA-256 = 34100875…4c4c
python3 -c "import zipfile;n=zipfile.ZipFile('app/build/outputs/apk/release/app-release.apk').namelist();print(len(n),'AndroidManifest.xml' in n,'resources.arsc' in n)"
# 期望：152 True True
```

### 14.3 两个已踩过的坑

1. **`optimizeReleaseResources` 会静默打出坏包（根因已实证）。**
   AGP 9.4.1 的资源优化任务调用的是

   ```bash
   aapt2 optimize <linked .ap_> --shorten-resource-paths \
     --resource-path-shortening-map=<路径> -o <resources-release-optimize.ap_>
   ```

   注意是 `--选项=值` 形式。而本容器经 `android.aapt2FromMavenOverride` 强制的 build-tools 36.0.0
   （aarch64）aapt2 只认 `--选项 值`，于是打印 `unknown option '--resource-path-shortening-map=…'`
   并非零退出、完全不产出文件；AGP 既不校验 aapt2 退出码、也不校验声明的产物是否存在，
   照样写 `output-metadata.json` 报成功。`packageRelease` 拿到一个不存在的资源文件，最终打出
   **没有 `AndroidManifest.xml`、`resources.arsc`、`res/`** 的 APK（`apksigner verify` 报 `Missing AndroidManifest.xml`）。
   - **当前默认选择**：`gradle.properties` 设 `android.enableResourceOptimizations=false` 关掉该任务，
     复用 `processReleaseResources` 的 linked-resources `.ap_`。实测优化只省 **6.4 KB**
     （33,076,005 → 33,069,588 B），不值得为它承担静默坏包风险。
   - **真修（已在本容器验证）**：给 aapt2 套一个只做参数改写的壳（把 `--resource-path-shortening-map=x`
     拆成 `--resource-path-shortening-map x`，其余参数原样 `exec`），并让 `$GRADLE_USER_HOME/gradle.properties`
     的 `android.aapt2FromMavenOverride` 指向这个壳。之后 `-Pandroid.enableResourceOptimizations=true`
     能正常产出 `resources-release-optimize.ap_`（835,253 B）与合法 APK（152 条目、含 manifest/arsc、v2 验签通过）。
     本容器已装：`/root/aapt2-shim/aapt2`。
   - **识别方法**：产物条目数应为 152 且含 manifest/arsc；若只有 85 个条目、没有 res，就是坏包，不要发布。
     `_build.sh` 现在会在构建后自动做这项自检（`APK_CHECK:` 行）。
2. **Gradle 偶发 `FileHasher … java.io.IOException: Operation not permitted`。** 容器里文件监视（inotify）在 sdcardfs 上不稳定，已设 `org.gradle.vfs.watch=false`；若仍出现，先杀干净残留的 `GradleDaemon` 进程再重试。
