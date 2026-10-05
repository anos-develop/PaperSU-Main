// ---------------------------------------------------------------------------
// paperSU: 寄生授权白名单
//
// 存储位置是 /data/adb/ksu/parasite_whitelist —— ksud 的地盘，不是应用自己的
// shared_prefs。这一点很关键：寄生的整个意义就是"管理器可以被删掉"，而白名单如果
// 存在管理器的私有目录里，管理器一删名单就没了，网页端也就再也改不了授权，
// 形成一个自己锁死自己的死局。放在 ksud 目录下，管理器删了名单还在。
//
// 文件格式：一行一个包名，UTF-8，行首尾空白忽略，# 开头是注释。
// 读写都要 root（/data/adb 非 root 不可读），所以统一走 su -c。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.parasite

import android.content.Context
import com.sukisu.ultra.ksuApp
import java.io.File

object ParasiteWhitelist {

    /** ksud 的地盘；管理器删了它也在。 */
    const val FILE = "/data/adb/ksu/parasite_whitelist"

    /** 老的存放位置：应用私有 prefs。只用于一次性迁移。 */
    private const val LEGACY_PREFS = "parasite"
    private const val LEGACY_KEY = "allowed_packages"

    private fun su(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrDefault("")

    /** 解析文件内容：一行一个包名。 */
    fun parse(text: String): Set<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    /** 当前白名单。读不到（没 root / 文件不存在）返回空集。 */
    fun get(@Suppress("UNUSED_PARAMETER") ctx: Context = ksuApp): Set<String> =
        parse(su("cat $FILE 2>/dev/null"))

    fun isAllowed(pkg: String?, ctx: Context = ksuApp): Boolean {
        if (pkg.isNullOrBlank()) return false
        return pkg in get(ctx)
    }

    /** 把整个名单写回去（比 sed 删行安全，不用操心转义）。 */
    private fun write(pkgs: Set<String>) {
        val body = pkgs.sorted().joinToString("\n") { it }
        // 用单引号 heredoc，避免 shell 解释包名里的字符
        su("mkdir -p /data/adb/ksu && cat > $FILE << 'PAPERSU_EOF'\n$body\nPAPERSU_EOF\nchmod 600 $FILE")
    }

    fun allow(pkg: String, ctx: Context = ksuApp) {
        if (pkg.isBlank()) return
        write(get(ctx) + pkg.trim())
    }

    fun revoke(pkg: String, ctx: Context = ksuApp) {
        write(get(ctx) - pkg.trim())
    }

    fun clear(ctx: Context = ksuApp) {
        write(emptySet())
    }

    /**
     * 一次性迁移：把老版本存在 prefs 里的名单搬到 ksud 目录，然后清掉 prefs 里的。
     * 迁移只在 ksud 文件还不存在且 prefs 里有东西时发生，不会覆盖新名单。
     */
    fun migrateIfNeeded(ctx: Context = ksuApp) {
        runCatching {
            val legacy = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getStringSet(LEGACY_KEY, null)?.toSet().orEmpty()
            if (legacy.isEmpty()) return
            val hasNew = su("test -f $FILE && echo yes").trim() == "yes"
            if (hasNew) return
            write(legacy)
            ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit()
                .remove(LEGACY_KEY).apply()
        }
    }

    /** 给界面用：文件是否可读（也就是有没有 root）。 */
    fun available(): Boolean = su("test -d /data/adb/ksu && echo ok").contains("ok")

    @Suppress("unused")
    private fun legacyPrefsFile(ctx: Context): File = File(ctx.filesDir.parentFile, "shared_prefs")
}