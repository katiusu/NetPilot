package io.github.katiusu.netpilot.ui.screen.subpage

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.katiusu.netpilot.AppSettings
import io.github.katiusu.netpilot.LocaleHelper
import io.github.katiusu.netpilot.ui.component.SubPageScaffold
import io.github.katiusu.netpilot.ui.theme.AppTheme
import io.github.katiusu.netpilot.ui.util.applyWindowBackground
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

abstract class BaseSubPageActivity : ComponentActivity() {

    @get:StringRes
    protected abstract val titleRes: Int

    protected open val topBarActions: (@Composable () -> Unit)? = null

    @Composable
    protected abstract fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    )

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = AppSettings.load(this)
        val themeMode = try {
            ColorSchemeMode.valueOf(settings.themeMode)
        } catch (_: Exception) {
            ColorSchemeMode.System
        }

        applyWindowBackground(settings.themeMode)

        setContent {
            AppTheme(themeMode = themeMode) {
                SubPageScaffold(
                    title = stringResource(titleRes),
                    isBlurEnabled = settings.isBlurEnabled,
                    onBack = { finish() },
                    topBarActions = topBarActions,
                ) { padding ->
                    SubPageContent(settings.isBlurEnabled, padding)
                }
            }
        }
    }
}
