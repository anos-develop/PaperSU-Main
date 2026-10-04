package com.sukisu.ultra.ui.util

import android.util.Log

/**
 * The app's only channel to the **web manager**.
 *
 * ⚠️ The web service does **not** live in the app: it runs inside **ksud (the root daemon)**.
 * That is deliberate — if it ran in the app, swiping the manager out of recents or the
 * system's "clean up" would kill the process and close the port, and the browser would just
 * say "connection refused". Hosting it in ksud means:
 *   · swiping the app away (or a one-tap clean-up) does not stop it;
 *   · after a reboot ksud's post-fs-data hook brings it back on its own;
 *   · no foreground service and no persistent notification are needed.
 *
 * So the app side only does three things: **toggle**, **fetch the URL (which carries the
 * access key)**, and **hand that URL to a browser**.
 *
 * 🔐 Authentication: the server requires a private key, and the URL looks like
 * `http://127.0.0.1:18427/papersu/<64 hex chars>`. ksud generates the key and stores it in
 * `/data/adb/ksu/webadmin.conf` (0600). **The app only passes it through memory** — it is
 * never logged, never written to preferences, and never passed as a command-line argument
 * (because `ps` is world-readable).
 *
 * Matching command line (usable from a root shell on the device):
 * ```
 * ksud webadmin on           # turn on and start the daemon (also prints the keyed URL)
 * ksud webadmin off          # turn off (the key is kept; next time it is the same link)
 * ksud webadmin url          # print the URL including the access key
 * ksud webadmin status       # enabled / port / whether a key is set (**no key itself**)
 * ksud webadmin reset-token  # new key; old links stop working immediately
 * ```
 *
 * Security properties this file deliberately preserves (ported from 7kimisu, GPL-3.0-or-later):
 *   · [redact] keeps the key out of logcat — logcat is readable by other apps on some
 *     builds, and the key is enough to self-grant root on this device;
 *   · the key never travels as an argv;
 *   · [diagnose] masks the `token=` line, because that report is meant to be copied and
 *     sent to someone else.
 */
object WebAdminCli {

    private const val TAG = "paperSU-webadmin"

    /**
     * 🔐 Mask any **access key** that may appear in the output.
     *
     * `ksud webadmin on` / `url` print the full keyed URL. Logging that verbatim would
     * contradict the "the key is never written to a log" promise above, and whoever reads
     * the log could then self-grant root. Everything matching `/{prefix}/{long hex}` becomes
     * `<hidden>`, so the log keeps the shape but not the secret.
     */
    private fun redact(s: String): String =
        s.replace(Regex("(/papersu/)[0-9a-fA-F]{16,}"), "$1<hidden>")

    /** Run one `ksud webadmin` subcommand and return stdout (empty string on failure). */
    private fun run(args: String): String = runCatching {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = getRootShell().newJob()
            .add("${getKsuDaemonPath()} webadmin $args")
            .to(out, err)
            .exec()
        if (result.code != 0) {
            Log.w(TAG, "ksud webadmin $args exited ${result.code}: ${redact(err.joinToString("\n"))}")
        }
        out.joinToString("\n").trim()
    }.getOrElse {
        Log.w(TAG, "ksud webadmin $args failed", it)
        ""
    }

    /** Toggle it. Turning it on also starts the resident ksud process. */
    fun setEnabled(enable: Boolean): Boolean {
        val out = run(if (enable) "on" else "off")
        // ⚠️ `on` prints the key on stdout, so it must be redacted (see redact above).
        Log.i(TAG, "webadmin ${if (enable) "on" else "off"} → ${redact(out)}")
        return out.isNotEmpty()
    }

    /** The URL (**including the 64-char access key**); empty when ksud is missing or down. */
    fun url(): String {
        val out = run("url")
        return if (out.startsWith("http://")) out else ""
    }

