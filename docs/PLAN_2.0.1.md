# NetPilot 2.0.1（versionCode 24）计划

> 目标：把联发科基带从「只认得出」做到「能读能写」，办法是**探测做实**（真握手，不靠猜）＋**按 AT 方言与平台世代选映射表**（不假定一套参数通吃）。
> 状态：计划稿，未实施。2.0.0（23）已发布，本版只叠加基带能力，不动判定语义与 UI 骨架。

## 0. 一页纸

三句话：

1. **探测**：现在的 BasebandDetector 只看属性、特征文件、binder 服务存在性，capabilities 写死空集。本版往下走两层——对候选通道真发 `AT` 收 OK，再发 `AT+ERAT?` 读当前 RAT，用回包形状判定方言。
2. **映射**：MTK 的制式写入只有一句 `AT+ERAT=<rat>,<prefer>`，rat 是**世代集合编号**（19 条），不是 RIL 的 0..33。所以要在 `NetworkMode.value` 与世代集合之间建一张显式表，再按方言（双参/单参）与平台（有无 NR）裁剪可写集合。
3. **校验**：AT 写出去只是「送进 binder」，不代表 modem 接受。必须回读 `AT+ERAT?` 比对世代掩码，且判据是**允许宽松、禁止严格**（回读多一代算成功，少一代算失败）。

## 1. 现状与缺口

| 位置 | 现在 | 缺口 |
| --- | --- | --- |
| `core/radio/BasebandVendor.kt`（113 行） | 五种厂商、三档置信度、五种能力枚举 | `capabilities` 恒为 `emptySet()`，能力枚举定义了没人填 |
| `core/radio/BasebandDetector.kt`（336 行） | 属性前缀 → 特征文件 `File.exists` → `ServiceManager.getService("phoneEx")` | 只证明「服务在」，没证明「能通」；AT 节点只 exists 不打开；`logText()` 尾部固定写「本次未做 AT 探测」 |
| `core/priv/ControlManager.kt:278` | `setMode` 直接交给 `acquire()?.setMode` | 没有按厂商/方言分派的后端层 |
| `core/priv/NetworkControlChannel.kt` | 只有 getMode/setMode/slot 三类 | 缺一个通用 AT 透传入口 |

关键约束（决定架构）：**App 进程（untrusted_app）碰不到 vendor.mediatek 的 binder 服务**，AT 通道必须跑在特权进程——root 的 `PrivilegedCli`，或 Shizuku 的 `ShizukuControllerService`（shell uid）。所以 AT 逻辑只能是**无 Context 的 object**，两侧共用。

## 2. 标准从哪来

三条独立来源，互相印证之后才敢写：

| 来源 | 许可 | 给了什么 |
| --- | --- | --- |
| bunnylock（btsearch/bunnylock）backend/mediatek | GPL-3.0，**只读参考，不抄代码** | 注释自述「与 MediaTek EngineerMode 相同的 AT 命令」：AT+ERAT 制式、AT+EPBSE/H 频段、AT+EMMCHLCK 小区锁；ERAT 的 19 条 rat 表与 prefer 规则；节点候选 7 个；先读后写、同值不写 |
| xaga-5g-sa-enabler（Angxddeep） | MIT，可借鉴 | AT+E5GOPT 的 SA/NSA 位掩码（1/3/5/7）；同一句 AT 的三条 binder 路径与**确切事务号**：HIDL 101、AIDL 11、phoneEx 39；参数形态逐个字段写明 |
| ModemManager plugins/mtk | LGPL-2.1+ | `\+ERAT:` 的正则形态，佐证回包是 4 字段：当前 rat、gprs 状态、rat、prefer |
| MTK EngineerMode 反编译源码（LinkBoi00/EngineeringMode，`sources/com/mediatek/engineermode/nrconfigure/NrConfigActivity.java`） | 逆向产物，非文档 | **官方 App 的完整 Java**：查询发 `AT+E5GOPT?`、写入发 `AT+E5GOPT=1/3/5/7`、统一走 `EmUtils.invokeOemRilRequestStringsEm(phoneId, {cmd, respPrefix}, handler)`、回包前缀 `+E5GOPT:`、改 SA 后弹「Please reboot the phone」 |
| luci-app-5gmodem `docs/modems/mediatek-mifi.md`（fildunsky） | 开源实践文档，MT6735 CPE 实测 | 正确通道是 unix socket **`/dev/socket/rild-atci`**（stock `atcid` 用的同一条）；`/dev/radio/*` 与 `atci1/2` 被 `mtkrild` 持有并读走回包，写入方只能看到 `OK`；`AT+ERAT?` 回包实际是 `7,0,6,0`（**可能不带 `+ERAT:` 前缀**）；`AT+EPBSE=<gsm>,<umts>,<lte_fdd>,<lte_tdd>` 立即生效、无需重启 |
| Redmi Turbo 5 Max 的 NR 调查（wanzdoan/RedmiTurbo5Max_hyperos4_lk_patcher） | 民间逆向，新平台实测 | 新平台上 Phone 自己发 **`AT+E5GOPT=3,2`** 与 **`AT+E5GOPT=7,2`**（双参数），而 `AT+E5GOPT=5`、`AT+E5GOPT=7,2` 在该机型回 `ERROR`；与 xaga 的单参 `=7` 不是同一形状 |

