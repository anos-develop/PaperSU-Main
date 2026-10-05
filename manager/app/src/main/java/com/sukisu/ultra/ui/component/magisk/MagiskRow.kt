// paperSU: 设置页里的「Magisk 修补」一行 + 弹窗。
// 只在内核 4.x（EngineMode 判定为 Magisk 模式）时出现 —— 这就是"两套 UI 切换"。
package com.sukisu.ultra.ui.component.magisk

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.LocalUiMode
import com.sukisu.ultra.ui.UiMode
import com.sukisu.ultra.ui.component.engine.EngineMode
import com.sukisu.ultra.ui.component.material.SegmentedListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference

object MagiskRowState {
    var show by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var log by mutableStateOf("")
    var result by mutableStateOf("")

    fun open() {
        log = ""
        result = ""
        busy = false
        show = true
    }
}

@Composable
fun MagiskRow() {
    // 只有内核 4.x（Magisk 模式）才显示
    val ctx = LocalContext.current
    if (EngineMode.current(ctx) != EngineMode.Mode.Magisk) return
    val title = stringResource(R.string.magisk_title)
    val summary = stringResource(R.string.magisk_summary)
    val onTap = { MagiskRowState.open() }
    if (LocalUiMode.current == UiMode.Miuix) {
        ArrowPreference(
            title = title,
            summary = summary,
            onClick = onTap,
            startAction = { Icon(Icons.Rounded.Build, contentDescription = title) }
        )
    } else {
        SegmentedListItem(
            onClick = onTap,
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            leadingContent = { Icon(Icons.Rounded.Build, contentDescription = title) }
        )
    }
}

@Composable
fun MagiskHost() {
    if (!MagiskRowState.show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!MagiskRowState.busy) MagiskRowState.show = false },
        title = { Text(stringResource(R.string.magisk_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.magisk_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                if (MagiskRowState.log.isNotEmpty()) {
                    Text(
                        text = MagiskRowState.log,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
                if (MagiskRowState.result.isNotEmpty()) {
                    Text(
                        text = MagiskRowState.result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !MagiskRowState.busy,
                onClick = {
                    MagiskRowState.busy = true
                    MagiskRowState.log = ""
                    MagiskRowState.result = ""
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            val boot = MagiskPatcher.dumpCurrentBoot()
                                ?: return@withContext null to "提取当前 boot 失败（需要 root）"
                            val p = MagiskPatcher.patch(ctx, boot) { line ->
                                MagiskRowState.log += line + "\n"
                            }
                            p to null
                        }
                        MagiskRowState.busy = false
                        MagiskRowState.result = if (out.first != null)
                            "修补完成：${out.first}\n用 fastboot 刷入，或从 /sdcard 取走。"
                        else
                            "修补失败。${out.second ?: "看上面的输出。"}"
                    }
                }
            ) { Text(stringResource(if (MagiskRowState.busy) R.string.magisk_working else R.string.magisk_start)) }
        },
        dismissButton = {
            TextButton(
                enabled = !MagiskRowState.busy,
                onClick = { MagiskRowState.show = false }
            ) { Text(stringResource(R.string.parasite_cancel)) }
        }
    )
}