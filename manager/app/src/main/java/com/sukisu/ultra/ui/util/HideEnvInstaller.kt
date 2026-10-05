// ---------------------------------------------------------------------------
// paperSU: 一键隐藏环境
//   点击 → 用户确认 → 把 APK 内置的隐藏模块交给"管理器自己的模块安装界面"。
//
//   为什么不是直接跑 ksud module install：
//   管理器本来就有一套从 zip 安装模块的流程（IntentDispatcher 里的 HandleZipFileIntent），
//   它会解析 zip、弹出"这是模块/内核"的确认与后续选择项，需要用户参与。
//   所以这里只做两件事：把内置模块解到缓存，然后用 ACTION_VIEW 的 Intent 把它交给那个界面。
//   模块打进 APK，所以整个过程不需要网络。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.sukisu.ultra.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object HideEnvInstaller {

    private const val TAG = "paperSU-hide-env"

    /** APK 内置的隐藏模块。 */
    private const val ASSET_NAME = "hide-module.zip"

    data class Result(val ok: Boolean, val log: String)

    /** 把内置模块解到缓存目录；失败返回 null。 */
    private fun extractAsset(context: Context): File? = runCatching {
        val out = File(context.cacheDir, ASSET_NAME)
        context.assets.open(ASSET_NAME).use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        out.takeIf { it.length() > 0 }
    }.getOrNull()

    /**
     * 解出内置模块并交给管理器的模块安装界面（会弹出确认与后续选择，由用户操作）。
     */
    suspend fun launch(context: Context, onLog: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun emit(line: String) {
            sb.appendLine(line)
            Log.i(TAG, line)
            onLog(line)
        }

        val zip = extractAsset(context)
        if (zip == null) {
            emit("[-] 内置隐藏模块解包失败（APK 里没有 assets/$ASSET_NAME）")
            return@withContext Result(false, sb.toString())
        }
        emit("[*] 内置模块已就绪：${zip.absolutePath}（${zip.length()} 字节）")

        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", zip)
        }.getOrElse { e ->
            emit("[-] 生成 content:// 失败：${e.javaClass.simpleName} ${e.message}")
            return@withContext Result(false, sb.toString())
        }
        emit("[*] 交给管理器的安装界面：$uri")

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/zip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(context.packageName)
        }

        val ok = runCatching { context.startActivity(intent) }
            .onFailure { e -> emit("[-] 无法打开安装界面：${e.javaClass.simpleName} ${e.message}") }
            .isSuccess
        if (ok) emit("[+] 已打开安装界面，请按提示确认安装")
        Result(ok, sb.toString())
    }
}