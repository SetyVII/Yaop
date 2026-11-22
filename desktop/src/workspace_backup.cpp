// Workspace: encrypted backup export and restore.
#include "workspace.h"
#include "backup.h"
#include "syncpolicy.h"
#include "query.h"
#include "remoteops.h"
#include "bootstrapsnapshot.h"
#include "nodeops.h"
#include <QSaveFile>
#include <QRegularExpression>
#include <QImage>
#include <QMimeData>
#include <QGuiApplication>
#include <QClipboard>
#include "bip39.h"
#include "outbox.h"
#include "merge.h"
#include <QSysInfo>

#include <QCoreApplication>
#include <QDate>
#include <QLocale>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QNetworkInformation>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSet>
#include <QSettings>
#include <QStandardPaths>
#include <QUrl>
#ifdef Q_OS_LINUX
#include <malloc.h>
#endif

#include "workspace_internal.h"

namespace pkm {

using namespace detail;

// ---------------------------------------------------------------------------
// .yaop backup (spec §15.6, plan backup): container in backup.{h,cpp}
// ---------------------------------------------------------------------------
QString Workspace::exportBackup(const QString &path)
{
    if (m_vault.vk.isEmpty())
        return QStringLiteral("error: no vault");
    QSaveFile file(path);
    if (!file.open(QIODevice::WriteOnly))
        return QStringLiteral("error: cannot write %1").arg(path);
    backup::Writer w(&file, m_vault.vk, quint32(m_vault.epoch));
    int objects = 0, types = 0, assets = 0;
    auto json = [](const QJsonObject &o) { return QJsonDocument(o).toJson(QJsonDocument::Compact); };
    for (const ObjectType &t : m_store->types()) {
        w.write({ backup::KType, t.id, 0, 0, json(t.toJson()) });
        ++types;
    }
    for (const Object &o : m_store->allObjects()) {
        w.write({ backup::KObj, o.id, 0, 0, json(o.toJson(true)) });
        ++objects;
        const QString assetId = o.properties.value(QStringLiteral("asset_id")).toString();
        if (!o.id.startsWith(QLatin1String("media_")) || assetId.isEmpty())
            continue;
        QFile in(assetLocalPath(assetId));
        if (!in.open(QIODevice::ReadOnly))
            continue; // never downloaded on this device: the backup carries what is local
        const qint64 size = in.size();
        const quint32 total = quint32(qMax<qint64>(1, (size + backup::kAssetChunk - 1) / backup::kAssetChunk));
        for (quint32 i = 0; i < total; ++i)
            w.write({ backup::KAsset, assetId, i, total, in.read(backup::kAssetChunk) });
        ++assets;
    }
    if (!w.finish() || !file.commit())
        return QStringLiteral("error: write failed");
    return QStringLiteral("ok: %1 objects, %2 files").arg(objects).arg(assets);
}

QString Workspace::restoreBackup(const QString &path)
{
    if (m_vault.vk.isEmpty())
        return QStringLiteral("error: no vault");
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
        return QStringLiteral("error: cannot read %1").arg(path);
    QHash<QString, QByteArray> pending;
    int objects = 0, assets = 0;
    QString err;
    std::optional<quint32> epoch;
    {
        ListBatchScope batch(this);
        epoch = backup::read(&file, m_vault.vk, [&](const backup::Record &r) {
        if (r.kind == backup::KType) {
            const ObjectType t = ObjectType::fromJson(QJsonDocument::fromJson(r.data).object());
            if (!t.id.isEmpty()) {
                m_types.insert(t.id, t);
                m_store->putType(t);
                emit typesChanged();
            }
        } else if (r.kind == backup::KObj) {
            const QJsonObject wire = QJsonDocument::fromJson(r.data).object();
            applyRemoteObject(wire, QStringLiteral("obj")); // merge: never overwrites newer local data
            const auto it = m_objects.constFind(r.id);
            if (it != m_objects.constEnd()) { // queue it so it also reaches the server
                m_store->dequeueOpsFor(r.id);
                m_store->queueOp(newSyncOpId(), r.id, it.value().toJson(true));
            }
            ++objects;
        } else if (r.kind == backup::KAsset) {
            QByteArray &buf = pending[r.id];
            buf.append(r.data);
            if (r.n + 1 >= r.total) {
                const Object media = m_objects.value(objectIdForMedia(r.id));
                QString mime = media.properties.value(QStringLiteral("mime")).toString();
                if (mime.isEmpty())
                    mime = QStringLiteral("application/octet-stream");
                const QString name = media.properties.value(QStringLiteral("file_name")).toString();
                if (!storeAssetFile(r.id, assetExtensionForMime(mime), buf).isEmpty()) {
                    Store::AssetMeta meta;
                    meta.id = r.id;
                    meta.objectId = media.id;
                    meta.name = name;
                    meta.mime = mime;
                    meta.size = buf.size();
                    meta.createdAt = media.createdAt;
                    m_store->putAssetMeta(meta);
                    m_assetMime.insert(r.id, mime);
                    m_store->queueUpload({ r.id, name, mime, media.id });
                    ++assets;
                }
                pending.remove(r.id);
            }
        }
    }, &err);
    }
    if (!epoch)
        return QStringLiteral("error: %1").arg(err);
    m_pendingCount = m_store->pendingOpCount();
    emit syncStateChanged();
    notifyObjectListChanged();
#ifdef Q_OS_LINUX
    malloc_trim(0);
#endif
    schedulePush(1500);
    uploadPendingAssets();
    return QStringLiteral("ok: %1 objects, %2 files").arg(objects).arg(assets);
}

} // namespace pkm
