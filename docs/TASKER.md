# NetPilot × Tasker / Locale 插件接入文档

> 本文里的 **action 名、extra 键、类型** 全部与代码中的
> `app/src/main/java/com/katiusu/netpilot/core/tasker/TaskerContract.kt` 一一对应。
> 代码改了本文却没改，属于 bug —— `TaskerContract.TASKER_ACTION_SPECS` 就是本文
> 第 3 节表格的「单一事实来源」（Locale 编辑界面也读它）。

NetPilot 对外提供两条自动化通道，可以单独用，也可以混用：

| 通道 | 方向 | 用途 | 需要配置在哪 |
| --- | --- | --- | --- |
| **广播命令** | Tasker → NetPilot | 切制式、换默认数据卡、锁 4G、开关自动降级、读状态 | Tasker「发送意图」动作 |
| **事件广播** | NetPilot → Tasker | 回执、降级/恢复、制式变化、换卡、每次采样 | Tasker「Intent Received」事件 |
| **Locale 插件** | 双向（Tasker 托管配置） | 把上面两条包成 Tasker 原生「插件」动作/条件 | Tasker「插件 → NetPilot」 |

---

## 1. 前提条件（以及没授权时会怎样）

1. **NetPilot 已安装并至少启动过一次**（`NetPilot.install()` 会初始化配置、监控引擎与特权层）。
   本插件的接收器都是静态注册的，进程可能被广播冷启动，代码里已经处理（接收器每次都会
   幂等补挂事件出口）。
2. **特权通道**：切制式、换默认数据卡、锁 4G 这三类动作最终要写
   `settings put global preferred_network_mode*` / 调 `ITelephony`，必须有 **Root** 或 **Shizuku**：
   - Root：设备已 root，且 su 授权已给 NetPilot；
   - Shizuku：Shizuku 正在运行，地址授权已给 NetPilot（应用内会请求）。
   - **两个都没有时**：命令**不会崩、也不会静默**。执行器返回失败，记一条 WARN 日志到
     NetPilot 日志页，并回执 `netpilot.result_ok=false`，`netpilot.result_message`
     写明「请检查 Root / Shizuku 是否已授权」。Tasker 侧可以直接用回执做分支。
3. **不需要特权**的动作：`TOGGLE_AUTO_DOWNGRADE`、`SET_AUTO_DOWNGRADE`、
   `GET_STATUS`、`SAMPLE_NOW`（只读当前状态；`SAMPLE_NOW` 会触发一次采样，需要电话权限，
   一般已随应用授予）。
4. **不需要给 NetPilot 加任何新权限**：两个 receiver 用 `android:exported="true"` 暴露，
   **故意不设 `android:permission`** —— 设了之后发送方 Tasker 必须持有该权限才能调用，
   插件就直接失效了。代价见第 9 节「安全取舍」。
5. **Android 版本**：targetSdk 34 / minSdk 34。静态接收者收不到「后台应用发的隐式广播」
   这条限制（Android 8 起）**只影响发送方是普通 App 的隐式广播**；Tasker 用「发送意图」
   时把 Package 或 Class 填上就是定向广播，后台也能送达（见第 3 节）。

---

## 2. 广播命令总表（Tasker → NetPilot）

所有 action 都在 `com.katiusu.netpilot.action.*` 命名空间下。

