package com.sukisu.ultra.ui.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.sukisu.ultra.R
import com.sukisu.ultra.ui.MainActivity

/**
 * Dialer secret-code entry point: `*#*#<code>#*#*` (default 70707, changeable in settings).
 *
 * Turns hidden mode off and brings the manager back up. It starts [MainActivity]
 * explicitly with CLEAR_TASK rather than relying on the launcher intent, so it works no
 * matter what state the launcher entry is in - and so the new activity builds fresh
 * ViewModels instead of reusing the ones that were constructed while masked.
 *
 * Adapted from 7kimisu's StealthReceiver. Two deliberate simplifications, both because
 * our hidden mode is app-side rather than kernel-side:
 *  * there is no `/data/adb/...` copy of the code to read, so no root shell, no
 *    `goAsync()` and no background thread - the comparison is pure preference access and
 *    runs inline on the receiver's (main) thread;
 *  * there is therefore no "uninstalled and reinstalled, now locked out" failure mode,
 *    because uninstalling clears the preference and the mask together. The original had
 *    to union the on-disk and in-preference codes to avoid exactly that.
 *
 * What is ported **verbatim in spirit** is the security hardening from 7kimisu v2.28/v2.29:
 * sender validation, fail-open only when the sender is unknowable, rate limiting, and
 * never writing the code itself to the log.
 */
class StealthReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SECRET_CODE) return

        // Security, check the sender BEFORE looking at the content.
        // `SECRET_CODE` is NOT a protected broadcast: any app can send
        // Intent("android.provider.Telephony.SECRET_CODE", "android_secret_code://70707").
        // Without this check anyone could turn hidden mode off with the public default
        // code.
        if (!senderTrusted(context)) {
            Log.w(TAG, "密令广播来自非可信发送方，已忽略")
            return
        }

        // The manifest only declares the scheme, so we receive every secret code.
        val dialed = intent.data?.host ?: return

        // Brute-force guard: the default code is public, so anyone can poke at the dialer.
        // Purely in-memory, cleared on process death - it exists to blunt scripted
        // guessing, not to be a real lockout.
        val now = System.currentTimeMillis()
        if (now < blockedUntil) {
            Log.w(TAG, "密令尝试被暂时挡下（连错过多）")
            return
        }

        val expected = Stealth.effectiveCode()
        // Never log the digits themselves: a mis-dial used to leave the real code lying in
        // logcat, where adb / bugreports can read it.
        Log.i(TAG, "收到密令（内容已隐去） 隐身=${Stealth.isEnabled()}")

        if (dialed != expected) {
            Log.w(TAG, "密令不匹配，已忽略")
            fails++
            if (fails >= MAX_FAILS) {
                blockedUntil = System.currentTimeMillis() + BLOCK_MILLIS
                fails = 0
                Log.w(TAG, "密令连错 $MAX_FAILS 次，暂停 ${BLOCK_MILLIS / 1000} 秒")
            }
            return
        }
        fails = 0

        val wasEnabled = Stealth.isEnabled()
        val restored = Stealth.setEnabled(false)

        val message = if (wasEnabled && restored) {
            context.getString(R.string.stealth_restore_disabled)
        } else {
            context.getString(R.string.stealth_restore_not_enabled)
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

        // Only tell the user their hidden mode just ended if it actually was on.
        if (wasEnabled && restored) {
            notifyStealthRestored(context)
        }

        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val started = runCatching { context.startActivity(launch) }
        Log.i(TAG, "启动界面=${started.isSuccess}")
        if (started.isFailure) {
            Toast.makeText(context, R.string.stealth_launch_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Is the broadcast from something we should trust?
     *
     * Judgement (matching 7kimisu v2.29 - the version where the adb hole was closed):
     *  * system (1000) / telephony (1001) / ourselves   -> allow
     *  * any other uid: it must HAVE a package identity, and then either
     *      1. be the device's default dialer, or
     *      2. be a preinstalled/system app (covers vendor telephony components).
     *  * no package identity (adb shell is uid 2000, `am broadcast` likewise) -> deny
     *
     * **Do not** replace this with "uid < 10000 is fine". adb shell is uid 2000, so that
     * rule let anyone with a USB cable run
     * `am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://70707`
     * and turn hidden mode off.
     *
     * Fail-open below API 34 (there is no `sentFromUid` before that): refusing the code
     * because we cannot identify the dialer would lock the user out of their own app,
     * which is the worse failure. This is a known, unfixable gap on Android 13 and older.
     */
    private fun senderTrusted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        val uid = runCatching { sentFromUidCompat() }.getOrDefault(-1)
        if (uid < 0) return true // unknown sender, fail open as above
        if (uid == Process.SYSTEM_UID || uid == Process.PHONE_UID || uid == Process.myUid()) return true

        val pkgs = LinkedHashSet<String>()
        runCatching { sentFromPackageCompat() }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { pkgs.add(it) }
        runCatching { context.packageManager.getPackagesForUid(uid) }
            .getOrNull()?.forEach { pkgs.add(it) }
        if (pkgs.isEmpty()) return false

        val dialer = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        if (dialer != null && pkgs.contains(dialer)) return true

        return pkgs.any { pkg ->
            runCatching {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            }.getOrDefault(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sentFromUidCompat(): Int = sentFromUid

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun sentFromPackageCompat(): String? = sentFromPackage

    /**
     * Quiet, tappable notification confirming hidden mode ended.
     *
     * Android 10+ blocks background activity starts, so the `startActivity` above may be
     * refused and no UI appears. The notification is a fallback so the user is not left
     * wondering whether the code worked; tapping it opens the manager.
     *
     * Deliberately low-profile for privacy: IMPORTANCE_LOW (no sound, no heads-up) and
     * VISIBILITY_SECRET (nothing shown on the lock screen) - "hidden mode" is itself
     * sensitive. Wrapped in runCatching: a receiver must never crash.
     */
    private fun notifyStealthRestored(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching

            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.stealth_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
            manager.createNotificationChannel(channel)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "用户没给通知权限，静默跳过")
                return@runCatching
            }

            val open = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.stealth_restore_disabled))
                .setContentText(context.getString(R.string.stealth_notification_text))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure {
            Log.w(TAG, "发通知失败（忽略，不影响关闭隐藏模式）", it)
        }
    }

    companion object {
        const val ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE"

        private const val TAG = "paperSU-stealth"
        private const val CHANNEL_ID = "stealth_restore"
        private const val NOTIFICATION_ID = 70001

        /** How many wrong codes before a temporary pause. */
        private const val MAX_FAILS = 5

        /** How long that pause lasts. */
        private const val BLOCK_MILLIS = 30_000L

        @Volatile
        private var fails = 0

        @Volatile
        private var blockedUntil = 0L
    }
}
