package com.katiusu.netpilot.core.datacard

/** 一张 SIM 卡的运行时信息。 */
data class SimSlotInfo(
    val slotIndex: Int,
    val subId: Int,
    val carrierName: String,
    val displayName: String,
    val isDefaultData: Boolean,
    val isActive: Boolean,
) {
    val title: String
        get() = when {
            displayName.isNotBlank() -> displayName
            carrierName.isNotBlank() -> carrierName
            else -> "SIM ${slotIndex + 1}"
        }
}

/**
 * 单张卡的策略模式 —— 「双卡独立策略」这条需求就落在这个模型上。
 *
 * 关键在于：降级策略可以只对某一张卡生效（比如卡 1 是主力 5G 卡、卡 2 是备用
 * 保号卡），而不是全局一刀切。
 *
 * [desc] 是下拉框里显示给用户的一行说明，必须回答三件事：**什么条件下触发、
 * 触发后做什么、什么时候切回去**。措辞不是装饰：用户反馈过「选项看不出来到底在
 * 干什么」，所以每一条都按「触发 / 动作 / 恢复」写满。
 *
 * 历史：这里曾经有 `FIXED_MODE`（固定制式）和 `AUTO_DOWNGRADE`（假 5G 自动降级）。
 * 前者已按用户要求**彻底删除**（不再支持手动锁死制式），后者只是改名为
 * [NETWORK_QUALITY]；旧配置里的这两个名字由 [DataCardStore] 做兼容映射。
 */
enum class SimPolicyMode(
    val label: String,
    val desc: String,
) {
    FOLLOW_SYSTEM(
        "跟随系统",
        "触发：无。动作：完全不动这张卡 —— 不切数据卡、不改制式，网络质量降级与 Wi-Fi " +
            "降级都不作用在它身上。恢复：无（因为没有做过任何修改）",
    ),
    NETWORK_QUALITY(
        "网络质量自动降级",
        "触发：这张卡作为默认数据卡，信号满格但网络差（Ping 超时或 SINR 过低），" +
            "或信号过差（RSRP 低于阈值）。动作：自动降到 4G。恢复：网络连续多轮恢复正常后，" +
            "自动写回降级前的完整制式（5G 自动）",
    ),
    WIFI_DOWNGRADE(
        "连 Wi-Fi 时降为 4G",
        "触发：这张卡只要连上任意一个 Wi-Fi（不看是哪个 Wi-Fi，也不看信号好坏）。" +
            "动作：立刻降到 4G 省电。恢复：断开 Wi-Fi 后，自动写回连上 Wi-Fi 之前记录的制式",
    ),
    CUSTOM(
        "自定义",
        "自己勾选下面要启用的策略，每一项都有独立开关：打开的项才生效，关掉的项完全不执行。" +
            "两项的触发/动作/恢复条件写在各自的说明里",
    ),
}

/**
 * 一张卡可以单独启用的策略项。
 *
 * 只有 [SimPolicyMode.CUSTOM] 才会逐项看开关；其余模式由模式本身决定要用哪几项
 * （见 `DataCardEngine.effectiveStrategies`，那里是这个映射的**唯一**出处）。
 */
enum class DataCardStrategy {
    /** 网络质量自动降级：假满格 / 信号过差 → 4G，网络恢复正常后自动还原。 */
    NETWORK_QUALITY,

    /** 连 Wi-Fi 时降为 4G：连上任意 Wi-Fi 就降，断开后自动还原。 */
    WIFI_DOWNGRADE,
}

/** 一张卡的策略配置。 */
data class SimPolicy(
    val subId: Int,
    val slotIndex: Int,
    val mode: SimPolicyMode = SimPolicyMode.NETWORK_QUALITY,
    /**
     * 这张卡的总开关：关掉之后所有策略都不作用在这张卡上 —— 网络质量降级、Wi-Fi
     * 降级，以及「被 Wi-Fi 规则切过去当默认数据卡」都会被跳过。
     * 界面上就是每张卡 Card 头部那个独立开关。
     */
    val enabled: Boolean = true,
    /** 允许这张卡承载移动数据。 */
    val dataAllowed: Boolean = true,
    /** Wi-Fi 降级期间的目标制式（默认 9 = 4G/3G/2G 自动，即俗称的 4G）。 */
    val downgradeModeValue: Int = 9,
    /**
     * [SimPolicyMode.CUSTOM] 下是否启用「网络质量自动降级」。
     *
     * 默认 true：从「网络质量自动降级」模式切到「自定义」时，用户原本在用的策略
     * 应当保持生效，不该因为换个模式就静默失效。
     */
    val customNetworkQuality: Boolean = true,
    /**
     * [SimPolicyMode.CUSTOM] 下是否启用「连 Wi-Fi 时降为 4G」。
     *
     * 默认 false：它会实实在在改制式，必须是用户主动打开才生效。
     */
    val customWifiDowngrade: Boolean = false,
)

/**
 * 一条「按 Wi-Fi 切换数据卡」的规则。参考 TrafficSIM 的 JSON 规则引擎，但
 * 去掉了 Xposed 依赖，改用 `settings put global multi_sim_data_call` 落地。
 */
data class DataCardRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** 空串表示「任意 Wi-Fi」。 */
    val ssid: String = "",
    /** 非空时优先级高于 SSID 匹配（同一 SSID 多个 AP 时精确定位）。 */
    val bssid: String = "",
    /**
     * 命中后要把默认数据卡切到哪个 subId；-1 表示**不切卡**。
     *
     * 这是求值的唯一依据（规则自带目标卡）：一条规则切到哪张卡，写在规则自己身上，
     * 不再由某张卡的「订阅关系」决定。
     */
    val targetSubId: Int = -1,
    /**
     * 命中后给 [targetSubId] 这张卡设置的制式裸值；0 表示不改制式。
     *
     * targetSubId 为 -1（不切卡）时它不生效 —— 因为它描述的是「目标卡要设成什么制式」。
     */
    val targetModeValue: Int = 0,
    /** 数值越大越先匹配。 */
    val priority: Int = 0,
    /** 两次切换之间的最小间隔，防止在信号边缘反复横跳。 */
    val cooldownSec: Int = 120,
    /** 离开该 Wi-Fi 后是否回到「默认卡」（[DataCardStore.homeSubId]）。 */
    val revertOnLeave: Boolean = true,
)

/** 规则匹配结果。 */
data class RuleMatch(
    val rule: DataCardRule,
    val reason: String,
)