| # | Action | 参数（类型） | 说明 |
| --- | --- | --- | --- |
| 1 | `SET_NETWORK_MODE` | `netpilot.sub_id` (Int)、`netpilot.mode_value` (Int) | 按 RIL 裸值切制式（0..33） |
| 2 | `SET_PRESET` | `netpilot.sub_id` (Int)、`netpilot.preset` (String) | 按预设名切制式 |
| 3 | `TOGGLE_AUTO_DOWNGRADE` | 无 | 反转「假 5G 自动降级」开关 |
| 4 | `SET_AUTO_DOWNGRADE` | `netpilot.enabled` (Boolean) | 显式设置自动降级开关 |
| 5 | `SET_DATA_SIM` | `netpilot.sub_id` (Int) | 把默认数据卡切到指定卡 |
| 6 | `SWITCH_DATA_SIM` | 无 | 双卡一键互换默认数据卡 |
| 7 | `LOCK_LTE` | `netpilot.lock` (Boolean) | true=锁「仅 4G」，false=恢复运营商默认 |
| 8 | `SAMPLE_NOW` | 无 | 立即采样一次并回执快照 |
| 9 | `GET_STATUS` | 无 | 只读当前状态快照（不触发采样） |

**预设名**（`netpilot.preset`，大小写不敏感，`-` 与空格等价于 `_`）：

| 预设名 | 效果 |
| --- | --- |
| `5g` / `5g_auto` / `nr_auto` / `auto_5g` | 5G/4G 自动（NR + LTE） |
| `5g_only` / `nr_only` / `only_5g` / `sa` | 仅 5G (NR) |
| `5g_4g` / `nr_lte` | 5G/4G 自动 |
| `4g` / `4g_auto` / `lte_auto` / `auto_4g` | 4G/3G/2G 自动 |
| `4g_only` / `lte_only` / `only_4g` / `only_lte` | 仅 4G (LTE) |
| `3g` / `3g_auto` / `wcdma` / `3g_only` / `wcdma_only` | 3G |
| `2g` / `2g_only` / `gsm` / `gsm_only` | 仅 2G |

**常用 RIL 裸值**（`netpilot.mode_value`）：`11`=仅 4G (LTE)、`9`=4G/3G/2G 自动、
`10`=4G/3G/2G 自动(全制式)、`23`=仅 5G (NR)、`24`=5G/4G 自动、
`26`=5G/4G/3G/2G 自动(联通)、`27`=5G/4G/3G/2G 自动(电信)、
`32`=5G/4G/3G/2G 自动(移动)、`1`=仅 2G (GSM)、`2`=仅 3G (WCDMA)。

> ⚠️ 注意 `9` 的官方名是「4G/3G/2G 自动」，**不含 NR**；要 5G 自动请用 `24`/`26`/`27`/`32`。
> 这是「假 5G」判定里最容易搞错的一档，改制式时别把 `11` 和 `9` 混着当「5G 自动」。

---

## 3. 每条命令怎么发

### 3.1 adb（调试 / 直接验证权限链路）

```bash
# 1) 按裸值切制式：卡 1 锁「仅 4G」
adb shell am broadcast -a com.katiusu.netpilot.action.SET_NETWORK_MODE \
    --ei netpilot.sub_id 1 --ei netpilot.mode_value 11

# 2) 按预设切制式
adb shell am broadcast -a com.katiusu.netpilot.action.SET_PRESET \
    --ei netpilot.sub_id 1 --es netpilot.preset 4g_only

# 3) 反转自动降级开关
adb shell am broadcast -a com.katiusu.netpilot.action.TOGGLE_AUTO_DOWNGRADE

# 4) 显式开/关自动降级
adb shell am broadcast -a com.katiusu.netpilot.action.SET_AUTO_DOWNGRADE \
    --ez netpilot.enabled true

# 5) 默认数据卡切到卡 2
adb shell am broadcast -a com.katiusu.netpilot.action.SET_DATA_SIM \
    --ei netpilot.sub_id 2

# 6) 双卡一键互换
adb shell am broadcast -a com.katiusu.netpilot.action.SWITCH_DATA_SIM

# 7) 游戏模式：锁「仅 4G」/ 恢复
adb shell am broadcast -a com.katiusu.netpilot.action.LOCK_LTE --ez netpilot.lock true
adb shell am broadcast -a com.katiusu.netpilot.action.LOCK_LTE --ez netpilot.lock false

# 8) 立即采样一次
adb shell am broadcast -a com.katiusu.netpilot.action.SAMPLE_NOW

# 9) 读状态（不采样）
adb shell am broadcast -a com.katiusu.netpilot.action.GET_STATUS
```

