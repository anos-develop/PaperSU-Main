// ---------------------------------------------------------------------------
// paperSU: 运行模式判定
//   同一个应用里内置两套界面，启动时按内核决定用哪一套：
//     内核 5.x / 6.x  →  EngineMode.KernelSU   调 ksud，走现有的 GKI / LKM 那套
//     内核 4.x        →  EngineMode.Magisk     走 Magisk 的 boot 修补那套
//   判定只读 /proc/version 对应的系统属性，不需要 root。
//   用户可以在设置里手动覆盖（比如自己刷了非 GKI 的 KernelSU 内核）。
// ---------------------------------------------------------------------------
package com.sukisu.ultra.ui.component.engine

import android.content.Context
import com.sukisu.ultra.ksuApp

object EngineMode {

    enum class Mode(val label: String) {
        KernelSU("KernelSU"),
        Magisk("Magisk"),
    }

    private const val PREFS = "engine"
    private const val KEY_OVERRIDE = "mode_override"

    /** 内核版本串，例如 "6.1.141-android14-11-o-g984c12362a16"。 */
    fun kernelRelease(): String = runCatching {
        System.getProperty("os.version") ?: ""
    }.getOrDefault("")

    /** 内核主版本号；读不到时返回 -1。 */
    fun kernelMajor(): Int {
        val rel = kernelRelease()
        val head = rel.substringBefore('-').substringBefore('.')
        return head.toIntOrNull() ?: -1
    }

    /** 自动判定：>= 5 走 KernelSU，4.x 走 Magisk。读不到就保守按 KernelSU。 */
    fun detect(): Mode {
        val major = kernelMajor()
        return when {
            major in 1..4 -> Mode.Magisk
            else -> Mode.KernelSU
        }
    }

    /** 用户手动指定的模式；没指定过返回 null。 */
    fun override(ctx: Context = ksuApp): Mode? {
        val v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDE, null) ?: return null
        return Mode.entries.firstOrNull { it.name == v }
    }

    fun setOverride(mode: Mode?, ctx: Context = ksuApp) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (mode == null) remove(KEY_OVERRIDE) else putString(KEY_OVERRIDE, mode.name)
        }.apply()
    }

    /** 最终生效的模式：手动覆盖优先，否则自动判定。 */
    fun current(ctx: Context = ksuApp): Mode = override(ctx) ?: detect()

    /** 给界面用的一句话说明。 */
    fun explain(ctx: Context = ksuApp): String {
        val rel = kernelRelease().ifBlank { "未知" }
        val major = kernelMajor()
        val auto = detect()
        val ov = override(ctx)
        val eff = ov ?: auto
        val why = when {
            ov != null -> "手动指定为 ${ov.label}"
            major in 1..4 -> "内核主版本 $major < 5，走 Magisk 的 boot 修补"
            major >= 5 -> "内核主版本 $major >= 5，调 ksud"
            else -> "读不到内核版本，保守按 KernelSU"
        }
        return "内核：$rel\n自动判定：${auto.label}（$why）\n当前生效：${eff.label}"
    }
}