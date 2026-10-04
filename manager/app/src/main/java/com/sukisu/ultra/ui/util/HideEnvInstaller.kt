// ---------------------------------------------------------------------------
// paperSU: 一键隐藏环境
//   点击后由用户确认 → 用 sh 脚本从云端下载隐藏模块 → 交给 ksud 刷入。
//   隐藏模块自身会再分出子模块，那部分不在本文件职责内。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.util

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HideEnvInstaller {

    private const val TAG = "paperSU-hide-env"

    /**
     * ⚠️ 占位地址 —— 把这里换成隐藏模块的真实直链（一个 zip 包）。
     *
     * 换法：只改这一行的字符串即可，下面的脚本与刷入流程都不用动。
     * 例：const val MODULE_URL = "https://github.com/你的账号/你的仓库/releases/download/v1/hide-module.zip"
     */
    const val MODULE_URL: String = "https://raw.githubusercontent.com/anos-develop/PaperSU-Main/main/Magisk-Modle/hide-module.zip"

    /** 占位地址检测：还没换成真地址时，直接告诉用户，别去网上瞎请求。 */
    fun isPlaceholder(): Boolean = MODULE_URL.contains("example.com")

    data class Result(val ok: Boolean, val log: String)

    /**
     * 下载并刷入隐藏模块。整个过程在 root shell 里跑，日志逐行回调出来给界面显示。
     */
    suspend fun install(onLog: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun emit(line: String) {
            sb.appendLine(line)
            Log.i(TAG, line)
            onLog(line)
        }

        if (isPlaceholder()) {
            emit("[-] 隐藏模块地址还是占位值，请先在 HideEnvInstaller.MODULE_URL 里填入真实直链")
            return@withContext Result(false, sb.toString())
        }

        emit("[*] 隐藏模块地址：$MODULE_URL")
        emit("[*] 开始下载并刷入…")

        // 只用 /system/bin/sh + 常见下载工具，避免依赖 App 侧的网络栈
        val script = """
            set -e
            URL='$MODULE_URL'
            D=/data/local/tmp/papersu_hide
            rm -rf "${'$'}D"
            mkdir -p "${'$'}D"
            cd "${'$'}D"
            if command -v curl >/dev/null 2>&1; then
                curl -fL --retry 2 -o hide-module.zip "${'$'}URL"
            elif command -v wget >/dev/null 2>&1; then
                wget -O hide-module.zip "${'$'}URL"
            elif command -v busybox >/dev/null 2>&1; then
                busybox wget -O hide-module.zip "${'$'}URL"
            else
                echo "NODL"
                exit 2
            fi
            [ -s hide-module.zip ] || { echo "EMPTY"; exit 3; }
            echo "SIZE=${'$'}(stat -c%s hide-module.zip 2>/dev/null || echo '?')"
            ksud module install "${'$'}D/hide-module.zip"
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
        val ok = text.contains("INSTALLED") && !text.contains("NODL") && !text.contains("EMPTY")
        if (ok) emit("[+] 模块已刷入，请重启手机使隐藏环境生效")
        else emit("[-] 未成功，请检查上面的日志（网络 / 地址 / ksud 是否可用）")
        Result(ok, sb.toString())
    }
}