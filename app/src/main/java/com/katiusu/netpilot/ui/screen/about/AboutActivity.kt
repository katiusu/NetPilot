package com.katiusu.netpilot.ui.screen.about

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
import com.katiusu.netpilot.AppSettings
import com.katiusu.netpilot.LicenseActivity
import com.katiusu.netpilot.ui.theme.AppTheme
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 「关于」独立页面。
 *
 * 之所以单开一个 Activity 而不是塞进底栏：底栏已经排了 5 页，而
 * [AboutPageContent] 自带顶栏（且没有返回按钮），套进 SubPageScaffold 会出现双顶栏。
 * 这里在内容之上叠一个浮动返回按钮，既保留它自带的折叠 Logo 顶栏，又给了明确的退路。
 */
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
