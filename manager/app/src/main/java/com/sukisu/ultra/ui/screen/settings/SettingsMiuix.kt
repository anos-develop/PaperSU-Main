package com.sukisu.ultra.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Adb
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.DisplaySettings
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.KsuIsValid
import com.sukisu.ultra.ui.component.dialog.rememberLoadingDialog
import com.sukisu.ultra.ui.component.miuix.SendLogDialog
import com.sukisu.ultra.ui.component.uninstalldialog.UninstallDialog
import com.sukisu.ultra.ui.theme.LocalEnableBlur
import com.sukisu.ultra.ui.util.BlurredBar
import com.sukisu.ultra.ui.util.LocaleHelper
import com.sukisu.ultra.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import androidx.compose.material.icons.rounded.VisibilityOff
import com.sukisu.ultra.ui.component.miuix.EditText
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import com.sukisu.ultra.ui.util.WebAdminCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import com.sukisu.ultra.ui.security.Stealth
import com.sukisu.ultra.ui.security.restartUiFresh
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import com.sukisu.ultra.ui.component.hideenv.HideEnvHost
import com.sukisu.ultra.ui.component.hideenv.HideEnvDialogState
import com.sukisu.ultra.ui.screen.settings.GkiKernelInfoItem
import com.sukisu.ultra.ui.screen.settings.GkiReflashKernelItem
import com.sukisu.ultra.ui.component.parasite.ParasiteHost
import com.sukisu.ultra.ui.component.engine.EngineHost
import com.sukisu.ultra.ui.component.magisk.MagiskHost

/**
 * @author weishu
 * @date 2023/1/1.
 */
