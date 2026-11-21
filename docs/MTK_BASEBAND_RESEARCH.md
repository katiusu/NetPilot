# 联发科（MediaTek）基带网络制式切换 · 前人经验调研

> 调研日期：2026-11-21　范围：只调研，不动 NetPilot 代码。
> 目的：NetPilot 现有写制式通道（ITelephony / settings）在高通上可用，在联发科上不生效，先看市面上已经跑通的方案。

## 0. 结论

1. **不是「联发科不支持切制式」，而是通道和命令完全不同。** 高通走 QMI / settings / radio HAL；MTK 走 **AT 命令 AT+ERAT**，或者 **phoneEx 的 OEM hook**，或者工程模式 / ATCI 调试通道。
2. **已有成熟开源实现跑通**：btsearch/bunnylock（GPL-3.0）对 MTK 的 RAT 锁定走 AT，真机表里 Android 15/16 的 Motorola、OnePlus、Oppo 多款 MTK 机型在 **仅 Shizuku（ADB shell 权限）** 下就能锁 RAT。
3. **最省事的接入点不是 /dev/ttyC*，而是 phoneEx 的 OEM hook**（invokeOemRilRequestRawBySlot）：不需要访问 tty 节点权限，root 和 Shizuku 都能用，形态和 NetPilot 现有的 app_process / Shizuku UserService 完全一致。
4. **MTK 的 RIL 有明显怪癖**：它会跳过「自己认为已经是当前值」的写请求，而且**看不到用 AT 直接设的模式**；所以 AT 写和 Android 层状态会不同步，前人是在 Android 写成功后再补发一次 AT 来对齐。
5. 复用红线：bunnylock 是 **GPL-3.0**，与 NetPilot 的 Apache-2.0 不兼容，只能读行为不能抄代码（和当年处理 NetworkSwitch GPL-3.0 一样，走 clean-room）。

## 1. 为什么 NetPilot 的写法在 MTK 上不生效

NetPilot 的写入链路是：settings 写 preferred_network_mode → 兜底 app_process 调 ITelephony / settings put。这条路依赖 vendor RIL 实现 RIL_REQUEST_SET_PREFERRED_NETWORK_TYPE / setAllowedNetworkTypesForReason。

MTK 的 vendor RIL（mtkfusionrild / libmtk-ril.so）把这些请求翻译成 AT+ERAT。前人（bunnylock）在代码注释里写得很直白：

> MediaTek's RIL skips a mode it thinks is already set, and it doesn't see one set with AT+ERAT.

即：RIL 侧有缓存判断，AT 侧改了它不知道；反之 RIL 认为「已经是这个模式」时干脆不发命令。结果就是 App 层看起来成功、实际基带没变，这正是「只能在高通上运作」的观感来源。

## 2. 路径 A：MTK AT 命令直接写基带

### 2.1 制式：AT+ERAT

来源 1（权威、可直接抄逻辑）：chromiumos 的 ModemManager MTK 插件 plugins/mtk/mm-broadband-modem-mtk.c

- 读：AT+ERAT?，响应 +ERAT:<current rat>,<gprs status>,<rat>,<prefer>
- 写：AT+ERAT=<rat>,<prefer>（第 488 行）
- 老插件只认 2G/3G/4G：0=2G、1=3G、2=2G+3G、3=4G、4=2G+4G、5=3G+4G、6=2G+3G+4G

来源 2（含 5G，实际手机 RIL 用的表）：btsearch/bunnylock，modem/src/main/java/dev/ririxi/bunnylock/backend/mediatek/Mediatek.kt:82-104

| rat | 含义 |
|---|---|
| 0 | 2G |
| 1 | 3G |
| 2 | 2G+3G |
| 3 | 4G |
| 4 | 2G+4G |
| 5 | 3G+4G |
| 6 | 2G+3G+4G |
| **15** | **5G** |
| **19** | **4G+5G** |
| **21** | **3G+4G+5G** |
| **22** | **2G+3G+4G+5G** |
| 7 / 8 / 10 / 11 / 12 / 14 / 26 / 30 | 含 CDMA(C2K) 的组合，**只读不写** |

<prefer> 取值（bunnylock 注释：MediaTek's RIL sends these）：0 = 无偏好，2 = 3G，4 = LTE，128 = NR。

所以「降到 LTE only」在 MTK 上就是 AT+ERAT=3,4（4G 且偏好 LTE），「锁 5G」是 AT+ERAT=15,128，「2G/3G/4G/5G 全开」是 AT+ERAT=22,0。

CSDN 那类文章把 AT+ERAT 说成 0/1/2/3 对应 GSM/WCDMA/TD-SCDMA，是功能机时代的老参数，别当准。

### 2.2 5G SA / NSA 开关：AT+E5GOPT

来源：Angxddeep/xaga-5g-sa-enabler（MIT，逆向 EngineerMode.apk 的 NrConfigActivity）。

AT+E5GOPT=<3bit mask>：1=关 NR，3=仅 SA，5=仅 NSA，7=SA+NSA。写入 modem 的 NVRAM/NVDATA，**要重启才生效**。