类型开关：`--es` = String，`--ei` = Int，`--ez` = Boolean。

**背景限制提醒**：上面这些是**隐式**广播。如果 NetPilot 处于后台，Manifest 静态接收者
收不到普通 App 发的隐式广播 —— 但**从 adb shell（uid 2000）发的广播不受这条限制**，
所以排查时可以直接用上面的命令。要在 Android 14 上百分百确定送达，加上组件名更稳：

```bash
adb shell am broadcast -n com.katiusu.netpilot/.core.tasker.TaskerCommandReceiver \
    -a com.katiusu.netpilot.action.SET_PRESET --ei netpilot.sub_id 1 --es netpilot.preset 4g_only
```

（`-n` 里的 `.core.tasker.TaskerCommandReceiver` 会展开成
`com.katiusu.netpilot.core.tasker.TaskerCommandReceiver`。）

### 3.2 Tasker「发送意图」（Send Intent）

Tasker → 任务 → `+` → **发送意图（Send Intent）**，字段这样填：

| 字段 | 填什么 |
| --- | --- |
| 动作 / Action | 例如 `com.katiusu.netpilot.action.SET_PRESET` |
| 包 / Package | `com.katiusu.netpilot` ← **建议必填**（避开后台隐式广播限制） |
| 类 / Class | 留空；想更稳可填 `com.katiusu.netpilot.core.tasker.TaskerCommandReceiver`（与 Package 二选一即可，也可以同时填） |
| 目标 / Target | `广播接收器 / Broadcast Receiver` |
| 额外 / Extra | 一行一个 `键:值`，例如 `netpilot.sub_id:1`、`netpilot.preset:4g_only`、`netpilot.enabled:true` |
| 类别 / Category、数据类型 / Mime、数据 / Data | 都留空 |

Extra 的**类型**：Tasker 按值自动判断 —— 纯数字（`1`）→ Int，`true`/`false` → Boolean，
其余 → String。`netpilot.sub_id:1` 会以 Int 送达，正好是接收器期望的 `getInt`。
**不要写成 `01` 或 `1.0`**，那会被当成字符串或 Double，接收器读不到就按「缺参数」处理，
回执里会说缺少 `netpilot.sub_id`。

任务失败排查：接收器的回执广播 `com.katiusu.netpilot.event.RESULT` 里有中文原因，
用下面的「Intent Received」接一下就能看到（`netpilot.result_message`）。

### 3.3 更省事的方式：Locale 插件

推荐日常用插件（第 6 节），参数在图形界面里选，不用手打键名和类型。

---

## 4. 事件回执（NetPilot → Tasker）

所有事件在 `com.katiusu.netpilot.event.*` 命名空间下，全部是**隐式**广播
（不带 package —— 带上了只有 NetPilot 自己收得到，Tasker 永远收不到）。

| Action | 何时发 | 附带字段 |
| --- | --- | --- |
| `event.RESULT` | **每个命令执行完** | `netpilot.result_ok` (Boolean)、`netpilot.result_message` (String)；`GET_STATUS`/`SAMPLE_NOW` 还带快照字段 |
| `event.DOWNGRADED` | 未降级 → 已降级 | `netpilot.downgraded=true` + 快照字段 |
| `event.RECOVERED` | 已降级 → 未降级 | `netpilot.downgraded=false` |
| `event.MODE_CHANGED` | 通过广播/插件切制式成功后 | `netpilot.sub_id` (Int)、`netpilot.mode_value` (Int) |
| `event.DATA_SIM_CHANGED` | 通过广播/插件换默认数据卡成功后 | `netpilot.sub_id` (Int) |
| `event.SIGNAL_SAMPLED` | 每次采样（= 监控循环的每个 tick） | 快照字段 + `netpilot.downgraded` |