一条反证：Fibocom FM350 模组手册里 `AT+ERAT` 是**单参数**（`AT+ERAT=3` 表 LTE only、21 表 default）。同一个命令名在不同平台上参数形状不同——这就是「按版本选表」不是可选项、而是必须的证据。

### 2.1 关于「官方文档」：有，但没有公开的手机侧 AT 手册

先说结论，免得绕：

- **手机侧（smartphone RIL / `mtkfusionrild`）的 AT 命令集，没有公开的 MTK 官方文档。** MTK 把它放在标 Confidential 的内部手册里，只给方案商（ODM/品牌）与模组客户，靠 NDA 交付。
- 公网上能下到的「MTK AT Command Set」，全是 **MAUI 时代（2G/3G 功能机平台）** 的流出件（例如 doku.pub 上那份 Revision 0.07、ntyibiao.cn 的 `AtCmdSet.pdf`，正文自称「supported AT command set of MAUI project」）。这些件里**没有 `AT+ERAT`、没有 `AT+E5GOPT`**——它们是 5G 时代才出现的，不在那个代码库里。
- **模组侧**（MT2735 / MT2731 等 5G 模组平台）是有官方 AT 手册的，但要找模组厂拿（Fibocom / 广和通 / 移远），且命令集与手机侧不完全一致——第 2 节那条 Fibocom 单参数反证就出自这类手册。

真正能替代文档、而且比文档更硬的三类材料，本计划全部依赖它们：

| 类别 | 具体件 | 为什么比文档可信 |
| --- | --- | --- |
| 官方 App 的反编译源码 | EngineerMode 的 `NrConfigActivity.java`（完整 Java，不是 smali） | 这就是「MTK 自己怎么发这条 AT」的原始代码，命令字符串、解析逻辑、走哪条 IPC 一字不改 |
| 官方 vendor 源码 | `vendor_AOSP` 里 `mediatek/proprietary/factory/inc/me_func.h`、`me_connection.h`；`mtk-ril/ril_oem.c` | 方案商拿到的 MTK 交付代码，AT 通道的接口签名与调用约定在这里定义 |
| 同芯片机型的运行时实测 | luci-app-5gmodem 的 MT6735 CPE 记录；Turbo 5 Max 的 NR 调查 | 真实回包长什么样、哪条路能读、哪些值被拒，只有跑过才知道 |

所以本计划的取证方式是「**源码 + 实测回包**」，不是「照文档抄表」。文档能给的只有命令名，给不了参数形状、事务号、回包格式——而这三样恰好是「按版本选映射表」的全部内容。

### 2.2 三类材料立刻改掉了原计划的两处

