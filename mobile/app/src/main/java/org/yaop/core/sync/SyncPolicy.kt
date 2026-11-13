package org.yaop.core.sync

/**
 * Decisions of the sync cycle that do not depend on Android, kept pure so they are testable.
 */
object SyncPolicy {
    /**
     * Must this device restart the pull from 0 and resend everything it has?
     *
     * Yes when the server is not the one this device last synced with (it was reset, replaced or
     * is a brand-new empty one) — even if its log looks "ahead" of our cursor — or when its log is
     * behind our cursor. [knownVault] is the vault id stored after the last successful handshake
     * (null if none): without it we cannot tell, so we resend once; the merge makes that idempotent.
     */
    fun needsFullResend(knownVault: String?, serverVault: String, cursor: Long, serverHead: Long): Boolean =
        knownVault != serverVault || cursor > serverHead

    /** Ops after which an up-to-date device uploads a fresh bootstrap snapshot. */
    const val BOOTSTRAP_EVERY_OPS = 1000L

    /**
     * Should this device upload a bootstrap snapshot now? Only when it holds the converged state:
     * it has pulled everything the server has, has nothing waiting to be pushed, and the log has
     * grown enough since the last bootstrap.
     */
    fun shouldProduceBootstrap(cursor: Long, serverHead: Long, bootstrapSeq: Long?, outboxPending: Int): Boolean =
        outboxPending == 0 && cursor == serverHead && serverHead > 0 &&
            serverHead - (bootstrapSeq ?: 0L) >= BOOTSTRAP_EVERY_OPS

    /** Must a device start from the bootstrap instead of replaying the log? */
    fun shouldStartFromBootstrap(cursor: Long, bootstrapSeq: Long?, compactFloor: Long): Boolean =
        bootstrapSeq != null && (cursor == 0L || cursor < compactFloor)

    /** Trash retention (spec §3.4, spec/object.md trashed_at): 30 days, then the local copy is dropped. */
    const val TRASH_RETENTION_DAYS = 30L

    /** Has an object's stay in the trash expired? Empty or unparseable dates never expire. */
    fun trashExpired(trashedAt: String?, now: java.time.Instant): Boolean {
        if (trashedAt.isNullOrBlank()) return false
        val t = runCatching { java.time.Instant.parse(trashedAt) }.getOrNull() ?: return false
        return !t.plus(java.time.Duration.ofDays(TRASH_RETENTION_DAYS)).isAfter(now)
    }

    /**
     * Purge only what other devices can already learn: the tombstone has been sealed for the
     * server (synced). A pending one is kept until it travels.
     */
    fun canPurgeTrashed(trashedAt: String?, syncStatus: String, now: java.time.Instant): Boolean =
        trashExpired(trashedAt, now) && syncStatus == "synced"

    /** Whole days left before the purge (0 = due now); null when the date is empty or unparseable. */
    fun trashDaysLeft(trashedAt: String?, now: java.time.Instant): Int? {
        if (trashedAt.isNullOrBlank()) return null
        val t = runCatching { java.time.Instant.parse(trashedAt) }.getOrNull() ?: return null
        val remaining = java.time.Duration.between(now, t.plus(java.time.Duration.ofDays(TRASH_RETENTION_DAYS)))
        if (remaining.isNegative || remaining.isZero) return 0
        // Round up: 29 days and 1 hour left reads as 30.
        return ((remaining.toHours() + 23) / 24).toInt()
    }
}