**快照字段**（随事件一起发，读不到的字段干脆不带这个 extra）：

| Extra | 类型 | 说明 |
| --- | --- | --- |
| `netpilot.sub_id` | Int | 采样对应的卡 |
| `netpilot.network_type` | String | 展示用制式名，如 `NR_SA` / `LTE` / `WLAN` / `未知` |
| `netpilot.rsrp` | Int | RSRP（dBm，负数）；读不到时**不带** |
| `netpilot.sinr` | Int | SINR（dB）；读不到时不带 |
| `netpilot.ping_ms` | Int | 一次 TCP 握手往返毫秒；无响应时不带 |
| `netpilot.downgraded` | Boolean | 当前是否处于降级态 |
| `netpilot.status_text` | String | 单行状态，形如 `5G 自动 · RSRP -92 dBm · 35 ms · 未降级`（仅回执里的状态类命令） |
| `netpilot.channel` | String | 当前特权通道：Root / Shizuku / 无 |

> 「读不到」与「值为 0」是两回事：NULL 信号时不会发 `netpilot.rsrp`，
> 所以 Tasker 里判断要写成 `%rsrp 不匹配 *`（或先用 `%rsrp` 是否为空/未设置来判断），
> 不能直接 `%rsrp > -100` 就当读到了 —— 这与「假 5G」判定踩的是同一个坑。

### 4.1 在 Tasker 里接收：用「Intent Received」事件（必须是动态注册）

Tasker → 配置文件/Profile → `+` → **事件 / Event** → **系统 / System** →
**收到的意图 / Intent Received**，字段：

| 字段 | 值 |
| --- | --- |
| 动作 / Action | 例如 `com.katiusu.netpilot.event.RESULT`（可只写 `com.katiusu.netpilot.event.*` 通配） |
| 类别 / Category | 留空 |
| 计划 / Scheme、主人 / Mime Type | 留空 |

**为什么必须用「Intent Received」而不能指望 NetPilot 的广播叫醒 Tasker**：
Android 8（API 26）起，**后台应用的静态（Manifest 声明）接收者收不到隐式广播**。
Tasker 的「Intent Received」在**运行期间**用 `registerReceiver` 动态注册，
动态接收者不受这条限制，所以能收到 NetPilot 发的事件；
代价是 **Tasker 没在运行时事件会丢**（它只在自己进程活着时注册）。
两种应对：

1. 让 Tasker 保持运行（关闭它的电池优化限制；或用「前台服务」类插件保活）；
2. 对「必须不丢」的场景别依赖事件，改成 Tasker 定时轮询：
   发 `GET_STATUS`/`SAMPLE_NOW`，再接 `event.RESULT` 读字段 —— 但轮询一样需要 Tasker 在跑，
   区别只是「由谁发起」。

事件里取字段用 `%字段名`：例如 `netpilot.result_message` 在 Tasker 里读
`%result_message`（Tasker 会把 extra 键去掉前缀？**不会** —— Tasker 对 Intent 的 extra
用原始 key，含点号，所以请用 `%netpilot_result_message`。
Tasker 把 extra 键里的 `.` 映射成 `_`，因为变量名不允许点号。**看到的名字以 Tasker
「变量」界面实际显示的为准**（不同 Tasker 版本对非法字符的替换规则可能不同）。
最稳的做法：在「Intent Received」任务里加一个「弹出提示」显示
`%netpilot_result_message` 或直接用 `Flash %netpilot_result_ok` 试一遍，
确认 Tasker 实际生成的名字后再写进条件。

---

## 5. Locale 插件

Tasker 的「插件」动作/条件就是兼容 Locale 协议的插件机制。

### 5.1 添加路径

- **作为动作**：Tasker → 任务 → `+` → **插件 / Plugin** → **NetPilot**
  → 点右侧「配置 / 编辑」图标 → 选择「执行动作」→ 选动作与参数 → 保存。
