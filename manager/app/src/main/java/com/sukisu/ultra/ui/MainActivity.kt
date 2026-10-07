package com.sukisu.ultra.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults.flingBehavior
import androidx.compose.foundation.pager.PagerDefaults.pageNestedScrollConnection
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.sukisu.ultra.Natives
import com.sukisu.ultra.ui.component.bottombar.BottomBar
import com.sukisu.ultra.ui.component.bottombar.MainPagerState
import com.sukisu.ultra.ui.component.bottombar.NavigationBadgeState
import com.sukisu.ultra.ui.component.bottombar.SideRail
import com.sukisu.ultra.ui.component.bottombar.rememberMainPagerState
import com.sukisu.ultra.ui.component.bottombar.useNavigationRail
import com.sukisu.ultra.ui.kernelFlash.KernelFlashScreen
import com.sukisu.ultra.ui.navigation3.HandleZipFileIntent
import com.sukisu.ultra.ui.navigation3.IntentDispatcher
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Navigator
import com.sukisu.ultra.ui.navigation3.Route
import com.sukisu.ultra.ui.navigation3.rememberNavigator
import com.sukisu.ultra.ui.privileged.PartitionFlashScreen
import com.sukisu.ultra.ui.screen.mine.MinePager
import com.sukisu.ultra.ui.screen.about.AboutScreen
import com.sukisu.ultra.ui.screen.appprofile.AppProfileScreen
import com.sukisu.ultra.ui.screen.colorpalette.ColorPaletteScreen
import com.sukisu.ultra.ui.screen.executemoduleaction.ExecuteModuleActionScreen
import com.sukisu.ultra.ui.screen.flash.FlashScreen
import com.sukisu.ultra.ui.screen.home.HomePager
import com.sukisu.ultra.ui.screen.install.InstallScreen
import com.sukisu.ultra.ui.screen.kpm.KpmScreen
import com.sukisu.ultra.ui.screen.module.ModulePager
import com.sukisu.ultra.ui.screen.modulerepo.ModuleRepoDetailScreen
import com.sukisu.ultra.ui.screen.modulerepo.ModuleRepoScreen
import com.sukisu.ultra.ui.screen.settings.SettingPager
import com.sukisu.ultra.ui.screen.settings.tools.ToolsScreen
import com.sukisu.ultra.ui.screen.sulog.SulogScreen
import com.sukisu.ultra.ui.screen.superuser.SuperUserPager
import com.sukisu.ultra.ui.screen.susfs.SuSFSScreen
import com.sukisu.ultra.ui.screen.template.AppProfileTemplateScreen
import com.sukisu.ultra.ui.screen.templateeditor.TemplateEditorScreen
import com.sukisu.ultra.ui.screen.umountmanager.UmountManagerScreen
import com.sukisu.ultra.ui.theme.KernelSUTheme
import com.sukisu.ultra.ui.theme.LocalColorMode
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.theme.LocalEnableSnowfall
import com.sukisu.ultra.ui.theme.LocalEnableTrollRain
import com.sukisu.ultra.ui.component.SnowfallOverlay
import com.sukisu.ultra.ui.component.TrollRainOverlay
import com.sukisu.ultra.ui.theme.LocalEnableFloatingBottomBar
import com.sukisu.ultra.ui.theme.LocalEnableFloatingBottomBarBlur
import com.sukisu.ultra.ui.theme.LocalEnableNavigationBadge
import com.sukisu.ultra.ui.theme.LocalModuleDescriptionMaxLines
import com.sukisu.ultra.ui.util.WallpaperHost
import com.sukisu.ultra.ui.util.getSuperuserCount
import com.sukisu.ultra.ui.util.install
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import com.sukisu.ultra.ui.util.rememberContentReady
import com.sukisu.ultra.ui.viewmodel.MainActivityViewModel
import com.sukisu.ultra.ui.viewmodel.MainPagerConfig
import com.sukisu.ultra.ui.viewmodel.ModuleViewModel
import com.sukisu.ultra.ui.viewmodel.SuperUserViewModel
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.sukisu.ultra.ui.component.engine.EngineMode