它走的通道是 vendor.mediatek.hardware.mtkradioex@3.0::IMtkRadioEx 的 invokeOemRilRequestStringsEm（HIDL transaction 101），由 app_process 加载 dex 直接调 —— 不用 EngineerMode APK。

### 2.3 其它相关 AT

| 命令 | 用途 | 来源 |
|---|---|---|
| AT+EPBSE | GSM/UMTS/LTE 频段掩码（十进制 32bit 字） | bunnylock |
| AT+EPBSEH | 频段掩码（十六进制，含 NR） | bunnylock |
| AT+EMMCHLCK=<on>,<act>,<gsm1900>,<arfcn>[,<pci>[,<mode>]] | 锁频点/小区 | bunnylock |
| AT+ECASW=0/1 | 载波聚合开关，**重启 modem 才生效** | bunnylock |
| AT+ESUO=4/5 | 把 AT 会话绑定到 SIM1/SIM2 协议栈（会话级！） | BeaRerM |
| AT+ERSA | 出现在 libmtkrilutils.c，与 RAT 相关（未深挖） | softs.im |

### 2.4 AT 走哪个设备节点

bunnylock 的候选顺序（Mediatek.kt:34-43）：

```
/dev/radio/pttycmd1          SIM1
/dev/radio/atci1             SIM1
/dev/radio/pttyeng           SIM1（工程口）
/dev/radio/atci-serv-fw      SIM1（框架侧 atci）
/dev/ttyC0                   SIM1（CCCI，双卡时也只到 SIM1）
/dev/radio/ptty2cmd1         SIM2
/dev/radio/atci2             SIM2
```

BeaRerM 的候选（CCCI）：/dev/ttyC0、ttyC1、ttyC3、ttyC5、ttyC6。

坑：**被 RIL 占着的节点打不开**。BeaRerM 记录实测机 /dev/ttyC2 被 mtkfusionrild 持有、/dev/ttyC0 空闲。打开失败就要换下一个，不能假设某个路径一定存在。

## 3. 路径 B：phoneEx 的 OEM hook（推荐路线）

bunnylock 的 root/OemHook.kt 说明：AT 命令通过 telephony 服务下发，而不是走 modem tty，所以 **ADB shell（Shizuku）用户和 root 都能用**。候选服务：MTK 的 phoneEx（invokeOemRilRequestRaw*）、Motorola 的 motoexttelephony、Unisoc 的 irit、Oplus 的 IOplusTelephonyInternalExt.sendAtCmd、ITelephony 的 OEM request（Android 11-14）。

机制：ServiceManager.getService("phoneEx") 拿到 MTK 的 PhoneInterfaceManagerEx binder，反射调 invokeOemRilRequestRawBySlot(slot, request, response)，把 AT+ERAT=3,4 加回车塞进去，从 response buffer 读回字符串。

优点：不碰 /dev 权限（Shizuku 的 shell uid 也能用）；天然带 slot 参数，双卡可分别写；失败时能拿到 RIL 错误码，便于回读校验。

私有 API 在 framework jar 里（mediatek-framework.jar 之类），需要 PathClassLoader 从 /system/framework、/system_ext/framework、/product/framework、/vendor/framework、/odm/framework 里找 Stub 类 —— bunnylock 就是这么做的。

## 4. 路径 C：binder 直调（跨芯片通用）

Daniel Ritter 的博客（Pixel 6a / Android 15 实测）：

```
service call phone 107                        # 读 allowed network types
service call phone 108 i32 0 i32 0 i64 32771  # 写（示例为 2G only）
```

107/108 是 ITelephony.TRANSACTION_getAllowedNetworkTypesForReason / TRANSACTION_setAllowedNetworkTypesForReason 在当前固件里的序号，**不同 ROM 会变**，要在 framework.jar 里查。

这条路和 NetPilot 现在那条本质是同一个（都进 vendor RIL），在 MTK 上是否生效仍然取决于 MTK 的 setAllowedNetworkTypesForReason 实现，不解决「RIL 看不见 AT 改动」的问题。当兜底可以，当主力不行。

## 5. 路径 D：工程模式 / ATCI

- 暗码 *#*#3646633#*#* 进 MTK EngineerMode，里面有 Network Selecting / Nr Configure，能手动改 RAT 与 SA/NSA。缺点：依赖厂商预置 APK 和 em_hidl daemon，LineageOS 这类官方构建不允许带。
- OPPO 系 MTK：*#800# → ATCI → ENABLE ATCI，之后 AT 通道打开，可用串口/ATCI 客户端发命令。
- ATCI 的实现：vendor/bin/atcid，framework 侧 AtciService 连 LocalSocket，socket 名 **atci-service**（native）和 **atci-service-framework**（Java 侧）。协议是一条一条 AT 文本加回车。要 root 才连得上 socket。
- 结论：适合做救援/诊断，不适合做产品主路径。

## 6. 真机证据（bunnylock TESTED_DEVICES.md 摘录）

