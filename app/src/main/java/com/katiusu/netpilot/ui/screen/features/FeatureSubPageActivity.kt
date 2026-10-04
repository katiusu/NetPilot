package com.katiusu.netpilot.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.katiusu.netpilot.R
import com.katiusu.netpilot.ui.component.pref.HookOptionView
import com.katiusu.netpilot.ui.component.pref.HookSection
import com.katiusu.netpilot.ui.component.pref.HookSectionCard
import com.katiusu.netpilot.ui.component.pref.HookSubPage
import com.katiusu.netpilot.ui.screen.subpage.BaseSubPageActivity
import com.katiusu.netpilot.ui.util.LocalSubPageScrollBehavior
import com.katiusu.netpilot.ui.util.pageScrollModifiers

/**
 * 功能二级页面：演示基于 [BaseSubPageActivity] 的独立 Activity 子页面。
 *
 * 本页配置项由功能页通过 [HookSubPage] 并入搜索，命中即可直接进入本页，因此子页面无需再放搜索栏。
 */
class FeatureSubPageActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.example_arrow_sub_title

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val specs = remember { featureSpecs() }
        val section = remember(specs) {
            HookSection(
                titleRes = R.string.example_arrow_sub_title,
                specs = listOf(specs.first { it.key == "example_sub_text" }),
            )
        }
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
                HookSectionCard(section) {
                    section.specs.forEach { spec ->
                        HookOptionView(spec = spec)
                    }
                }
            }
        }
    }
}
