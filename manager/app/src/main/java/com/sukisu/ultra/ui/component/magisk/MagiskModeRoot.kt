// ---------------------------------------------------------------------------
// paperSU: Magisk 模式的界面 —— 功能对齐 KernelSU 管理器
//
// 内核 4.x 的机器没有 KernelSU，这一档以 Magisk 作为 root 提供者，界面照 KSU 管理器的
// 结构来：底部四个页签（主页 / 超级用户 / 模块 / 设置），内容全部来自真实的 root 查询。
//
// 两条硬规则：
//   1. 所有 root 调用都在 Dispatchers.IO 上，主线程碰它会直接黑屏（踩过）。
//   2. root 一律走 libsu 的 Shell.cmd()，不要自己 ProcessBuilder("su")（也踩过）。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.magisk

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

// ---------------------------------------------------------------------------
// 状态与采集
// ---------------------------------------------------------------------------

data class GrantedApp(val uid: Int, val packages: List<String>, val label: String, val allowed: Boolean)

data class MagiskModule(val id: String, val name: String, val description: String, val enabled: Boolean)

data class MagiskUiState(
    val kernel: String = "",
    val root: Boolean = false,
    val magiskVersion: String = "",
    val apps: List<GrantedApp> = emptyList(),
    val modules: List<MagiskModule> = emptyList(),
    val installed: Boolean = false,
    val loading: Boolean = true,
)

private fun labelOf(ctx: Context, pkg: String): String = runCatching {
    val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
    ctx.packageManager.getApplicationLabel(ai).toString()
}.getOrDefault(pkg)

private suspend fun loadState(ctx: Context): MagiskUiState = withContext(Dispatchers.IO) {
    val kernel = EngineMode.kernelRelease()
    val root = MagiskPatcher.hasRoot()
    val ver = if (root) MagiskPatcher.magiskVersion() else ""
    val installed = root && MagiskPatcher.root("ls /data/adb/magisk").contains("magisk")

    // 已授权应用：magisk --sqlite "SELECT uid,policy FROM policies"
    val apps = mutableListOf<GrantedApp>()
    runCatching {
        val out = MagiskPatcher.root("magisk --sqlite \"SELECT uid,policy FROM policies\"")
        for (line in out.lineSequence()) {
            val uid = Regex("uid=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val pol = Regex("policy=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pkgs = runCatching {
                ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            }.getOrDefault(emptyList())
            if (pkgs.isEmpty()) continue
            apps += GrantedApp(uid, pkgs, labelOf(ctx, pkgs.first()), pol == 2)
        }
    }

    // 模块：/data/adb/modules 下每个目录一个模块
    val modules = mutableListOf<MagiskModule>()
    runCatching {
        val ids = MagiskPatcher.root("ls /data/adb/modules 2>/dev/null")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
        for (id in ids) {
            val prop = MagiskPatcher.root("cat /data/adb/modules/$id/module.prop 2>/dev/null")
            val name = Regex("(?m)^name=(.*)$").find(prop)?.groupValues?.get(1)?.trim() ?: id
            val desc = Regex("(?m)^description=(.*)$").find(prop)?.groupValues?.get(1)?.trim() ?: ""
            val disabled = MagiskPatcher.root("ls /data/adb/modules/$id/disable 2>/dev/null").contains("disable")
            modules += MagiskModule(id, name, desc, !disabled)
        }
    }

    MagiskUiState(kernel, root, ver, apps, modules, installed, loading = false)
}

// ---------------------------------------------------------------------------
// 根容器：底部导航 + 四页
// ---------------------------------------------------------------------------

@Composable
fun MagiskModeRoot() {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(MagiskUiState()) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) { state = loadState(ctx) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = page == 0, onClick = { page = 0 },
                    icon = { Icon(Icons.Rounded.Home, "主页") }, label = { Text("主页") }
                )
                NavigationBarItem(
                    selected = page == 1, onClick = { page = 1 },
                    icon = { Icon(Icons.Rounded.Settings, "超级用户") }, label = { Text("超级用户") }
                )
                NavigationBarItem(
                    selected = page == 2, onClick = { page = 2 },
                    icon = { Icon(Icons.Rounded.Extension, "模块") }, label = { Text("模块") }
                )
                NavigationBarItem(
                    selected = page == 3, onClick = { page = 3 },
                    icon = { Icon(Icons.Rounded.Build, "设置") }, label = { Text("设置") }
                )
            }
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            when (page) {
                0 -> HomePage(state) { tick++ }
                1 -> SuperUserPage(ctx, state) { tick++ }
                2 -> ModulePage(ctx, state) { tick++ }
                else -> SettingsPage(state) { tick++ }
            }
        }
    }
    MagiskHost()
}