| 机型 | Android | 权限 | 后端 | RAT |
|---|---|---|---|---|
| Motorola G54 5G | 15 | Shizuku | MediaTek AT | OK |
| Motorola Moto E22 | 13 | Shizuku | MediaTek AT | OK |
| OnePlus Nord CE5 | 16 | Shizuku | MediaTek AT | OK |
| Oppo Find X9 Pro | 16 | Shizuku | MediaTek AT | OK |
| Oppo Reno 13 | 16 | Shizuku | MediaTek AT | OK |
| Redmi Note 10 | 14 | Root | MediaTek AT | OK（自定义 ROM） |
| Oppo A74 5G / Reno 5 5G / Realme 8 Pro | 13 | Shizuku | Android radio HAL | OK |
| Realme C30 / C53 / Samsung A06 | 11-16 | Shizuku | Android radio HAL | RAT OK、频段 FAIL |

信息量：MTK 上 **Shizuku 就够**的机型不少，不必强求 root；同一个 MTK 芯片不同 ROM 可能一个走 AT 通、一个只能走 HAL；HAL 路线在部分机型上连频段都锁不了。

## 7. 前人踩过的坑（照抄会踩）

1. **状态不同步**：MTK RIL 会跳过「它认为已经是当前值」的请求，也看不到 AT+ERAT 改的值。bunnylock 的做法是 Android 写成功后**再补发一次 AT+ERAT**，让两边对齐（MediatekBackend.kt:246-260）。
2. **节点被占**：/dev/ttyC2 常被 mtkfusionrild 持有，必须按候选列表逐个探测并缓存命中的那个。
3. **会话级命令**：AT+ESUO 只对当前打开的会话有效，必须和后续查询在同一个 fd 里发。
4. **改完要重启才生效**：AT+ECASW（CA）、AT+E5GOPT（SA/NSA，写 NVRAM）都需要重启 modem/整机。
5. **5G 参数分两套**：AT+ERAT 管 RAT 组合（含 15/19/21/22 这些 5G 组合），AT+E5GOPT 管 SA/NSA 开关，别混。
6. **老资料会骗人**：功能机时代的 AT+ERAT 参数表、ModemManager 老插件（只到 4G）都不适用于现在的 5G 手机。
7. **不要假设回读可信**：AT 写入后要以 AT+ERAT? 的回读为准，而不是以 App 层 API 的返回为准。

## 8. 对 NetPilot 的适配评估（只评估，不实现）

现状：app/src/main/java 里 **grep 不到任何 mediatek/mtk/qualcomm 分支**，「只支持高通」不是代码里写死的判断，而是「只实现了一条通道」的结果。

要支持 MTK，最小改动形态是加一个 backend 抽象，而不是改判定逻辑：

- **检测**：ro.board.platform / ro.hardware / Build.HARDWARE 判 mt*，或者干脆不判芯片、直接探测 phoneEx 服务与 AT 节点（bunnylock 是探测式，不硬编码芯片）。
- **写 RAT**：优先 phoneEx.invokeOemRilRequestRawBySlot 发 AT+ERAT=<rat>,<prefer>；拿不到 phoneEx 再退到 tty 节点列表；都失败才走现有 ITelephony。
- **读回校验**：AT+ERAT? 解析第 3 个字段，和现有 modeWriteAccepted 的位掩码比对逻辑可以对接。
- **双卡**：slot 参数用 SubscriptionManager.getSlotIndex(subId)，注意 /dev/ttyC0 只到 SIM1。
- **降级目标映射**：NetPilot 内部用位掩码（降级目标 9 = LTE only），MTK 要转成 ERAT 的 rat 值（4G only → 3），映射表单独写，不能复用高通数值。
- **风险**：改的是基带级设置，写错会丢网（bunnylock README 专门警告）。必须保留「回读 + 失败回滚 + 冷却」这套现有机制。
- **许可证**：bunnylock GPL-3.0 不能抄；xaga-5g-sa-enabler MIT 可参考实现；ModemManager 插件 LGPL-2.1+。

## 9. 参考

- bunnylock（GPL-3.0）https://github.com/btsearch/bunnylock
- bunnylock 实测表 https://github.com/btsearch/bunnylock/blob/main/TESTED_DEVICES.md
- xaga-5g-sa-enabler（MIT）https://github.com/Angxddeep/xaga-5g-sa-enabler
- BeaRerM https://github.com/Luckyji6/BeaRerM
- ModemManager MTK 插件 https://chromium.googlesource.com/chromiumos/third_party/modemmanager-next/+/master/plugins/mtk/mm-broadband-modem-mtk.c
- MTK atcid 源码 https://github.com/andr3jx/MTK6577/blob/master/mediatek/source/external/atcid/atci_service/src/atci_service.c
- Android 15 service call 107/108 https://www.daniel-ritter.de/blog/change-preferred-network-mode-on-android-15-2g-3g-lte-4g-5g-with-shell-command/
- MTK AT 命令手册（老，功能机）https://github.com/luckasfb/Development_Documents/blob/master/MTK-Mediatek-Alps-Documents/AT_Command_Set_full.pdf
