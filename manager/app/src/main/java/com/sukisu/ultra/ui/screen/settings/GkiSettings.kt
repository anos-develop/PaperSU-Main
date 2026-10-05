// ---------------------------------------------------------------------------
// paperSU: 内置内核（GKI / 非 LKM）模式专有的设置项
//   只在 !uiState.isLkmMode 时显示。设置页里每个 item 是一行，所以这里按行导出，
//   每行都是自包含的 composable，设置页只需要在列表里插一行调用即可。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.screen.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.Natives
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Route
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.LocalUiMode
import top.yukonga.miuix.kmp.preference.ArrowPreference
import com.sukisu.ultra.ui.component.material.SegmentedListItem

/** 内置内核模式：显示内核身份（版本串 / 运行模式）。 */
@Composable
fun GkiKernelInfoTitle(): String {
    val full = runCatching { Natives.getFullVersion() }.getOrNull()
    return full?.takeIf { it.isNotBlank() } ?: stringResource(R.string.gki_kernel_unknown)
}

/** 内置内核模式：跳到"安装/刷入内核"页面重新刷一次。 */
@Composable
fun GkiReflashKernelItem() {
    val navigator = LocalNavigator.current
    val title = stringResource(R.string.gki_reflash_kernel)
    val summary = stringResource(R.string.gki_reflash_kernel_summary)
    GkiTwoLineItem(
        icon = Icons.Filled.SystemUpdate,
        title = title,
        summary = summary,
        onClick = { runCatching { navigator.push(Route.Install()) } }
    )
}

/** 内置内核模式：内核信息行。 */
@Composable
fun GkiKernelInfoItem() {
    val title = stringResource(R.string.gki_kernel_info)
    val version = GkiKernelInfoTitle()
    GkiTwoLineItem(
        icon = Icons.Filled.Info,
        title = title,
        summary = version,
        onClick = null
    )
}

/** 内部：按当前界面模式渲染一行（Miuix 用 ArrowPreference，Material 用 SegmentedListItem）。 */
@Composable
private fun GkiTwoLineItem(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: (() -> Unit)?
) {
    if (LocalUiMode.current == UiMode.Miuix) {
        ArrowPreference(
            title = title,
            summary = summary,
            startAction = { Icon(icon, contentDescription = title) },
            onClick = onClick ?: {}
        )
    } else {
        SegmentedListItem(
            onClick = onClick ?: {},
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(icon, contentDescription = title) }
        )
    }
}