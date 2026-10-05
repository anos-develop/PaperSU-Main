// ---------------------------------------------------------------------------
// paperSU: Magisk 模式的 boot 修补
//
// 内核 4.x 的机器走这一套：把 Magisk 的修补链（magiskboot / magiskinit / magisk /
// init-ld / stub.apk + boot_patch.sh）从 APK 的 assets 解出来，然后照 Magisk 官方
// 的方式修补一份 boot 镜像，产出 magisk_patched.img。
//
// 修补能力来自 Magisk（topjohnwu，GPL-3.0-or-later），LICENSE 随资产一起放在
// assets/magisk/LICENSE-Magisk。本文件只做"解包 + 调用 + 收结果"，不改 Magisk 逻辑。
//
// 注意：assets 解出来的文件【没有执行位】，必须显式 chmod 755 —— 这是个老坑。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.magisk

import android.content.Context
import java.io.File

object MagiskPatcher {

    /** 随 APK 打包的 Magisk 资产目录名。 */
    private const val ASSET_DIR = "magisk"

    /** 需要可执行位的文件。 */
    private val EXECUTABLES = setOf(
        "busybox", "magiskboot", "magiskinit", "magisk", "magiskpolicy", "init-ld"
    )

    /** 工作目录：/data/data/<pkg>/files/magisk。 */
    fun workDir(ctx: Context): File = File(ctx.filesDir, ASSET_DIR)

    /** 把资产解到工作目录并补上执行位；已解过则直接复用。 */
    fun ensureAssets(ctx: Context): File {
        val dir = workDir(ctx)
        if (!dir.exists()) dir.mkdirs()
        val am = ctx.assets
        val names = am.list(ASSET_DIR).orEmpty()
        for (name in names) {
            val out = File(dir, name)
            // 可执行的每次都重写（保证权限）；其余只补缺失的
            if (name in EXECUTABLES || !out.exists()) {
                am.open("$ASSET_DIR/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }
            if (name in EXECUTABLES) out.setExecutable(true, false)
        }
        // 脚本也要可读可执行
        for (s in listOf("boot_patch.sh", "util_functions.sh")) {
            File(dir, s).takeIf { it.exists() }?.setExecutable(true, false)
        }
        return dir
    }

    private fun su(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrDefault("")

    /**
     * 把当前系统的 boot 分区 dump 到 /sdcard/papersu_stock_boot.img。
     * 小米这台 boot 在 /dev/block/by-name/boot，需要 root 才读得到。
     * 返回可读的路径（失败返回 null）。
     */
    fun dumpCurrentBoot(): String? {
        val out = "/sdcard/papersu_stock_boot.img"
        val log = su("dd if=/dev/block/by-name/boot of=$out bs=4096 2>&1; ls -l $out 2>&1")
        return if (log.contains("No such") || log.contains("Permission denied") || log.contains("denied")) null else out
    }

    /**
     * 修补一份 boot 镜像。返回产出文件在【设备上】的路径，失败返回 null。
     * bootOnDevice 必须是设备上的真实路径（比如 /sdcard/papersu_stock_boot.img）。
     * onLog 会收到每一步的输出，方便界面显示。
     */
    fun patch(ctx: Context, bootOnDevice: String, onLog: (String) -> Unit): String? {
        val dir = ensureAssets(ctx)
        val work = dir.absolutePath
        val outName = "magisk_patched.img"
        val outPath = "/sdcard/$outName"

        // 把 boot 镜像复制进工作目录（boot_patch.sh 要在同目录里干活）
        val local = "$work/boot.img"
        onLog(su("cp '$bootOnDevice' '$local' && ls -l '$local'"))

        // 照着 Magisk 的方式跑：cd 到工作目录，busybox sh boot_patch.sh boot.img
        val cmd = buildString {
            append("cd '$work' && ")
            append("export PATH='$work':\$PATH && ")
            append("chmod 755 '$work'/* 2>/dev/null; ")
            append("KEEPVERITY=true KEEPFORCEENCRYPT=true ./busybox sh ./boot_patch.sh '$local' 2>&1; ")
            append("echo \"===EXIT:\$?===\"; ")
            append("ls -l '$work'/new-boot.img 2>&1")
        }
        val log = su(cmd)
        onLog(log)
        if (!log.contains("new-boot.img")) return null

        // 搬到 /sdcard 方便用户取用
        val moved = su("cp '$work/new-boot.img' '$outPath' && chmod 644 '$outPath' && ls -l '$outPath'")
        onLog(moved)
        return if (moved.contains(outName)) outPath else null
    }

    /** 从输出里抠出 Magisk 的版本信息，界面用。 */
    fun version(ctx: Context): String = su("'${workDir(ctx).absolutePath}/magisk' -v 2>&1").trim()
}