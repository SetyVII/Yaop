#pragma once
// Version history (product §16.3): one snapshot per editing session, kept 30 days, restorable
// per object or per block. Pure rules; storage and compression live in the app.

#include "model.h"

#include <QByteArray>
#include <QJsonObject>
#include <QVector>

namespace pkm::history {

inline constexpr int kRetentionDays = 30;

// What a snapshot keeps: title (and rich title), type and properties (blocks included), as
// compact JSON. Merge stamps, sync state and timestamps are left out, so two snapshots of the
// same content are byte-identical.
QByteArray snapshotContent(const Object &o);

// True when `now` should be stored after `last` (the newest stored snapshot, or empty).
bool shouldSnapshot(const QByteArray &last, const QByteArray &now);

struct SnapshotMeta {
    qint64 id = 0;
    QString createdAt; // ISO-8601 UTC
};
// Snapshots older than `days` before `nowIso`. Unreadable dates never expire.
QVector<qint64> expiredSnapshots(const QVector<SnapshotMeta> &all, const QString &nowIso, int days = kRetentionDays);

// The current object with title, type, properties and blocks taken from the snapshot. The id
// and merge metadata stay, so saving it is an ordinary edit that syncs and can be undone by
// restoring again.
Object restoreObject(const Object &current, const QByteArray &snapshot);

// Only one block from the snapshot: replaces the block with that id, or puts it back after the
// closest earlier snapshot block that still exists (first when none). Unchanged if the snapshot
// has no such block.
Object restoreBlock(const Object &current, const QByteArray &snapshot, const QString &blockId);

} // namespace pkm::history
