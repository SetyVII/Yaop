package org.yaop.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    private val min = 60_000L

    @Test fun disabledNeverLocks() {
        val p = AppLockPolicy(enabled = false, timeoutMs = 0)
        assertFalse(p.shouldLock(10 * min, 0)); assertFalse(p.shouldLock(0, null))
    }

    @Test fun coldStartLocksWhenEnabled() =
        assertTrue(AppLockPolicy(true, 5 * min).shouldLock(0, null))

    @Test fun locksOnlyAfterTimeout() {
        val p = AppLockPolicy(true, 5 * min)
        assertFalse(p.shouldLock(now = 4 * min, backgroundedAt = 0))
        assertTrue(p.shouldLock(now = 5 * min, backgroundedAt = 0))
        assertTrue(p.shouldLock(now = 9 * min, backgroundedAt = 0))
    }

    @Test fun zeroTimeoutLocksImmediately() =
        assertTrue(AppLockPolicy(true, 0).shouldLock(100, 100))

    @Test fun minutesConversion() = org.junit.Assert.assertEquals(900_000L, AppLockPolicy.minutesToMs(15))
}