// paperSU: 二级页面必须自己把上一页盖住。Nav3 会把上一页留在组合里。
// 这里〔两层〕都有用，缺一不可：
//   ① 不透明页面色 —— 保证任何情况下都不透底（壁纸关掉时就是它，不会变黑/变白）
//   ② 上面再叠壁纸 —— 有壁纸时观感和首页一致，并且壁纸本身也盖住上一页
// 曾经只留 ①（Material 色板在 Miuix 下取成白色 ✗）、后来只留 ②（壁纸关掉时一片黑 ✗），
// 两次都是这样踩出来的。
@Composable
private fun OpaquePage(content: @Composable () -> Unit) {
    val bg = if (LocalUiMode.current == UiMode.Miuix) {
        MiuixTheme.colorScheme.background.copy(alpha = 1f)
    } else {
        MaterialTheme.colorScheme.background.copy(alpha = 1f)
    }
    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        WallpaperHost { content() }
    }
}

class MainActivity : ComponentActivity() {
    private val intentChannel = Channel<Intent>(capacity = Channel.BUFFERED)
    private var contentReady = false
    private var splashStartedAt = 0L
    private val splashAnimationDurationMs = 500L


    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.sukisu.ultra.ui.util.LocaleHelper.wrap(newBase))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashStartedAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        // paperSU: 内核 4.x 的机器走 Magisk 模式，KernelSU 的状态永远不会就绪。
        // 原来这里无条件等 contentReady，而 contentReady 由内容里的 SideEffect 置位，
        // 内容又起不来 —— 结果是启动画面永远挂着。Magisk 模式下只等那点动画时间。
        val magiskMode = EngineMode.current(this) == EngineMode.Mode.Magisk
        splashScreen.setKeepOnScreenCondition {
            val animating = SystemClock.uptimeMillis() - splashStartedAt < splashAnimationDurationMs
            if (magiskMode) animating else (!contentReady || animating)
        }

        // paperSU: apply hidden mode before anything reads Natives.isManager, otherwise a
        // first frame would render the root state and then flip to "not installed".
        runCatching { com.sukisu.ultra.ui.security.Stealth.applyMask() }

        val isManager = runCatching { Natives.isManager }.getOrDefault(false)
        if (isManager && Natives.kernelUAPIVersion == Natives.managerUAPIVersion) install()

        if (savedInstanceState == null) intent?.let { intentChannel.trySend(it) }

        // paperSU: every fresh install opens with the bundled wallpaper already set,
        // so first-run users see the intended look without touching any setting.
        // seedDefaultIfNeeded() is idempotent (it records `wallpaper_seeded`), and it
        // runs off the main thread because it copies a drawable into the private dir.
        // WallpaperPrefs notifies the UI once `wallpaper_*` is written, so the theme
        // picks up the translucency without needing a restart.
        Thread {
            runCatching {
                com.sukisu.ultra.ui.util.WallpaperStore.seedDefaultIfNeeded(applicationContext)
            }
        }.start()

        setContent {
            // paperSU: 两套界面在这里分流。Magisk 模式（内核 4.x，没有 KernelSU）走自己的
            // 极简主页；那套以 ksud 为中心的界面在这类机器上根本组合不出来，硬上只会白屏。
            if (magiskMode) {
                com.sukisu.ultra.ui.component.magisk.MagiskModeRoot()
                return@setContent
            }
            val viewModel = viewModel<MainActivityViewModel>()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val selectedMainPage by viewModel.selectedMainPage.collectAsStateWithLifecycle()
            val appSettings = uiState.appSettings
            val uiMode = uiState.uiMode
            val darkMode = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && isSystemInDarkTheme())

            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkMode },
                )
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }

            val navigator = rememberNavigator(Route.Main)
            val systemDensity = LocalDensity.current
            val density = remember(systemDensity, uiState.pageScale) {
                Density(systemDensity.density * uiState.pageScale, systemDensity.fontScale)
            }

            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalDensity provides density,
                LocalColorMode provides appSettings.colorMode.value,
                LocalEnableBlur provides uiState.enableBlur,
                LocalEnableSnowfall provides uiState.enableSnowfall,
                LocalEnableTrollRain provides uiState.enableTrollRain,
                LocalEnableFloatingBottomBar provides uiState.enableFloatingBottomBar,
                LocalEnableFloatingBottomBarBlur provides uiState.enableFloatingBottomBarBlur,
                LocalEnableNavigationBadge provides uiState.enableNavigationBadge,
                LocalModuleDescriptionMaxLines provides uiState.moduleDescriptionMaxLines,
                LocalUiMode provides uiMode,
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                        IntentDispatcher(intentChannel = intentChannel)
                        HandleZipFileIntent()
                        val swipeDismiss = if (uiState.enableSwipeDismiss) {
                            if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl) {
                                NavSwipeDirection.RightToLeft
                            } else {
                                NavSwipeDirection.LeftToRight
                            }
                        } else {
                            NavSwipeDirection.None
                        }
                        val mainScreenEntry = @Composable {
                            MainScreen(
                                initialPage = selectedMainPage,
                                pagerInterceptionMode = uiState.pagerInterceptionMode,
                                onPageChanged = viewModel::setSelectedMainPage,
                            )
                        }

                        val navDisplay = @Composable {
                            NavDisplay(
                                backStack = navigator.backStack,
                                effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                                onBack = {
                                    when (val top = navigator.current()) {
                                        is Route.TemplateEditor -> {
                                            if (!top.readOnly) {
                                                navigator.setResult("template_edit", true)
                                            } else {
                                                navigator.pop()
                                            }
                                        }

                                        else -> navigator.pop()
                                    }
                                }) {
                                entry<Route.Main>(swipeDismiss = swipeDismiss) { mainScreenEntry() }
                                entry<Route.About>(swipeDismiss = swipeDismiss) { OpaquePage { AboutScreen() } }
                                entry<Route.Sulog>(swipeDismiss = swipeDismiss) { OpaquePage { SulogScreen() } }
                                entry<Route.ColorPalette>(swipeDismiss = swipeDismiss) { OpaquePage { ColorPaletteScreen() } }
                                entry<Route.AppProfileTemplate>(swipeDismiss = swipeDismiss) { OpaquePage { AppProfileTemplateScreen() } }
                                entry<Route.TemplateEditor>(swipeDismiss = swipeDismiss) { key -> OpaquePage { TemplateEditorScreen(key.template, key.readOnly) } }
                                entry<Route.AppProfile>(swipeDismiss = swipeDismiss) { key -> OpaquePage { AppProfileScreen(key.uid) } }
                                entry<Route.ModuleRepo>(swipeDismiss = swipeDismiss) { OpaquePage { ModuleRepoScreen() } }
                                entry<Route.ModuleRepoDetail>(swipeDismiss = swipeDismiss) { key -> OpaquePage { ModuleRepoDetailScreen(key.module) } }
                                entry<Route.Install>(swipeDismiss = swipeDismiss) { key -> OpaquePage { InstallScreen(preselectedKernelUri = key.preselectedKernelUri) } }
                                entry<Route.Flash>(swipeDismiss = swipeDismiss) { key -> OpaquePage { FlashScreen(key.flashIt) } }
                                entry<Route.ExecuteModuleAction>(swipeDismiss = swipeDismiss) { key ->
                                    ExecuteModuleActionScreen(
                                        key.moduleId,
                                        key.fromShortcut
                                    )
                                }
                                entry<Route.Home>(swipeDismiss = swipeDismiss) { OpaquePage { mainScreenEntry() } }
                                entry<Route.SuperUser>(swipeDismiss = swipeDismiss) { OpaquePage { mainScreenEntry() } }
                                entry<Route.Module>(swipeDismiss = swipeDismiss) { OpaquePage { mainScreenEntry() } }
                                entry<Route.Settings>(swipeDismiss = swipeDismiss) { OpaquePage { mainScreenEntry() } }
                                entry<Route.Mine>(swipeDismiss = swipeDismiss) { OpaquePage { mainScreenEntry() } }
                                entry<Route.PartitionFlash>(swipeDismiss = swipeDismiss) { OpaquePage { PartitionFlashScreen() } }
                                entry<Route.KernelFlash>(swipeDismiss = swipeDismiss)  { key ->
                                        KernelFlashScreen(
                                            key.kernelUri,
                                            key.selectedSlot,
                                            key.kpmPatchEnabled,
                                            key.kpmUndoPatch
                                        )
                                    }
                                    entry<Route.Kpm>(swipeDismiss = swipeDismiss)  { OpaquePage { KpmScreen() } }
                                    entry<Route.SuSFS>(swipeDismiss = swipeDismiss)  { OpaquePage { SuSFSScreen() } }
                                    entry<Route.Tool>(swipeDismiss = swipeDismiss)  { OpaquePage { ToolsScreen() } }
                                    entry<Route.UmountManager>(swipeDismiss = swipeDismiss)  { OpaquePage { UmountManagerScreen() } }
                            }
                        }

                        // paperSU: 根部必须有【一层真正不透明】的底。
                        // 主题为了透壁纸，把 background / surface 全改成了半透明；而 Scaffold 的
                        // 默认容器色就是 background。壁纸没设置时 WallpaperHost 不画任何东西，
                        // 于是整棵 UI 树合成到"没有底"上 → 全黑（全屏搜索页最明显）。
                        // 之前只在各个二级 entry 上补（OpaquePage），但 Route.Main 承载了
                        // 底部导航那整套 tab 界面，它是唯一没被包的那个，所以白补了三轮。
                        // 这层放在 WallpaperHost 下面：有壁纸 → 壁纸盖住它；没壁纸 → 就是它。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (uiMode == UiMode.Miuix) {
                                        MiuixTheme.colorScheme.background.copy(alpha = 1f)
                                    } else {
                                        MaterialTheme.colorScheme.background.copy(alpha = 1f)
                                    }
                                )
                        ) {
                            WallpaperHost {
                                when (uiMode) {
                                    UiMode.Material -> androidx.compose.material3.Scaffold(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                                    ) { navDisplay() }

                                    UiMode.Miuix -> Scaffold { navDisplay() }
                                }
                            }
                            // paperSU: 7kimisu personalization overlays (snow / troll rain).
                            //
                            // 放在 NavDisplay 外面、壁纸上面。原来这两行挂在 MainScreen 的
                            // Pager Box 里，也就是 Route.Main 那一页内部 —— 任何被 push 上来的
                            // 页面（主题、关于、模块仓库…）都会完整盖住它，于是在「主题」页拨
                            // 开雪花开关，效果画在底下看不见，看起来就是"打开了没效果"。
                            // 挂到这一层则覆盖整个 NavDisplay，任何页面之上都能看到。
                            if (LocalEnableSnowfall.current) SnowfallOverlay()
                            if (LocalEnableTrollRain.current) TrollRainOverlay()
                        }
                        SideEffect { contentReady = true }
                    }
                    SideEffect { contentReady = true }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentChannel.trySend(intent)
    }
}

