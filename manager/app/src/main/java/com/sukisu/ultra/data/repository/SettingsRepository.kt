package com.sukisu.ultra.data.repository

interface SettingsRepository {
    var uiMode: String
    var appLanguage: String
    var checkUpdate: Boolean
    var checkModuleUpdate: Boolean
    var alternativeIcon : Boolean
    var themeMode: Int
    var miuixMonet: Boolean
    var keyColor: Int
    var colorStyle: String
    var colorSpec: String
    var enablePredictiveBack: Boolean
    var enableSwipeDismiss: Boolean
    var pagerInterceptionMode: Int
    var enableBlur: Boolean
    // paperSU: ported from 7kimisu's personalization set (snow / troll-rain overlays)
    var enableSnowfall: Boolean
    var enableTrollRain: Boolean
    var enableFloatingBottomBar: Boolean
    var enableFloatingBottomBarBlur: Boolean
    var enableNavigationBadge: Boolean
    var navigationRailExpanded: Boolean
    var pageScale: Float
    var moduleDescriptionMaxLines: Int
    var enableWebDebugging: Boolean
    var moduleSortEnabledFirst: Boolean
    var moduleSortActionFirst: Boolean
    var moduleRepoSortOrder: Int
    var superuserShowSystemApps: Boolean
    var superuserShowOnlyPrimaryUserApps: Boolean
    var superuserSortOption: Int
    var suLogFilters: Set<String>?
    var showFullStatus: Boolean
    var autoJailbreak: Boolean
    var useSoftReboot: Boolean
    // paperSU: hidden mode (app-side equivalent of 7kimisu's stealth mode)
    var stealthEnabled: Boolean
    var stealthCode: String
    /**
     * paperSU: the web manager switch (UI state only).
     *
     * The service itself runs inside **ksud**, configured by
     * `/data/adb/ksu/webadmin.conf` and driven by `ksud webadmin on|off`. Keeping the switch
     * here means closing the app, a one-tap clean-up or a reboot do not disturb the page.
     */
    var webAdminEnabled: Boolean
    // paperSU: ported from 7kimisu's wallpaper system (ui/util/WallpaperStore.kt)
    var wallpaperPath: String
    var wallpaperKind: String
    var wallpaperLandPath: String
    var wallpaperLandKind: String
    var wallpaperDim: Float
    var wallpaperBlur: Float
    var wallpaperSeeded: Boolean
    var wallpaperSeed: Boolean
    var wallpaperSeedColor: Int
    // paperSU: ported from 7kimisu's translucent-UI support (ui/theme/*Theme.kt)
    var uiTranslucent: Boolean
    var uiTranslucentAlpha: Float
    /** paperSU: derived from [wallpaperKind]; enabling seeds the built-in wallpaper. */
    var wallpaperEnabled: Boolean
    val intentToken: String

    suspend fun getSuCompatStatus(): String
    suspend fun getSuCompatPersistValue(): Long?
    fun isSuEnabled(): Boolean
    fun setSuEnabled(enabled: Boolean): Boolean
    fun setSuCompatModePref(mode: Int)
    fun getSuCompatModePref(): Int

    suspend fun getKernelUmountStatus(): String
    fun isKernelUmountEnabled(): Boolean
    fun setKernelUmountEnabled(enabled: Boolean): Boolean

    suspend fun getSelinuxHideStatus(): String
    fun isSelinuxHideEnabled(): Boolean
    fun setSelinuxHideEnabled(enabled: Boolean): Int

    suspend fun getSulogStatus(): String
    suspend fun getSulogPersistValue(): Long?
    fun setSulogEnabled(enabled: Boolean): Boolean

    suspend fun getAdbRootStatus(): String
    suspend fun getAdbRootPersistValue(): Long?
    fun setAdbRootEnabled(enabled: Boolean): Boolean

    fun isDefaultUmountModules(): Boolean
    fun setDefaultUmountModules(enabled: Boolean): Boolean

    fun isLkmMode(): Boolean

    fun execKsudFeatureSave()
}