// ---------------------------------------------------------------------------
// 主页
// ---------------------------------------------------------------------------

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) { content() }
}

@Composable
private fun HomePage(state: MagiskUiState, onReload: () -> Unit) {
    PageColumn {
        Text("PaperSU", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    state.loading -> MaterialTheme.colorScheme.surfaceVariant
                    state.root -> Color(0xFF0F3D2E)
                    else -> MaterialTheme.colorScheme.errorContainer
                }
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val fg = if (state.root) Color.White else MaterialTheme.colorScheme.onErrorContainer
                    Text(
                        text = if (state.loading) "检测中…" else if (state.root) "工作中" else "未获得 root",
                        style = MaterialTheme.typography.headlineSmall, color = fg
                    )
                    Text("版本：" + state.magiskVersion.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f))
                    Text("Magisk", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f))
                }
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(
                        if (state.root) Color(0xFF19C37D) else MaterialTheme.colorScheme.error
                    )
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                InfoRow("运行模式", "Magisk（内核 4.x）")
                InfoRow("内核版本", state.kernel.ifBlank { "读取中…" })
                InfoRow("Magisk 版本", state.magiskVersion.ifBlank { "—" })
                InfoRow("Magisk 已安装", if (state.installed) "是" else "否")
                InfoRow("已授权应用", "${state.apps.size} 个")
                InfoRow("已安装模块", "${state.modules.size} 个")
            }
        }

        Text(stringResource(R.string.magisk_engine_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onReload) { Text("重新检测") }
    }
}

// ---------------------------------------------------------------------------
// 超级用户
// ---------------------------------------------------------------------------

@Composable
private fun SuperUserPage(ctx: Context, state: MagiskUiState, onReload: () -> Unit) {
    PageColumn {
        Text("超级用户", style = MaterialTheme.typography.headlineSmall)
        Text("来自 magisk 的授权策略表，按真实 UID 对应到包名。",
            style = MaterialTheme.typography.bodySmall)

        if (state.apps.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("（没有已授权的应用）", modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            for (app in state.apps) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.titleSmall)
                            Text(app.packages.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall)
                            Text("uid=${app.uid}   " + if (app.allowed) "ROOT" else "已拒绝",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (app.allowed) Color(0xFF19C37D) else MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = {
                            Thread {
                                MagiskPatcher.root("magisk --sqlite \"DELETE FROM policies WHERE uid=${app.uid}\"")
                                Thread.sleep(300)
                                onReload()
                            }.start()
                        }) { Text("撤销") }
                    }
                }
            }
        }
        TextButton(onClick = onReload) { Text("刷新") }
    }
}

// ---------------------------------------------------------------------------
// 模块
// ---------------------------------------------------------------------------

@Composable
private fun ModulePage(ctx: Context, state: MagiskUiState, onReload: () -> Unit) {
    PageColumn {
        Text("模块", style = MaterialTheme.typography.headlineSmall)
        Text("/data/adb/modules 下的模块，点按钮切换启用状态。",
            style = MaterialTheme.typography.bodySmall)

        if (state.modules.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text("（没有安装模块）", modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            for (m in state.modules) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text(m.id, style = MaterialTheme.typography.bodySmall)
                            if (m.description.isNotEmpty()) {
                                Text(m.description, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (m.enabled) "已启用" else "已停用",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (m.enabled) Color(0xFF19C37D) else MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = {
                            Thread {
                                val p = "/data/adb/modules/${m.id}/disable"
                                if (m.enabled) MagiskPatcher.root("touch $p")
                                else MagiskPatcher.root("rm -f $p")
                                Thread.sleep(300)
                                onReload()
                            }.start()
                        }) { Text(if (m.enabled) "停用" else "启用") }
                    }
                }
            }
        }
        TextButton(onClick = onReload) { Text("刷新") }
    }
}

// ---------------------------------------------------------------------------
// 设置
// ---------------------------------------------------------------------------

@Composable
private fun SettingsPage(state: MagiskUiState, onReload: () -> Unit) {
    val ctx = LocalContext.current
    PageColumn {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                InfoRow("设备", android.os.Build.MODEL)
                InfoRow("Android", android.os.Build.VERSION.RELEASE)
                InfoRow("Magisk 版本", state.magiskVersion.ifBlank { "—" })
                InfoRow("授权接口", ctx.packageName + ".parasite.auth")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.magisk_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.magisk_summary),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { MagiskRowState.open() }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.magisk_start))
                }
            }
        }

        Text("boot 修补能力来自 Magisk（topjohnwu，GPL-3.0-or-later），许可证随资产一起分发。",
            style = MaterialTheme.typography.bodySmall)

        TextButton(onClick = onReload) { Text("重新检测") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}