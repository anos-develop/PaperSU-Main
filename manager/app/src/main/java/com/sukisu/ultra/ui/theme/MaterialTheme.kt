package com.sukisu.ultra.ui.theme

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ui.util.WallpaperPrefs
import com.sukisu.ultra.ui.util.WallpaperStore
import com.sukisu.ultra.ui.util.rememberWallpaperSlot
import com.sukisu.ultra.ui.webui.MonetColorsProvider

@Composable
fun MaterialKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val amoledMode = appSettings.colorMode.isAmoled
    val dynamicColor = appSettings.keyColor == 0

    val colorScheme = rememberKernelSUColorScheme(
        seedColor = if (dynamicColor) Color.Unspecified else Color(appSettings.keyColor),
        isDark = darkTheme,
        isAmoled = amoledMode,
        paletteStyle = appSettings.paletteStyle,
        colorSpec = appSettings.colorSpec,
    )

    LaunchedEffect(darkTheme) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val animatedColorScheme = colorScheme.animateAsState()

    // paperSU (ported from 7kimisu): when a wallpaper is active and "translucent UI" is
    // enabled, make the large container colours translucent so the wallpaper shows
    // through. With no wallpaper set surfaceAlpha stays 1f and this is a no-op.
    val wallpaperRev = WallpaperPrefs.observe()
    val wallpaperSlot = rememberWallpaperSlot()
    val surfaceAlpha = remember(wallpaperRev, wallpaperSlot) {
        val r = SettingsRepositoryImpl()
        if (WallpaperStore.hasWallpaper(wallpaperSlot) && r.uiTranslucent) {
            r.uiTranslucentAlpha.coerceIn(0.02f, 1f)
        } else 1f
    }
    val themedColorScheme =
        if (surfaceAlpha >= 1f) animatedColorScheme else animatedColorScheme.translucent(surfaceAlpha)

    MaterialExpressiveTheme(
        colorScheme = themedColorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = Typography,
        content = {
            MonetColorsProvider.UpdateCss(colorScheme)
            content()
        }
    )
}

/**
 * paperSU: make the large container colours translucent (ported from 7kimisu).
 * Cards stay a bit more opaque than the page background so text remains readable.
 */
private fun ColorScheme.translucent(alpha: Float): ColorScheme {
    val card = (alpha + (1f - alpha) * 0.28f).coerceAtMost(1f)
    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceDim = surfaceDim.copy(alpha = alpha),
        surfaceBright = surfaceBright.copy(alpha = alpha),
        surfaceVariant = surfaceVariant.copy(alpha = card),
        surfaceContainer = surfaceContainer.copy(alpha = card),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = card),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = card),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = card),
        surfaceContainerLowest = surfaceContainerLowest.copy(alpha = card),
    )
}
