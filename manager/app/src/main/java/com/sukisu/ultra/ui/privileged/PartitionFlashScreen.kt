package com.sukisu.ultra.ui.privileged

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sukisu.ultra.ui.license.LicenseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Partition flashing, gated on a VIP licence.
 *
 * Everything runs on Dispatchers.IO; nothing root-related may touch the main thread.
 */
@Composable
fun PartitionFlashScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pro by remember { mutableStateOf<Boolean?>(null) }
    var partitions by remember { mutableStateOf<List<PartitionFlasher.Partition>>(emptyList()) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var askFlash by remember { mutableStateOf<PartitionFlasher.Partition?>(null) }
    var asked by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<PartitionFlasher.Partition?>(null) }

    fun append(text: String) {
        log += text
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val part = target
        target = null
        if (uri == null || part == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                val cached = File(context.cacheDir, "psu-flash-${System.currentTimeMillis()}.img")
                val copied = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        cached.outputStream().use { output -> input.copyTo(output) }
                    }
                    cached
                }.getOrNull()
                if (copied == null || !copied.isFile || copied.length() == 0L) {
                    append("读不到镜像内容\n")
                } else {
                    append("\n=== ${part.name} ===\n")
                    when (val outcome = PartitionFlasher.flash(part, copied, ::append)) {
                        is PartitionFlasher.FlashOutcome.Done ->
                            append("完成，写入 ${outcome.bytes} 字节。恢复到原状态请刷回备份。\n")
                        is PartitionFlasher.FlashOutcome.Refused ->
                            append("未执行：${outcome.reason}\n")
                    }
                    runCatching { copied.delete() }
                }
                Unit
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            pro = LicenseManager.current(context)?.isPro == true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("分区刷写", style = MaterialTheme.typography.headlineSmall)
        Text(
            "直接对 /dev/block/by-name 下的分区写镜像。覆盖不可撤销。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )

        when (pro) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            false -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("VIP 功能", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "分区刷写需要 VIP 授权。到「我的」页粘贴卡密激活后即可使用。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            true -> {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) {
                                val list = PartitionFlasher.list(context)
                                append("找到 ${list.size} 个分区\n")
                                partitions = list
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp)
                ) { Text("读取分区表") }

                Text(
                    "备份会写到 " + (context.getExternalFilesDir(null)?.absolutePath ?: "?") + "/partitions/",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp)
                )

                if (busy) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                        Text("  处理中…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(partitions, key = { it.name }) { part ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        part.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(part.humanSize, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    part.block,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (part.dangerous) {
                                    Text(
                                        "⚠ 刷坏这个分区可能导致无法开机",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Row(modifier = Modifier.padding(top = 4.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                withContext(Dispatchers.IO) {
                                                    append("\n=== 备份 ${part.name} ===\n")
                                                    val f = PartitionFlasher.backup(context, part, ::append)
                                                    append(if (f != null) "备份好了：${f.absolutePath}\n" else "备份失败\n")
                                                }
                                                busy = false
                                            }
                                        }
                                    ) { Text("备份") }
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            asked = ""
                                            askFlash = part
                                        }
                                    ) { Text("刷写") }
                                }
                            }
                        }
                    }
                }

                if (log.isNotBlank()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        Column(
                            Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                log,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    val ask = askFlash
    if (ask != null) {
        val mustType = ask.dangerous
        AlertDialog(
            onDismissRequest = { askFlash = null },
            title = { Text("刷写 ${ask.name}？") },
            text = {
                Column {
                    Text("$ ${ask.block}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("大小 ${ask.humanSize}", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        if (mustType) {
                            "这是关键分区。刷坏它可能让手机无法开机。建议先点「备份」。\n\n" +
                                "确认请在下框输入分区名：${ask.name}"
                        } else {
                            "分区内容会被镜像完全覆盖，不可撤销。建议先点「备份」。"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (mustType) {
                        OutlinedTextField(
                            value = asked,
                            onValueChange = { asked = it },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !mustType || asked.trim() == ask.name,
                    onClick = {
                        target = ask
                        askFlash = null
                        picker.launch(arrayOf("*/*"))
                    }
                ) { Text("选择镜像并刷写") }
            },
            dismissButton = {
                TextButton(onClick = { askFlash = null }) { Text("取消") }
            }
        )
    }
}