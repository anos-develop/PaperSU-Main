// ---------------------------------------------------------------------------
// paperSU: Magisk 模式的界面 —— 功能对齐 KernelSU 管理器
//
// 四页：主页 / 超级用户 / 模块 / 设置。
//   · 超级用户：列出【所有有启动图标的已安装应用】，用开关给/撤 root —— 和 KSU 一样
//   · 模块：列出 /data/adb/modules，可启用/停用，可【从 zip 安装】
//   · 设置：修补 boot 镜像，两条路 —— 提取当前分区（要 root）/ 选文件（不需要 root）
//
// 三条硬规则（全部踩过坑）：
//   1. root 一律走 libsu 的 Shell.cmd()，不要自己 ProcessBuilder("su")
//   2. 所有 root 调用都在 Dispatchers.IO，主线程碰它直接黑屏
//   3. 修补用户选的镜像文件【不需要 root】—— 只有提取当前 boot 分区才需要
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.magisk

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
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
import java.io.File
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding

// ---------------------------------------------------------------------------
// 数据
// ---------------------------------------------------------------------------

data class AppEntry(val label: String, val pkg: String, val uid: Int, val allowed: Boolean)
data class MagiskModule(val id: String, val name: String, val description: String, val enabled: Boolean)
data class MagiskUiState(
    val kernel: String = "",
    val root: Boolean = false,
    val magiskVersion: String = "",
    val magiskInstalled: Boolean = false,
    val apps: List<AppEntry> = emptyList(),
    val modules: List<MagiskModule> = emptyList(),
    val loading: Boolean = true,
)

/** 读取 magisk 授权表里的 uid → 是否允许。 */
private fun allowedUids(): Set<Int> = runCatching {
    Regex("uid=(\\d+)").findAll(MagiskPatcher.root("magisk --sqlite \"SELECT uid,policy FROM policies\""))
        .mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
}.getOrDefault(emptySet())

private fun allApps(ctx: Context, allowed: Set<Int>): List<AppEntry> = runCatching {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(intent, 0).mapNotNull { ri ->
        val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
        val uid = runCatching { pm.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return@mapNotNull null
        val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
        AppEntry(label, pkg, uid, uid in allowed)
    }.distinctBy { it.pkg }.sortedBy { it.label }
}.getOrDefault(emptyList())

private suspend fun loadState(ctx: Context): MagiskUiState = withContext(Dispatchers.IO) {
    val root = MagiskPatcher.hasRoot()
    val ver = if (root) MagiskPatcher.magiskVersion() else ""
    val installed = root && MagiskPatcher.root("ls /data/adb/magisk").contains("magisk")
    val mods = mutableListOf<MagiskModule>()
    if (root) runCatching {
        val ids = MagiskPatcher.root("ls /data/adb/modules 2>/dev/null")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("(") }
        for (id in ids) {
            val prop = MagiskPatcher.root("cat /data/adb/modules/$id/module.prop 2>/dev/null")
            val name = Regex("(?m)^name=(.*)$").find(prop)?.groupValues?.get(1)?.trim() ?: id
            val desc = Regex("(?m)^description=(.*)$").find(prop)?.groupValues?.get(1)?.trim() ?: ""
            val disabled = MagiskPatcher.root("ls /data/adb/modules/$id/disable 2>/dev/null").contains("disable")
            mods += MagiskModule(id, name, desc, !disabled)
        }
    }
    MagiskUiState(
        kernel = EngineMode.kernelRelease(),
        root = root,
        magiskVersion = ver,
        magiskInstalled = installed,
        apps = allApps(ctx, if (root) allowedUids() else emptySet()),
        modules = mods,
        loading = false,
    )
}

/** 把 content Uri 复制到应用外部目录（root 也读得到）。 */
private fun copyUriToCache(ctx: Context, uri: android.net.Uri, name: String): File? = runCatching {
    val dir = File(ctx.getExternalFilesDir(null), "patch").apply { mkdirs() }
    val out = File(dir, name)
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { input.copyTo(it) }
    }
    out
}.getOrNull()

// ---------------------------------------------------------------------------
// 根容器
// ---------------------------------------------------------------------------

