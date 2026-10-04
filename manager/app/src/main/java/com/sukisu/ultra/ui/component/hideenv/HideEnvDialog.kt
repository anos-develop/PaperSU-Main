// ---------------------------------------------------------------------------
// paperSU: 一键隐藏环境的确认弹窗（写法参照 InstallScreen 里选 KMI 的那个弹窗）
//   设置页里只需要两处改动：
//     1) 在 composable 顶层加一行  HideEnvHost()
//     2) 开关打开时改成            HideEnvDialogState.show = true
//   所有状态都放在下面的 object 里，设置页不需要声明任何 remember。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.hideenv

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
import com.sukisu.ultra.ui.util.HideEnvInstaller
import kotlinx.coroutines.launch

/** 弹窗状态。设置页只要写 HideEnvDialogState.show = true 就能弹出来。 */
object HideEnvDialogState {
    var show by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var log by mutableStateOf("")

    fun open() {
        log = ""
        busy = false
        show = true
    }

    fun close() {
        if (!busy) show = false
    }
}

/**
 * 挂在设置页 composable 顶层即可（和 UninstallDialog 同级）。
 * 用户确认后调用 [HideEnvInstaller.install]，下载并刷入隐藏模块。
 */
@Composable
fun HideEnvHost() {
    if (!HideEnvDialogState.show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { HideEnvDialogState.close() },
        title = { Text(stringResource(R.string.hide_env_confirm_title)) },
        text = {
            Text(
                if (HideEnvDialogState.busy) {
                    HideEnvDialogState.log.ifBlank { stringResource(R.string.hide_env_running) }
                } else {
                    stringResource(R.string.hide_env_confirm_message)
                }
            )
        },
        confirmButton = {
            TextButton(
                enabled = !HideEnvDialogState.busy,
                onClick = {
                    HideEnvDialogState.busy = true
                    HideEnvDialogState.log = ""
                    scope.launch {
                        val res = HideEnvInstaller.install { line ->
                            HideEnvDialogState.log = HideEnvDialogState.log + line + "\n"
                        }
                        HideEnvDialogState.busy = false
                        HideEnvDialogState.show = false
                        Toast.makeText(
                            ctx,
                            ctx.getString(if (res.ok) R.string.hide_env_done else R.string.hide_env_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            ) { Text(stringResource(R.string.hide_env_confirm_ok)) }
        },
        dismissButton = {
            TextButton(
                enabled = !HideEnvDialogState.busy,
                onClick = { HideEnvDialogState.close() }
            ) { Text(stringResource(R.string.hide_env_confirm_cancel)) }
        }
    )
}