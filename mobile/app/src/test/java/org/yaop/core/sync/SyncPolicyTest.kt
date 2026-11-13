package org.yaop.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyTest {
    @Test fun newEmptyServerAfterWorkingLocally() =
        assertTrue(SyncPolicy.needsFullResend(knownVault = null, serverVault = "A", cursor = 0, serverHead = 0))

    @Test fun differentServerEvenIfItsLogLooksAhead() =
        assertTrue(SyncPolicy.needsFullResend("A", "B", cursor = 5, serverHead = 900))

    @Test fun serverLogBehindOurCursor() =
        assertTrue(SyncPolicy.needsFullResend("A", "A", cursor = 50, serverHead = 3))

    @Test fun sameServerNormalCaseDoesNotResend() =
        assertFalse(SyncPolicy.needsFullResend("A", "A", cursor = 10, serverHead = 40))
}

class BootstrapPolicyTest {
    @Test fun producesOnlyWhenConvergedAndLogGrew() {
        assertTrue(SyncPolicy.shouldProduceBootstrap(cursor = 2000, serverHead = 2000, bootstrapSeq = null, outboxPending = 0))
        assertFalse(SyncPolicy.shouldProduceBootstrap(2000, 2000, null, outboxPending = 3))
        assertFalse(SyncPolicy.shouldProduceBootstrap(cursor = 1990, serverHead = 2000, bootstrapSeq = null, outboxPending = 0))
        assertFalse(SyncPolicy.shouldProduceBootstrap(2000, 2000, bootstrapSeq = 1500, outboxPending = 0))
        assertFalse(SyncPolicy.shouldProduceBootstrap(0, 0, null, 0))
    }

    @Test fun startsFromBootstrapWhenNewOrBehindTheFloor() {
        assertTrue(SyncPolicy.shouldStartFromBootstrap(cursor = 0, bootstrapSeq = 900, compactFloor = 0))
        assertTrue(SyncPolicy.shouldStartFromBootstrap(cursor = 50, bootstrapSeq = 900, compactFloor = 400))
        assertFalse(SyncPolicy.shouldStartFromBootstrap(cursor = 500, bootstrapSeq = 900, compactFloor = 400))
        assertFalse(SyncPolicy.shouldStartFromBootstrap(cursor = 0, bootstrapSeq = null, compactFloor = 0))
    }

}

class TrashPolicyTest {
    private val now = java.time.Instant.parse("2026-12-31T12:00:00Z")

    @Test fun trashExpiresAtThirtyDays() =
        assertTrue(SyncPolicy.trashExpired("2026-12-01T12:00:00Z", now))

    @Test fun trashOneSecondShortDoesNotExpire() =
        assertFalse(SyncPolicy.trashExpired("2026-12-01T12:00:01Z", now))

    @Test fun emptyOrBadTrashDateNeverExpires() {
        assertFalse(SyncPolicy.trashExpired(null, now))
        assertFalse(SyncPolicy.trashExpired("garbage", now))
    }

    @Test fun expiredAndSyncedIsPurged() =
        assertTrue(SyncPolicy.canPurgeTrashed("2026-11-01T00:00:00Z", "synced", now))

    @Test fun pendingTombstoneIsKeptUntilPushed() =
        assertFalse(SyncPolicy.canPurgeTrashed("2026-11-01T00:00:00Z", "pending", now))

    @Test fun daysLeftRoundsUpAndBottomsAtZero() {
        assertEquals(30, SyncPolicy.trashDaysLeft("2026-12-31T11:00:00Z", now))
        assertEquals(1, SyncPolicy.trashDaysLeft("2026-12-01T13:00:00Z", now))
        assertEquals(0, SyncPolicy.trashDaysLeft("2026-11-01T00:00:00Z", now))
        assertNull(SyncPolicy.trashDaysLeft("garbage", now))
    }
}
