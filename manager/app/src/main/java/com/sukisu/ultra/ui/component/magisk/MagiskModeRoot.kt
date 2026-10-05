// ---------------------------------------------------------------------------
// paperSU: Magisk 模式的界面（模仿 KernelSU 管理器的首页布局）
//
// 内核 4.x 的机器没有 KernelSU，所以这一档用 Magisk 作为 root 提供者。界面照 KSU 首页
// 的样子来：顶部一张大状态卡（工作中 / 版本 / 提供者），下面是一列信息项，最后是
// boot 修补入口。
//
// 两条硬规则（都踩过坑）：
//   1. 所有 root 调用都必须在 IO 线程上加载，主线程一旦阻塞，界面就是一片黑。
//   2. root 一律走 libsu，不要自己 ProcessBuilder("su")。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.magisk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.component.engine.EngineMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 首页要展示的状态，全部在 IO 线程上采集。 */
data class MagiskUiState(
    val kernel: String = "",
    val root: Boolean = false,
    val magiskVersion: String = "",
    val policyCount: Int = 0,
    val moduleCount: Int = 0,
    val loading: Boolean = true,
)

private suspend fun loadState(): MagiskUiState = withContext(Dispatchers.IO) {
    MagiskUiState(
        kernel = EngineMode.kernelRelease(),
        root = MagiskPatcher.hasRoot(),
        magiskVersion = MagiskPatcher.magiskVersion(),
        policyCount = MagiskPatcher.policyCount(),
        moduleCount = MagiskPatcher.moduleCount(),
        loading = false,
    )
}

@Composable
fun MagiskModeRoot() {
    var state by remember { mutableStateOf(MagiskUiState()) }
    var reloadTick by remember { mutableStateOf(0) }

    // 关键：绝不在主线程碰 su。这里整体切到 IO。
    LaunchedEffect(reloadTick) {
        state = loadState()
    }

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
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium
        )

        // ---------------- 顶部状态卡（照 KSU 的样子）----------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.loading) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else if (state.root) {
                    Color(0xFF0F3D2E)
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.loading -> "检测中…"
                            state.root -> "工作中"
                            else -> "未获得 root"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (state.root) Color.White else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "版本：" + (state.magiskVersion.ifBlank { "—" }),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.root) Color.White.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Magisk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.root) Color.White.copy(alpha = 0.85f)
                                else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.root) Color(0xFF19C37D)
                            else MaterialTheme.colorScheme.error
                        )
                )
            }
        }

        // ---------------- 信息项 ----------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                InfoRow("运行模式", "Magisk（内核 4.x）")
                InfoRow("内核版本", state.kernel.ifBlank { "读取中…" })
                InfoRow("Magisk 版本", state.magiskVersion.ifBlank { "—" })
                InfoRow("已授权应用", "${state.policyCount} 个")
                InfoRow("已安装模块", "${state.moduleCount} 个")
            }
        }

        // ---------------- boot 修补入口 ----------------
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
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { MagiskRowState.open() }) {
                        Text(stringResource(R.string.magisk_start))
                    }
                    TextButton(onClick = { reloadTick++ }) {
                        Text("重新检测")
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.magisk_engine_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }

    MagiskHost()
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}