这两处是写计划的当时按推测填的，查到实测后必须改，已改在下面第 3、5 节：

1. **tty 节点读不到回包。** 原计划写「tty：写 + 读」，错。`/dev/radio/pttycmd1`、`atci1` 这些由 `mtkrild` 打开并独占读取，**写入方只能看到 `OK`，拿不到 `+ERAT:` 那种带数据的回包**。正确的读写通道是 unix socket `/dev/socket/rild-atci`。
2. **`AT+E5GOPT` 的参数形状按平台分。** xaga（MT6895）用单参 `AT+E5GOPT=7`，Turbo 5 Max 上 Phone 自己发的是双参 `AT+E5GOPT=7,2`，且 `=5` 在该机型直接 `ERROR`。这不再是「可能要分表」，而是**必须分表**——而且分表维度是「平台/机型」，不是「Android 版本」。

## 3. 探测做实：四层判据

每一层都能独立否决，越往下越硬。前一层过了就继续，过了 L4 才算 `Confidence.CONFIRMED`。

### L1 静态层（已有，补一处）

属性 + `Build` + 特征文件。保持现有逻辑，只补一条：连 `ro.board.platform` 都读不到时，不判 UNKNOWN，而是标 `LIKELY` 并注明「属性不可读」——现在的实现这一步会掉到 unknownProfile。

### L2 服务层（已有，加深）

`ServiceManager.getService` 已查 `phoneEx`。再查两个 MTK 专有服务，名字从 xaga 代码逐字取：

| 服务名 | 出现代际 | 用途 |
| --- | --- | --- |
| `phoneEx` | 常见于 MTK 全系 | IMtkTelephonyEx，事务 39 发 AT 且有 reply |
| `vendor.mediatek.hardware.mtkradioex.modem.IMtkRadioExModem/slot1` | Android 12+（AIDL 化后） | 事务 11 发 AT，oneway |
| `vendor.mediatek.hardware.mtkradioex@3.0::IMtkRadioEx` + instance `mtkEm1` | Android 8–11（HIDL） | 事务 101，oneway |

注意：`getService` 返回非 null 只证明「这台机器装了 MTK 的 telephony 扩展」，**不能证明能 transact**（SELinux 可能拒）。所以 L2 只用于判定厂商，能力交给 L3。

### L3 握手层（新）

对每个候选通道，按优先级试：

1. **ATCI unix socket（首选，唯一可靠的读通道）**：`/dev/socket/rild-atci` 是 stock `atcid` 用的同一条 socket。连上去发 `AT...\r`，读到 `OK`/`ERROR` 为止，**回包完整**。Kotlin 侧用 `android.net.LocalSocket` + `LocalSocketAddress("rild-atci", Namespace.FILESYSTEM)`。需要 shell 或 root（socket 属 radio 组，untrusted_app 进不去），所以只能在特权进程里做。
2. **tty 节点（只能写，不能读）**：候选 `/dev/radio/pttycmd1`、`atci1`、`pttyeng`、`atci-serv-fw`、`/dev/radio/ptty2cmd1`、`atci2`、`/dev/ttyC0`。其中 `pttycmd*` / `atci*` 由 `mtkrild` 持有并**读走回包**，写入方只看到 `OK`；`/dev/ttyC0` 被 `gsm0710muxd` 持有；`ttyC1`–`ttyC3` 空闲但不响应 AT。所以 tty 只当「发送口」，**永远不拿它读值**。副卡节点（`ptty2cmd1`、`atci2`）供 slot 1。
2. **phoneEx**：事务 39 发 `AT`，reply 里含 OK → 通过。参数序严格照 xaga：interfaceToken(`com.mediatek.internal.telephony.IMtkTelephonyEx`) → writeInt(slot) → writeLong(token) → writeString(cmd) → writeStrongBinder(null)。
3. **AIDL modem**：事务 11，参数序 interfaceToken → writeInt(serial) → writeStringArray([cmd, ""]) → writeInt(3)。oneway，收不到回包，**握手只能判「送出去了」**，因此不作为读通道。
4. **HIDL**：事务 101，同上，oneway。

