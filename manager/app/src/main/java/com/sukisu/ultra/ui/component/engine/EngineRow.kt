// paperSU: 设置页里的「运行模式」一行，显示当前判定结果并允许手动覆盖。
package com.sukisu.ultra.ui.component.engine

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import top.yukonga.miuix.kmp.preference.ArrowPreference

object EngineRowState {
    var show by mutableStateOf(false)
}

@Composable
fun EngineRow() {
    val ctx = LocalContext.current
    val cur = EngineMode.current(ctx)
    val title = stringResource(R.string.engine_title)
    val summary = cur.label + " · " + stringResource(
        if (cur == EngineMode.Mode.Magisk) R.string.engine_summary_magisk
        else R.string.engine_summary_ksu
    )
    val onTap = { EngineRowState.show = true }

    if (LocalUiMode.current == UiMode.Miuix) {
        ArrowPreference(
            title = title,
            summary = summary,
            onClick = onTap,
            startAction = { Icon(Icons.Rounded.Memory, contentDescription = title) }
        )
    } else {
        SegmentedListItem(
            onClick = onTap,
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(Icons.Rounded.Memory, contentDescription = title) }
        )
    }
}

@Composable
fun EngineHost() {
    if (!EngineRowState.show) return
    val ctx: Context = LocalContext.current
    AlertDialog(
        onDismissRequest = { EngineRowState.show = false },
        title = { Text(stringResource(R.string.engine_title)) },
        text = {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text(EngineMode.explain(ctx), style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(R.string.engine_override_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                EngineMode.setOverride(EngineMode.Mode.KernelSU, ctx)
                EngineRowState.show = false
            }) { Text("KernelSU") }
        },
        dismissButton = {
            TextButton(onClick = {
                EngineMode.setOverride(EngineMode.Mode.Magisk, ctx)
                EngineRowState.show = false
            }) { Text("Magisk") }
        }
    )
}