- **作为条件**：Tasker → 配置文件/任务里的「状态 / State」
  → **插件 / Plugin** → **NetPilot** → 配置 → 选「判断条件」→ 保存。

配置界面里三种内容：

1. **插件类型**：执行动作 / 判断条件；
2. **执行动作**（类型=动作时）：动作下拉（9 个，与第 2 节表格一致）、参数
   （SIM 卡下拉 / subId 输入框、制式下拉、预设下拉、开关）；
3. **判断条件**（类型=条件时）：三选一 ——
   - `当前已降级（假 5G 被降为 4G）`
   - `自动降级开关已打开`
   - `信号监控正在运行`

判断条件走 `QUERY_CONDITION`，接收器用 `RESULT_CONDITION_SATISFIED(16)` /
`UNSATISFIED(17)` 回答；**判断不了的一律回 17（不满足）** —— 宁可 Tasker 什么都不做，
也不要基于一个不确定的状态动手。

### 5.2 配置里到底存了什么

保存时 NetPilot 回传一个只含基本类型的 Bundle（Tasker 存进它自己的任务里，执行时原样发回）：

| 键 | 类型 | 说明 |
| --- | --- | --- |
| `netpilot.plugin_action` | String | 要执行的接收侧 action（第 2 节 9 个之一） |
| `netpilot.plugin_condition` | String | 要判断的条件键（`downgraded` / `auto_downgrade_enabled` / `monitor_running`） |
| `netpilot.sub_id` | Int | 目标卡 |
| `netpilot.mode_value` | Int | 制式裸值 |
| `netpilot.preset` | String | 预设名 |
| `netpilot.enabled` | Boolean | 自动降级开关目标值 |
| `netpilot.lock` | Boolean | 是否锁「仅 4G」 |

**动作与条件走同一份执行逻辑**（`TaskerCommandExecutor`），所以插件能做的事 =
广播能做的事，文档不会出现两套参数说明。

### 5.3 用 adb 直接模拟插件广播（排错用）

```bash
# 模拟「执行动作：锁仅 4G」
adb shell am broadcast -a com.twofortyfouram.locale.intent.action.FIRE_SETTING \
    --es netpilot.plugin_action com.katiusu.netpilot.action.LOCK_LTE --ez netpilot.lock true

# 模拟「判断条件：是否已降级」（结果码 16/17 在 logcat / dumpsys 里看）
adb shell am broadcast -a com.twofortyfouram.locale.intent.action.QUERY_CONDITION \
    --es netpilot.plugin_condition downgraded
```

> Bundle 版（`--es ... --ez ...` 之外）用 `am broadcast --es netpilot.plugin_action ...` 是
> 把参数放在**广播 Intent 自身**的 extras 里，接收器两种都能读（它直接从 Intent 读
> `netpilot.*`，不依赖外层 Bundle），所以上面的命令可以直接验证动作逻辑。

---

## 6. 两个完整示例

### 示例 A：到家连上家庭 Wi-Fi，自动把默认数据卡切到卡 2

需求：卡 1 是主力 5G 卡（流量贵），卡 2 是保号大流量卡；在家用 Wi-Fi 时把**默认数据卡**
换成卡 2，离开家换回卡 1（这样家里的备用机/热点场景不烧卡 1 的流量）。

**准备**：先在 NetPilot 首页记下两张卡的 `subId`（一般卡 1 = 1，卡 2 = 2；
双卡双待的 subId 不保证与卡槽号一致，**以首页显示为准**）。

**Tasker 配置（插件版，推荐）**

1. Profile：`状态 / State` → `网络 / Net` → `WiFi 已连接 / Wifi Connected`
   - SSID 填你的家庭 Wi-Fi 名（**不要**填 BSSID）
   - 结束任务（Exit Task）勾上
