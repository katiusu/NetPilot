# 代码来源与许可追溯

按文件列出「参考了什么」，用于许可证合规审查。

## MIT — 可直接使用，需保留版权声明

### Network_Enhance（https://github.com/ScarletHanami/Network_Enhance, MIT, (c) 2026 寒碑听风）
Shell 脚本（Magisk/KSU 模块），不是 Android 应用。

| 借鉴到的文件 | 借鉴内容 |
|---|---|
| `core/monitor/FakeSignalDetector.kt` | 判定顺序与严格比较语义（源码 `common.sh:924-990`）：先 `RSRP > 阈值`，再 OR(`Ping > 阈值`, `SINR < 阈值`, `Ping 失败`) |
| `core/monitor/MonitorModels.kt` | 阈值字段与含义（上游 `config.sh` 的取值：RSRP -85 / Ping 200ms / SINR 0 / 冷却 1800s / 恢复 3 轮 / 无网回滚 2 轮 / 间隔 120s / 降级模式 9 / 锁定 LTE 模式 11）。本应用自 1.1.0 起把默认值收敛为 冷却 60s / 恢复 2 轮 / 无网回滚 2 轮 / 间隔 60s，其余与上游一致 |
| `core/monitor/AutoDowngradeEngine.kt` | 状态机与 5 条转换规则；**两处有意偏离已在文件头注释写明**（持久化+自愈、无网回滚后退出自动模式） |
| `core/monitor/MonitorSettings.kt` | 运营商 PNM 修正表（电信 27 / 移动 32 / 联通 26 / 广电 33） |
| `core/priv/root/RootController.kt` | `settings put global preferred_network_mode[1]` 作为兜底下发路径的可行性（其 README 明确记录免 Root 下 `service call phone` 被拒、`setprop persist.*` 无效，但该 setting 有效） |

### TrafficSIM（https://github.com/L-aros/TrafficSIM, MIT, (c) 2026 L-aros）
LSPosed 模块。**只借鉴了规则字段与语义，没有复制 Xposed Hook 代码。**

| 借鉴到的文件 | 借鉴内容 |
|---|---|
| `core/datacard/DataCardModels.kt` | `DataCardRule` 的字段设计：ssid / bssid / targetSubId / priority / cooldownSec / revertOnLeave |
| `core/datacard/DataCardEngine.kt` | 匹配优先级（BSSID 精确 > SSID > 任意）、冷却时间、离开 Wi-Fi 回切 |

## NetworkSwitch（GPL-3.0）— 参考与 clean-room 重写记录

### NetworkSwitch（https://github.com/aunchagaonkar/NetworkSwitch, GPL-3.0）
Root/Shizuku 双通道网络切换应用。**仅作为行为参考与隐藏 API 目标清单来源**：下表是上游与本案的实测重合度，以及已 clean-room 重写的文件。

测量方法 = `tools/check_provenance.py`（剥离块注释/行注释 → 按标识符·数字·符号 tokenize → `difflib.SequenceMatcher` 求相似度、≥12 token 连续重合串占比、最长重合串）：

| 我们的文件 | 上游对应文件 | 相似度 | ≥12 串占比 | 最长串 |
|---|---|---|---|---|
| `core/priv/TelephonyReflection.kt` | `service/TelephonyReflection.kt` | 0.761 → **0.291** | 98.2% → **11.3%** | 745 → **25** |
| `core/mode/NetworkModeBitmaskMapper.kt` | `service/NetworkModeBitmaskMapper.kt` | 0.292 | 12.1% | 47 |
| `core/mode/NetworkMode.kt` | `domain/model/NetworkSwitchModels.kt` | 0.239 | 0% | — |
| `core/priv/ControlManager.kt` | `data/source/NetworkControlDataSource.kt` | 0.074 | 0% | — |
| `core/priv/shizuku/ShizukuController.kt` | `data/source/ShizukuNetworkControlDataSource.kt` | 0.181 | 4.8% | 25 |
| `core/priv/shizuku/ShizukuControllerService.kt` | `service/ShizukuControllerService.kt` | 0.527 | 27.2% | 31 |
| `app/src/main/aidl/.../IShizukuController.aidl` | 同名文件 | 0.630 | 0% | — |

