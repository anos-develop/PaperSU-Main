package com.sukisu.ultra.ui.screen.mine

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.engine.EngineMode
import com.sukisu.ultra.ui.license.LicenseManager
import com.sukisu.ultra.ui.navigation3.LocalNavigator
import com.sukisu.ultra.ui.navigation3.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The 我的 tab.
 *
 * Licence handling is deliberately offline: a card key is a signed string and the signature is
 * checked on the phone, so nothing about the device is ever transmitted and the app keeps working
 * with no network. The account section is a placeholder for the developer's own backend and does
 * nothing until its endpoints are filled in.
 */
private const val STORE_URL = "https://vip.adt.shdiv.net"
private const val REKEY_URL = "https://vip-anos-rekey.adt.shdiv.net"

@Composable
fun MinePager(
    bottomInnerPadding: Dp,
    @Suppress("UNUSED_PARAMETER") isCurrentPage: Boolean,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    var license by remember { mutableStateOf<LicenseManager.License?>(null) }
    var cardKey by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        license = withContext(Dispatchers.IO) { LicenseManager.current(context) }
        loaded = true
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomInnerPadding + 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("我的", style = MaterialTheme.typography.headlineSmall)

        // ------------------------------------------------------------ 授权状态
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("授权状态", style = MaterialTheme.typography.titleMedium)
                val now = license
                if (now == null) {
                    Text(
                        if (loaded) "未激活 —— 只有基础功能可用" else "读取中…",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    Text(
                        "已激活：${now.tier}" + (if (now.user.isBlank()) "" else "（${now.user}）"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (now.expiry.isNotBlank()) {
                        Text("到期：${now.expiry}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        LicenseManager.clear(context)
                        license = null
                        message = "已在本机移除授权"
                    }) { Text("移除本机授权") }
                }
            }
        }

        // ------------------------------------------------------------ 卡密激活
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("卡密激活", style = MaterialTheme.typography.titleMedium)
                Text(
                    "把卡密粘贴进来，本机离线验签 —— 不联网、不上报任何设备信息。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = cardKey,
                    onValueChange = { cardKey = it },
                    label = { Text("卡密") },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Button(
                    onClick = {
                        message = when (val res = LicenseManager.activate(context, cardKey)) {
                            is LicenseManager.Outcome.Ok -> {
                                license = res.license
                                "激活成功：${res.license.tier}"
                            }
                            is LicenseManager.Outcome.Bad -> "激活失败：${res.reason}"
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text("激活") }
                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }

        // ------------------------------------------------------------ 账号
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("账号登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "登录接口还没接上 —— 你的服务端地址和返回格式发过来之后，这里会接上。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { message = "登录接口尚未接入" }) { Text("登录") }
                    TextButton(onClick = { username = ""; password = "" }) { Text("清空") }
                }
            }
        }

        // ------------------------------------------------------------ VIP 功能
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("VIP 功能", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        license?.isVip == true -> "全部功能已解锁（VIP）"
                        license?.isPro == true -> "Magisk 模式已解锁（Pro）；分区刷写需要 VIP"
                        else -> "未解锁 —— 到上面粘贴卡密（Pro=Magisk 模式，VIP=全部功能）"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(
                    onClick = { navigator.push(Route.PartitionFlash) },
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text("分区刷写") }
            }
        }

        // ------------------------------------------------------------ 购买 / 查卡
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("购买与查询", style = MaterialTheme.typography.titleMedium)
                Text(STORE_URL, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { open(STORE_URL) }) { Text("购买卡密") }
                    TextButton(onClick = { open(REKEY_URL) }) { Text("查询卡密") }
                }
            }
        }

        // ------------------------------------------------------------ 设备信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("设备信息", style = MaterialTheme.typography.titleMedium)
                val rows = listOf(
                    "机型" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "Android" to Build.VERSION.RELEASE,
                    "内核" to EngineMode.kernelRelease(),
                    "运行模式" to EngineMode.current(context).name,
                    "ABI" to Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
                )
                for ((k, v) in rows) {
                    Text("$k：$v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        Text(
            "纸SU · " + stringResource(R.string.app_name),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(16.dp))
    }
}