    /**
     * Issue a new access key and return the **new full URL** (empty on failure).
     *
     * ⚠️ The key only ever appears in this return value (via the root shell's stdout). It is
     * not passed as an argument (invisible to `ps`) and is not logged.
     */
    fun resetToken(): String {
        val out = run("reset-token")
        return out.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("http://") } ?: ""
    }

    /** Raw status text (for diagnostics; the settings page shows it as-is). */
    fun status(): String = run("status")

    /**
     * Push the app's toggle state into ksud (run whenever the settings page opens).
     *
     * Two things make this necessary:
     *  1. the toggle lives in app preferences while the service reads
     *     `/data/adb/ksu/webadmin.conf` — after an app update the switch is already "on" and
     *     the user will not flip it again, so without this the service would never start;
     *  2. after an APK upgrade `/data/adb/ksu/bin/ksud` is new, but an already-running daemon
     *     will not replace itself.
     *
     * ⚠️ This uses **sync**, not restart: `onCreate` can run often, and a hard restart would
     * drop the service for about a second every time even though ksud usually has not
     * changed. `sync` lets ksud compare the running binary against the on-disk one and only
     * restart when they actually differ. (The settings page's "restart" action still does a
     * real restart — that one exists to rescue a stuck service.)
     */
    fun syncPref(enabled: Boolean): Boolean = if (enabled) sync() else setEnabled(false)

    /** Ensure it is running, and running the current ksud; a no-op when already current. */
    fun sync(): Boolean {
        val out = run("sync")
        Log.i(TAG, "webadmin sync → ${redact(out)}")
        return out.isNotEmpty()
    }

    /** Restart the daemon (stop then start), used to pick up a new ksud after an upgrade. */
    fun restart(): Boolean {
        val out = run("restart")
        Log.i(TAG, "webadmin restart → ${redact(out)}")
        return out.isNotEmpty()
    }

    /** On-device diagnostics: one command that gathers everything worth knowing. */
    fun diagnose(): String = runRaw(
        buildString {
            append("echo '=== installed ksud ==='; ")
            append("ls -l /data/adb/ksu/bin/ksud /data/adb/ksud 2>&1; ")
            append("echo '=== ksud webadmin status ==='; ")
            append("/data/adb/ksu/bin/ksud webadmin status 2>&1; ")
            append("echo '=== webadmin.conf (key masked) ==='; ")
            // 🔐 This report is meant to be copied and sent to someone else, so the access
            //    key must never travel with it (the key means root on this device).
            append("sed 's/^\\(token=\\).*/\\1<hidden>/' /data/adb/ksu/webadmin.conf 2>&1; ")
            append("echo '=== webadmin.status ==='; ")
            append("cat /data/adb/ksu/webadmin.status 2>&1; ")
            append("echo '=== pid file ==='; ")
            append("cat /data/adb/ksu/webadmin.pid 2>&1; ")
            append("echo '=== daemon ppid (1 = detached from its session) ==='; ")
            append("ps -A -o PID,PPID,ARGS 2>/dev/null | grep 'ksud webadmin serve' | grep -v grep; ")
            append("echo '=== ksud processes ==='; ")
            append("ps -A -o PID,ARGS 2>/dev/null | grep -i ksud | head -8; ")
            append("echo '=== who is listening on 18427 ==='; ")
            append("(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep 18427; ")
            append("echo '=== log tail ==='; ")
            append("tail -n 25 /data/adb/ksu/webadmin.log 2>&1")
        }
    )

    /** Run a custom root-shell script and return its output. */
    private fun runRaw(script: String): String = runCatching {
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val r = getRootShell().newJob().add(script).to(out, err).exec()
        buildString {
            append(out.joinToString("\n"))
            if (err.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("[stderr] ").append(err.joinToString("\n"))
            }
            if (isEmpty()) append("(no output, exit code ${r.code})")
        }.trim()
    }.getOrElse { "failed: ${it.message}" }

    /** Open it in the device's browser (the app supplies the URL so nobody has to retype it). */
    fun openInBrowser(context: android.content.Context, url: String): String? {
        if (url.isBlank()) return "no URL yet (is ksud running?)"
        return runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
    }

    /** Copy the full keyed URL to the clipboard. */
    fun copyUrl(context: android.content.Context, url: String): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("paperSU web manager", url))
        true
    }.getOrDefault(false)
}