@Composable
fun MagiskModeRoot() {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(MagiskUiState()) }
    var tick by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf("") }

    LaunchedEffect(tick) { state = loadState(ctx) }

    Scaffold(
        // paperSU: 必须让内容避开状态栏和手势条。之前没写这一行，
        // 顶部标题会顶到状态栏、列表最后几项会被底部导航栏压住 —— 看起来就是"没显示完全"。
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = page == 0, onClick = { page = 0 },
                    icon = { Icon(Icons.Rounded.Home, "主页") }, label = { Text("主页") })
                NavigationBarItem(selected = page == 1, onClick = { page = 1 },
                    icon = { Icon(Icons.Rounded.Settings, "超级用户") }, label = { Text("超级用户") })
                NavigationBarItem(selected = page == 2, onClick = { page = 2 },
                    icon = { Icon(Icons.Rounded.Extension, "模块") }, label = { Text("模块") })
                NavigationBarItem(selected = page == 3, onClick = { page = 3 },
                    icon = { Icon(Icons.Rounded.Build, "设置") }, label = { Text("设置") })
            }
        }
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when (page) {
                0 -> HomePage(state, { tick++ }, { tick++ })
                1 -> SuperUserPage(ctx, state, toast) { tick++ }
                2 -> ModulePage(ctx, state, toast) { tick++ }
                else -> SettingsPage(state, toast) { tick++ }
            }
        }
    }
    MagiskHost()
}

@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

// ---------------------------------------------------------------------------
// 主页
// ---------------------------------------------------------------------------

@Composable
private fun HomePage(state: MagiskUiState, onReload: () -> Unit, onRequestRoot: () -> Unit) {
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
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val fg = if (state.root) Color.White else MaterialTheme.colorScheme.onErrorContainer
                    Text(if (state.loading) "检测中…" else if (state.root) "工作中" else "未获得 root",
                        style = MaterialTheme.typography.headlineSmall, color = fg)
                    Text("版本：" + state.magiskVersion.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f))
                    Text("Magisk", style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f))
                }
                Box(Modifier.size(56.dp).clip(CircleShape).background(
                    if (state.root) Color(0xFF19C37D) else MaterialTheme.colorScheme.error))
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                InfoRow("运行模式", "Magisk（内核 4.x）")
                InfoRow("内核版本", state.kernel.ifBlank { "读取中…" })
                InfoRow("Magisk 版本", state.magiskVersion.ifBlank { "—" })
                InfoRow("Magisk 已安装", if (state.magiskInstalled) "是" else "否")
                InfoRow("已安装应用", "${state.apps.size} 个")
                InfoRow("已授权 root", "${state.apps.count { it.allowed }} 个")
                InfoRow("已安装模块", "${state.modules.size} 个")
            }
        }
        // 主动请求 root（仅 Magisk 模式有这一块）。
        // 点一下就跑一次 su，Magisk 会弹出授权框；用户点允许（建议勾永久记住）之后，
        // hasRoot() 就为 true，界面会变成"工作中"。
        if (!state.root) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("还没有 root 权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "paperSU 自己需要一次 root。这一下会弹 Magisk 的框 —— 只此一次，而且只针对 paperSU 本身。" +
                            "允许之后它会把自己的 uid 固化成永久，之后再给别的应用授权就是直接写策略表，不再弹任何框。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    TextButton(onClick = onRequestRoot, modifier = Modifier.padding(top = 8.dp)) {
                        Text("获取 root（仅此一次）")
                    }
                }
            }
        }
        Row {
            TextButton(onClick = onRequestRoot) {
                Text(if (state.root) "重新申请 root" else "请求 root")
            }
            TextButton(onClick = onReload) { Text("重新检测") }
        }
    }
}

// ---------------------------------------------------------------------------
// 超级用户：所有应用 + 开关（和 KSU 一样）
// ---------------------------------------------------------------------------

@Composable
private fun SuperUserPage(ctx: Context, state: MagiskUiState, toast: String, onReload: () -> Unit) {
    var pending by remember { mutableStateOf<Set<Int>>(emptySet()) }
    PageColumn {
        Text("超级用户", style = MaterialTheme.typography.headlineSmall)
        Text("下面是你手机上所有有启动图标的应用。打开开关就是给它 root（写进 magisk 的授权表）。",
            style = MaterialTheme.typography.bodySmall)
        if (toast.isNotEmpty()) Text(toast, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)

        for (app in state.apps) {
            val checked = if (app.uid in pending) !app.allowed else app.allowed
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                        Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                        Text("uid=${app.uid}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = checked, onCheckedChange = { want ->
                        pending = pending + app.uid
                        Thread {
                            MagiskPatcher.setUidPolicy(app.uid, want)
                            Thread.sleep(250)
                            onReload()
                        }.start()
                    })
                }
            }
        }
        TextButton(onClick = onReload) { Text("刷新") }
    }
}

// ---------------------------------------------------------------------------
// 模块：列表 + 启用停用 + 从 zip 安装
// ---------------------------------------------------------------------------