握手失败要区分原因并落日志：节点不存在 / 打开被拒（EACCES）/ 打开成功但超时无回应 / 回应是 ERROR。这四种对应完全不同的用户可行动作，现在混成一句「不可用」。

### L4 方言层（新）

在握手通过的通道上发 `AT+ERAT?`，按回包判方言：

| 回包 | 方言 | 可写 |
| --- | --- | --- |
| `+ERAT: a,b,c,d` 四字段 | `MTK_DUAL`（双参） | ✓ |
| `+ERAT: n` 单字段 | `MODEM_SINGLE`（模组式） | ✗ 只识别不写 |
| `ERROR` / `+CME ERROR` | `REFUSED` | ✗ |
| 超时 | `UNKNOWN` | ✗ |

双参时把第 3 个字段（rat）留在 profile 里，后面既用于判平台世代，也用于写入前的「同值不写」优化。

### 探测结果怎么落盘

`BasebandProfile` 扩四个字段（JSON 兼容，旧值读不到就取默认）：

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `channelKind` | enum | `TTY / PHONE_EX / AIDL / HIDL / NONE`，可读通道 |
| `writeKinds` | Set | 能写的那几条（tty 与 phoneEx 通常都有；只有 AIDL/HIDL 时为空，见第 5 节） |
| `dialect` | enum | 上面四档 |
| `currentRat` | Int | `AT+ERAT?` 读到的 rat，`-1` 表示没读到 |

缓存键不变（`np_baseband_profile`），TTL 从 7 天降到 **1 天**——通道能力会随系统更新、root 状态、Shizuku 启停变化，7 天太钝。

## 4. 映射表：从 RIL 值到 AT 命令

### 4.1 表 A：世代集合 ↔ ERAT rat

这是全部映射的地基。世代用位掩码表示，取值 `G2=1, G3=2, G4=4, G5=8, CDMA=16`。

| rat | 世代集合 | 可写 | 说明 |
| --- | --- | --- | --- |
| 0 | G2 | ✓ | GSM |
| 1 | G3 | ✓ | WCDMA / TD-SCDMA（MTK 不区分） |
| 2 | G2+G3 | ✓ | 3G 自动 |
| 3 | G4 | ✓ | LTE |
| 4 | G2+G4 | ✓ | 少见组合 |
| 5 | G3+G4 | ✓ | LTE+WCDMA |
| 6 | G2+G3+G4 | ✓ | 4G 自动（降级目标） |
| 15 | G5 | ✓ | NR only |
| 19 | G4+G5 | ✓ | 5G+4G |
| 21 | G3+G4+G5 | ✓ | 5G 自动 |
| 22 | G2+G3+G4+G5 | ✓ | 全制式 |
| 7 | CDMA | ✗ | 禁写 |
| 8 | G2+CDMA | ✗ | 禁写 |
| 10 | G2+G3+CDMA | ✗ | 禁写 |
| 11 | G4+CDMA | ✗ | 禁写 |
| 12 | G2+G4+CDMA | ✗ | 禁写 |
| 14 | G2+G3+G4+CDMA | ✗ | 禁写 |
| 26 | G4+G5+CDMA | ✗ | 禁写 |
| 30 | 全部+CDMA | ✗ | 禁写 |

**为什么含 CDMA 的一律禁写**：bunnylock 的注释写「带 C2K 的模式只读不写」，且当代 MTK 平台上 CDMA 已无网可驻，写进去唯一的后果是让 modem 多扫一个不存在的制式，部分平台会直接拒写并回 ERROR。宁可少一个可写档，也不要让用户点一下掉网。

### 4.2 表 B：prefer 规则

rat 之外还有第二个参数，来源同一份 EngineerMode 逻辑：

| 条件 | prefer |
| --- | --- |
| 集合恰好 = G5 | 0 |
| 集合含 G5 | 128 |
| 集合 = G2+G3+G4 | 4 |
| 集合 = G2+G3 | 2 |
| 其余 | 0 |

