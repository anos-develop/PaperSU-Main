// paperSU: 设置页里的「寄生」一行。写法参照「一键隐藏环境」那一项。
package com.sukisu.ultra.ui.component.parasite

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun ParasiteRow() {
    val title = stringResource(R.string.settings_parasite_title)
    val summary = stringResource(R.string.settings_parasite_summary)
    val onTap = { ParasiteDialogState.open() }
    if (LocalUiMode.current == UiMode.Miuix) {
        ArrowPreference(
            title = title,
            summary = summary,
            onClick = onTap,
            startAction = {
                androidx.compose.material3.Icon(
                    Icons.Rounded.Cloud,
                    contentDescription = title
                )
            }
        )
    } else {
        SegmentedListItem(
            onClick = onTap,
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = {
                androidx.compose.material3.Icon(
                    Icons.Rounded.Cloud,
                    contentDescription = title
                )
            }
        )
    }
}