# NetPilot 快捷设置磁贴（QS Tile）

本文档描述 NetPilot 提供的两个快捷设置磁贴：它们的作用、实现行为、需要合并进
`AndroidManifest.xml` 的片段，以及使用与排障方法。

两个磁贴都继承 `android.service.quicksettings.TileService`，只用系统自带的
Quick Settings API 实现，**不引入任何第三方依赖**，也不申请额外权限（磁贴本身
不需要权限，但两个磁贴的实际功能都依赖特权通道，见「前置条件」）。

---

## 一、两个磁贴

| 磁贴 | 实现类 | 点击行为 |
| --- | --- | --- |
| 网络模式 | `com.katiusu.netpilot.core.qs.NetworkModeTileService` | 在 4 种制式之间**循环切换**默认数据卡的优选网络模式 |
| 自动降级 | `com.katiusu.netpilot.core.qs.DowngradeTileService` | **开 / 关**假 5G 自动降级监控 |

对应源文件：

- `app/src/main/java/com/katiusu/netpilot/core/qs/NetworkModeTileService.kt`
- `app/src/main/java/com/katiusu/netpilot/core/qs/DowngradeTileService.kt`

### 1. 网络模式磁贴

点击顺序固定为一个闭环（不读取、不依赖 `NetworkMode.quickPresets`，避免其
元素类型变化带来的耦合）：

```
仅 5G (NR_ONLY, 23)
  → 5G/4G 自动 (NR_LTE, 24)
  → 仅 4G (LTE_ONLY, 11)
  → 4G/3G/2G 自动 (LTE_GSM_WCDMA, 9)
  → 回到 仅 5G
```

> 注意：`LTE_GSM_WCDMA`（值 9）的标签是 `4G/3G/2G 自动`，它**不包含 NR**，
> 因此在磁贴副标题里显示为 4G 档，不会被误标成 5G。

行为细节：

- 副标题（`tile.subtitle`）显示当前模式的短名称，取自
  `NetPilot.currentModeShort(subId)`；读不到时显示 `qs_network_mode_unknown`。
- 点击后先按**当前实际模式**在循环里定位：命中则切到下一项；命中不到
  （例如当前是厂商私有模式、或通道不可用导致读数为 -1）则**从第一项开始**，
  保证每次点击都有可预期的结果，而不是「点了没反应」。
- 切换过程中磁贴被临时置为 `STATE_UNAVAILABLE` 并显示「切换中…」
  （`qs_network_mode_busy`），防止用户以为没反应而连点；切换结束后立即回到
  `STATE_ACTIVE` 并刷新副标题。
- 没有默认数据卡（`subId < 0`）时：磁贴为 `STATE_UNAVAILABLE`，副标题为
  `qs_network_mode_no_sim`。
- 成功提示 Toast `qs_network_mode_switched`（填入新模式标签）；失败提示
  Toast `qs_network_mode_failed`，参数是 `NetPilot.channelLabel()`，用来告诉
  用户当前到底有没有可用通道。
- 生命周期：`onStartListening()` 时刷新一次；`onDestroy()` 里取消协程作用域。

### 2. 自动降级磁贴

- 三态显示：
  - **没有特权通道**（`NetPilot.channelLabel()` 不是 `Root`/`Shizuku`）→
    `STATE_UNAVAILABLE`，副标题 `qs_downgrade_no_channel`。
  - 自动降级**已开** → `STATE_ACTIVE`。
  - 自动降级**已关** → `STATE_INACTIVE`。
- 点击调用 `NetPilot.toggleAutoDowngrade(context)`（返回切换后的新状态），
  然后用 Toast `qs_downgrade_toast_on` / `qs_downgrade_toast_off` 反馈。
- 副标题额外显示**降级状态机的当前阶段**，由
  `MonitorEngine.downgrade.value.phase(System.currentTimeMillis(), NetPilot.thresholds())`
  实时计算：

| `MonitorPhase` | 副标题字符串 |
| --- | --- |
| `OFF` | `qs_downgrade_off` |
| `WATCHING` | `qs_downgrade_watching` |
| `DOWNGRADED_COOLDOWN` | `qs_downgrade_cooldown` |
| `RECOVERING` | `qs_downgrade_recovering` |
| `ROLLBACK` | `qs_downgrade_rollback` |

- 开关自动降级会同时启动 / 停止前台监控服务（`MonitorService`），所以点击后
  可能看到常驻通知出现或消失，这是预期行为。

---

## 二、线程模型（为什么不用 `runBlocking`）

`TileService.onClick()` 运行在 QS 面板的 binder 线程上，在里面 `runBlocking`
等待跨进程调用会**卡住整个快捷设置面板**。因此两个磁贴都自建：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

