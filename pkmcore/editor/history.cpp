#include "history.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>

namespace pkm::history {

namespace {

Object snapshotObject(const QByteArray &snapshot)
{
    return Object::fromJson(QJsonDocument::fromJson(snapshot).object());
}

} // namespace

QByteArray snapshotContent(const Object &o)
{
    const QJsonObject wire = o.toJson(true);
    QJsonObject keep;
    for (const char *k : { "title", "title_runs", "type_id", "properties" })
        if (wire.contains(QLatin1String(k)))
            keep.insert(QLatin1String(k), wire.value(QLatin1String(k)));
    return QJsonDocument(keep).toJson(QJsonDocument::Compact);
}

bool shouldSnapshot(const QByteArray &last, const QByteArray &now)
{
    return !now.isEmpty() && now != last;
}

QVector<qint64> expiredSnapshots(const QVector<SnapshotMeta> &all, const QString &nowIso, int days)
{
    QVector<qint64> out;
    const QDateTime now = QDateTime::fromString(nowIso, Qt::ISODateWithMs);
    if (!now.isValid())
        return out;
    const QDateTime limit = now.addDays(-days);
    for (const SnapshotMeta &s : all) {
        const QDateTime at = QDateTime::fromString(s.createdAt, Qt::ISODateWithMs);
        if (at.isValid() && at < limit)
            out.append(s.id);
    }
    return out;
}

Object restoreObject(const Object &current, const QByteArray &snapshot)
{
    const Object snap = snapshotObject(snapshot);
    Object out = current;
    out.title = snap.title;
    out.titleRuns = snap.titleRuns;
    if (!snap.typeId.isEmpty())
        out.typeId = snap.typeId;
    out.properties = snap.properties;
    out.blocks = snap.blocks;
    return out;
}

Object restoreBlock(const Object &current, const QByteArray &snapshot, const QString &blockId)
{
    const Object snap = snapshotObject(snapshot);
    int si = -1;
    for (int i = 0; i < snap.blocks.size(); ++i)
        if (snap.blocks[i].id == blockId)
            si = i;
    if (si < 0)
        return current;
    Object out = current;
    for (Block &b : out.blocks)
        if (b.id == blockId) {
            b = snap.blocks[si];
            return out;
        }
    // Put it back after the closest earlier block of the snapshot that is still here.
    int insertAt = 0;
    for (int j = si - 1; j >= 0 && insertAt == 0; --j)
        for (int k = 0; k < out.blocks.size(); ++k)
            if (out.blocks[k].id == snap.blocks[j].id) {
                insertAt = k + 1;
                break;
            }
    out.blocks.insert(insertAt, snap.blocks[si]);
    return out;
}

} // namespace pkm::history