2. 进入任务：`插件 / Plugin` → `NetPilot` → 配置
   - 插件类型 = `执行动作`
   - 动作 = `切换默认数据卡`
   - SIM 卡 = `卡 2 · … · subId 2`
   - 保存（Tasker 列表里会显示 `NetPilot：默认数据卡切到 subId 2`）
3. 退出任务：同样加一个 `插件 → NetPilot`，动作 = `切换默认数据卡`，SIM 卡选卡 1。

**Tasker 配置（广播版，等价）**

- 进入任务：`发送意图` → Action `com.katiusu.netpilot.action.SET_DATA_SIM`、
  Package `com.katiusu.netpilot`、Target = 广播接收器、Extra `netpilot.sub_id:2`
- 退出任务：同上，Extra 改成 `netpilot.sub_id:1`

**不要踩的坑**

- 「切换默认数据卡」只改**默认数据卡（DDS）**，不改 Wi-Fi 开关；
  想同时关移动数据请在 NetPilot 侧用「双卡独立策略」，或用 Tasker 的「移动数据」动作 —— 
  但**本插件不做移动数据开关**（不在 NetPilot 的能力范围内）。
- 换卡需要特权通道；没 Root / Shizuku 时会失败，回执里会写明。
- 建议在任务里加一句 `等待 / Wait 3 秒` 再切，避免刚连上 Wi-Fi 时后台还在收尾。

**用 adb 验证一次**

```bash
adb shell am broadcast -a com.katiusu.netpilot.action.SET_DATA_SIM --ei netpilot.sub_id 2
```

然后看 NetPilot 日志页应有「默认数据卡已切到 subId 2」，同时 Tasker 的
`Intent Received`（Action `com.katiusu.netpilot.event.DATA_SIM_CHANGED`）也会收到一条。

### 示例 B：地铁上「信号满格但没网」自动降到 4G，出站恢复正常

**方式一：交给 NetPilot 自己判（推荐）**

NetPilot 的监控循环本来就在做这件事：RSRP 很强（默认 > -85 dBm）但 ping 超时
（默认 > 200 ms、连续 2 次）就降级，恢复正常连续 3 次后回退。

- Tasker 只负责在「通勤时段 + 地铁 Wi-Fi/基站特征」时打开开关：
  `插件 → NetPilot → 执行动作 → 设置自动降级开关`（打开），离开时关掉；
  或广播版：`SET_AUTO_DOWNGRADE` + `netpilot.enabled:true` / `false`。
- 想联动提示：加 `事件 → 系统 → 收到的意图`，Action
  `com.katiusu.netpilot.event.DOWNGRADED` → 任务里 `提示 / Flash`
  「已切 4G」；再配一个 `event.RECOVERED` → 「已恢复 5G」。
- 阈值不在插件里改（插件只做开关），阈值在 NetPilot 设置页调。

**方式二：Tasker 全权控制（不想开 NetPilot 的自动判定时）**

1. Profile：`时间` 07:30–09:30 + `状态 → 传感器 → 气压/基站` 之类的地铁特征（可选）。
2. 任务循环（每分钟）：
   - `发送意图`：`com.katiusu.netpilot.action.SAMPLE_NOW`，Package `com.katiusu.netpilot`；
   - `等待 2 秒`；
   - 用 `Intent Received`（Action `com.katiusu.netpilot.event.RESULT`）拿
     `%netpilot_result_ok` 与 `%netpilot_ping_ms`（Tasker 变量名以实际显示为准）；
   - 若 `ping_ms` 超阈值 **且** `network_type` 含 `NR`：`发送意图` →
     `com.katiusu.netpilot.action.LOCK_LTE` + Extra `netpilot.lock:true`（或
     `SET_NETWORK_MODE` + `netpilot.mode_value:11`）；
   - 连续 N 次正常后反过来发 `netpilot.lock:false` 恢复。
