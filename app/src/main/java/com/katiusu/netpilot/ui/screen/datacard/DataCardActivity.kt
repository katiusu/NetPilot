package com.katiusu.netpilot.ui.screen.datacard

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.katiusu.netpilot.R
import com.katiusu.netpilot.core.NetPilot
import com.katiusu.netpilot.core.datacard.DataCardEngine
import com.katiusu.netpilot.core.datacard.DataCardRule
import com.katiusu.netpilot.core.datacard.DataCardStore
import com.katiusu.netpilot.core.datacard.DataCardStrategy
import com.katiusu.netpilot.core.datacard.SimPolicy
import com.katiusu.netpilot.core.datacard.SimPolicyMode
import com.katiusu.netpilot.core.datacard.SimSlotInfo
import com.katiusu.netpilot.ui.screen.subpage.BaseSubPageActivity
import com.katiusu.netpilot.ui.util.LocalSubPageScrollBehavior
import com.katiusu.netpilot.ui.util.pageScrollModifiers
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 数据卡与双卡策略页。
 *
 * 三块内容：
 * 1. SIM 卡列表（可切换默认数据卡）；
 * 2. 每张卡一张 Card：总开关 + 策略模式下拉（跟随系统 / 网络质量自动降级 /
 *    连 Wi-Fi 时降为 4G / 自定义），选「自定义」时下面出现两项各自带开关的策略；
 * 3. 独立的 Wi-Fi 规则库（增删改规则，规则自带「切到哪张卡」）。
 *
 * 规则触发依赖监控循环每次采样的 SSID/BSSID（Android 8 之后静态 CONNECTIVITY_ACTION 已失效，
 * 因此不注册广播，见 core/datacard/DataCardEngine.kt）。
 *
 * 历史上这里还有一块「这张卡订阅的 Wi-Fi 规则（可多选）」勾选列表。按用户要求
 * **整块删除**：规则切到哪张卡回到「规则自带 targetSubId」，卡下面不再挂规则订阅。
 */
class DataCardActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.np_dc_title

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        DataCardScreen(contentPadding)
    }
}

