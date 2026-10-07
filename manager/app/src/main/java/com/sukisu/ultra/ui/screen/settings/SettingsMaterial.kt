package com.sukisu.ultra.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Adb
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.Fence
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.platform.LocalContext
import com.sukisu.ultra.ui.security.Stealth
import com.sukisu.ultra.ui.security.restartUiFresh
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import android.widget.Toast
import com.sukisu.ultra.ui.util.WebAdminCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.KsuIsValid
import com.sukisu.ultra.ui.component.material.ExpressiveScaffold
import com.sukisu.ultra.ui.component.material.SegmentedColumn
import com.sukisu.ultra.ui.component.material.SegmentedDropdownItem
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import com.sukisu.ultra.ui.component.material.SegmentedSwitchItem
import com.sukisu.ultra.ui.component.material.SendLogBottomSheet
import com.sukisu.ultra.ui.component.material.SnackBarHost
import com.sukisu.ultra.ui.component.material.expressiveTopAppBarColors
import com.sukisu.ultra.ui.util.LocaleHelper
import com.sukisu.ultra.ui.component.uninstalldialog.UninstallDialog
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
fun SettingPagerMaterial(
    uiState: SettingsUiState,
    actions: SettingsScreenActions,
    bottomInnerPadding: Dp,
    isKpmAvailable: Boolean,
    isSusfsSupported: Boolean
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackBarHost = remember { SnackbarHostState() }
    val showUninstallDialog = rememberSaveable { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }

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

    ExpressiveScaffold(
        topBar = {
            TopBar(scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SnackBarHost(hostState = snackBarHost, modifier = Modifier.padding(bottom = bottomInnerPadding)) },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            KsuIsValid {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            com.sukisu.ultra.ui.component.magisk.MagiskRow()
                        },
                        {
                            com.sukisu.ultra.ui.component.engine.EngineRow()
                        },
                        // paperSU: 内置内核模式专有的两项（LKM 模式不显示）
                        {
                            if (!uiState.isLkmMode) { GkiKernelInfoItem() }
                        },
                        {
                            if (!uiState.isLkmMode) { GkiReflashKernelItem() }
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.SystemUpdate,
                                title = stringResource(id = R.string.settings_check_update),
                                summary = stringResource(id = R.string.settings_check_update_summary),
                                checked = uiState.checkUpdate,
                                onCheckedChange = actions.onSetCheckUpdate
                            )
                        },
                        {
                            // paperSU: 模块检查更新（LKM 才需要） —— 按运行模式显示
                            if (uiState.isLkmMode) {
                                SegmentedSwitchItem(
                                    icon = Icons.Filled.SystemUpdateAlt,
                                    title = stringResource(id = R.string.settings_module_check_update),
                                    summary = stringResource(id = R.string.settings_check_update_summary),
                                    checked = uiState.checkModuleUpdate,
                                    onCheckedChange = actions.onSetCheckModuleUpdate
                                )
                            }
                        },
                        {
                            // paperSU: 一键隐藏环境（移植自月虹隐藏模块）
                            val hideEnvContext = LocalContext.current
                            var hideEnvOn by remember { mutableStateOf(Stealth.isEnabled()) }
                            // paperSU: 这是一个"按钮"而不是开关 —— 点它弹出确认窗，再下载并刷入
                            // paperSU: 这是一个"按钮"而不是开关 —— 点它弹出确认窗，再下载并刷入
                            // paperSU: 一键隐藏环境（LKM 才有意义） —— 按运行模式显示
                            if (uiState.isLkmMode) {
                                SegmentedListItem(
                                    onClick = { HideEnvDialogState.open() },
                                    headlineContent = { Text(stringResource(id = R.string.settings_hide_env_title)) },
                                    supportingContent = { Text(stringResource(id = R.string.settings_hide_env_summary)) }
                                )
                            }
                        },
                        {
                            com.sukisu.ultra.ui.component.parasite.ParasiteRow()
                        }
                    )
                )
            }

            // paperSU: hidden mode. Turning it on masks Natives.isManager so the whole UI
            // drops to "not installed"; the dialer secret code brings it back.
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = listOf(
                    {
                        val stealthContext = LocalContext.current
                        var stealthOn by remember { mutableStateOf(Stealth.isEnabled()) }
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.VisibilityOff,
                            title = stringResource(id = R.string.settings_stealth_enabled),
                            summary = stringResource(id = R.string.settings_stealth_enabled_summary) +
                                    "\n" + stringResource(
                                id = R.string.settings_stealth_code_summary,
                                Stealth.effectiveCode()
                            ),
                            checked = stealthOn,
                            onCheckedChange = { want ->
                                Stealth.setEnabled(want)
                                stealthOn = want
                                restartUiFresh(stealthContext)
                            }
                        )
                    }
                )
            )

            // paperSU: the dialer code that leaves hidden mode. Editable right here so the
            // user never has to leave the page. Only digits are kept; emptying the field
            // resets it to the shipped default.
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = listOf(
                    {
                        var stealthCode by remember { mutableStateOf(Stealth.effectiveCode()) }
                        OutlinedTextField(
                            value = stealthCode,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(12)
                                stealthCode = digits
                                if (digits.isBlank()) Stealth.clearCode() else Stealth.setCode(digits)
                            },
                            label = { Text(stringResource(id = R.string.settings_stealth_code)) },
                            supportingText = {
                                Text(
                                    stringResource(
                                        id = R.string.settings_stealth_code_summary,
                                        stealthCode.ifBlank { Stealth.DEFAULT_CODE }
                                    )
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                )
            )

            // paperSU: local web manager. The service runs inside ksud, so closing the app (or
            // a one-tap clean-up) does not stop it. This page only toggles it and hands out the
            // keyed link; reading that link needs a root shell, hence the off-main-thread fetch.
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
            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = buildList {
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.Language,
                            title = waTitle,
                            summary = if (waOn) {
                                stringResource(
                                    id = R.string.webadmin_summary_on,
                                    waUrl.ifBlank { waReading }
                                ) + "\n\n" + waLocalOnly
                            } else {
                                waSummaryOff
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
                    }
                    if (waOn) {
                        add {
                            SegmentedListItem(
                                onClick = {
                                    WebAdminCli.openInBrowser(waContext, waUrl)?.let { err ->
                                        Toast.makeText(
                                            waContext,
                                            String.format(waBrowserFailed, err),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                headlineContent = { Text(stringResource(id = R.string.webadmin_open)) },
                                supportingContent = {
                                    Text(stringResource(id = R.string.webadmin_open_summary))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                            )
                        }
                        add {
                            SegmentedListItem(
                                onClick = {
                                    val ok = WebAdminCli.copyUrl(waContext, waUrl)
                                    Toast.makeText(
                                        waContext,
                                        if (ok) waCopyOk else waCopyFail,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                headlineContent = { Text(stringResource(id = R.string.webadmin_copy)) },
                                supportingContent = {
                                    Text(stringResource(id = R.string.webadmin_copy_summary))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                            )
                        }
                        add {
                            SegmentedListItem(
                                onClick = {
                                    // Issue the new key AND copy it in one action, so there is no
                                    // way to end up without a working link (no dialog needed).
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
                                },
                                headlineContent = { Text(waReset) },
                                supportingContent = { Text(waResetSummary) },
                                leadingContent = {
                                    Icon(
                                        Icons.Rounded.Key,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                            )
                        }
                    }
                    add {
                        SegmentedListItem(
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
                            },
                            headlineContent = { Text(waDiagnose) },
                            supportingContent = { Text(waDiagnoseSummary) },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                        )
                    }
                }
            )

            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = buildList {
                    add {
                        SegmentedDropdownItem(
                            icon = Icons.Filled.DisplaySettings,
                            title = stringResource(id = R.string.settings_ui_mode),
                            summary = stringResource(id = R.string.settings_ui_mode_summary),
                            items = UiMode.entries.map { it.name },
                            selectedIndex = if (uiState.uiMode == UiMode.Material.value) 1 else 0,
                            onItemSelected = actions.onSetUiModeIndex
                        )
                    }
                    add {
                        val languageSystemLabel = stringResource(id = R.string.settings_language_system)
                        val languageTags = remember { listOf(LocaleHelper.SYSTEM) + LocaleHelper.SUPPORTED_TAGS }
                        val languageNames = remember(languageSystemLabel) {
                            languageTags.map { if (it.isEmpty()) languageSystemLabel else LocaleHelper.displayName(it) }
                        }
                        SegmentedDropdownItem(
                            icon = Icons.Rounded.Language,
                            title = stringResource(id = R.string.settings_language),
                            summary = stringResource(id = R.string.settings_language_summary),
                            items = languageNames,
                            selectedIndex = languageTags.indexOf(uiState.appLanguage).coerceAtLeast(0),
                            onItemSelected = { index -> actions.onSetLanguage(languageTags[index]) }
                        )
                    }
                    add {
                        SegmentedListItem(
                            onClick = actions.onOpenTheme,
                            headlineContent = { Text(stringResource(id = R.string.settings_theme)) },
                            supportingContent = { Text(stringResource(id = R.string.settings_theme_summary)) },
                            leadingContent = { Icon(Icons.Filled.Palette, stringResource(id = R.string.settings_theme)) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                    add {
                        SegmentedSwitchItem(
                            icon = Icons.Rounded.Android,
                            title = stringResource(id = R.string.icon_switch_title),
                            summary = stringResource(id = R.string.icon_switch_summary),
                            checked = uiState.alternativeIcon,
                            onCheckedChange = actions.onSetAlternativeIcon
                        )
                    }
                }
            )

            val profileTemplate = stringResource(id = R.string.settings_profile_template)
            KsuIsValid {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = actions.onOpenProfileTemplate,
                            headlineContent = { Text(profileTemplate) },
                            supportingContent = { Text(stringResource(id = R.string.settings_profile_template_summary)) },
                            leadingContent = { Icon(Icons.Filled.Description, profileTemplate) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                )
                val toolsTitle = stringResource(id = R.string.settings_tools)
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = actions.onOpenTools,
                            headlineContent = { Text(toolsTitle) },
                            supportingContent = { Text(stringResource(id = R.string.settings_tools_summary)) },
                            leadingContent = { Icon(Icons.Filled.Fence, toolsTitle) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                )
            }


            val susfsTitle = stringResource(id = R.string.susfs_config_title)
            if (isSusfsSupported) {
                if (isKpmAvailable) {
                    SegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        content = listOf {
                            SegmentedListItem(
                                onClick = actions.onOpenSusfsConfig,
                                headlineContent = { Text(susfsTitle) },
                                supportingContent = { Text(stringResource(id = R.string.settings_kpm_summary)) },
                                leadingContent = { Icon(Icons.Filled.Fence, susfsTitle) },
                                trailingContent = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        null
                                    )
                                }
                            )
                        }
                    )
                }
            }

            KsuIsValid {
                val suCompatModeItems = listOf(
                    stringResource(id = R.string.settings_mode_enable_by_default),
                    stringResource(id = R.string.settings_mode_disable_until_reboot),
                    stringResource(id = R.string.settings_mode_disable_always),
                )

                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            val suSummary = when (uiState.suCompatStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sucompat_summary)
                            }
                            SegmentedDropdownItem(
                                icon = Icons.Filled.AdminPanelSettings,
                                title = stringResource(id = R.string.settings_sucompat),
                                summary = suSummary,
                                items = suCompatModeItems,
                                enabled = uiState.suCompatStatus == "supported",
                                selectedIndex = uiState.suCompatMode,
                                onItemSelected = actions.onSetSuCompatMode
                            )
                        },
                        {
                            val umountSummary = when (uiState.kernelUmountStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_kernel_umount_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.Filled.LayersClear,
                                title = stringResource(id = R.string.settings_kernel_umount),
                                summary = umountSummary,
                                enabled = uiState.kernelUmountStatus == "supported",
                                checked = uiState.isKernelUmountEnabled,
                                onCheckedChange = actions.onSetKernelUmountEnabled
                            )
                        },
                        {
                            val selinuxHideSummary = when (uiState.selinuxHideStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_selinux_hide_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.Filled.Security,
                                title = stringResource(id = R.string.settings_selinux_hide),
                                summary = selinuxHideSummary,
                                enabled = uiState.selinuxHideStatus == "supported",
                                checked = uiState.isSelinuxHideEnabled,
                                onCheckedChange = actions.onSetSelinuxHideEnabled
                            )
                        },
                        {
                            val sulogSummary = when (uiState.sulogStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_sulog_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.AutoMirrored.Filled.Article,
                                title = stringResource(id = R.string.settings_sulog),
                                summary = sulogSummary,
                                enabled = uiState.sulogStatus == "supported",
                                checked = uiState.isSulogEnabled,
                                onCheckedChange = actions.onSetSulogEnabled
                            )
                        },
                        {
                            val adbRootSummary = when (uiState.adbRootStatus) {
                                "unsupported" -> stringResource(id = R.string.feature_status_unsupported_summary)
                                "managed" -> stringResource(id = R.string.feature_status_managed_summary)
                                else -> stringResource(id = R.string.settings_adb_root_summary)
                            }
                            SegmentedSwitchItem(
                                icon = Icons.Filled.Adb,
                                title = stringResource(id = R.string.settings_adb_root),
                                summary = adbRootSummary,
                                enabled = uiState.adbRootStatus == "supported",
                                checked = uiState.isAdbRootEnabled,
                                onCheckedChange = actions.onSetAdbRootEnabled
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.RestartAlt,
                                title = stringResource(id = R.string.settings_soft_reboot),
                                summary = stringResource(id = R.string.settings_soft_reboot_summary),
                                enabled = !uiState.isLateLoadMode,
                                checked = uiState.isLateLoadMode || uiState.useSoftReboot,
                                onCheckedChange = actions.onSetUseSoftReboot
                            )
                        },
                    )
                )

                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            SegmentedSwitchItem(
                                icon = Icons.AutoMirrored.Filled.Rule,
                                title = stringResource(id = R.string.settings_umount_modules_default),
                                summary = stringResource(id = R.string.settings_umount_modules_default_summary),
                                checked = uiState.isDefaultUmountModules,
                                onCheckedChange = actions.onSetDefaultUmountModules
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.DeveloperMode,
                                title = stringResource(id = R.string.enable_web_debugging),
                                summary = stringResource(id = R.string.enable_web_debugging_summary),
                                checked = uiState.enableWebDebugging,
                                onCheckedChange = actions.onSetEnableWebDebugging
                            )
                        },
                        {
                            SegmentedSwitchItem(
                                icon = Icons.Filled.FlashOn,
                                title = stringResource(id = R.string.settings_auto_jailbreak),
                                summary = stringResource(id = R.string.settings_auto_jailbreak_summary),
                                enabled = uiState.isLateLoadMode,
                                checked = uiState.autoJailbreak,
                                onCheckedChange = actions.onSetAutoJailbreak
                            )
                        }
                    )
                )
            }

            if (uiState.isLkmMode) {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf(
                        {
                            val uninstall = stringResource(id = R.string.settings_uninstall)
                            SegmentedListItem(
                                onClick = { showUninstallDialog.value = true },
                                enabled = !uiState.isLateLoadMode,
                                headlineContent = { Text(uninstall) },
                                leadingContent = { Icon(Icons.Filled.Delete, uninstall) }
                            )
                        }
                    )
                )
            }

            val kpmTitle = stringResource(id = R.string.kpm_title)
            if (isKpmAvailable) {
                SegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = actions.onOpenKpm,
                            headlineContent = { Text(kpmTitle) },
                            supportingContent = { Text(stringResource(id = R.string.settings_kpm_summary)) },
                            leadingContent = { Icon(Icons.Filled.Fence, kpmTitle) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    null
                                )
                            }
                        )
                    }
                )
            }

            SegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                content = listOf(
                    {
                        SegmentedListItem(
                            onClick = { showBottomSheet = true },
                            headlineContent = { Text(stringResource(id = R.string.send_log)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.BugReport,
                                    stringResource(id = R.string.send_log)
                                )
                            },
                        )
                    },
                    {
                        SegmentedListItem(
                            onClick = actions.onOpenAbout,
                            headlineContent = { Text(stringResource(id = R.string.about)) },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.Info,
                                    stringResource(id = R.string.about)
                                )
                            },
                        )
                    }
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (showBottomSheet) {
                SendLogBottomSheet(
                    onDismiss = { showBottomSheet = false },
                    snackbarHostState = snackBarHost,
                )
            }
            Spacer(modifier = Modifier.height(bottomInnerPadding))
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    LargeFlexibleTopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        colors = expressiveTopAppBarColors(),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}
