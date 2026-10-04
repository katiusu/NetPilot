# 代码来源与许可追溯

按文件列出「参考了什么」，用于许可证合规审查。

## MIT — 可直接使用，需保留版权声明

### Network_Enhance（https://github.com/ScarletHanami/Network_Enhance, MIT, (c) 2026 寒碑听风）
Shell 脚本（Magisk/KSU 模块），不是 Android 应用。

| 借鉴到的文件 | 借鉴内容 |
|---|---|
| `core/monitor/FakeSignalDetector.kt` | 判定顺序与严格比较语义（源码 `common.sh:924-990`）：先 `RSRP > 阈值`，再 OR(`Ping > 阈值`, `SINR < 阈值`, `Ping 失败`) |
| `core/monitor/MonitorModels.kt` | 全部默认阈值：RSRP -85 / Ping 200ms / SINR 0 / 冷却 1800s / 恢复 3 轮 / 无网回滚 2 轮 / 间隔 120s / 降级模式 9 / 锁定 LTE 模式 11 |
| `core/monitor/AutoDowngradeEngine.kt` | 状态机与 5 条转换规则；**两处有意偏离已在文件头注释写明**（持久化+自愈、无网回滚后退出自动模式） |
| `core/monitor/MonitorSettings.kt` | 运营商 PNM 修正表（电信 27 / 移动 32 / 联通 26 / 广电 33） |
| `core/priv/root/RootController.kt` | `settings put global preferred_network_mode[1]` 作为兜底下发路径的可行性（其 README 明确记录免 Root 下 `service call phone` 被拒、`setprop persist.*` 无效，但该 setting 有效） |

### TrafficSIM（https://github.com/L-aros/TrafficSIM, MIT, (c) 2026 L-aros）
LSPosed 模块。**只借鉴了规则字段与语义，没有复制 Xposed Hook 代码。**

| 借鉴到的文件 | 借鉴内容 |
|---|---|
| `core/datacard/DataCardModels.kt` | `DataCardRule` 的字段设计：ssid / bssid / targetSubId / priority / cooldownSec / revertOnLeave |
| `core/datacard/DataCardEngine.kt` | 匹配优先级（BSSID 精确 > SSID > 任意）、冷却时间、离开 Wi-Fi 回切 |

## GPL-3.0 — ⚠️ 有传染性，见 README 第六节

### NetworkSwitch（https://github.com/aunchagaonkar/NetworkSwitch, GPL-3.0）
Root/Shizuku 双通道网络切换应用。

| 涉及文件 | 程度 |
|---|---|
| `core/mode/NetworkModeBitmaskMapper.kt` | **逐字移植**（仅改 package），来源 `NetworkModeBitmaskMapper.kt` |
| `core/mode/NetworkMode.kt` | 制式枚举 0..33 与 `carrierDefault` 运营商默认表，来源 `NetworkSwitchModels.kt` |
| `core/priv/TelephonyReflection.kt` | 隐藏 API 的调用形状与 `HiddenApiBypass` 豁免前缀列表 |
| `core/priv/ControlManager.kt` | `ControlMethod` / `ChannelStatus` 的通道抽象形状 |
| `app/src/main/aidl/.../IShizukuController.aidl` | AIDL 接口形状 |

> 处理建议（三选一）：
> 1. 自己用 → 无需处理；
> 2. 分发 → 整个项目以 GPL-3.0 开源，附完整源码与本文件；
> 3. 分发且不想 GPL → clean-room 重写上表 5 个文件（枚举是 Android 公开常量 `TelephonyManager.NETWORK_MODE_*` 的机械映射，位运算由平台定义，重写成本低且不构成衍生作品）。

## Apache-2.0

- [Miuix](https://github.com/YuKongA/Miuix) 0.9.4（`top.yukonga.miuix.kmp`）—— UI 组件库
- [Haze](https://github.com/chrisbanes/haze) —— 模糊背景
- AndroidX / Jetpack Compose
- [Shizuku](https://github.com/RikkaApps/Shizuku) API 13.1.5 —— 特权通道（运行时依赖用户安装的 Shizuku）

## 本地模板（用户自有）

`MiuixGui` 模板：项目骨架、`prefs/` 偏好体系、`ui/component/` 组件、液态玻璃导航栏、模糊工具。**非第三方代码。**
