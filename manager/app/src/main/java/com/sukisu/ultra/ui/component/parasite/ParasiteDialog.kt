// ---------------------------------------------------------------------------
// paperSU: 寄生
//   开关本身不"寄生"到任何别的应用里。它做两件事：
//     1) 把网页管理端交给 ksud 常驻，于是删掉管理器之后，用浏览器打开那个带密钥的
//        链接照样能管理 root（网页端本来就跑在 ksud 里，post-fs-data 钩子会让它
//        开机自己回来）。
//     2) 维护一份授权白名单：只有名单里的包名，才能通过 ParasiteAuthProvider 拿到
//        那个链接。默认名单是空的 —— 谁都不许用。
//   被授权的应用是主动来问链接的，我们只核对包名，不干预任何应用的启动。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.parasite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.util.WebAdminCli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ParasiteDialogState {
    var show by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var url by mutableStateOf("")
    var enabled by mutableStateOf(false)
    var allowed by mutableStateOf<Set<String>>(emptySet())
    var input by mutableStateOf("")
    var authority by mutableStateOf("")

    fun open(ctx: Context) {
        url = ""
        busy = false
        input = ""
        allowed = ParasiteWhitelist.get(ctx)
        authority = "${ctx.packageName}.parasite.auth"
        show = true
    }

    fun close() {
        if (!busy) show = false
    }
}

@Composable
fun ParasiteHost() {
    if (!ParasiteDialogState.show) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    fun refresh() {
        ParasiteDialogState.allowed = ParasiteWhitelist.get(ctx)
    }

    AlertDialog(
        onDismissRequest = { ParasiteDialogState.close() },
        title = { Text(stringResource(R.string.parasite_confirm_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    when {
                        ParasiteDialogState.busy -> stringResource(R.string.parasite_working)
                        ParasiteDialogState.url.isNotEmpty() ->
                            stringResource(R.string.parasite_done, ParasiteDialogState.url)
                        else -> stringResource(R.string.parasite_confirm_message)
                    }
                )

                // ---------------- 授权白名单 ----------------
                Text(
                    text = stringResource(R.string.parasite_whitelist_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Text(
                    text = stringResource(R.string.parasite_whitelist_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (ParasiteDialogState.allowed.isEmpty()) {
                    Text(
                        text = stringResource(R.string.parasite_whitelist_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    ParasiteDialogState.allowed.sorted().forEach { pkg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pkg,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                ParasiteWhitelist.revoke(pkg, ctx)
                                refresh()
                            }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.parasite_revoke))
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = ParasiteDialogState.input,
                    onValueChange = { ParasiteDialogState.input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.parasite_add_label)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    TextButton(
                        enabled = ParasiteDialogState.input.isNotBlank(),
                        onClick = {
                            ParasiteWhitelist.allow(ParasiteDialogState.input.trim(), ctx)
                            ParasiteDialogState.input = ""
                            refresh()
                        }
                    ) { Text(stringResource(R.string.parasite_add)) }
                }

                Text(
                    text = stringResource(R.string.parasite_authority_hint, ParasiteDialogState.authority),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !ParasiteDialogState.busy,
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