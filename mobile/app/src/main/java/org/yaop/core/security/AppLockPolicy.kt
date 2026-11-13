package org.yaop.core.security

/**
 * Pure decision logic of the app lock (spec §15.5), kept free of Android types so it is unit
 * testable. [timeoutMs] == 0 means "lock every time the app leaves the foreground".
 */
data class AppLockPolicy(
    val enabled: Boolean,
    val timeoutMs: Long,
) {
    /**
     * Should the UI lock when the app returns to the foreground?
     * [backgroundedAt] is the wall-clock (elapsed-realtime) instant it left the foreground,
     * or null on a cold start (always locks while enabled).
     */
    fun shouldLock(now: Long, backgroundedAt: Long?): Boolean {
        if (!enabled) return false
        if (backgroundedAt == null) return true
        return now - backgroundedAt >= timeoutMs
    }

    companion object {
        /** Timeouts offered in settings, in minutes (0 = immediately). */
        val TIMEOUT_MINUTES = listOf(0, 1, 5, 15, 60)
        fun minutesToMs(minutes: Int): Long = minutes * 60_000L
    }
}
