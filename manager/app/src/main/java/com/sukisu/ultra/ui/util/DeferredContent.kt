package com.sukisu.ultra.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope

/**
 * Returns true only after the navigation transition animation has completed
 * and an additional buffer frame has passed.
 *
 * Timeline:
 * - During animation: returns false → page shows lightweight placeholder (smooth animation)
 * - Animation ends + 1 frame: returns true → heavy content composes
 *   (stutter is invisible because the page is already static)
 *
 * The value is sticky — once true it never reverts to false,
 * so content stays visible during exit transitions.
 */
@Composable
fun rememberContentReady(): Boolean {
    val transitionRunning = LocalNavTransitionScope.current.isRunning
    val ready = remember { mutableStateOf(false) }

    LaunchedEffect(transitionRunning) {
        if (!transitionRunning && !ready.value) {
            withFrameNanos { }
            ready.value = true
        }
    }

    // paperSU: 硬超时兜底。
    //
    // 上面那条只依赖导航过渡状态。实测在 K20 Pro 上把运行模式切成 "KernelSU"（内核 4.x 上
    // 根本没有 KernelSU）之后，主内容永远组合不出来，transitionRunning 也就一直不是我们
    // 期望的状态 —— 于是 ready 永远是 false，页面永久停在启动 logo 上，只能清 prefs 才能恢复。
    // 这里加一道与过渡状态无关的兜底：最多等 CONTENT_READY_TIMEOUT_MS，然后无条件放行。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(CONTENT_READY_TIMEOUT_MS)
        ready.value = true
    }

    return ready.value
}

/** 见 rememberContentReady 里的说明。 */
private const val CONTENT_READY_TIMEOUT_MS = 1_500L
