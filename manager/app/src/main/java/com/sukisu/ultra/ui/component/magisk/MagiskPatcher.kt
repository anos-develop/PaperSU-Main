// ---------------------------------------------------------------------------
// paperSU: Magisk 模式的 boot 修补
//
// 内核 4.x 的机器走这一套：把 Magisk 的修补链从 APK 的 assets 解出来，照 Magisk 官方的
// 方式修补一份 boot 镜像，产出 magisk_patched.img。
//
// 关键点：
//   · root 必须走 libsu（com.topjohnwu.superuser），也就是 Magisk 官方用的那套。
//     自己 ProcessBuilder("su","-c",...) 在 Android 应用里不可靠 —— 表现就是 su 明明
//     有授权，命令却拿不到 root。
//   · assets 解出来的文件【没有执行位】，必须显式 setExecutable。
//   · 所有 su 调用都在 IO 线程上跑（调用方负责），绝不占主线程。
//
// 修补能力来自 Magisk（topjohnwu，GPL-3.0-or-later），LICENSE 随资产放在
// assets/magisk/LICENSE-Magisk。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.magisk

import android.content.Context
import android.util.Log
// paperSU: 不再依赖 libsu 的 Shell（它对 su 位置的探测在本机不成立）
import java.io.File

object MagiskPatcher {

    private const val TAG = "paperSU-magisk"
    private const val ASSET_DIR = "magisk"

    private val EXECUTABLES = setOf(
        "busybox", "magiskboot", "magiskinit", "magisk", "magiskpolicy", "init-ld"
    )

    fun workDir(ctx: Context): File = File(ctx.filesDir, ASSET_DIR)

    /**
     * su 二进制的候选位置。
     *
     * paperSU: 这里踩过一个很坑的问题 —— libsu 只会在 /system/bin、/system/xbin、/sbin
     * 这几个传统位置找 su，而 MIUI (Android 11) 上 Magisk 的 su 实际装在
     * /product/bin/su。找不到时 libsu 不会报错，而是把状态标成"Magisk 未安装"，
     * 于是管理器弹"需要下载完整版 Magisk" —— 明明 Magisk 30.7 装得好好的。
     *
     * 所以这里不再依赖 libsu 的探测：自己按候选表找，找不到再问 which。
     */
    private val SU_CANDIDATES = listOf(
        "/product/bin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/magisk/.core/bin/su",
    )

    @Volatile
    private var cachedSu: String? = null