@Composable
private fun DataCardScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = LocalSubPageScrollBehavior.current

    var sims by remember { mutableStateOf<List<SimSlotInfo>>(emptyList()) }
    var policies by remember { mutableStateOf<Map<Int, SimPolicy>>(emptyMap()) }
    var rules by remember { mutableStateOf<List<DataCardRule>>(emptyList()) }
    var rulesEnabled by remember { mutableStateOf(false) }
    var defaultSubId by remember { mutableStateOf(-1) }
    var editing by remember { mutableStateOf<DataCardRule?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun reload() {
        sims = NetPilot.sims(context)
        policies = DataCardStore.policies(context)
        rules = DataCardStore.rules(context)
        rulesEnabled = DataCardStore.rulesEnabled(context)
        defaultSubId = NetPilot.defaultDataSubId()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { reload() }
    }

    fun setDefaultSim(subId: Int) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { NetPilot.setDefaultDataSubId(context, subId) }
            Toast.makeText(
                context,
                if (ok) R.string.np_dc_switch_ok else R.string.np_dc_switch_fail,
                Toast.LENGTH_SHORT,
            ).show()
            withContext(Dispatchers.IO) { reload() }
            busy = false
        }
    }

    /** 没配置过的卡现造一个默认策略 —— 和 [DataCardStore.policyOf] 的回退值保持一致，
     *  但不在这里读磁盘（组合期读 Prefs 会踩 StrictMode）。 */
    fun policyOr(sim: SimSlotInfo): SimPolicy =
        policies[sim.subId] ?: SimPolicy(subId = sim.subId, slotIndex = sim.slotIndex)

    fun updatePolicy(target: SimPolicy) {
        DataCardStore.setPolicy(context, target)
        policies = DataCardStore.policies(context)
    }

    fun newRule(targetSubId: Int = -1): DataCardRule = DataCardRule(
        id = UUID.randomUUID().toString(),
        name = "${context.getString(R.string.np_dc_rule_add)} ${rules.size + 1}",
        targetSubId = targetSubId,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrollBehavior != null) {
                    Modifier.pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                    )
                } else {
                    Modifier
                }
            ),
        contentPadding = contentPadding,
    ) {
        item {
            SmallTitle(text = stringResource(R.string.np_dc_section_sim))
        }
        if (sims.isEmpty()) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    BasicComponent(title = stringResource(R.string.np_dc_no_sim), summary = null)
                }
            }
        } else {
            items(sims, key = { it.subId }) { sim ->
                SimCard(
                    sim = sim,
                    isDefault = sim.subId == defaultSubId,
                    enabled = !busy,
                    onSetDefault = { setDefaultSim(sim.subId) },
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.dc_section_policy_rules))
        }
        // 每张 SIM 一张 Card：头部是这张卡的总开关，下面是策略模式与「自定义」的子开关。
        items(sims, key = { "policy_${it.subId}" }) { sim ->
            val policy = policyOr(sim)
            SimPolicyCard(
                sim = sim,
                policy = policy,
                onEnabledChange = { checked -> updatePolicy(policyOr(sim).copy(enabled = checked)) },
                onModeChange = { mode -> updatePolicy(policyOr(sim).copy(mode = mode)) },
                onCustomNetworkQualityChange = { on ->
                    updatePolicy(policyOr(sim).copy(customNetworkQuality = on))
                },
                onCustomWifiDowngradeChange = { on ->
                    updatePolicy(policyOr(sim).copy(customWifiDowngrade = on))
                },
            )
        }

        item {
            SmallTitle(text = stringResource(R.string.dc_section_rule_library))
        }
        item {
            RuleIntroCard()
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.np_dc_section_rules),
                    summary = stringResource(R.string.dc_rule_library_summary),
                    checked = rulesEnabled,
                    onCheckedChange = {
                        DataCardStore.setRulesEnabled(context, it)
                        rulesEnabled = it
                    },
                )
            }
        }
        if (rules.isEmpty()) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    BasicComponent(title = stringResource(R.string.np_dc_rule_empty), summary = null)
                }
            }
        } else {
            items(rules, key = { it.id }) { rule ->
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    BasicComponent(
                        title = ruleTitle(rule),
                        summary = ruleLibrarySummary(rule, sims),
                    )
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.np_dc_rule_edit),
                            onClick = { editing = rule },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = stringResource(R.string.np_dc_rule_delete),
                            onClick = {
                                DataCardStore.saveRules(context, rules.filterNot { it.id == rule.id })
                                rules = DataCardStore.rules(context)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TextButton(
                    text = stringResource(R.string.dc_rule_add_custom),
                    onClick = { editing = newRule() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { rule ->
        RuleEditorDialog(
            rule = rule,
            sims = sims,
            onDismiss = { editing = null },
            onSave = { updated ->
                val next = rules.filterNot { it.id == updated.id } + updated
                DataCardStore.saveRules(context, next.sortedByDescending { it.priority })
                rules = DataCardStore.rules(context)
                editing = null
            },
        )
    }
}

/**
 * 规则说明卡。
 *
 * 用户反馈「规则也没有说明管理什么」：这里一次讲清四件事 —— 规则管的是默认数据卡、
 * 命中后切到哪张卡由规则自己指定、匹配优先级怎么排、冷却时间是干嘛的。
 */
@Composable
private fun RuleIntroCard() {
    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        MiuixText(
            text = stringResource(R.string.dc_intro_title),
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.main,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        MiuixText(
            text = stringResource(R.string.dc_intro_body),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        MiuixText(
            text = stringResource(R.string.dc_intro_match),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        MiuixText(
            text = stringResource(R.string.dc_intro_cooldown),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

/**
 * 一张卡一张 Card：总开关 → 策略模式下拉 → （模式 = 自定义时）两项带开关的策略。
 *
 * 视觉规则：
 * - 总开关关掉时，整块子项降透明度（仍然可以点，方便先把策略配好再打开开关）；
 * - 「自定义」下某一项自身关掉时，这一行也降透明度，一眼看出哪条没启用。
 */
@Composable
private fun SimPolicyCard(
    sim: SimSlotInfo,
    policy: SimPolicy,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (SimPolicyMode) -> Unit,
    onCustomNetworkQualityChange: (Boolean) -> Unit,
    onCustomWifiDowngradeChange: (Boolean) -> Unit,
) {
    // 有效策略集合的唯一出处：界面「亮还是暗」和执行侧「跑还是不跑」读的是同一个函数。
    val active = DataCardEngine.effectiveStrategies(policy)

    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        SwitchPreference(
            title = stringResource(R.string.dc_policy_switch_title, sim.slotIndex + 1, sim.title),
            summary = stringResource(R.string.dc_policy_switch_summary),
            checked = policy.enabled,
            onCheckedChange = onEnabledChange,
        )
        WindowDropdownPreference(
            items = SimPolicyMode.entries.map { it.label },
            selectedIndex = policy.mode.ordinal,
            title = stringResource(R.string.np_dc_policy_title, sim.slotIndex + 1),
            summary = policy.mode.desc,
            onSelectedIndexChange = { index ->
                onModeChange(SimPolicyMode.entries.getOrElse(index) { SimPolicyMode.FOLLOW_SYSTEM })
            },
        )
        if (policy.mode == SimPolicyMode.CUSTOM) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (policy.enabled) 1f else 0.38f),
            ) {
                MiuixText(
                    text = stringResource(R.string.dc_custom_title),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp),
                )
                StrategySwitchRow(
                    title = stringResource(R.string.dc_custom_network_quality),
                    summary = stringResource(R.string.dc_custom_network_quality_desc),
                    checked = policy.customNetworkQuality,
                    dimmed = policy.enabled && DataCardStrategy.NETWORK_QUALITY !in active,
                    onCheckedChange = onCustomNetworkQualityChange,
                )
                StrategySwitchRow(
                    title = stringResource(R.string.dc_custom_wifi_downgrade),
                    summary = stringResource(R.string.dc_custom_wifi_downgrade_desc),
                    checked = policy.customWifiDowngrade,
                    dimmed = policy.enabled && DataCardStrategy.WIFI_DOWNGRADE !in active,
                    onCheckedChange = onCustomWifiDowngradeChange,
                )
            }
        }
    }
}

/**
 * 「自定义」模式下的一项策略：一行带独立开关，配一句话说明「触发 / 动作 / 恢复」。
 *
 * [dimmed] 只表达「这一项自己关着」（且总开关是开的）—— 总开关关掉时由外层 Column
 * 统一变暗。两层 alpha 会相乘（0.38 × 0.38 ≈ 0.14），文字会淡到看不清，所以这里
 * 明确把两种情况分开：谁暗、暗多少，只有一处决定。
 */
@Composable
private fun StrategySwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    dimmed: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.38f else 1f),
    ) {
        SwitchPreference(
            title = title,
            summary = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SimCard(
    sim: SimSlotInfo,
    isDefault: Boolean,
    enabled: Boolean,
    onSetDefault: () -> Unit,
) {
    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        BasicComponent(
            title = stringResource(R.string.np_dc_sim_title, sim.slotIndex + 1, sim.title),
            summary = sim.carrierName.ifBlank { sim.displayName },
        )
        if (isDefault) {
            MiuixText(
                text = stringResource(R.string.np_dc_is_default),
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote2,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
        } else {
            TextButton(
                text = stringResource(R.string.np_dc_set_default),
                onClick = onSetDefault,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            )
        }
    }
}
