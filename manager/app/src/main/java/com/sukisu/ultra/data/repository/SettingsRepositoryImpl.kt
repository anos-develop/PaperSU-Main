package com.sukisu.ultra.data.repository

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.topjohnwu.superuser.ShellUtils
import com.sukisu.ultra.Natives
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.magica.BootCompletedReceiver
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.screen.modulerepo.RepoSort
import com.sukisu.ultra.ui.util.execKsud
import com.sukisu.ultra.ui.util.WallpaperStore
import com.sukisu.ultra.ui.util.getFeaturePersistValue
import com.sukisu.ultra.ui.util.getFeatureStatus
import com.sukisu.ultra.ui.util.LocaleHelper
import java.security.SecureRandom

private const val SETTINGS_PREFS = "settings"
private const val KEY_USE_SOFT_REBOOT = "soft_reboot"

/** Prefer soft reboot: always in jailbreak mode, or when the setting is enabled. */
fun isSoftRebootPreferred(): Boolean =
    Natives.isLateLoadMode || ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_USE_SOFT_REBOOT, false)

class SettingsRepositoryImpl : SettingsRepository {

    private companion object {
        private const val INTENT_TOKEN_KEY = "intent_token"
        private val secureRandom = SecureRandom()
    }

    private val prefs by lazy {
        ksuApp.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
    }

    override var uiMode: String
        get() = prefs.getString("ui_mode", UiMode.DEFAULT_VALUE) ?: UiMode.DEFAULT_VALUE
        set(value) = prefs.edit { putString("ui_mode", value) }

    override var appLanguage: String
        get() = LocaleHelper.getCurrentLanguage(ksuApp)
        set(value) = LocaleHelper.setLanguage(ksuApp, value)

    override var checkUpdate: Boolean
        get() = prefs.getBoolean("check_update", true)
        set(value) = prefs.edit { putBoolean("check_update", value) }

    override var checkModuleUpdate: Boolean
        get() = prefs.getBoolean("module_check_update", true)
        set(value) = prefs.edit { putBoolean("module_check_update", value) }

    override var alternativeIcon : Boolean
        get() = prefs.getBoolean("use_alt_icon", false)
        set(value) = prefs.edit { putBoolean("use_alt_icon", value)}

    override var themeMode: Int
        get() = prefs.getInt("color_mode", 0)
        set(value) = prefs.edit { putInt("color_mode", value) }

    override var miuixMonet: Boolean
        get() = prefs.getBoolean("miuix_monet", false)
        set(value) = prefs.edit { putBoolean("miuix_monet", value) }

    override var keyColor: Int
        get() = prefs.getInt("key_color", 0)
        set(value) = prefs.edit { putInt("key_color", value) }

    override var colorStyle: String
        get() = prefs.getString("color_style", PaletteStyle.TonalSpot.name) ?: PaletteStyle.TonalSpot.name
        set(value) = prefs.edit { putString("color_style", value) }

    override var colorSpec: String
        get() = prefs.getString("color_spec", ColorSpec.SpecVersion.SPEC_2025.name) ?: ColorSpec.SpecVersion.SPEC_2025.name
        set(value) = prefs.edit { putString("color_spec", value) }

    override var enablePredictiveBack: Boolean
        get() = prefs.getBoolean("enable_predictive_back", false)
        set(value) = prefs.edit { putBoolean("enable_predictive_back", value) }

    override var enableSwipeDismiss: Boolean
        get() = prefs.getBoolean("enable_swipe_dismiss", true)
        set(value) = prefs.edit { putBoolean("enable_swipe_dismiss", value) }

    override var pagerInterceptionMode: Int
        get() = prefs.getInt("pager_interception_mode", 1)
        set(value) = prefs.edit { putInt("pager_interception_mode", value.coerceIn(0, 2)) }

    override var enableBlur: Boolean
        get() = prefs.getBoolean("enable_blur", false)
        set(value) = prefs.edit { putBoolean("enable_blur", value) }

    override var enableSnowfall: Boolean
        get() = prefs.getBoolean("enable_snowfall", false)
        set(value) = prefs.edit { putBoolean("enable_snowfall", value) }

    override var enableTrollRain: Boolean
        get() = prefs.getBoolean("enable_troll_rain", false)
        set(value) = prefs.edit { putBoolean("enable_troll_rain", value) }