3. 结束任务：不管当前状态，发一次 `LOCK_LTE --ez netpilot.lock false` 兜底恢复，
   避免留在「仅 4G」状态里耗电或掉 5G 覆盖。

**为什么更推荐方式一**：方式二的判定逻辑全在 Tasker 里，Tasker 被杀/被省电策略掐掉就
完全不工作了；方式一的判定在 NetPilot 的前台服务里，配合 `netpilot.downgraded` 事件做提示，
可靠得多。Tasker 只当「开关的遥控器」。

---

## 7. 排错

1. **看应用内日志页**（NetPilot → 日志）：所有命令都会记一条
   `收到命令：<action>`，失败会记 WARN 原因。这是第一现场。
2. **命令有没有送达**：
   ```bash
   adb shell dumpsys activity broadcasts | grep -i netpilot
   ```
   看有没有你的 action、接收者数量、以及「已投递/被跳过」的痕迹。
   也可以直接看 logcat：
   ```bash
   adb logcat | grep -iE "NetPilot|Tasker接口|Locale插件"
   ```
3. **怀疑权限链路**：先发一条最轻的命令
   `adb shell am broadcast -a com.katiusu.netpilot.action.GET_STATUS`，
   能收到 `event.RESULT`（`netpilot.channel` 会告诉你当前走 Root 还是 Shizuku）说明
   应用侧正常；再试 `SET_NETWORK_MODE`，失败就是特权层问题。
4. **`settings put` 被拒 / 写了没效果**：部分运营商定制 ROM 与「无线固件」层会拒绝
   `preferred_network_mode`，或者写进去后被 Modem 立刻回滚。表现是命令回执成功但制式没变。
   此时换 Shizuku / Root 通道再试（NetPilot 会依次尝试），仍不行就是设备不支持该模式
   （例如某些地区的 `NR_ONLY`）。
5. **Shizuku 未授权 / 未运行**：应用内发起请求；Shizuku 应用必须正在运行（重启后要重新启动
   Shizuku）。回执会说「请检查 Root / Shizuku 是否已授权」。
6. **subId 写错**：`subId` 与卡槽号不一定相同。用 NetPilot 首页显示的值，
   或：
   ```bash
   adb shell dumpsys telephony.registry | grep -iE "mSubscription|subId"
   ```
   写了一个不存在的 subId 时，回执是「切换默认数据卡到 subId X 失败：subId 可能不存在」。
7. **Tasker 收不到事件**：确认事件动作是 `Intent Received` 而不是「发送意图」；
   确认 Tasker 正在运行（第 4.1 节）；确认变量名（`netpilot.result_ok` → Tasker 里可能是
   `%netpilot_result_ok`，以 Tasker 变量列表实际显示为准）。
8. **Tasker 里找不到 NetPilot 插件**：确认 Manifest 里
   `TaskerEditActivity` 的 intent-filter 有 `EDIT_SETTING` 且 `android:exported="true"`；
   装完新版本后 Tasker 需要重启一次才会重扫插件列表。
9. **命令没反应、日志里也没有**：多半是隐式广播被后台限制拦了 —— 在「发送意图」里把
   Package 填成 `com.katiusu.netpilot`（或 Class 填
   `com.katiusu.netpilot.core.tasker.TaskerCommandReceiver`）。

---

## 8. AndroidManifest.xml 片段

