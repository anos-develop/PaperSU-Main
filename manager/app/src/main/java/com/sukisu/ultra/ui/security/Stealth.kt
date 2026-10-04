package com.sukisu.ultra.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.sukisu.ultra.Natives
import com.sukisu.ultra.data.repository.SettingsRepositoryImpl

/**
 * paperSU hidden mode - the app-side equivalent of 7kimisu's stealth mode.
 *
 * Mechanism: while enabled, [Natives.nativeSetStealthMask] makes `Natives.isManager`
 * report `false`. HomeViewModel and the other consumers of that flag then degrade to the
 * normal "not installed / tap to install" UI on their own, with no per-screen changes.
 * The dialer secret code (see [StealthReceiver]) clears the mask again.
 *
 * **How this differs from 7kimisu's version** (important, do not confuse the two):
 * 7kimisu flips a *kernel* flag, so the kernel stops advertising the MANAGER flag and
 * other apps cannot detect the root solution at all. This implementation only disguises
 * this app's own user interface - anyone else querying the kernel still gets the truth.
 *
 * No root shell and no on-disk copy are needed here: the code lives in app preferences,
 * and uninstalling clears both the preference and the mask together, so the
 * "uninstall + reinstall leaves you locked out of hidden mode" failure that 7kimisu has
 * to defend against cannot happen in this design.
 */
object Stealth {

    /** Shipped default, same value 7kimisu uses. */
    const val DEFAULT_CODE = "70707"

    fun isEnabled(): Boolean =
        runCatching { SettingsRepositoryImpl().stealthEnabled }.getOrDefault(false)

    /**
     * Push the persisted state into the native mask.
     * Must run before anything reads [Natives.isManager] - i.e. at the start of
     * MainActivity.onCreate and from [StealthBootReceiver].
     *
     * @return whether hidden mode is currently on
     */
    fun applyMask(): Boolean {
        val on = isEnabled()
        runCatching { Natives.nativeSetStealthMask(on) }
        return on
    }

    /** Turn hidden mode on/off and apply it immediately. */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        SettingsRepositoryImpl().stealthEnabled = enabled
        Natives.nativeSetStealthMask(enabled)
        true
    }.getOrDefault(false)

    /** The code that unlocks hidden mode; falls back to the shipped default. */
    fun effectiveCode(): String = runCatching {
        SettingsRepositoryImpl().stealthCode
            .trim()
            .filter { it.isDigit() }
            .take(MAX_CODE_LENGTH)
            .takeIf { it.isNotBlank() }
            ?: DEFAULT_CODE
    }.getOrDefault(DEFAULT_CODE)

    /** Persist a new code. Rejects anything that has no digits. */
    fun setCode(code: String): Boolean = runCatching {
        val clean = code.trim().filter { it.isDigit() }.take(MAX_CODE_LENGTH)
        if (clean.isBlank()) {
            false
        } else {
            SettingsRepositoryImpl().stealthCode = clean
            true
        }
    }.getOrDefault(false)

    /**
     * Forget the custom code so [effectiveCode] falls back to [DEFAULT_CODE].
     * Used when the user empties the field in settings.
     */
    fun clearCode(): Boolean = runCatching {
        SettingsRepositoryImpl().stealthCode = ""
        true
    }.getOrDefault(false)

    /** Dialer codes are short; clamp so a paste can never store something absurd. */
    private const val MAX_CODE_LENGTH = 12
}

/**
 * Rebuild the UI from scratch: clear the ViewModelStore first, then recreate.
 *
 * ViewModels survive `Activity.recreate()` by design, and MainActivityViewModel /
 * HomeViewModel compute their state once at construction. Without clearing, the new
 * activity would re-render the *old* "not installed" state and hidden mode would look
 * like it did not turn off until some later refresh.
 */
fun restartUiFresh(context: Context) {
    val activity = context.findActivity()
    (activity as? androidx.lifecycle.ViewModelStoreOwner)?.let {
        runCatching { it.viewModelStore.clear() }
    }
    activity?.recreate()
}

/**
 * Dig the real Activity out of any Context. Compose's LocalContext is sometimes a
 * ContextThemeWrapper rather than an Activity, in which case `context as? Activity`
 * silently fails and `recreate()` never happens.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