### 4.3 表 C：NetworkMode → 世代集合

34 个 RIL 值到世代集合的**显式表**，不推导。`NetworkMode.gen` 只是 UI 分组用的最高代际，拿它推世代会错——例如 21（`TDSCDMA_CDMA_EVDO_GSM_WCDMA`）的 `gen` 是 3，实际含 CDMA。

| RIL | NetworkMode | 世代集合 | rat | prefer | 备注 |
| --- | --- | --- | --- | --- | --- |
| 0 | WCDMA_PREF | G2+G3 | 2 | 2 | |
| 1 | GSM_ONLY | G2 | 0 | 0 | |
| 2 | WCDMA_ONLY | G3 | 1 | 2 | |
| 3 | GSM_UMTS | G2+G3 | 2 | 2 | |
| 4 | CDMA | CDMA | — | — | 禁写 |
| 5 | CDMA_NO_EVDO | CDMA | — | — | 禁写 |
| 6 | EVDO_NO_CDMA | CDMA | — | — | 禁写 |
| 7 | GLOBAL | 含 CDMA | — | — | 禁写（语义不可确定） |
| 8 | LTE_CDMA_EVDO | G4+CDMA | — | — | 禁写 |
| 9 | LTE_GSM_WCDMA | G2+G3+G4 | 6 | 4 | 降级目标 |
| 10 | LTE_CDMA_EVDO_GSM_WCDMA | G2+G3+G4+CDMA | — | — | 禁写 |
| 11 | LTE_ONLY | G4 | 3 | 4 | 锁 4G 目标 |
| 12 | LTE_WCDMA | G3+G4 | 5 | 4 | |
| 13 | TDSCDMA_ONLY | G3 | 1 | 2 | **近似**：MTK 无纯 TD 档 |
| 14 | TDSCDMA_WCDMA | G3 | 1 | 2 | |
| 15 | LTE_TDSCDMA | G3+G4 | 5 | 4 | |
| 16 | TDSCDMA_GSM | G2+G3 | 2 | 2 | |
| 17 | LTE_TDSCDMA_GSM | G2+G3+G4 | 6 | 4 | |
| 18 | TDSCDMA_GSM_WCDMA | G2+G3 | 2 | 2 | |
| 19 | LTE_TDSCDMA_WCDMA | G3+G4 | 5 | 4 | |
| 20 | LTE_TDSCDMA_GSM_WCDMA | G2+G3+G4 | 6 | 4 | |
| 21 | TDSCDMA_CDMA_EVDO_GSM_WCDMA | G2+G3+CDMA | — | — | 禁写 |
| 22 | LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA | G2+G3+G4+CDMA | — | — | 禁写 |
| 23 | NR_ONLY | G5 | 15 | 0 | 5G 主推 |
| 24 | NR_LTE | G4+G5 | 19 | 128 | |
| 25 | NR_LTE_CDMA_EVDO | G4+G5+CDMA | — | — | 禁写 |
| 26 | NR_LTE_GSM_WCDMA | G3+G4+G5 | 21 | 128 | 联通默认 |
| 27 | NR_LTE_CDMA_EVDO_GSM_WCDMA | G2+G3+G4+G5+CDMA | — | — | 禁写 |
| 28 | NR_LTE_WCDMA | G3+G4+G5 | 21 | 128 | |
| 29 | NR_LTE_TDSCDMA | G4+G5 | 19 | 128 | |
| 30 | NR_LTE_TDSCDMA_GSM | G2+G4+G5 | 22 | 128 | **宽松**：ERAT 22 多开 G3 |
| 31 | NR_LTE_TDSCDMA_WCDMA | G3+G4+G5 | 21 | 128 | |
| 32 | NR_LTE_TDSCDMA_GSM_WCDMA | G2+G3+G4+G5 | 22 | 128 | |
| 33 | NR_LTE_TDSCDMA_CDMA_EVDO_GSM_WCDMA | 全部+CDMA | — | — | 禁写 |