val LocalMainPagerState = staticCompositionLocalOf<MainPagerState> { error("LocalMainPagerState not provided") }

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun MainScreen(
    initialPage: Int = 0,
    pagerInterceptionMode: Int = PagerInterceptionMode.CrossAxisInterceptor.ordinal,
    onPageChanged: (Int) -> Unit = {},
) {
    val navController = LocalNavigator.current
    val enableBlur = LocalEnableBlur.current
    // paperSU: 7kimisu personalization overlays
    val enableSnowfall = LocalEnableSnowfall.current
    val enableTrollRain = LocalEnableTrollRain.current
    val enableFloatingBottomBar = LocalEnableFloatingBottomBar.current
    val enableFloatingBottomBarBlur = LocalEnableFloatingBottomBarBlur.current
    val useNavigationRail = useNavigationRail(enableFloatingBottomBar)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { MainPagerConfig.PAGE_COUNT })
    val mainPagerState = rememberMainPagerState(
        pagerState = pagerState,
        animatePageChanges = !useNavigationRail,
    )
    val isFullFeatured = Natives.isFullFeatured()
    val pagerMode = PagerInterceptionMode.entries.getOrElse(pagerInterceptionMode) {
        PagerInterceptionMode.Native
    }
    val interceptPagerGestures = pagerMode == PagerInterceptionMode.CrossAxisInterceptor
    var userScrollEnabled by remember(isFullFeatured) { mutableStateOf(isFullFeatured) }

    val enableNavigationBadge = LocalEnableNavigationBadge.current
    val badgeEnabled = enableNavigationBadge && isFullFeatured
    val moduleViewModel = viewModel<ModuleViewModel>()
    val moduleUiState by moduleViewModel.uiState.collectAsStateWithLifecycle()

    val superUserViewModel = viewModel<SuperUserViewModel>()
    val grantedUidCount by remember(superUserViewModel) {
        superUserViewModel.uiState
            .map { state -> state.groupedApps.count { it.anyAllowSu } }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(0)

    var startupPreloadStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isFullFeatured) {
        if (!isFullFeatured || startupPreloadStarted) {
            return@LaunchedEffect
        }

        moduleViewModel.initializePreferences()
        val moduleState = moduleViewModel.uiState.value
        if (!moduleState.hasLoaded) {
            if (!moduleState.isRefreshing) moduleViewModel.fetchModuleList()
            moduleViewModel.uiState.first { it.hasLoaded }
        }
        moduleViewModel.syncModuleUpdateInfo(moduleViewModel.uiState.value.modules)

        val superUserState = superUserViewModel.uiState.value
        if (!superUserState.hasLoaded) {
            superUserViewModel.initializePreferences()
            if (superUserState.isRefreshing) {
                superUserViewModel.uiState.first { it.hasLoaded }
            } else {
                superUserViewModel.loadAppList().join()
            }
        }

        startupPreloadStarted = true
    }

    // Loading the app list just for a badge is too expensive; read the kernel allowlist instead.
    var superuserCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(badgeEnabled, grantedUidCount) {
        superuserCount = if (badgeEnabled) withContext(Dispatchers.IO) { getSuperuserCount() } else 0
    }

    val navigationBadge = if (badgeEnabled) {
        NavigationBadgeState(
            superuserCount = superuserCount,
            moduleEnabledCount = moduleUiState.modules.count { it.enabled },
            moduleUpdatableCount = moduleUiState.updateInfo.count { it.value.downloadUrl.isNotBlank() },
        )
    } else {
        NavigationBadgeState()
    }
    val uiMode = LocalUiMode.current
    val surfaceColor = when (uiMode) {
        UiMode.Material -> MaterialTheme.colorScheme.surface // Blur is not used in Material, this is just a placeholder
        UiMode.Miuix -> MiuixTheme.colorScheme.surface
    }
    val blurBackdrop = rememberBlurBackdrop(enableBlur)

    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    val settledPage = mainPagerState.pagerState.settledPage
    LaunchedEffect(settledPage) {
        onPageChanged(settledPage)
    }

    val currentPage = mainPagerState.pagerState.currentPage
    LaunchedEffect(currentPage) {
        mainPagerState.syncPage()
    }

    MainScreenBackHandler(mainPagerState, navController)

    CompositionLocalProvider(
        LocalMainPagerState provides mainPagerState
    ) {
        val contentReady = rememberContentReady()
        val pagerContent = @Composable { bottomInnerPadding: Dp ->
            Box(modifier = if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop) else Modifier) {
                HorizontalPager(
                    modifier = Modifier
                        .pagerGestureOverride(
                            pagerState = mainPagerState.pagerState,
                            mode = pagerMode,
                            enabled = userScrollEnabled,
                        )
                        .then(if (enableFloatingBottomBar && enableFloatingBottomBarBlur) Modifier.layerBackdrop(backdrop) else Modifier),
                    state = mainPagerState.pagerState,
                    beyondViewportPageCount = if (contentReady) 3 else 0,
                    overscrollEffect = null,
                    userScrollEnabled = userScrollEnabled && !interceptPagerGestures,
                    pageNestedScrollConnection = if (interceptPagerGestures) {
                        PagerGestureNestedScrollConnection
                    } else {
                        pageNestedScrollConnection(
                            state = mainPagerState.pagerState,
                            orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                        )
                    },
                    flingBehavior = flingBehavior(
                        state = mainPagerState.pagerState,
                        snapAnimationSpec = PagerNavigationSpringSpec,
                    ),
                ) { page ->
                    val isCurrentPage = page == settledPage
                    when (page) {
                        0 -> if (contentReady || isCurrentPage) HomePager(navController, bottomInnerPadding, isCurrentPage)
                        // paperSU: KPM 移到第二个位置
                        1 -> if (contentReady || isCurrentPage) KpmScreen(bottomInnerPadding)
                        2 -> if (contentReady || isCurrentPage) SuperUserPager(navController, bottomInnerPadding, isCurrentPage)
                        3 -> if (contentReady || isCurrentPage) ModulePager(bottomInnerPadding, isCurrentPage)
                        4 -> if (contentReady || isCurrentPage) SettingPager(navController, bottomInnerPadding, isCurrentPage)
                        5 -> if (contentReady || isCurrentPage) MinePager(bottomInnerPadding, isCurrentPage)
                    }
                }
                // paperSU: 7kimisu personalization overlays (snow / troll rain).
                // Both fill their own bounds and take no pointer input.
                if (enableSnowfall) SnowfallOverlay()
                if (enableTrollRain) TrollRainOverlay()
            }
        }

        if (useNavigationRail) {
            val startInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Start)
            val navBarBottomPadding = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Row {
                        SideRail(navigationBadge)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }

                UiMode.Miuix -> Scaffold { _ ->
                    Row {
                        SideRail(navigationBadge)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(startInsets)
                        ) {
                            pagerContent(navBarBottomPadding)
                        }
                    }
                }
            }
        } else {
            val bottomBar = @Composable {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BottomBar(
                        blurBackdrop = blurBackdrop,
                        backdrop = backdrop,
                        navigationBadge = navigationBadge,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }

            when (uiMode) {
                UiMode.Material -> androidx.compose.material3.Scaffold(
                    bottomBar = bottomBar,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }

                UiMode.Miuix -> Scaffold(bottomBar = bottomBar) { innerPadding ->
                    pagerContent(innerPadding.calculateBottomPadding())
                }
            }
        }
    }
}

@Composable
private fun MainScreenBackHandler(
    mainState: MainPagerState,
    navController: Navigator,
) {
    val isPagerBackHandlerEnabled by remember {
        derivedStateOf {
            navController.current() is Route.Main && navController.backStackSize() == 1 && mainState.selectedPage != 0
        }
    }

    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = navEventState,
        isBackEnabled = isPagerBackHandlerEnabled,
        onBackCompleted = {
            mainState.animateToPage(0)
        }
    )
}
