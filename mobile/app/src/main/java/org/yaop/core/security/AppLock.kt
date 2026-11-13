package org.yaop.core.security

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local app lock (spec §15.5, plan app lock). The unlock method is the device's own biometric or
 * screen credential (BiometricPrompt, see ui/lock), so there is no app-specific PIN to forget.
 *
 * Scope (documented decision): this is a UI gate. The VK stays wrapped by the Android Keystore
 * and background sync keeps working while locked; what the lock protects is anyone holding the
 * unlocked phone from reading the vault. Share/quick-capture only write into the local database
 * and show nothing, so they do not need to unlock.
 */
class AppLock(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var backgroundedAt: Long? = null

    private val _locked = MutableStateFlow(policy().enabled)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun policy() = AppLockPolicy(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        timeoutMs = AppLockPolicy.minutesToMs(prefs.getInt(KEY_TIMEOUT_MIN, 1)),
    )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) {
            prefs.edit().putBoolean(KEY_ENABLED, v).apply()
            if (!v) _locked.value = false
        }

    var timeoutMinutes: Int
        get() = prefs.getInt(KEY_TIMEOUT_MIN, 1)
        set(v) = prefs.edit().putInt(KEY_TIMEOUT_MIN, v).apply()

    /** Hide content from recents and screenshots. */
    var secureScreen: Boolean
        get() = prefs.getBoolean(KEY_SECURE, false)
        set(v) = prefs.edit().putBoolean(KEY_SECURE, v).apply()

    /** Cold start is already covered by the initial [locked] value; only real background trips count. */
    fun onBackgrounded(changingConfigurations: Boolean) {
        if (changingConfigurations || _locked.value) return
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (policy().shouldLock(SystemClock.elapsedRealtime(), since)) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }

    private companion object {
        const val PREFS = "yaop_app_lock"
        const val KEY_ENABLED = "enabled"
        const val KEY_TIMEOUT_MIN = "timeout_min"
        const val KEY_SECURE = "secure_screen"
    }
}
