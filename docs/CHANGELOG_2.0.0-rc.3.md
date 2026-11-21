# 2.0.0-rc.3（versionCode 22）

> 基线：2.0.0-rc.2 / versionCode 21 / 包名 io.github.katiusu.netpilot。本版是「一次性完成」的缺陷修复版：24 条审查结论 + 2 条同主题延伸项全部落地，另加一项新的只读能力（基带探测）。
> 所有改动均未在本地编译（本机为 PRoot，按要求不在本地跑 Gradle），首次真实编译在 GitHub Actions。

## 一、安全（对外接口）

1. Tasker 命令广播加口令校验。写类命令（改制式、切卡、开关降级、锁 LTE）必须带 netpilot.token，与开启接口时生成的随机口令比对（MessageDigest.isEqual + 显式 UTF-8）；只读命令（SAMPLE_NOW、GET_STATUS）放行；不在 RECEIVE_ACTIONS 的未知 action 一律拒绝（fail-closed）。口令随接口开启生成、关闭后保留。
2. 回执广播改为定向。能找到已安装的 Tasker 包就 setPackage 定向发送并带快照；找不到时只发 ok/message，不带 RSRP/SINR/Ping/通道名。
3. 事件广播（降级/恢复/制式变更/换卡/采样）改为定向，找不到目标包时直接丢弃并记一条 DEBUG。此前这五条都带快照且隐式广播。
4. Locale 插件入口加来源白名单。只接受 Tasker 两个包名与 com.android.shell；包名为 null 时放行但记 WARN（避免正常用户插件静默失效）；不可信来源在解析 Bundle 之前就拒绝。

## 二、降级状态机

5. 探测被跳过（息屏省电）时不再算「网络无响应」。原实现会把 probeSkipped 当成 ping 失败，可能误顶到无网回滚阈值。
6. 写回失败不再丢弃降级记录。原 finish() 无论成败都清 active/savedMode，一次写回失败用户就永久卡在降级制式；现在失败保留 savedMode、累加 restoreAttempts，退避重试最多 3 次，到上限后把原因写进 lastEvent 并停止重试。
7. 监控循环与手动采样加互斥锁，消除两个 tick 并发写状态机的问题。
8. 通知文本先发送后缓存。原来先写 lastNotificationText 再发通知，发送失败被吞掉后同文本永不再发。
9. 服务被拉起不再等于「用户想监控」。onCreate 不再无条件 setMonitorWanted(true)；新增持久化标记 np_monitor_user_stopped：用户点「停止」写 true，显式开启（开关/磁贴/Tasker/总开关）写 false，冷启动对账与开机自启都先看它。修复「点停止后下次打开 App 又被拉起」。

## 三、特权通道

10. settings 兜底写制式按 subId 映射到正确的键（副卡写 preferred_network_mode1），映射不出来直接失败而不是猜键；写完回读，值对上才算成功。
11. 子进程命令读取加超时。原 readText() 读到 EOF 才返回，5 秒超时是死代码；现在读线程 daemon 化、与 waitFor 共用 5 秒预算、超时 destroyForcibly 并返回已读部分。
12. 制式记忆合成不可变对象，消除三个 volatile 字段在锁外读到「新 subId + 旧值」的撕裂。
13. 通道探测移出主锁，探测期间不再阻塞其他写入；锁序固定为 probe→主锁。
14. 隐藏 API 豁免失败不再被永久缓存，下次调用可重试（只记一次警告）。
15. 切数据卡对 void 返回的反射方法改为回读校验，不再把「调用返回了」当成功；已是目标卡时短路。

## 四、双卡与 Wi-Fi 策略

16. homeSubId 改为 -1 哨兵 + 一次性迁移，修复「从未设置过主卡」时 home 恒等于当前卡的死循环；导出备份不再写 -1，恢复时 -1 视为删除该键而不是写入。
17. 降级前的制式记录改为持久化（saved_modes_before_wifi），进程被杀不再丢失；还原成功才删记录，失败保留并指数退避（60s 起，上限 15 分钟）。
18. 规则冷却改为按 (规则, 目标卡) 独立计时，全局只剩 5 秒防抖。原来一张卡的冷却会挡住另一张卡的规则。
19. 写制式的节流时间戳语义改为「上次尝试」，失败也记 —— 否则失败后下一轮采样会立刻再 fork 一次特权进程。

## 五、保活与配置

20. 亮灭屏重排闹钟加三条规则与 30 秒节流：闹钟不存在必排（安全阀）、已按当前屏幕状态排则不动、状态真变了且超过 30 秒才重排。修复频繁亮灭屏把 15 分钟心跳饿死。
21. ConfigState.reload 现在会通知 observers（值真的变了才通知，首次加载不通知），导入配置后 Tasker 组件启停等系统侧会同步。
22. 配置备份导入：policies 空数组与 rules 一致，语义为「清空」。
23. BootReceiver 的 QUICKBOOT 分支只补幂等注释，逻辑未动（厂商快速开机与 BOOT_COMPLETED 会重复到达）。

## 六、UI 与线程

24. 跳页用自增 ticket 触发，修复「跳到当前页」被 State 相等性吞掉的问题；两处 EXTRA_PAGE 读取收敛为一个函数。
25. 两个 QS 磁贴的协程作用域从 Dispatchers.Default 改到 Dispatchers.IO（阻塞的特权调用不该占 CPU 池）。
26. 日志列表改用 StateFlow 订阅，后台线程 append 也能触发重组（原 SnapshotStateList 跨线程写有风险）。

## 七、新功能：基带只读探测

27. 新增 core/radio/：按「系统属性 → 特征文件存在性 → ServiceManager 是否注册 phoneEx」三级判据识别高通 / 联发科 / 三星 / 紫光 / 未知，带置信度与证据链，结果落 np_baseband_profile（7 天）。
28. 本版只做检测，不做任何写入：不 open 任何 /dev 节点、不发任何 AT 指令（这些是 RIL 独占的 modem 串口，打开就可能抢占用）。capabilities 恒为空集。
29. 设置页系统兼容性卡片加一行「基带」，未知时显示「未知（本次探测无结论）」，不显示成功样式。

## 既有行为变更提醒

- 关闭总开关再打开会清除「用户已停止」标记（显式开启路径）。用户若认为总开关不该恢复监控，这是产品取舍，可再调。
- 已冻结的 Tasker 集成：写类命令老脚本必须补 netpilot.token，文档 docs/TASKER.md 第 3.4 节有说明。

## 已知限制（本版未处理）

- 本版本地未编译，CI 是第一次真实编译。
- 基带探测在高通开发机上无法验证联发科分支；手机端只能靠用户回传的日志（探测原文会写进日志页）。
- 自动降级写回失败达到重试上限后，UI 仍显示「降级中」（lastEvent 里写明「写回已停止重试，请手动恢复」），没有专门的警示界面。
- 用户「停止」后设置页的自动降级开关仍显示「开启」（服务不跑），界面观感不一致。
- core/bridge/XposedServiceManager.kt、core/bridge/AppRestarter.kt 仍有同类的「无超时读子进程输出」，本次未在授权范围内。
- AndroidManifest 里两个 Tasker receiver 仍 exported=true 且无 signature 权限：能挡住改状态，挡不住同机 App 用一条广播唤醒进程。
- SubscriptionSwitcher 的切换回读窗口缩短到 250ms，生效慢的 ROM 会被判失败并由下一轮采样复核（有意取舍，避免长时间占用 Shizuku 的 binder 线程）。