    /** 找到可执行的 su，找不到返回 null。结果会缓存。 */
    fun findSu(): String? {
        cachedSu?.let { if (File(it).canExecute()) return it }
        for (c in SU_CANDIDATES) {
            if (File(c).canExecute()) { cachedSu = c; return c }
        }
        val viaWhich = runCatching {
            val p = ProcessBuilder("sh", "-c", "which su")
                .redirectErrorStream(true).start()
            val t = p.inputStream.bufferedReader().readText()
            p.waitFor()
            t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && File(it).canExecute() }
        }.getOrNull()
        if (viaWhich != null) cachedSu = viaWhich
        Log.i(TAG, "findSu -> ${viaWhich ?: "none"}")
        return viaWhich
    }

    /**
     * 跑一条 root 命令，返回合并后的输出。
     *
     * paperSU: 不再走 libsu 的 Shell.cmd —— 它探测不到 su 时会弹自己的"下载 Magisk"
     * 引导页，而不是把我们引到已有的 Magisk 上。直接 spawn 目标 su 才是确定的行为。
     */
    fun root(vararg cmds: String): String {
        val su = findSu() ?: return "找不到 su 二进制（试过 ${SU_CANDIDATES.joinToString()}）\n"
        return runCatching {
            val p = ProcessBuilder(listOf(su, "-c", cmds.joinToString("\n")))
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrElse { e ->
            Log.w(TAG, "root command failed", e)
            "su error: ${e.message}\n"
        }
    }

    /** 有没有 root。 */
    fun hasRoot(): Boolean = runCatching {
        val out = root("id")
        Log.i(TAG, "hasRoot: ${out.trim().take(120)}")
        out.contains("uid=0")
    }.getOrDefault(false)

    /** Magisk 版本串，例如 "30.7:MAGISK:R"。 */
    fun magiskVersion(): String = root("magisk -v").trim().lineSequence().firstOrNull().orEmpty().trim()

    /** 已授权的应用数量。 */
    fun policyCount(): Int = runCatching {
        val out = root("magisk --sqlite \"SELECT uid FROM policies\"")
        out.lineSequence().count { it.contains("uid=") }
    }.getOrDefault(0)

    /** 模块数量。 */
    fun moduleCount(): Int = runCatching {
        val out = root("ls /data/adb/modules 2>/dev/null")
        out.lineSequence().map { it.trim() }.count { it.isNotEmpty() && !it.startsWith("(") }
    }.getOrDefault(0)

    /** 把资产解到工作目录并补执行位。 */
    fun ensureAssets(ctx: Context): File {
        val dir = workDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        val names = ctx.assets.list(ASSET_DIR).orEmpty()
        for (name in names) {
            val out = File(dir, name)
            if (name in EXECUTABLES || !out.exists()) {
                runCatching {
                    ctx.assets.open("$ASSET_DIR/$name").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                }.onFailure { Log.w(TAG, "extract $name failed", it) }
            }
            if (name in EXECUTABLES) out.setExecutable(true, false)
        }
        for (s in listOf("boot_patch.sh", "util_functions.sh")) {
            File(dir, s).takeIf { it.exists() }?.setExecutable(true, false)
        }
        return dir
    }

    /** 提取当前 boot 分区。成功返回设备路径。 */
    fun dumpCurrentBoot(): String? {
        val out = "/sdcard/papersu_stock_boot.img"
        val log = root("dd if=/dev/block/by-name/boot of=$out bs=4096 2>&1", "ls -l $out")
        Log.i(TAG, "dump: $log")
        return if (log.contains("No such") || log.contains("Permission denied")) null else out
    }

    /**
     * 修补一份 boot 镜像。bootOnDevice 是设备上的路径。
     * 返回 /sdcard/magisk_patched.img 或 null。
     */
    fun patch(ctx: Context, bootOnDevice: String, onLog: (String) -> Unit): String? {
        val dir = ensureAssets(ctx)
        val work = dir.absolutePath
        val local = "$work/boot.img"
        val outName = "magisk_patched.img"
        val outPath = "/sdcard/$outName"

        onLog(root("cp '$bootOnDevice' '$local'", "ls -l '$local'"))
        onLog("--- 开始修补 ---\n")
        val log = root(
            "cd '$work'",
            "chmod 755 '$work'/* 2>/dev/null",
            "KEEPVERITY=true KEEPFORCEENCRYPT=true ./busybox sh ./boot_patch.sh '$local' 2>&1",
            "echo ===EXIT:\$?===",
            "ls -l '$work'/new-boot.img 2>&1"
        )
        onLog(log)
        if (!log.contains("new-boot.img")) return null
        onLog("--- 复制产物 ---\n")
        onLog(root("cp '$work/new-boot.img' '$outPath'", "chmod 644 '$outPath'", "ls -l '$outPath'"))
        return if (root("ls '$outPath'").contains(outName)) outPath else null
    }

    // -----------------------------------------------------------------------
    // 不需要 root 的那条路：修补【用户自己选的文件】
    //   Magisk 官方就有"选择并修补一个文件"。只有提取当前 boot 分区才需要 root，
    //   修补本身不需要 —— magiskboot 只是拆包/改 ramdisk/重新打包，全是文件操作。
    // -----------------------------------------------------------------------
    fun patchFile(ctx: Context, srcFile: File, onLog: (String) -> Unit): String? {
        val dir = ensureAssets(ctx)
        val work = dir.absolutePath
        val local = "$work/boot.img"
        runCatching { srcFile.copyTo(File(local), overwrite = true) }
            .onFailure { onLog("复制文件失败：${it.message}\n"); return null }
        onLog("已准备：${srcFile.name}（${srcFile.length()} 字节）\n--- 开始修补 ---\n")
        // 不需要 root，普通 shell 就够
        val log = plainShell(
            "cd '$work'",
            "chmod 755 '$work'/* 2>/dev/null",
            "KEEPVERITY=true KEEPFORCEENCRYPT=true ./busybox sh ./boot_patch.sh '$local' 2>&1",
            "echo ===EXIT:\$?===",
            "ls -l '$work'/new-boot.img 2>&1"
        )
        onLog(log)
        if (!log.contains("new-boot.img")) return null
        val outDir = File("/sdcard")
        val outPath = "$work/magisk_patched.img"
        onLog(plainShell("cp '$work/new-boot.img' '$outPath'", "ls -l '$outPath'"))
        onLog("产物：$outPath\n（在 /sdcard/Android/data/${ctx.packageName}/ 之外也能用文件管理器取走）\n")
        @Suppress("UNUSED_VARIABLE") val unused = outDir
        return outPath
    }

    /** 跑一条【普通用户】命令（不需要 root）。同样不再走 libsu。 */
    fun plainShell(vararg cmds: String): String = runCatching {
        val p = ProcessBuilder(listOf("sh", "-c", cmds.joinToString("\n")))
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrElse { "shell error: ${it.message}\n" }

    /** 安装一个 Magisk 模块 zip（需要 root）。 */
    fun installModule(zipOnDevice: String): String =
        root("magisk --install-module '$zipOnDevice' 2>&1")

    /** 卸载模块（删目录 + 标记移除）。 */
    fun removeModule(id: String): String =
        root("touch /data/adb/modules/$id/remove", "ls /data/adb/modules/$id/remove")

    /**
     * 某个 uid 现在到底是不是被授权的。写完策略表后用它复核一次 ——
     * 只看命令返回码是不够的，magisk --sqlite 出错时也可能返回 0。
     */
    fun policyHasUid(uid: Int, allow: Boolean): Boolean = runCatching {
        val out = root("magisk --sqlite \"SELECT uid FROM policies WHERE uid=$uid\"")
        val has = out.contains("uid=$uid")
        val ok = if (allow) has else !has
        Log.i(TAG, "policyHasUid uid=$uid allow=$allow raw=[${out.trim().take(200)}] -> $ok")
        ok
    }.getOrDefault(false)

    /** 授予 / 撤销某个 uid 的 root。allow=true -> policy=2，false -> 删除该行。 */
    fun setUidPolicy(uid: Int, allow: Boolean): String {
        val cmd = if (allow) {
            "magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ($uid,2,0,1,1)\""
        } else {
            "magisk --sqlite \"DELETE FROM policies WHERE uid=$uid\""
        }
        val out = root(cmd)
        // paperSU: 这里以前没有任何日志 —— 写失败时外面完全看不出来，
        // 只表现为开关弹回去 / 目标应用照样触发 Magisk 的询问框。
        Log.i(TAG, "setUidPolicy uid=$uid allow=$allow -> [${out.trim().take(300)}]")
        return out
    }

    // -----------------------------------------------------------------------
    // 主动请求 root（只在 Magisk 模式用）
    //   跑一条 su 命令，Magisk 会弹出授权对话框。用户点了允许，之后 hasRoot() 就为 true。
    //   必须在 IO 线程上调用，也不能在 setContent 之前调用（会卡启动画面）。
    //   返回 (是否成功, 输出)。
    // -----------------------------------------------------------------------
    fun requestRoot(): Pair<Boolean, String> {
        // 第一步：用最朴素的方式敲一次 su。
        //   为什么必须这样：libsu 在探测不到可用 su 时，会走它自己的"Magisk 未安装"
        //   引导（弹"需要下载完整版 Magisk"），而不是向已有的 Magisk 申请授权。
        //   直接 spawn 一个 su 进程才是让 Magisk 弹授权框的那条路。
        val raw = runCatching {
            val suBin = findSu() ?: "su"
            val p = ProcessBuilder(suBin, "-c", "id").redirectErrorStream(true).start()
            val s = p.inputStream.bufferedReader().readText()
            p.waitFor()
            s.trim()
        }.getOrElse { "spawn su failed: ${it.message}" }
        Log.i(TAG, "requestRoot raw su -> $raw")

        // 第二步：再用 libsu 拿一份规范输出（用户点了允许之后这次就能成功）。
        val out = root("id")
        val ok = out.contains("uid=0") || raw.contains("uid=0")
        Log.i(TAG, "requestRoot -> ok=$ok")
        return ok to (if (out.contains("uid=0")) out.trim() else raw)
    }

    // -----------------------------------------------------------------------
    // 授权 = 直接写 Magisk 的策略表，不让目标应用弹框
    //
    // 这是 Magisk 官方管理器的做法：在它的超级用户列表里勾一个应用，它就直接往
    // magisk.db 的 policies 表写一行，那个应用下次执行 su 时【不会】弹请求框。
    // paperSU 作为 root 管理器当然也该这么做 —— 弹框是"没有管理器"时的兜底。
    //
    // 前提：paperSU 自己拿到过一次 root。之后：
    //   · 给别的应用授权 → 直接写表，不弹框
    //   · 撤销授权       → 直接删行，不弹框
    // -----------------------------------------------------------------------

    /** 给某个 uid 授权（直写策略表，不触发任何弹窗）。 */
    fun grantUid(uid: Int): Boolean {
        val out = root(
            "magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ($uid,2,0,1,1)\""
        )
        val ok = !out.contains("error", ignoreCase = true)
        Log.i(TAG, "grantUid($uid) -> $ok : ${out.trim()}")
        return ok
    }

    /** 撤销某个 uid 的授权。 */
    fun revokeUid(uid: Int): Boolean {
        val out = root("magisk --sqlite \"DELETE FROM policies WHERE uid=$uid\"")
        Log.i(TAG, "revokeUid($uid) -> ${out.trim()}")
        return true
    }

    /**
     * paperSU 自己拿 root。
     *   1. 已有 root → 直接把自己的 uid 写成永久，返回。
     *   2. 还没有 → 走一次 su（唯一一次弹框，而且是给 paperSU 自己），成功后固化成永久。
     */
    fun ensureSelfRoot(uid: Int): Pair<Boolean, String> {
        if (hasRoot()) {
            grantUid(uid)
            return true to "已有 root，已固化为永久"
        }
        val raw = runCatching {
            val suBin = findSu() ?: "su"
            val p = ProcessBuilder(suBin, "-c", "id").redirectErrorStream(true).start()
            val s = p.inputStream.bufferedReader().readText()
            p.waitFor()
            s.trim()
        }.getOrElse { "spawn su failed: ${it.message}" }
        if (hasRoot()) {
            grantUid(uid)
            return true to "已获得 root 并固化为永久"
        }
        return false to raw
    }
    }