两条判据，整个方案的安全性就压在这里：

- **允许宽松**：表里没有精确对应的组合（30），选一个**超集**写进去，并在日志与 UI 里标「MTK 上会多开一档」。多一代通常无害，modem 按网络侧能力搜网。
- **禁止严格**：任何会导致「比用户要的少一代」的映射都不允许。想锁 5G+4G（24）时，不能因为表里没有精确组合就退到更小的集合。

### 4.4 平台世代裁剪

4G 平台（MT6765/6768/6771/6785/6789/6833 一类）写 rat 15/19/21/22 会回 ERROR。判定两层：

1. **属性白名单**：平台串命中 MTK 5G 代（mt6835 / mt6853 / mt6877 / mt6879 / mt6883 / mt6885 / mt6889 / mt6893 / mt6895 / mt6897 / mt6983 / mt6985 / mt6989）→ 允许 NR 档；命中 4G 代 → 屏蔽 NR 档；都不命中 → 走第 2 条。
2. **当前 rat 反推**：L4 读到的 rat ∈ {15,19,21,22,26,30} → 这台机器至少支持到 NR 档；∈ {0..14} → 只有 4G 及以下。注意 5G 手机插 4G 卡时当前 rat 也会是 6，所以反推只能**正向放行、不能反向否决**。

合起来的规则：**属性否决不推翻、属性放行或未知才用 rat 反推放行；两者都不放行则屏蔽 NR 档**。

## 5. 通道实现：四条路径与能力矩阵

| 路径 | 事务号 | 有回包 | 能做 | 需要 |
| --- | --- | --- | --- | --- |
| `/dev/socket/rild-atci` | — | ✓ | 写 + 读 | shell 或 root（radio 组） |
| tty 节点 | — | ✗ 被 mtkrild 读走 | **只能写** | root，或 shell + 节点可写 |
| phoneEx | 39 | ✓ | 写 + 读 | shell 或 root |
| AIDL modem | 11 | ✗ oneway | 只能写 | shell 或 root |
| HIDL | 101 | ✗ oneway | 只能写 | shell 或 root |

**核心决策：没有可回读的通道，就不许走 AT 写。** 理由很直接：oneway 的 transact 成功只代表 parcel 进了 binder 驱动，modem 收没收、认不认，一个字节都不知道。写制式没有回执，用户点一下可能什么都没发生，也可能掉网，而 UI 只能瞎报「成功」。

所以能力按「能读 + 能写」配对给：

| 可读通道 | 可写通道 | SET_MODE | READ_MODE |
| --- | --- | --- | --- |
| atci socket | atci socket | ✓ | ✓ |
| atci socket | tty / phoneEx / AIDL / HIDL | ✓ | ✓ |
| phoneEx | phoneEx / AIDL / HIDL | ✓ | ✓ |
| 只有 tty | tty | ✗ | ✗ 能写不能读，等于不能校验 |
| 无 | AIDL / HIDL | ✗ | ✗ |

最后一行是关键：**只有 oneway 通道时，`capabilities` 不含 SET_MODE**，UI 明确写「本机只能识别，不能通过联发科通道改制式」，落回现有 RIL 通道（`TelephonyReflection` + settings）。不装成功。

### 写入优先级

能读的前提下，写按 tty → phoneEx → AIDL → HIDL 的顺序试，第一个不报 ERROR 的算数。写之前先 `AT+ERAT?` 读一次，**世代集合完全相同就不写**（照 bunnylock 的做法，也避开 MTK RIL「跳过同值写」的坑）。

### 回读判定

回包解析要容错：实测到的形态既有 `+ERAT: 7,0,6,0`，也有裸的 `7,0,6,0`（CPE 实测）。**先剥可选前缀，再按逗号切**，字段数 < 4 就当方言不匹配、不写。

写完 300–800 ms 后读 `AT+ERAT?`，取第 3 字段 rat 查表 A 得 `readMask`，与 `wantedMask` 比：