```xml
<!-- ===== Tasker 广播命令接收器 ===== -->
<receiver
    android:name=".core.tasker.TaskerCommandReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.katiusu.netpilot.action.SET_NETWORK_MODE" />
        <action android:name="com.katiusu.netpilot.action.SET_PRESET" />
        <action android:name="com.katiusu.netpilot.action.TOGGLE_AUTO_DOWNGRADE" />
        <action android:name="com.katiusu.netpilot.action.SET_AUTO_DOWNGRADE" />
        <action android:name="com.katiusu.netpilot.action.SET_DATA_SIM" />
        <action android:name="com.katiusu.netpilot.action.SWITCH_DATA_SIM" />
        <action android:name="com.katiusu.netpilot.action.LOCK_LTE" />
        <action android:name="com.katiusu.netpilot.action.SAMPLE_NOW" />
        <action android:name="com.katiusu.netpilot.action.GET_STATUS" />
    </intent-filter>
</receiver>

<!-- ===== Locale / Tasker 插件：执行与条件 ===== -->
<receiver
    android:name=".core.tasker.LocaleFireReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="com.twofortyfouram.locale.intent.action.FIRE_SETTING" />
        <action android:name="com.twofortyfouram.locale.intent.action.QUERY_CONDITION" />
    </intent-filter>
</receiver>

<!-- ===== Locale / Tasker 插件：配置界面 ===== -->
<activity
    android:name=".tasker.TaskerEditActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:theme="@style/Theme.NetPilot">
    <intent-filter>
        <action android:name="com.twofortyfouram.locale.intent.action.EDIT_SETTING" />
        <!-- SETTING=作为动作配置，CONDITION=作为条件配置 -->
        <category android:name="com.twofortyfouram.locale.intent.category.SETTING" />
        <category android:name="com.twofortyfouram.locale.intent.category.CONDITION" />
    </intent-filter>
</activity>
```

- **`android:exported="true"` 是必须的**：发送方 Tasker 是另一个 App（另一个 UID），
  Android 12 起带 intent-filter 的组件必须显式声明 `exported`，填 `false` 会直接
  收不到任何外部广播（Tasker 会显示任务执行了但什么都没发生）。
- **不设 `android:permission`**：设了收发双方都得持有该权限，Tasker 拿不到，
  插件不可用。若确实要收紧，可以改成「只用定向广播 + 在代码里校验 `intent.component`」，
  但那也需要 Tasker 侧配合。
- **不需要新增 `<uses-permission>`**，也不需要 `<queries>`：
  这几条命令都不做包名解析，接收器只读自己的状态。

---

## 9. 安全取舍与已知限制

**安全取舍**

1. 两个 receiver 都是 `exported="true"` 且无权限保护 —— 设备上**任何 App** 都能发这些
   广播来控制 NetPilot（切制式、换卡）。这是为了能用 Tasker（跨 App）付出的代价。
   缓解：所有命令都会被写入 NetPilot 日志（谁发的看不出，但**发了什么、结果如何**都有记录），
   且动作仅限于「通信制式 / 默认数据卡 / 降级开关 / 读状态」，不涉及文件、短信、安装等敏感能力。
   若以后要支持敏感动作，应改成「自定义权限 + 签名级校验」或 `setPackage` 定向 + 组件校验。
2. 事件广播是隐式的（不带 package），因此**别的 App 也能注册同名 action 偷看**这些事件。
   内容只有信号强度、制式、ping 延迟与降级状态，不含账号/位置/短信；
   需要保密时应该改成带签名权限的广播。
3. 插件配置只存基本类型，不含任何凭据；配置文件由 Tasker 保存，随 Tasker 的备份一起走。

**已知限制**

- 只做「制式 / 默认数据卡 / 降级开关 / 状态读取」，**不做**移动数据开关、短信、飞行模式。
- 事件里不包含 Wi-Fi SSID（`SignalSnapshot` 里有，但广播只发电话侧字段）。
- 广播接收器用 `goAsync()` 在后台线程执行；后台广播的预算是约 60 秒，
  特权通道探测（su 超时 5 秒 / Shizuku 绑定最长 15 秒）在其中，
  极端情况下（两个通道都卡满）可能超出预算并让本次广播被记 ANR。
  这是「冷启动 + 两个通道都不可用」的叠加场景，正常设备不会同时触发。
- `SIGNAL_SAMPLED` 的频率 = 监控间隔（默认 120 秒），别拿它当秒级数据源。