@Composable
private fun ModulePage(ctx: Context, state: MagiskUiState, toast: String, onReload: () -> Unit) {
    var installing by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        installing = true
        log = "正在安装…\n"
        Thread {
            val f = copyUriToCache(ctx, uri, "module.zip")
            if (f == null) { log += "复制失败\n"; installing = false; return@Thread }
            log += MagiskPatcher.installModule(f.absolutePath)
            Thread.sleep(400)
            installing = false
            onReload()
        }.start()
    }

    PageColumn {
        Text("模块", style = MaterialTheme.typography.headlineSmall)
        Text("/data/adb/modules 下的模块。安装是调 magisk --install-module，和官方一致。",
            style = MaterialTheme.typography.bodySmall)

        Row(Modifier.padding(top = 4.dp)) {
            TextButton(enabled = !installing, onClick = {
                zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }) { Text(if (installing) "安装中…" else "从 zip 安装") }
        }
        if (log.isNotEmpty()) {
            Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
        if (toast.isNotEmpty()) Text(toast, style = MaterialTheme.typography.bodySmall)

        if (state.modules.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("（没有安装模块）", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            for (m in state.modules) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall)
                            Text(m.id, style = MaterialTheme.typography.bodySmall)
                            if (m.description.isNotEmpty()) Text(m.description, style = MaterialTheme.typography.bodySmall)
                            Text(if (m.enabled) "已启用" else "已停用",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (m.enabled) Color(0xFF19C37D) else MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = {
                            Thread {
                                val p = "/data/adb/modules/${m.id}/disable"
                                if (m.enabled) MagiskPatcher.root("touch $p") else MagiskPatcher.root("rm -f $p")
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
// 设置：修补镜像（两条路）
// ---------------------------------------------------------------------------

@Composable
private fun SettingsPage(state: MagiskUiState, toast: String, onReload: () -> Unit) {
    val ctx = LocalContext.current
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 选一个文件来修补 —— 这条路不需要 root
    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        log = "已选择文件，开始修补（不需要 root）…\n"
        Thread {
            val f = copyUriToCache(ctx, uri, "chosen_boot.img")
            if (f == null) { log += "复制失败\n"; busy = false; return@Thread }
            val out = MagiskPatcher.patchFile(ctx, f) { line -> log += line }
            log += if (out != null) "\n✓ 完成：$out\n" else "\n✗ 失败\n"
            busy = false
        }.start()
    }

    PageColumn {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp)) {
                InfoRow("设备", android.os.Build.MODEL)
                InfoRow("Android", android.os.Build.VERSION.RELEASE)
                InfoRow("Magisk 版本", state.magiskVersion.ifBlank { "—" })
                InfoRow("授权接口", ctx.packageName + ".parasite.auth")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.magisk_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.magisk_summary),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Text("两条路：\n· 提取当前 boot 分区再修补 —— 需要 root\n· 选一个 boot 镜像文件修补 —— 【不需要 root】",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(enabled = !busy, onClick = { MagiskRowState.open() }) { Text("提取当前 boot 修补") }
                    TextButton(enabled = !busy, onClick = {
                        imgPicker.launch(arrayOf("*/*"))
                    }) { Text(if (busy) "修补中…" else "选文件修补") }
                }
                if (log.isNotEmpty()) {
                    Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                if (toast.isNotEmpty()) Text(toast, style = MaterialTheme.typography.bodySmall)
            }
        }

        // 运行模式切换：Magisk 模式 <-> KernelSU 模式，也可以交回自动判定。
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("运行模式", style = MaterialTheme.typography.titleMedium)
                Text(
                    EngineMode.explain(ctx),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = {
                        EngineMode.setOverride(EngineMode.Mode.Magisk, ctx); restartApp(ctx)
                    }) { Text("Magisk 模式") }
                    TextButton(onClick = {
                        EngineMode.setOverride(EngineMode.Mode.KernelSU, ctx); restartApp(ctx)
                    }) { Text("KernelSU 模式") }
                    TextButton(onClick = {
                        EngineMode.setOverride(null, ctx); restartApp(ctx)
                    }) { Text("自动") }
                }
                Text(
                    "切换后应用会自动重启并加载对应界面。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text("boot 修补能力来自 Magisk（topjohnwu，GPL-3.0-or-later），许可证随资产一起分发。",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onReload) { Text("重新检测") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 切换模式后重启应用，让 setContent 重新按新模式分流。 */
private fun restartApp(ctx: Context) {
    runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return@runCatching
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(i)
        (ctx as? android.app.Activity)?.finish()
    }
}