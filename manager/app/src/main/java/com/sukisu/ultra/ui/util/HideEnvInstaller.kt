// ---------------------------------------------------------------------------
// paperSU: 一键隐藏环境
//   点击 → 用户确认 → 从 APK 内置资源解出隐藏模块 → 交给 ksud 刷入。
//   ⚠️ 不再联网下载：实测 raw.githubusercontent.com 与 jsDelivr 在目标网络下都会失败，
//      所以模块直接打进 APK（assets/hide-module.zip），离线也能装。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.util

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HideEnvInstaller {

    private const val TAG = "paperSU-hide-env"

    /** APK 内置的隐藏模块。 */
    private const val ASSET_NAME = "hide-module.zip"

    data class Result(val ok: Boolean, val log: String)

    /** 把内置模块解到应用缓存目录，返回该文件；失败返回 null。 */
    private fun extractAsset(context: Context): File? = runCatching {
        val out = File(context.cacheDir, ASSET_NAME)
        context.assets.open(ASSET_NAME).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * 解内置模块并刷入。整个过程在 root shell 里跑，日志逐行回调给界面。
     */
    suspend fun install(context: Context, onLog: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun emit(line: String) {
            sb.appendLine(line)
            Log.i(TAG, line)
            onLog(line)
        }

        val asset = extractAsset(context)
        if (asset == null) {
            emit("[-] 内置隐藏模块解包失败（APK 里没有 assets/$ASSET_NAME 或写入缓存失败）")
            return@withContext Result(false, sb.toString())
        }
        emit("[*] 内置模块已就绪：${asset.absolutePath}（${asset.length()} 字节）")
        emit("[*] 开始刷入…")

        // 1) 把模块拷到 /data/local/tmp（ksud 一定能读到）
        // 2) 用绝对路径找 ksud（root shell 的 PATH 里没有它）
        // 3) 只在真正返回 0 时才算成功
        val script = """
            set -e
            D=/data/local/tmp/papersu_hide
            rm -rf "${'$'}D"
            mkdir -p "${'$'}D"
            cp "${asset.absolutePath}" "${'$'}D/hide-module.zip"
            chmod 644 "${'$'}D/hide-module.zip"
            echo "SIZE=${'$'}(stat -c%s "${'$'}D/hide-module.zip" 2>/dev/null || echo '?')"
            KSUD=""
            for c in /data/adb/ksu/bin/ksud /data/adb/ksud /system/bin/ksud /system/xbin/ksud; do
                if [ -x "${'$'}c" ]; then KSUD="${'$'}c"; break; fi
            done
            if [ -z "${'$'}KSUD" ]; then KSUD=$(command -v ksud 2>/dev/null || true); fi
            if [ -z "${'$'}KSUD" ]; then
                echo "NOKSUD"
                exit 4
            fi
            echo "KSUD=${'$'}KSUD"
            "${'$'}KSUD" module install "${'$'}D/hide-module.zip"
            rc=${'$'}?
            echo "KSUD_RC=${'$'}rc"
            if [ "${'$'}rc" -ne 0 ]; then echo "INSTALL_FAILED"; exit 5; fi
            echo "INSTALLED"
        """.trimIndent()

        val result = runCatching {
            val shell = getRootShell()
            Shell.cmd(script).exec()
        }.getOrElse { e ->
            emit("[-] 执行失败：${e.javaClass.simpleName} ${e.message}")
            return@withContext Result(false, sb.toString())
        }

        result.out.forEach { emit(it) }
        result.err.forEach { emit("[stderr] $it") }

        val text = sb.toString()
        val ok = text.contains("INSTALLED") &&
                !text.contains("NOKSUD") &&
                !text.contains("INSTALL_FAILED")
        if (ok) emit("[+] 模块已刷入，请重启手机使隐藏环境生效")
        else emit("[-] 未成功，请检查上面的日志")
        Result(ok, sb.toString())
    }
}