（加粗的两列是「重写前 → 重写后」。）

**重写记录**：`core/priv/TelephonyReflection.kt` 曾是唯一实质逐字的部分（相似度 0.761、98.2% 的 token 落在 ≥12 的公共串里、最长 745 token）。2026-10-04 重写为独立实现：把「猜哪个反射重载能用」改成运行时候选表逐条试调（`Candidate`/`dispatch`），公开方法面与隐藏 API 目标集合不变；重写后相似度 0.291、≥12 串占比 11.3%、最长串 25 token。

**剩余重合的构成**（逐条核对，均非表达层独创内容）：

- `ShizukuControllerService.kt` 的两条长串是 **AIDL 接口签名**（`override fun getCurrentNetworkMode(subId: Int): Int`、`class ShizukuControllerService() : IShizukuController.Stub() {`），由本项目自己的 `.aidl` 契约决定；
- `NetworkModeBitmaskMapper.kt` 的最长串（47）是构造参数名与 AOSP 常量值（`MAX_NETWORK_MODE = 33`、`ALL_NETWORK_TYPES`、`reasonUser` 等），属平台事实；
- `TelephonyReflection.kt` 的最长串（25）是 `HiddenApiBypass.addHiddenApiExemptions("Landroid/telephony/", "Lcom/android/internal/telephony/", …)` 的调用惯用法与豁免前缀清单（前缀由目标接口决定，属事实信息）；
- 其余为 import 块与公开方法签名。

复现：

```bash
git clone --depth 1 https://github.com/aunchagaonkar/NetworkSwitch _ref/networkswitch
python3 tools/check_provenance.py     # 打印上表；两条已复核的重合打印 reviewed 说明，不影响退出码
```

**许可影响**：1.0.1 及更早的版本移植了上表第一行的代码，因此那些版本以 GPL-3.0 分发。GPL-3.0 授权不可撤回，已分发的副本（含 Releases 中的 1.0.1 APK）仍受其约束；自 clean-room 重写后的提交起，本项目以 **Apache-2.0** 发布（见 [`LICENSE`](../LICENSE)）。

## Apache-2.0

- [Miuix](https://github.com/YuKongA/Miuix) 0.9.4（`top.yukonga.miuix.kmp`）—— UI 组件库
- [Haze](https://github.com/chrisbanes/haze) —— 模糊背景
- AndroidX / Jetpack Compose
- [Shizuku](https://github.com/RikkaApps/Shizuku) API 13.1.5 —— 特权通道（运行时依赖用户安装的 Shizuku）

## 本地模板（用户自有）

`MiuixGui` 模板：项目骨架、`prefs/` 偏好体系、`ui/component/` 组件、液态玻璃导航栏、模糊工具。**非第三方代码。**

**检查更新模组（1.2.0 新增）**：`core/update/UpdateChecker.kt` 与 `ui/component/UpdateDialog.kt` 移植自**同一作者**的另外两个工程（`MiuixGui`、`HyperImmersiveTaskbar`）里同形的 `UpdateChecker` / `UpdateDialog` 实现，两者逐字相同、均为用户自有代码。检查地址已按本工程改为
`https://api.github.com/repos/katiusu/NetPilot/releases/latest`（本项目自己的 Releases），不再指向任何模板占位仓库。

- 特性：`Check-Update-Module`（同作者工程）
- 许可证：无第三方许可证约束（用户自有代码）
- 查询命令：`grep -rn "github.com" app/src/main/java/com/katiusu/netpilot/core/update/`
  —— 只应出现 `katiusu/NetPilot`。
