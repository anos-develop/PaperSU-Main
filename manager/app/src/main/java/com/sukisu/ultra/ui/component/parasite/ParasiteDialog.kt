// ---------------------------------------------------------------------------
// paperSU: 寄生
//   开关本身不"寄生"到任何别的 App 里 —— 它做的是把网页管理端交给 ksud 常驻，
//   让 root 的控制台不再依赖本应用：删掉管理器之后，用浏览器打开那个带密钥的
//   链接，照样能管理 root。网页端本来就跑在 ksud（root 守护进程）里，
//   而且 ksud 的 post-fs-data 钩子会让它开机自己回来。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.parasite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.WebAdminCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 弹窗状态。设置页写 ParasiteDialogState.open() 就能弹出来。 */
object ParasiteDialogState {
    var show by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var url by mutableStateOf("")
    var enabled by mutableStateOf(false)

    fun open() {
        url = ""
        busy = false
        show = true
    }

    fun close() {
        if (!busy) show = false
    }
}

/**
 * 挂在设置页 composable 顶层即可（和 HideEnvHost 同级）。
 * 点「开启」→ 调 ksud webadmin on → 把带密钥的链接复制到剪贴板。
 */
@Composable
fun ParasiteHost() {
    if (!ParasiteDialogState.show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { ParasiteDialogState.close() },
        title = { Text(stringResource(R.string.parasite_confirm_title)) },
        text = {
            Text(
                when {
                    ParasiteDialogState.busy -> stringResource(R.string.parasite_working)
                    ParasiteDialogState.url.isNotEmpty() ->
                        stringResource(R.string.parasite_done, ParasiteDialogState.url)
                    else -> stringResource(R.string.parasite_confirm_message)
                }
            )
        },
        confirmButton = {
            TextButton(
                enabled = !ParasiteDialogState.busy && ParasiteDialogState.url.isEmpty(),
                onClick = {
                    ParasiteDialogState.busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val ok = WebAdminCli.setEnabled(true)
                            val u = if (ok) WebAdminCli.url() else ""
                            ok to u
                        }
                        ParasiteDialogState.busy = false
                        ParasiteDialogState.enabled = result.first
                        ParasiteDialogState.url = result.second
                        if (result.first && result.second.isNotEmpty()) {
                            runCatching {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("paperSU webadmin", result.second))
                            }
                            Toast.makeText(ctx, ctx.getString(R.string.parasite_copied), Toast.LENGTH_LONG).show()
                        } else if (!result.first) {
                            Toast.makeText(ctx, ctx.getString(R.string.parasite_failed), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            ) { Text(stringResource(R.string.parasite_enable)) }
        },
        dismissButton = {
            TextButton(
                enabled = !ParasiteDialogState.busy,
                onClick = { ParasiteDialogState.close() }
            ) { Text(stringResource(R.string.parasite_cancel)) }
        }
    )
}