| 关系 | 判定 | 日志 |
| --- | --- | --- |
| readMask == wantedMask | 成功 | INFO 一条 |
| readMask ⊃ wantedMask | 成功（宽松） | INFO + 「多开：X」 |
| readMask ⊂ wantedMask | 失败 | WARN「少了 X 代，可能被系统改写」 |
| 无法比较 | 失败 | WARN 带两个 rat 原始值 |

失败重试一次（AT 写入有偶发丢包），仍失败返回 false，由上层记日志、由降级引擎的冷却与无网回滚兜底。

## 6. 代码改动清单

### 新增（core/radio/，约 900–1100 行）

| 文件 | 行数估计 | 职责 |
| --- | --- | --- |
| `MtkAtChannel.kt` | 120 | `interface AtChannel`：open / send / close / isAlive；`sealed class AtReply{Ok, Error, Timeout, Refused}`；统一超时与错误分类 |
| `MtkAtTtyChannel.kt` | 180 | 节点候选表、open（含副卡节点）、写 `AT\r`、按行读到 OK/ERROR、关闭。纯 `FileInputStream/FileOutputStream`，无 Root 依赖 |
| `MtkAtBinderChannel.kt` | 320 | 三条 binder 路径的反射实现。HIDL 用 `HwBinder.getService(iface, instance, false)` + `HwParcel`；AIDL 与 phoneEx 用 `ServiceManager` + `Parcel`。事务号与参数序逐字段照第 3 节 |
| `MtkEratTable.kt` | 260 | 表 A + 表 B + 表 C（34 条），`generationsToRat`、`preferFor`、`generationsOf(NetworkMode)`、`isWritable`、`isSuperset`。纯数据，无 IO，可直接写单元测试 |
| `MtkPlatformTier.kt` | 90 | 5G / 4G 平台白名单 + 「属性否决不推翻」的裁剪规则 |
| `MtkAtBackend.kt` | 200 | 把上面几个拼成后端：探测 → 选通道 → 写前读 → 写 → 回读 → 判定 |

### 改动

| 文件 | 改什么 | 幅度 |
| --- | --- | --- |
| `core/radio/BasebandVendor.kt` | `BasebandProfile` 加 channelKind / writeKinds / dialect / currentRat；`capabilities` 由探测填；`summaryLine()` 与 `logText()` 跟上 | ~60 行 |
| `core/radio/BasebandDetector.kt` | 加 L3 握手、L4 方言；TTL 7 天 → 1 天；save/parseProfile 兼容新字段 | ~80 行 |
| `core/priv/NetworkControlChannel.kt` | 加默认实现 `suspend fun atCommand(cmd: String): String`（返回空串表不支持，不抛异常，与现有 readAuthStore 风格一致） | ~10 行 |
| `core/priv/PrivilegedCli.kt` | 加 `at <cmd>` 子命令：解析通道、发命令、把回包原样打到 stdout（带 `CLI_PREFIX`） | ~40 行 |
| `core/priv/RootController.kt` | 实现 `atCommand`（拼 `… PrivilegedCli at <cmd>` 走现有 `RootShell`） | ~25 行 |
| `core/priv/ShizukuController.kt` + AIDL + `ShizukuControllerService.kt` | AIDL 加 `String atCommand(String)`；服务端直接调同一个 object | ~40 行 |
| `core/priv/ControlManager.kt:278` | `setMode` 先问 MTK 后端：能力允许且方言可写 → 走 AT；否则原路。成功时更新 `modeMemo` 的既有语义不变 | ~30 行 |
| `core/NetPilot.kt` | `statusText()` 在有 MTK 能力时补一段基带信息 | ~15 行 |
| `ui/screen/settings/SettingsPage.kt` | 已有的「基带」那一行，summary 用扩展后的 `summaryLine()`，不加新控件 | ~5 行 |

合计约 **1000–1200 行新增 + 300 行改动**。不动 `AutoDowngradeEngine`、不动判定阈值、不动 UI 骨架。

## 7. 分期