- `NetPilot` 的挂起函数内部会自行切换到 IO 线程，磁贴不需要占主线程；
- 只有 `Toast` 需要 Looper，单独用 `withContext(Dispatchers.Main)` 兜底；
- 同一时刻只允许一个渲染 / 切换任务，新任务进来先取消旧的 (`pending?.cancel()`)，
  避免快速连点导致的状态错乱；
- `onDestroy()` 中 `scope.cancel()`，不留泄漏。

---

## 三、需要合并进 AndroidManifest.xml 的片段

把下面两段加在 `<application>` 内部，与 `MonitorService` 的 `<service>` 平级：

```xml
        <!-- NetPilot：网络模式循环磁贴 -->
        <service
            android:name=".core.qs.NetworkModeTileService"
            android:exported="true"
            android:icon="@drawable/ic_np_notification"
            android:label="@string/qs_network_mode_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>

        <!-- NetPilot：自动降级开关磁贴 -->
        <service
            android:name=".core.qs.DowngradeTileService"
            android:exported="true"
            android:icon="@drawable/ic_np_notification"
            android:label="@string/qs_downgrade_label"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
```

要点：

- `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"` 是**系统
  绑定磁贴服务的必要条件**，缺了系统不会绑定，磁贴会一直显示不出来。
- `android:exported="true"` 同样是必需的（系统要跨进程绑定），但因为上面那条
  signature 级权限，普通应用无法调用它。
- 图标与文案：磁贴的图标 / 标签在代码里通过 `tile.icon` / `tile.label` 设置，
  清单里的 `android:icon` / `android:label` 只为服务自身提供默认值，二者不冲突。
- **不需要** `<meta-data android:name="android.service.quicksettings.ACTIVE_TILE">`
  或 `TOGGLEABLE_TILE`：磁贴的启用 / 禁用状态完全由代码通过
  `tile.state` + `tile.updateTile()` 自己维护，交给系统自动翻转反而会和我们的
  状态机打架。
- 本功能**不需要**新增任何 `<uses-permission>`。

---

## 四、如何使用

1. 安装并至少打开一次 NetPilot。
2. 下拉通知栏 → 再下拉一次展开完整快捷设置面板 → 点「编辑」（铅笔）图标。
3. 在「未添加的磁贴」里找到 **网络模式** 与 **自动降级**，拖到面板上。
4. 回到面板点击即可：
   - 网络模式磁贴每点一次切换一档制式；
   - 自动降级磁贴点击即开关假 5G 自动降级。

---

## 五、前置条件

两个磁贴的**显示**不需要任何权限，但要真正生效需要：

- **网络模式磁贴**：需要有可用的特权通道（Root 或 Shizuku）来写入网络模式。
  没有通道时，磁贴仍会显示当前模式，但点击切换会失败并 Toast
  `qs_network_mode_failed`（参数为 `NetPilot.channelLabel()`）。
- **自动降级磁贴**：需要 Root / Shizuku 通道，否则磁贴直接显示为
  **不可用** 并提示「无特权通道」。开启后需要 `POST_NOTIFICATIONS` 权限才能
  看到前台服务通知（与监控页共用同一个前台服务）。
- 需要一张可用的 SIM 卡作为默认数据卡。

---

## 六、排障

| 现象 | 原因 / 处理 |
| --- | --- |
| 编辑面板里找不到这两个磁贴 | 清单片段没合并，或 `android:permission` / `android:exported` 缺失导致系统绑定失败。检查 Manifest 后重装应用（磁贴列表由系统在安装时扫描，改完必须重装）。 |
| 磁贴显示为「不可用」且副标题是「无特权通道」 | 没有 Root / Shizuku 通道，去设置页申请 Shizuku 权限或确认 Root 授权。 |
| 网络模式磁贴点击后副标题来回跳，但网络没变 | 特权通道不可用；失败 Toast 里会带当前通道名。 |
| 自动降级磁贴点了，通知没出现 | 检查通知权限（Android 13+ 需要 `POST_NOTIFICATIONS`）。 |
| 点了没反应 | 磁贴处于 `STATE_UNAVAILABLE`（无数据卡 / 切换中）时不会响应点击；等切换结束再试。 |

---

## 七、相关字符串资源

- `app/src/main/res/values/strings_qs.xml`：两个磁贴共用的 15 条 `qs_` 前缀字符串。
- 磁贴复用 `res/drawable/ic_np_notification.xml` 作为图标（与前台服务通知同一个图标）。

---

## 八、已知限制

- 网络模式磁贴只作用于**默认数据卡**（`NetPilot.defaultDataSubId()`），不提供
  多卡选择；如需按卡切换请使用应用内的功能页。
- 循环档位是写死的 4 档，不跟随用户在功能页里的自定义预设顺序。
- 磁贴副标题受系统限制，只能显示很短的文本，因此阶段名使用简写
  （如「冷却中」而不是完整句子）。
