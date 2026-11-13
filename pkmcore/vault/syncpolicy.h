// Pure decisions of the sync cycle (testable without a Workspace).
#pragma once
#include <QDateTime>
#include <QString>

namespace pkm::syncpolicy {

// Must this device restart the pull from 0 and resend everything it has?
// Yes when the server is not the one we last synced with (reset, replaced or brand-new and
// empty) even if its log looks ahead of our cursor, or when its log is behind our cursor.
// knownVault empty = never recorded: resend once, the merge makes it idempotent.
inline bool needsFullResend(const QString &knownVault, const QString &serverVault,
                            qlonglong cursor, qlonglong serverHead)
{
    return knownVault != serverVault || cursor > serverHead;
}

// Ops after which an up-to-date device uploads a fresh bootstrap snapshot.
inline constexpr qlonglong kBootstrapEveryOps = 1000;

// Only a device holding the converged state: pulled everything, nothing waiting to push, and the
// log has grown enough since the last bootstrap.
inline bool shouldProduceBootstrap(qlonglong cursor, qlonglong serverHead, qlonglong bootstrapSeq, int pending,
                                   qlonglong every = kBootstrapEveryOps)
{
    return pending == 0 && cursor == serverHead && serverHead > 0 && serverHead - bootstrapSeq >= every;
}

// New device, or behind the compaction floor: start from the bootstrap instead of replaying the log.
inline bool shouldStartFromBootstrap(qlonglong cursor, bool hasBootstrap, qlonglong compactFloor)
{
    return hasBootstrap && (cursor == 0 || cursor < compactFloor);
}

// Trash retention (spec §3.4, spec/object.md trashed_at): 30 days, then the local copy is dropped.
inline constexpr qint64 kTrashRetentionDays = 30;

// Has an object's stay in the trash expired? Unparseable or empty dates never expire.
inline bool trashExpired(const QString &trashedAt, const QDateTime &nowUtc)
{
    if (trashedAt.isEmpty())
        return false;
    const QDateTime t = QDateTime::fromString(trashedAt, Qt::ISODateWithMs);
    return t.isValid() && t.addDays(kTrashRetentionDays) <= nowUtc;
}

// Purge only what other devices already know: the tombstone must have reached the server
// (synced, nothing queued for it) before the local row disappears.
inline bool canPurgeTrashed(const QString &trashedAt, const QString &syncStatus, bool hasQueuedOp,
                            const QDateTime &nowUtc)
{
    return trashExpired(trashedAt, nowUtc) && syncStatus == QLatin1String("synced") && !hasQueuedOp;
}

} // namespace pkm::syncpolicy
