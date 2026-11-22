// Workspace: version history (product §16.3). One snapshot per editing session, stored locally
// zstd-compressed inside the encrypted DB, kept 30 days, restorable per object or per block.
#include "workspace.h"
#include "editormodel.h"
#include "history.h"
#include "store.h"
#include "vaultcrypto.h"
#include <QDateTime>
#include <QJsonDocument>

namespace pkm {

namespace {
QString nowIso() { return QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs); }

QByteArray unpack(const QByteArray &compressed)
{
    const std::optional<QByteArray> plain = vault::zstdDecompress(compressed);
    return plain ? *plain : QByteArray();
}
} // namespace

void Workspace::captureSnapshot(const QString &id)
{
    if (!m_store || !m_objects.contains(id))
        return;
    const QByteArray content = history::snapshotContent(m_objects.value(id));
    const QVector<Store::SnapshotRow> rows = m_store->snapshots(id);
    const QByteArray last = rows.isEmpty() ? QByteArray() : unpack(rows.first().compressed);
    if (history::shouldSnapshot(last, content))
        m_store->putSnapshot(id, nowIso(), vault::zstdCompress(content));
}

void Workspace::purgeOldSnapshots()
{
    if (!m_store)
        return;
    QVector<history::SnapshotMeta> metas;
    for (const Store::SnapshotRow &r : m_store->allSnapshotTimes())
        metas.append({ r.id, r.createdAt });
    m_store->deleteSnapshots(history::expiredSnapshots(metas, nowIso()));
}

QVariantList Workspace::objectHistory(const QString &id) const
{
    QVariantList out;
    if (!m_store)
        return out;
    for (const Store::SnapshotRow &r : m_store->snapshots(id)) {
        const Object snap = Object::fromJson(QJsonDocument::fromJson(unpack(r.compressed)).object());
        QStringList preview;
        for (const Block &b : snap.blocks)
            if (preview.size() < 3 && !b.plainText().trimmed().isEmpty())
                preview.append(b.plainText().trimmed().left(80));
        out.append(QVariantMap{ { "id", r.id }, { "createdAt", r.createdAt }, { "title", plainTitleFor(snap) },
                                { "blockCount", int(snap.blocks.size()) }, { "preview", preview } });
    }
    return out;
}

QVariantList Workspace::versionBlocks(const QString &id, qint64 snapshotId) const
{
    QVariantList out;
    if (!m_store)
        return out;
    QByteArray snapshot;
    for (const Store::SnapshotRow &r : m_store->snapshots(id))
        if (r.id == snapshotId)
            snapshot = unpack(r.compressed);
    if (snapshot.isEmpty())
        return out;
    const Object snap = Object::fromJson(QJsonDocument::fromJson(snapshot).object());
    QHash<QString, QString> now; // current text by block id
    if (const std::optional<Object> cur = currentObject(id))
        for (const Block &b : cur->blocks)
            now.insert(b.id, b.plainText());
    for (const Block &b : snap.blocks) {
        const QString text = b.plainText();
        const QString state = !now.contains(b.id) ? QStringLiteral("removed")
            : now.value(b.id) == text ? QStringLiteral("same") : QStringLiteral("changed");
        out.append(QVariantMap{ { "id", b.id }, { "type", b.type }, { "level", b.level },
                                { "text", text.left(600) }, { "state", state } });
    }
    return out;
}

bool Workspace::restoreVersion(const QString &id, qint64 snapshotId, const QString &blockId)
{
    if (!m_store || !m_objects.contains(id))
        return false;
    QByteArray snapshot;
    for (const Store::SnapshotRow &r : m_store->snapshots(id))
        if (r.id == snapshotId)
            snapshot = unpack(r.compressed);
    if (snapshot.isEmpty())
        return false;
    Object current = m_objects.value(id);
    if (auto *ed = m_editors.value(id)) {
        ed->flushNow();
        current = m_objects.value(id);
    }
    captureSnapshot(id); // the state being replaced stays restorable
    const Object restored = blockId.isEmpty() ? history::restoreObject(current, snapshot)
                                              : history::restoreBlock(current, snapshot, blockId);
    saveObject(restored); // an ordinary edit: stamped and synced like any other
    if (auto *ed = m_editors.value(id))
        ed->load(m_objects.value(id));
    emit objectUpdated(id);
    notifyObjectListChanged();
    return true;
}

} // namespace pkm