@Composable
fun SettingPagerMiuix(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
    isKpmAvailable: Boolean,
    isSusfsSupported: Boolean
) {
    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val loadingDialog = rememberLoadingDialog()
    val showUninstallDialog = rememberSaveable { mutableStateOf(false) }
    val showSendLogDialog = rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings),
                    scrollBehavior = scrollBehavior
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                // paperSU: 内置内核模式专有的两项（LKM 模式不显示）
                // paperSU: 寄生 —— 网页管理端常驻（ksud 提供），删掉管理器也能用
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        com.sukisu.ultra.ui.component.magisk.MagiskRow()
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        com.sukisu.ultra.ui.component.engine.EngineRow()
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        com.sukisu.ultra.ui.component.parasite.ParasiteRow()
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        if (!uiState.isLkmMode) { GkiKernelInfoItem() }
                        if (!uiState.isLkmMode) { GkiReflashKernelItem() }
                    }
                }

                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        SwitchPreference(
                            title = stringResource(id = R.string.settings_check_update),
                            summary = stringResource(id = R.string.settings_check_update_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.SystemUpdate,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_check_update),
                                    tint = colorScheme.onBackground
                                )
                            },
                            checked = uiState.checkUpdate,
                            onCheckedChange = actions.onSetCheckUpdate
                        )
                        KsuIsValid {
                            // paperSU: 模块检查更新（LKM 才需要） —— 按运行模式显示
                            if (uiState.isLkmMode) {
                                SwitchPreference(
                                    title = stringResource(id = R.string.settings_module_check_update),
                                    summary = stringResource(id = R.string.settings_check_update_summary),
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.SystemUpdateAlt,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = stringResource(id = R.string.settings_check_update),
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    checked = uiState.checkModuleUpdate,
                                    onCheckedChange = actions.onSetCheckModuleUpdate
                                )
                            }
                            // paperSU: 一键隐藏环境（移植自月虹隐藏模块）
                            val hideEnvContext2 = LocalContext.current
                            var hideEnvOn2 by remember { mutableStateOf(Stealth.isEnabled()) }
                            // paperSU: 这是一个"按钮"而不是开关 —— 点它弹出确认窗，再下载并刷入
                            // paperSU: 一键隐藏环境（LKM 才有意义） —— 按运行模式显示
                            if (uiState.isLkmMode) {
                                ArrowPreference(
                                    title = stringResource(id = R.string.settings_hide_env_title),
                                    summary = stringResource(id = R.string.settings_hide_env_summary),
                                    onClick = { HideEnvDialogState.open() },
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.VisibilityOff,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = stringResource(id = R.string.settings_hide_env_title),
                                            tint = colorScheme.onBackground
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        OverlayDropdownPreference(
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            startAction = {
                                Icon(
                                    Icons.Rounded.DisplaySettings,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_ui_mode),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onSelectedIndexChange = actions.onSetUiModeIndex
                        )
                        val languageSystemLabel = stringResource(id = R.string.settings_language_system)
                        val languageTags = remember { listOf(LocaleHelper.SYSTEM) + LocaleHelper.SUPPORTED_TAGS }
                        val languageNames = remember(languageSystemLabel) {
                            languageTags.map { if (it.isEmpty()) languageSystemLabel else LocaleHelper.displayName(it) }
                        }
                        OverlayDropdownPreference(
                            title = stringResource(id = R.string.settings_language),
                            summary = stringResource(id = R.string.settings_language_summary),
                            items = languageNames,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Language,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_language),
                                    tint = colorScheme.onBackground
                                )
                            },
                            selectedIndex = languageTags.indexOf(uiState.appLanguage).coerceAtLeast(0),
                            onSelectedIndexChange = { index -> actions.onSetLanguage(languageTags[index]) }
                        )
                        ArrowPreference(
                            title = stringResource(id = R.string.settings_theme),
                            summary = stringResource(id = R.string.settings_theme_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.settings_theme),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenTheme
                        )
                        SwitchPreference(
                        title = stringResource(id = R.string.icon_switch_title),
                        summary = stringResource(id = R.string.icon_switch_summary),
                        startAction = {
                            Icon(
                                Icons.Rounded.Android,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = stringResource(id = R.string.icon_switch_title),
                                tint = colorScheme.onBackground
                            )
                        },
                        checked = uiState.alternativeIcon,
                        onCheckedChange = actions.onSetAlternativeIcon
                        )
                    }

                    KsuIsValid {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val profileTemplate = stringResource(id = R.string.settings_profile_template)
                            ArrowPreference(
                                title = profileTemplate,
                                summary = stringResource(id = R.string.settings_profile_template_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Description,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = profileTemplate,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = actions.onOpenProfileTemplate
                            )
                        }
                        val toolsTitle = stringResource(id = R.string.settings_tools)
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                        ) {
                            ArrowPreference(
                                title = toolsTitle,
                                summary = stringResource(id = R.string.settings_tools_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeveloperMode,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = toolsTitle,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    actions.onOpenTools()
                                }
                            )
                        }
                    }

                    if (isKpmAvailable) {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val kpmTitle = stringResource(id = R.string.kpm_title)
                            ArrowPreference(
                                title = kpmTitle,
                                summary = stringResource(id = R.string.settings_kpm_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Code,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = kpmTitle,
                                        tint = colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    actions.onOpenKpm()
                                }
                            )
                        }
                    }

                    KsuIsValid {
                        if (isSusfsSupported) {
                            Card(
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth(),
                            ) {
                                val susfsTitle = stringResource(id = R.string.susfs_config_title)
                                ArrowPreference(
                                    title = susfsTitle,
                                    summary = stringResource(id = R.string.susfs_config_summary),
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.Settings,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = susfsTitle,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        actions.onOpenSusfsConfig()
                                    }
                                )
                            }
                        }
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val suCompatModeItems = listOf(
                                stringResource(id = R.string.settings_mode_enable_by_default),
                                stringResource(id = R.string.settings_mode_disable_until_reboot),
                                stringResource(id = R.string.settings_mode_disable_always),
                            )

                            val suSummary = when (uiState.suCompatStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sucompat_summary)
                            }
                            OverlayDropdownPreference(
                                title = stringResource(id = R.string.settings_sucompat),
                                summary = suSummary,
                                items = suCompatModeItems,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.AdminPanelSettings,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_sucompat),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.suCompatStatus == "supported",
                                selectedIndex = uiState.suCompatMode,
                                onSelectedIndexChange = actions.onSetSuCompatMode
                            )

                            val umountSummary = when (uiState.kernelUmountStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_kernel_umount_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_kernel_umount),
                                summary = umountSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.LayersClear,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_kernel_umount),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.kernelUmountStatus == "supported",
                                checked = uiState.isKernelUmountEnabled,
                                onCheckedChange = actions.onSetKernelUmountEnabled
                            )

                            // paperSU: hidden mode. Turning it on masks Natives.isManager so
                            // the whole UI drops to "not installed"; the dialer secret code
                            // brings it back. The current code is shown in the summary so the
                            // user knows exactly what to dial.
                            val stealthContext = LocalContext.current
                            var stealthOn by remember { mutableStateOf(Stealth.isEnabled()) }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_stealth_enabled),
                                summary = stringResource(id = R.string.settings_stealth_enabled_summary) +
                                        "\n" + stringResource(
                                    id = R.string.settings_stealth_code_summary,
                                    Stealth.effectiveCode()
                                ),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.VisibilityOff,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_stealth_enabled),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = stealthOn,
                                onCheckedChange = { want ->
                                    Stealth.setEnabled(want)
                                    stealthOn = want
                                    restartUiFresh(stealthContext)
                                }
                            )

                            // paperSU: the dialer code that leaves hidden mode. Editable right
                            // here so the user never has to leave the page. Only digits are kept;
                            // emptying the field resets it to the shipped default.
                            var stealthCode by remember { mutableStateOf(Stealth.effectiveCode()) }
                            EditText(
                                title = stringResource(id = R.string.settings_stealth_code),
                                value = stealthCode,
                                onValueChange = { raw ->
                                    val digits = raw.filter { it.isDigit() }.take(12)
                                    stealthCode = digits
                                    if (digits.isBlank()) Stealth.clearCode() else Stealth.setCode(digits)
                                },
                                summary = stringResource(
                                    id = R.string.settings_stealth_code_summary,
                                    stealthCode.ifBlank { Stealth.DEFAULT_CODE }
                                ),
                                textHint = Stealth.DEFAULT_CODE,
                            )

                            // paperSU: local web manager. The service runs inside ksud, so
                            // closing the app (or a one-tap clean-up) does not stop it. This page
                            // only toggles it and hands out the keyed link; reading that link
                            // needs a root shell, hence the off-main-thread fetch.
                            val waContext = LocalContext.current
                            val waScope = rememberCoroutineScope()
                            var waOn by remember {
                                mutableStateOf(
                                    com.sukisu.ultra.data.repository.SettingsRepositoryImpl().webAdminEnabled
                                )
                            }
                            var waUrl by remember { mutableStateOf("") }
                            LaunchedEffect(waOn) {
                                waUrl = if (waOn) {
                                    withContext(Dispatchers.IO) {
                                        WebAdminCli.syncPref(true)
                                        WebAdminCli.url()
                                    }
                                } else {
                                    ""
                                }
                            }
                            val waTitle = stringResource(id = R.string.webadmin_title)
                            val waSummaryOff = stringResource(id = R.string.webadmin_summary_off)
                            val waReading = stringResource(id = R.string.webadmin_reading)
                            val waLocalOnly = stringResource(id = R.string.webadmin_local_only)
                            val waKsudFailed = stringResource(id = R.string.webadmin_ksud_failed)
                            val waBrowserFailed = stringResource(id = R.string.webadmin_browser_failed)
                            val waCopyOk = stringResource(id = R.string.webadmin_copy_ok)
                            val waCopyFail = stringResource(id = R.string.webadmin_copy_fail)
                            val waReset = stringResource(id = R.string.webadmin_reset)
                            val waResetSummary = stringResource(id = R.string.webadmin_reset_summary)
                            val waResetFailed = stringResource(id = R.string.webadmin_reset_failed)
                            val waDiagnose = stringResource(id = R.string.webadmin_diagnose)
                            val waDiagnoseSummary = stringResource(id = R.string.webadmin_diagnose_summary)
                            val waCopyAllOk = stringResource(id = R.string.webadmin_copy_all_ok)
                            Card(
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .fillMaxWidth(),
                            ) {
                                SwitchPreference(
                                    title = waTitle,
                                    summary = if (waOn) {
                                        stringResource(
                                            id = R.string.webadmin_summary_on,
                                            waUrl.ifBlank { waReading }
                                        ) + "\n\n" + waLocalOnly
                                    } else {
                                        waSummaryOff
                                    },
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.Language,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = waTitle,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    checked = waOn,
                                    onCheckedChange = { want ->
                                        waOn = want
                                        com.sukisu.ultra.data.repository.SettingsRepositoryImpl().webAdminEnabled = want
                                        waScope.launch {
                                            val ok = withContext(Dispatchers.IO) { WebAdminCli.setEnabled(want) }
                                            if (want) waUrl = withContext(Dispatchers.IO) { WebAdminCli.url() }
                                            if (!ok) {
                                                Toast.makeText(waContext, waKsudFailed, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                                if (waOn) {
                                    ArrowPreference(
                                        title = stringResource(id = R.string.webadmin_open),
                                        summary = stringResource(id = R.string.webadmin_open_summary),
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.OpenInNew,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground
                                            )
                                        },
                                        onClick = {
                                            WebAdminCli.openInBrowser(waContext, waUrl)?.let { err ->
                                                Toast.makeText(
                                                    waContext,
                                                    String.format(waBrowserFailed, err),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    )
                                    ArrowPreference(
                                        title = stringResource(id = R.string.webadmin_copy),
                                        summary = stringResource(id = R.string.webadmin_copy_summary),
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.ContentCopy,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground
                                            )
                                        },
                                        onClick = {
                                            val ok = WebAdminCli.copyUrl(waContext, waUrl)
                                            Toast.makeText(
                                                waContext,
                                                if (ok) waCopyOk else waCopyFail,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                    ArrowPreference(
                                        title = waReset,
                                        summary = waResetSummary,
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.Key,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = null,
                                                tint = colorScheme.onBackground
                                            )
                                        },
                                        onClick = {
                                            // Issue the new key AND copy it in one action, so there is
                                            // no way to end up without a working link (no dialog needed).
                                            waScope.launch {
                                                val fresh = withContext(Dispatchers.IO) { WebAdminCli.resetToken() }
                                                if (fresh.isBlank()) {
                                                    Toast.makeText(waContext, waResetFailed, Toast.LENGTH_LONG).show()
                                                } else {
                                                    waUrl = fresh
                                                    val copied = WebAdminCli.copyUrl(waContext, fresh)
                                                    Toast.makeText(
                                                        waContext,
                                                        if (copied) waCopyOk else fresh,
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    )
                                }
                                ArrowPreference(
                                    title = waDiagnose,
                                    summary = waDiagnoseSummary,
                                    startAction = {
                                        Icon(
                                            Icons.Rounded.BugReport,
                                            modifier = Modifier.padding(end = 6.dp),
                                            contentDescription = null,
                                            tint = colorScheme.onBackground
                                        )
                                    },
                                    onClick = {
                                        waScope.launch {
                                            val report = withContext(Dispatchers.IO) { WebAdminCli.diagnose() }
                                            val cm = waContext.getSystemService(
                                                android.content.Context.CLIPBOARD_SERVICE
                                            ) as android.content.ClipboardManager
                                            cm.setPrimaryClip(
                                                android.content.ClipData.newPlainText("paperSU webadmin", report)
                                            )
                                            Toast.makeText(waContext, waCopyAllOk, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }

                            val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_selinux_hide_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_selinux_hide),
                                summary = selinuxHideSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Security,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_selinux_hide),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.selinuxHideStatus == "supported",
                                checked = uiState.isSelinuxHideEnabled,
                                onCheckedChange = actions.onSetSelinuxHideEnabled
                            )

                            val sulogSummary = when (uiState.sulogStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sulog_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_sulog),
                                summary = sulogSummary,
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Article,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_sulog),
                                        tint = if (uiState.sulogStatus == "supported") colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                                    )
                                },
                                enabled = uiState.sulogStatus == "supported",
                                checked = uiState.isSulogEnabled,
                                onCheckedChange = actions.onSetSulogEnabled
                            )

                            val adbRootSummary = when (uiState.adbRootStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_adb_root_summary)
                            }
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_adb_root),
                                summary = adbRootSummary,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Adb,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_adb_root),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                enabled = uiState.adbRootStatus == "supported",
                                checked = uiState.isAdbRootEnabled,
                                onCheckedChange = actions.onSetAdbRootEnabled
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_soft_reboot),
                                summary = stringResource(id = R.string.settings_soft_reboot_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.RestartAlt,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_soft_reboot),
                                        tint = if (uiState.isLateLoadMode) colorScheme.disabledOnSecondaryVariant else colorScheme.onBackground
                                    )
                                },
                                enabled = !uiState.isLateLoadMode,
                                checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                                onCheckedChange = actions.onSetUseSoftReboot
                            )
                        }

                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_umount_modules_default),
                                summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                                startAction = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Rule,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_umount_modules_default),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.isDefaultUmountModules,
                                onCheckedChange = actions.onSetDefaultUmountModules
                            )

                            SwitchPreference(
                                title = stringResource(id = R.string.enable_web_debugging),
                                summary = stringResource(id = R.string.enable_web_debugging_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DeveloperMode,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.enable_web_debugging),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                checked = uiState.enableWebDebugging,
                                onCheckedChange = actions.onSetEnableWebDebugging
                            )
                            SwitchPreference(
                                title = stringResource(id = R.string.settings_auto_jailbreak),
                                summary = stringResource(id = R.string.settings_auto_jailbreak_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.FlashOn,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = stringResource(id = R.string.settings_auto_jailbreak),
                                        tint = if (uiState.isLateLoadMode) colorScheme.onBackground else colorScheme.disabledOnSecondaryVariant
                                    )
                                },
                                enabled = uiState.isLateLoadMode,
                                checked = uiState.autoJailbreak,
                                onCheckedChange = actions.onSetAutoJailbreak
                            )
                        }
                    }

                    if (uiState.isLkmMode) {
                        Card(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth(),
                        ) {
                            val uninstall = stringResource(id = R.string.settings_uninstall)
                            ArrowPreference(
                                title = uninstall,
                                enabled = !uiState.isLateLoadMode,
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = uninstall,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = { showUninstallDialog.value = true },
                            )
                            UninstallDialog(
                                show = showUninstallDialog.value,
                                onDismissRequest = { showUninstallDialog.value = false }
                            )

                            // paperSU: 一键隐藏环境的确认弹窗（内容都在 HideEnvDialog.kt 里）
                            HideEnvHost()
                            EngineHost()

                            // paperSU: 寄生 —— 网页管理端常驻（ksud 提供），删掉管理器也能用
                            ParasiteHost()
                            MagiskHost()
                        }
                    }

                    Card(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = stringResource(id = R.string.send_log),
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = stringResource(id = R.string.send_log),
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = { showSendLogDialog.value = true },
                        )
                        SendLogDialog(
                            show = showSendLogDialog.value,
                            onDismissRequest = { showSendLogDialog.value = false },
                            loadingDialog = loadingDialog
                        )
                        val about = stringResource(id = R.string.about)
                        ArrowPreference(
                            title = about,
                            startAction = {
                                Icon(
                                    Icons.Rounded.Info,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = about,
                                    tint = colorScheme.onBackground
                                )
                            },
                            onClick = actions.onOpenAbout,
                        )
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}
