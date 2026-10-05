// ---------------------------------------------------------------------------
// paperSU: Magisk 模式的独立界面
//
// 内核 4.x 的机器上没有 KernelSU，管理器那套以 ksud 为中心的界面既起不来也不该起。
// 所以这一档走自己的极简主页：说清当前状态，给出 boot 修补入口。两套界面共用同一个
// APK，靠 EngineMode 在 setContent 最外层分流 —— 这就是"内置两套 UI"。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.magisk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.engine.EngineMode

@Composable
fun MagiskModeRoot() {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = EngineMode.explain(ctx),
            style = MaterialTheme.typography.bodySmall
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.magisk_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.magisk_summary),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = stringResource(R.string.magisk_intro),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                androidx.compose.material3.TextButton(
                    onClick = { MagiskRowState.open() },
                    modifier = Modifier.padding(top = 8.dp)
                ) { Text(stringResource(R.string.magisk_start)) }
            }
        }

        Text(
            text = stringResource(R.string.magisk_engine_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
    // 修补弹窗挂在这里
    MagiskHost()
}