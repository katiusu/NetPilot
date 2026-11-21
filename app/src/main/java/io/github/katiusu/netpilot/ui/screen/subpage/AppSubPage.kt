package io.github.katiusu.netpilot.ui.screen.subpage

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.katiusu.netpilot.LicenseActivity
import io.github.katiusu.netpilot.R
import io.github.katiusu.netpilot.ui.component.SubPageScaffold
import io.github.katiusu.netpilot.ui.screen.about.LicensePageContent
import io.github.katiusu.netpilot.ui.screen.features.FeatureSubPageActivity
import io.github.katiusu.netpilot.ui.screen.features.FeatureSubPageContent
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

internal enum class AppSubPage(val titleRes: Int, val activity: Class<out Activity>) {
    Features(R.string.example_arrow_sub_title, FeatureSubPageActivity::class.java),
    Licenses(R.string.third_party_licenses_title, LicenseActivity::class.java),
}

internal sealed interface AppPageRoute : NavKey {
    data object Main : AppPageRoute
    data class SubPage(val page: AppSubPage) : AppPageRoute
}

private val appPageBackStackSaver = Saver<NavBackStack, String>(
    save = { (it.lastOrNull() as? AppPageRoute.SubPage)?.page?.name.orEmpty() },
    restore = { name ->
        navBackStackOf(AppPageRoute.Main).apply {
            AppSubPage.entries.find { it.name == name }?.let { add(AppPageRoute.SubPage(it)) }
        }
    },
)

@Composable
internal fun rememberAppPageBackStack(): NavBackStack =
    rememberSaveable(saver = appPageBackStackSaver) { navBackStackOf(AppPageRoute.Main) }

internal val LocalOpenAppSubPage = staticCompositionLocalOf<((AppSubPage) -> Unit)?> { null }

@Composable
internal fun rememberOpenAppSubPage(page: AppSubPage): () -> Unit {
    val context = LocalContext.current
    val openInHost = LocalOpenAppSubPage.current
    return remember(context, openInHost, page) {
        {
            if (openInHost != null) {
                openInHost(page)
            } else {
                context.startActivity(Intent(context, page.activity))
            }
        }
    }
}

@Composable
internal fun AppSubPageContent(page: AppSubPage, isBlurEnabled: Boolean, onBack: () -> Unit) {
    if (page == AppSubPage.Licenses) {
        LicensePageContent(onBack = onBack, isBlurEnabled = isBlurEnabled)
        return
    }
    SubPageScaffold(
        title = stringResource(page.titleRes),
        isBlurEnabled = isBlurEnabled,
        onBack = onBack,
    ) { padding ->
        when (page) {
            AppSubPage.Features -> FeatureSubPageContent(padding)
            AppSubPage.Licenses -> Unit
        }
    }
}
