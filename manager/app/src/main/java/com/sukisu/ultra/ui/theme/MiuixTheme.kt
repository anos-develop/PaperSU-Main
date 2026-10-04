package com.sukisu.ultra.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl
import com.sukisu.ultra.ui.util.WallpaperPrefs
import com.sukisu.ultra.ui.util.WallpaperStore
import com.sukisu.ultra.ui.util.rememberWallpaperSlot
import com.sukisu.ultra.ui.webui.MonetColorsProvider
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@Composable
fun MiuixKernelSUTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val colorStyle = appSettings.paletteStyle
    val colorSpec = appSettings.colorSpec

    val miuixPaletteStyle = try {
        ThemePaletteStyle.valueOf(colorStyle.name)
    } catch (_: Exception) {
        ThemePaletteStyle.TonalSpot
    }

    val miuixColorSpec = if (colorSpec.effectiveFor(colorStyle) == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    val resolvedKeyColor: Color? = when {
        appSettings.keyColor != 0 -> Color(appSettings.keyColor)
        appSettings.colorMode.isMonet ->
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary

        else -> null
    }

    val controller = ThemeController(
        when (appSettings.colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
        },
        keyColor = resolvedKeyColor,
        isDark = darkTheme,
        paletteStyle = miuixPaletteStyle,
        colorSpec = miuixColorSpec,
    )

    MiuixTheme(
        controller = controller,
        content = {
            LaunchedEffect(darkTheme) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            MonetColorsProvider.UpdateCss()

            // paperSU (ported from 7kimisu): translucency so the wallpaper shows through.
            // With no wallpaper set surfaceAlpha stays 1f and this is a no-op.
            val wallpaperRev = WallpaperPrefs.observe()
            val wallpaperSlot = rememberWallpaperSlot()
            val surfaceAlpha = remember(wallpaperRev, wallpaperSlot) {
                val r = SettingsRepositoryImpl()
                if (WallpaperStore.hasWallpaper(wallpaperSlot) && r.uiTranslucent) {
                    r.uiTranslucentAlpha.coerceIn(0.02f, 1f)
                } else 1f
            }
            val scheme = MiuixTheme.colorScheme
            if (surfaceAlpha < 1f) {
                MiuixTheme(scheme.translucent(surfaceAlpha)) {
                    CompositionLocalProvider(
                        LocalContentColor provides scheme.onBackground,
                    ) {
                        content()
                    }
                }
            } else {
                CompositionLocalProvider(
                    LocalContentColor provides scheme.onBackground,
                ) {
                    content()
                }
            }
        }
    )
}

/**
 * paperSU: make the large Miuix container colours translucent (ported from 7kimisu).
 * Cards stay a bit more opaque than the page background so text remains readable.
 */
private fun Colors.translucent(alpha: Float): Colors {
    val card = (alpha + (1f - alpha) * 0.28f).coerceAtMost(1f)
    return copy(
        background = background.copy(alpha = alpha),
        surface = surface.copy(alpha = alpha),
        surfaceVariant = surfaceVariant.copy(alpha = card),
        surfaceContainer = surfaceContainer.copy(alpha = card),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = card),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = card),
    )
}
