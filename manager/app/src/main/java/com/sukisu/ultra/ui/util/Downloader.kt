package com.sukisu.ultra.ui.util

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import com.sukisu.ultra.ksuApp
import com.sukisu.ultra.ui.util.module.LatestVersionInfo
import okhttp3.Request

/**
 * @author weishu
 * @date 2023/6/22.
 */
suspend fun download(
    url: String,
    fileName: String,
    onDownloaded: (Uri) -> Unit = {},
    onDownloading: () -> Unit = {},
    onProgress: (Int) -> Unit = {}
) {
    onDownloading()

    val downloadId = DownloadManager.enqueue(
        context = ksuApp,
        url = url,
        fileName = fileName,
        onCompleted = onDownloaded,
    )

    DownloadManager.downloads
        .onEach { map -> map[downloadId]?.let { onProgress(it.progress) } }
        .first { map ->
            val status = map[downloadId]?.status
            status == DownloadManager.Status.COMPLETED ||
                status == DownloadManager.Status.FAILED
        }
}

internal suspend fun isDownloadAvailable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        ksuApp.contentResolver.openFileDescriptor(uri, "r").use { it != null }
    }.getOrDefault(false)
}

fun checkNewVersion(): LatestVersionInfo {
    if (!isNetworkAvailable(ksuApp)) return LatestVersionInfo()
    val url = "https://api.github.com/repos/anos-develop/PaperSU-Main/releases/latest"
    // default null value if failed
    val defaultValue = LatestVersionInfo()
    runCatching {
        ksuApp.okhttpClient.newCall(Request.Builder().url(url).build()).execute()
            .use { response ->
                if (!response.isSuccessful) {
                    return defaultValue
                }
                val body = response.body.string()
                val json = org.json.JSONObject(body)
                val changelog = json.optString("body")

                val assets = json.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (!name.endsWith(".apk")) {
                        continue
                    }

                    val regex = Regex("v(.+?)_(\\d+)-")
                    val matchResult = regex.find(name) ?: continue
                    matchResult.groupValues[1]
                    val versionCode = matchResult.groupValues[2].toLong()
                    val downloadUrl = asset.getString("browser_download_url")

                    return LatestVersionInfo(
                        versionCode,
                        downloadUrl,
                        changelog
                    )
                }
                // paperSU: 如果 Release 里没有符合 v<ver>_<code>- 命名的 APK，就从三个地方
                // 收集数字候选（tag、release 标题、所有资产名），取最大的那个当版本号。
                // 这样发布者随便怎么命名（例如 PaperSU-40972.40970.apk）都能被认到。
                val candidates = mutableListOf<Long>()
                json.optString("tag_name").trim().removePrefix("v").toLongOrNull()?.let { candidates += it }
                Regex("(\\d{4,})").findAll(json.optString("name")).forEach { candidates += it.value.toLong() }
                for (i in 0 until assets.length()) {
                    val an = assets.getJSONObject(i).getString("name")
                    Regex("(\\d{4,})").findAll(an).forEach { candidates += it.value.toLong() }
                }
                val best = candidates.maxOrNull()
                if (best != null && best > 0L) {
                    val firstApk = (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.getString("name").endsWith(".apk") }
                    return LatestVersionInfo(
                        best,
                        firstApk?.getString("browser_download_url") ?: json.optString("html_url"),
                        changelog
                    )
                }

            }
    }
    return defaultValue
}