| 期 | 内容 | 风险 | 建议 |
| --- | --- | --- | --- |
| P1 | 只做 L2/L3/L4 探测 + 表 A/B/C 落库 + UI 展示。**不写任何东西** | 零 | 进 2.0.1，先拿开发机与用户真机的探测日志验证判据 |
| P2 | 开写：tty + phoneEx 两条路径，按第 5 节能力矩阵，含回读判定 | 中 | 进 2.0.1，写错有冷却 + 无网回滚兜底 |
| P3 | E5GOPT（SA/NSA 开关）+ 频段掩码（EPBSE/EPBSEH） | 高 | 不进 2.0.1。SA 开关是「改一次就重启」的级别，要单独的确认流程与回滚 |

2.0.1 的范围 = P1 + P2。P3 另开版本。

## 8. 风险与不可验证项

| 风险 | 说明 | 缓解 |
| --- | --- | --- |
| 开发机是高通 | MTK 全路径一行都跑不了 | P1 就把「属性原文 + 服务列表 + 握手结果 + 回包原文」全写进日志页，给用户一个「导出基带诊断」按钮，一次回传就能验判据 |
| 写错丢网 | AT 写是 modem 级操作 | 只开已证实的两条通道 + 回读判定 + 禁写 CDMA + 允许宽松禁止严格 + 现有冷却/无网回滚原样保留 |
| oneway 无回执 | AIDL/HIDL 路径本质上「发出去就不知道」 | 第 5 节：没有可读通道就不给 SET_MODE |
| MTK RIL 跳过同值写 | 系统以为还是旧值 | 写前先读，同值不写；回读不一致时补发一次 |
| 方言判错 | 单参方言当双参写 | L4 按回包字段数判，判不出就不写 |
| 平台白名单不全 | 新平台不在表里 | 未知平台不屏蔽，靠「当前 rat 反推」正向放行；写失败回 ERROR 就落回 RIL 通道并记日志 |
| tty 回包被吞 | `mtkrild` 抢读，写入方只看到 OK | 读一律走 `/dev/socket/rild-atci`；tty 只写 |
| 命令参数形状跨平台变 | 同一句 `AT+E5GOPT` 在 MT6895 是单参、在新平台是双参且部分值回 ERROR | 每条命令都先 `?` 探形状再写；形状判不出就不写 |

## 9. 验收清单

P1 完成判据：

1. 高通机上跑，`capabilities` 为空、UI 显示「高通 · 通道：无 · 未知」，不出现任何 MTK 字样。
2. 日志里能看到 `L2 服务：phoneEx=有/无`、`L3 tty /dev/radio/atci1：打开被拒（EACCES）` 这类逐层结论，而不是一句「未知」。
3. 探测最长 3 s 内返回（现有 `TOTAL_TIMEOUT_MS`），不阻塞启动。
4. 「导出基带诊断」能导出成一段可读文本，包含所有原始回包。

P2 完成判据：

1. 能读到 rat 的机器上，选「仅 4G」→ 日志出现 `AT+ERAT=3,4` + 回读 rat=3 + 「成功」。
2. 选含 CDMA 的模式 → 直接不写，日志写「CDMA 模式在 MTK 上不支持写入」。
3. 4G 平台选「仅 5G」→ 不写，日志写「平台不支持 NR」。
4. 写入失败 → 现有冷却与无网回滚照常生效，不出现「写失败但引擎以为已降级」。
5. 断网或 modem 无响应时，整条探测链在 3 s 内退化到 `NONE`，不卡 UI。

## 10. 与 2.0.0 的关系

2.0.0 已经把「识别」做进去了（`BasebandVendor` / `BasebandDetector` / 设置页一行）。本版是在这条已落地的线上继续，不是推倒重来：

- 枚举、缓存键、UI 位置都不变，只把 `capabilities` 从空集填实、把探测从「存在性」推到「能通」。
- 版本号 `versionCode 24`、`versionName "2.0.1"`，与 2.0.0（23）连续。
- 不改 minSdk（33）、不改签名、不改包名。
