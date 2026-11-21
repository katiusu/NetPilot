package io.github.katiusu.netpilot.ui.screen.about

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.katiusu.netpilot.AppSettings
import io.github.katiusu.netpilot.LicenseActivity
import io.github.katiusu.netpilot.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings = remember { AppSettings.load(this) }
            val mode = remember(settings.themeMode) {
                runCatching { ColorSchemeMode.valueOf(settings.themeMode) }
                    .getOrDefault(ColorSchemeMode.System)
            }

            AppTheme(themeMode = mode) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AboutPageContent(
                        openLicensePage = {
                            startActivity(Intent(this@AboutActivity, LicenseActivity::class.java))
                        },
                        isBlurEnabled = settings.isBlurEnabled,
                    )
                    IconButton(
                        onClick = { finish() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 4.dp, top = 4.dp),
                    ) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = null)
                    }
                }
            }
        }
    }
}
