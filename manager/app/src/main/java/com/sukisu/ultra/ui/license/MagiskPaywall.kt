// ---------------------------------------------------------------------------
// paperSU: 内核 4.x（没有 KernelSU）的机器走 Magisk 模式，那是 Pro 功能。
//
// 之前 MagiskModeRoot() 是无条件进去的 —— detect() 对 4.x 直接返回 Magisk，
// MainActivity 也没查授权，所以 K20 Pro 这类机器不插卡就能用。这里补上门槛：
// 没授权就先给一个能直接输卡密的引导页，激活成功后重启进正式界面。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.license

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun MagiskPaywall() {
    val ctx = LocalContext.current
    var card by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Magisk 运行模式需要 Pro 授权",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Text(
            text = "\n本机内核低于 5.x，没有 KernelSU，只能走 Magisk 的 boot 修补。" +
                "这条路属于 Pro 及以上等级。\n\n" +
                "把购买到的卡密粘进下面的框，点激活即可。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = card,
            onValueChange = { card = it; message = "" },
            label = { Text("卡密") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        )

        Button(
            onClick = {
                when (val result = LicenseManager.activate(ctx, card)) {
                    is LicenseManager.Outcome.Ok -> {
                        ok = true
                        message = "已激活：${result.license.tierLabel}，正在重启…"
                        (ctx as? Activity)?.recreate()
                    }
                    is LicenseManager.Outcome.Bad -> {
                        ok = false
                        message = result.reason
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        ) {
            Text("激活")
        }

        if (message.isNotEmpty()) {
            Text(
                text = message,
                color = if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}