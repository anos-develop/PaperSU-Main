// ---------------------------------------------------------------------------
// paperSU: 寄生授权白名单
//   只有写在这个名单里的包名，才能通过 ParasiteAuthProvider 拿到网页控制台的链接。
//   名单由用户在设置页自己维护（从已安装应用里挑），默认是空的 —— 谁都不许用。
//   这不是"寄生到别人的应用里"：被授权的应用是主动来问链接的，管理器只负责
//   核对包名并给答复，不会去干预任何应用的启动。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.parasite

import android.content.Context
import com.sukisu.ultra.ksuApp

object ParasiteWhitelist {
    private const val PREFS = "parasite"
    private const val KEY = "allowed_packages"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前白名单（包名集合）。默认空集。 */
    fun get(ctx: Context = ksuApp): Set<String> =
        prefs(ctx).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    /** 某个包名是否被授权。 */
    fun isAllowed(pkg: String?, ctx: Context = ksuApp): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in get(ctx)
    }

    fun allow(pkg: String, ctx: Context = ksuApp) {
        if (pkg.isBlank()) return
        val next = get(ctx).toMutableSet().also { it += pkg }
        prefs(ctx).edit().putStringSet(KEY, next).apply()
    }

    fun revoke(pkg: String, ctx: Context = ksuApp) {
        val next = get(ctx).toMutableSet().also { it -= pkg }
        prefs(ctx).edit().putStringSet(KEY, next).apply()
    }

    fun clear(ctx: Context = ksuApp) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}