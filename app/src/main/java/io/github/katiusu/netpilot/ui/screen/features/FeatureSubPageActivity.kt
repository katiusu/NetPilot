package io.github.katiusu.netpilot.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.component.pref.HookOptionView
import io.github.katiusu.netpilot.ui.component.pref.HookSection
import io.github.katiusu.netpilot.ui.component.pref.HookSectionCard
import io.github.katiusu.netpilot.ui.component.pref.HookSubPage
import io.github.katiusu.netpilot.ui.screen.subpage.BaseSubPageActivity
import io.github.katiusu.netpilot.ui.util.LocalSubPageScrollBehavior
import io.github.katiusu.netpilot.ui.util.pageScrollModifiers

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
