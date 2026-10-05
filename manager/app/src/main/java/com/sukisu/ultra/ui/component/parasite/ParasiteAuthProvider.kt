// ---------------------------------------------------------------------------
// paperSU: 寄生授权接口
//   白名单里的应用可以调用它拿到网页控制台的链接；不在名单里的直接拒绝。
//   调用方身份用 callingPackage 判断，调用方无法伪造。
//   支持的 method：
//     "isAllowed"       -> boolean，我这个包在不在名单里
//     "getWebAdminUrl"  -> string，带密钥的完整链接（仅白名单可拿）
//     "getStatus"       -> string，网页端 enabled/port/token 的概述
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.parasite

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.sukisu.ultra.ui.util.WebAdminCli

class ParasiteAuthProvider : ContentProvider() {

    companion object {
        private const val TAG = "paperSU-parasite"
        const val AUTHORITY_SUFFIX = ".parasite.auth"
        const val METHOD_IS_ALLOWED = "isAllowed"
        const val METHOD_GET_URL = "getWebAdminUrl"
        const val METHOD_GET_STATUS = "getStatus"
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val caller = callingPackage
        val allowed = ParasiteWhitelist.isAllowed(caller, requireNotNull(context))
        Log.i(TAG, "call=$method from=$caller allowed=$allowed")
        val out = Bundle()
        if (!allowed) {
            out.putString(KEY_ERROR, "not allowed: ${caller ?: "unknown"}")
            return out
        }
        when (method) {
            METHOD_IS_ALLOWED -> out.putBoolean(KEY_RESULT, true)
            METHOD_GET_URL -> {
                val url = runCatching { WebAdminCli.url() }.getOrDefault("")
                if (url.isBlank()) out.putString(KEY_ERROR, "webadmin url unavailable") else out.putString(KEY_RESULT, url)
            }
            METHOD_GET_STATUS -> {
                val s = runCatching { WebAdminCli.status() }.getOrDefault("")
                out.putString(KEY_RESULT, s)
            }
            else -> out.putString(KEY_ERROR, "unknown method: $method")
        }
        return out
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}