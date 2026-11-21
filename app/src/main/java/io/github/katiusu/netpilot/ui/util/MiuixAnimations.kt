package io.github.katiusu.netpilot.ui.util

import androidx.compose.animation.core.SpringSpec
import androidx.compose.ui.unit.IntSize
import top.yukonga.miuix.kmp.anim.folmeSpring

val MiuixExpandSpec: SpringSpec<IntSize> = folmeSpring(damping = 1.0f, response = 0.4f)
