package com.sukisu.ultra.ui.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms hidden mode after a reboot.
 *
 * The mask lives in the loaded native library, so every fresh process starts unmasked.
 * Without this receiver hidden mode would quietly wear off on each restart - the user
 * would reboot and find the manager showing itself again.
 */
class StealthBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        runCatching { Stealth.applyMask() }
    }
}