    override var enableFloatingBottomBar: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar", false)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar", value) }

    override var enableFloatingBottomBarBlur: Boolean
        get() = prefs.getBoolean("enable_floating_bottom_bar_blur", false)
        set(value) = prefs.edit { putBoolean("enable_floating_bottom_bar_blur", value) }

    override var enableNavigationBadge: Boolean
        get() = prefs.getBoolean("enable_navigation_badge", true)
        set(value) = prefs.edit { putBoolean("enable_navigation_badge", value) }

    override var navigationRailExpanded: Boolean
        get() = prefs.getBoolean("nav_rail_expanded", false)
        set(value) = prefs.edit { putBoolean("nav_rail_expanded", value) }

    override var pageScale: Float
        get() = prefs.getFloat("page_scale", 1.0f)
        set(value) = prefs.edit { putFloat("page_scale", value) }

    override var moduleDescriptionMaxLines: Int
        get() = prefs.getInt("module_description_max_lines", 4)
        set(value) = prefs.edit { putInt("module_description_max_lines", value) }

    override var enableWebDebugging: Boolean
        get() = prefs.getBoolean("enable_web_debugging", false)
        set(value) = prefs.edit { putBoolean("enable_web_debugging", value) }

    override var moduleSortEnabledFirst: Boolean
        get() = prefs.getBoolean("module_sort_enabled_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_enabled_first", value) }

    override var moduleSortActionFirst: Boolean
        get() = prefs.getBoolean("module_sort_action_first", false)
        set(value) = prefs.edit { putBoolean("module_sort_action_first", value) }

    override var moduleRepoSortOrder: Int
        get() = prefs.getInt("module_repo_sort_order", RepoSort.UPDATED.ordinal)
        set(value) = prefs.edit { putInt("module_repo_sort_order", value) }

    override var superuserShowSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit { putBoolean("show_system_apps", value) }

    override var superuserShowOnlyPrimaryUserApps: Boolean
        get() = prefs.getBoolean("show_only_primary_user_apps", false)
        set(value) = prefs.edit { putBoolean("show_only_primary_user_apps", value) }

    override var superuserSortOption: Int
        get() = prefs.getInt("superuser_sort_option", 0)
        set(value) = prefs.edit { putInt("superuser_sort_option", value) }

    override var suLogFilters: Set<String>?
        get() = prefs.getStringSet("sulog_filters", null)?.toSet()
        set(filters) = prefs.edit { putStringSet("sulog_filters", filters) }

    override var showFullStatus: Boolean
        get() = prefs.getBoolean("show_fingerprint", true)
        set(value) = prefs.edit { putBoolean("show_fingerprint", value) }

    override var autoJailbreak: Boolean
        get() = prefs.getBoolean("auto_jailbreak", false)
        set(value) {
            runCatching {
                ksuApp.packageManager.setComponentEnabledSetting(
                    ComponentName(ksuApp, BootCompletedReceiver::class.java),
                    if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure {
                Log.e("Settings", "failed to change boot receiver state to $value", it)
            }
            prefs.edit {
                putBoolean("auto_jailbreak", value)
            }
        }

    override var useSoftReboot: Boolean
        get() = prefs.getBoolean(KEY_USE_SOFT_REBOOT, false)
        set(value) = prefs.edit { putBoolean(KEY_USE_SOFT_REBOOT, value) }

    // paperSU: hidden mode (app-side equivalent of 7kimisu's stealth mode)
    override var stealthEnabled: Boolean
        get() = prefs.getBoolean("stealth_enabled", false)
        set(value) = prefs.edit { putBoolean("stealth_enabled", value) }

    override var stealthCode: String
        get() = prefs.getString("stealth_code", "") ?: ""
        set(value) = prefs.edit { putString("stealth_code", value) }

    // paperSU: web manager switch. The service lives in ksud; see SettingsRepository.
    override var webAdminEnabled: Boolean
        get() = prefs.getBoolean("webadmin_enabled", false)
        set(value) = prefs.edit { putBoolean("webadmin_enabled", value) }

    // paperSU: ported from 7kimisu's wallpaper system (ui/util/WallpaperStore.kt)
    override var wallpaperPath: String
        get() = prefs.getString("wallpaper_path", "") ?: ""
        set(value) = prefs.edit { putString("wallpaper_path", value) }

    override var wallpaperKind: String
        get() = prefs.getString("wallpaper_kind", "none") ?: "none"
        set(value) = prefs.edit { putString("wallpaper_kind", value) }

    override var wallpaperLandPath: String
        get() = prefs.getString("wallpaper_land_path", "") ?: ""
        set(value) = prefs.edit { putString("wallpaper_land_path", value) }

    override var wallpaperLandKind: String
        get() = prefs.getString("wallpaper_land_kind", "none") ?: "none"
        set(value) = prefs.edit { putString("wallpaper_land_kind", value) }

    override var wallpaperDim: Float
        get() = prefs.getFloat("wallpaper_dim", 0.2f)
        set(value) = prefs.edit { putFloat("wallpaper_dim", value) }

    override var wallpaperBlur: Float
        get() = prefs.getFloat("wallpaper_blur", 0f)
        set(value) = prefs.edit { putFloat("wallpaper_blur", value) }

    override var wallpaperSeeded: Boolean
        get() = prefs.getBoolean("wallpaper_seeded", false)
        set(value) = prefs.edit { putBoolean("wallpaper_seeded", value) }

    override var wallpaperSeed: Boolean
        get() = prefs.getBoolean("wallpaper_seed", false)
        set(value) = prefs.edit { putBoolean("wallpaper_seed", value) }

    override var wallpaperSeedColor: Int
        get() = prefs.getInt("wallpaper_seed_color", 0)
        set(value) = prefs.edit { putInt("wallpaper_seed_color", value) }

    // paperSU: ported from 7kimisu's translucent-UI support
    override var uiTranslucent: Boolean
        get() = prefs.getBoolean("ui_translucent", true)
        set(value) = prefs.edit { putBoolean("ui_translucent", value) }

    override var uiTranslucentAlpha: Float
        get() = prefs.getFloat("ui_translucent_alpha", 0.02f)
        set(value) = prefs.edit { putFloat("ui_translucent_alpha", value) }

    // paperSU: derived from the wallpaper prefs. Enabling seeds the built-in default
    // wallpaper; disabling clears it. Both paths write `wallpaper_*` keys into the same
    // SharedPreferences, so WallpaperPrefs.notify -> Compose recomposition still works.
    override var wallpaperEnabled: Boolean
        get() = wallpaperKind != "none"
        set(value) {
            if (value) WallpaperStore.applyBuiltin(ksuApp) else WallpaperStore.clear()
        }

    override val intentToken: String
        get() {
        val existing = prefs.getString(INTENT_TOKEN_KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val token = ByteArray(32).also(secureRandom::nextBytes)
            .joinToString(separator = "") { "%02x".format(it) }
        prefs.edit { putString(INTENT_TOKEN_KEY, token) }
        return token
    }

    override suspend fun getSuCompatStatus(): String = getFeatureStatus("su_compat")

    override suspend fun getSuCompatPersistValue(): Long? = getFeaturePersistValue("su_compat")

    override fun isSuEnabled(): Boolean = Natives.isSuEnabled()

    override fun setSuEnabled(enabled: Boolean): Boolean = Natives.setSuEnabled(enabled)

    override fun setSuCompatModePref(mode: Int) = prefs.edit { putInt("su_compat_mode", mode) }

    override fun getSuCompatModePref(): Int = prefs.getInt("su_compat_mode", 0)

    override suspend fun getKernelUmountStatus(): String = getFeatureStatus("kernel_umount")

    override fun isKernelUmountEnabled(): Boolean = Natives.isKernelUmountEnabled()

    override fun setKernelUmountEnabled(enabled: Boolean): Boolean = Natives.setKernelUmountEnabled(enabled)

    override suspend fun getSelinuxHideStatus(): String = getFeatureStatus("selinux_hide")

    override fun isSelinuxHideEnabled(): Boolean = Natives.isSelinuxHideEnabled()

    override fun setSelinuxHideEnabled(enabled: Boolean): Int = Natives.setSelinuxHideEnabled(enabled)

    override suspend fun getSulogStatus(): String = getFeatureStatus("sulog")

    override suspend fun getSulogPersistValue(): Long? = getFeaturePersistValue("sulog")

    override fun setSulogEnabled(enabled: Boolean): Boolean = execKsud("feature set sulog ${if (enabled) 1 else 0}", true)

    override suspend fun getAdbRootStatus(): String = getFeatureStatus("adb_root")

    override suspend fun getAdbRootPersistValue(): Long? = getFeaturePersistValue("adb_root")

    override fun setAdbRootEnabled(enabled: Boolean): Boolean =
        if (execKsud("feature set adb_root ${if (enabled) 1 else 0}", true)) {
            ShellUtils.fastCmd("setprop ctl.restart adbd")
            true
        } else {
            false
        }

    override fun isDefaultUmountModules(): Boolean = Natives.isDefaultUmountModules()

    override fun setDefaultUmountModules(enabled: Boolean): Boolean = Natives.setDefaultUmountModules(enabled)

    override fun isLkmMode(): Boolean = Natives.isLkmMode

    override fun execKsudFeatureSave() {
        execKsud("feature save", true)
    }
}
