// Workspace: media files (sealed at rest) and their upload/download.
#include "workspace.h"
#include "imagecolors.h"
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
// Media / assets
// ---------------------------------------------------------------------------
void Workspace::storeToken(const QString &token)
{
    QSettings settings;
    if (writeSecret(QStringLiteral("device-token:") + m_secretScope,
                    m_deviceId + QLatin1Char('\n') + token)) {
        settings.remove(QStringLiteral("server/token"));
    } else {
        qWarning() << "no keychain: device token kept in the settings file";
        settings.setValue(QStringLiteral("server/token"), token);
    }
}

// The keychain secret is "<device_id>\n<token>": it only serves THIS device;
// anything else triggers a fresh enrollment with the available credentials.
QString Workspace::readStoredDeviceToken(const QString &scope)
{
    const QString stored = readSecret(QStringLiteral("device-token:") + scope);
    if (stored.contains(QLatin1Char('\n'))) {
        const qsizetype nl = stored.indexOf(QLatin1Char('\n'));
        const QString storedDevice = stored.left(nl).trimmed();
        if (storedDevice == m_deviceId)
            return stored.mid(nl + 1).trimmed();
        qWarning().noquote() << "device token of" << storedDevice
                             << "does not belong to this device" << m_deviceId
                             << "— dropping it, provider credentials again";
        return {};
    }
    return {};
}

static const char *const kAssetExts[] = { ".jpg", ".png", ".webp", ".gif", ".pdf", ".mp3", ".m4a", ".wav", ".ogg", "" };

static QString findAssetFile(const QString &dir, const QString &assetId)
{
    for (const char *ext : kAssetExts) {
        const QString candidate = dir + QLatin1Char('/') + assetId + QLatin1String(ext);
        if (QFile::exists(candidate))
            return candidate;
    }
    return {};
}

// Media files are sealed at rest  and decrypted on demand into a
// session directory under $XDG_RUNTIME_DIR (tmpfs, 0700) for QML/PDF/audio.
QString Workspace::assetLocalPath(const QString &assetId) const
{
    if (assetId.isEmpty())
        return {};
    const QString plain = findAssetFile(m_plainAssetsDir, assetId);
    if (!plain.isEmpty())
        return plain;
    const QString cached = findAssetFile(m_assetsDir, assetId);
    if (cached.isEmpty())
        return {};
    QFile in(cached);
    if (!in.open(QIODevice::ReadOnly))
        return {};
    QByteArray bytes = in.readAll();
    in.close();
    const QByteArray aad = QByteArrayLiteral("asset-file/") + assetId.toUtf8();
    if (CacheCipher::isSealed(bytes)) {
        const auto opened = m_fileCipher.open(bytes, aad);
        if (!opened) {
            // Sealed by another key (older cache): unusable, remove so a later
            // ensureAsset() re-downloads it.
            qWarning() << "asset cache: cannot decrypt" << assetId << "(old key?); resyncing";
            QFile::remove(cached);
            return {};
        }
        bytes = *opened;
    } else if (m_fileCipher.ready()) {
        // Written in clear by an older build: seal it in place.
        QFile out(cached);
        if (out.open(QIODevice::WriteOnly | QIODevice::Truncate))
            out.write(m_fileCipher.seal(bytes, aad));
    }
    const QString target = m_plainAssetsDir + QLatin1Char('/') + QFileInfo(cached).fileName();
    QFile out(target);
    if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return {};
    out.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    out.write(bytes);
    return target;
}

QString Workspace::storeAssetFile(const QString &assetId, const QString &ext, const QByteArray &bytes)
{
    QFile cached(m_assetsDir + QLatin1Char('/') + assetId + ext);
    const QByteArray stored = m_fileCipher.ready()
        ? m_fileCipher.seal(bytes, QByteArrayLiteral("asset-file/") + assetId.toUtf8())
        : bytes;
    if (!cached.open(QIODevice::WriteOnly | QIODevice::Truncate) || cached.write(stored) != stored.size()) {
        qWarning() << "asset cache write failed" << cached.fileName();
        return {};
    }
    cached.close();
    const QString target = m_plainAssetsDir + QLatin1Char('/') + assetId + ext;
    QFile out(target);
    if (!out.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return {};
    out.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    out.write(bytes);
    return target;
}

bool Workspace::hasAssetFile(const QString &assetId) const
{
    return !assetId.isEmpty()
        && (!findAssetFile(m_plainAssetsDir, assetId).isEmpty()
            || !findAssetFile(m_assetsDir, assetId).isEmpty());
}

void Workspace::ensureAsset(const QString &assetId)
{
    if (assetId.isEmpty() || hasAssetFile(assetId))
        return;
    if (m_downloading.contains(assetId))
        return;
    m_downloading.insert(assetId);
    // Transport id: the BLIND id of the media object (spec/sync.md §Assets).
    m_client.downloadAsset(blindId(objectIdForMedia(assetId)),
                          [this, assetId](bool ok, const QByteArray &cipher) {
        m_downloading.remove(assetId);
        if (!ok || cipher.isEmpty()) {
            qWarning() << "asset download failed" << assetId;
            return;
        }
        // Chunk count lives encrypted inside the media object (spec/sync.md).
        const QString mediaId = objectIdForMedia(assetId);
        const auto it = m_objects.constFind(mediaId);
        int chunks = 0;
        QString mime;
        if (it != m_objects.constEnd()) {
            chunks = int(it.value().properties.value(QLatin1String("chunks")).toDouble());
            mime = it.value().propertyString(QStringLiteral("mime"));
        }
        const auto plain = vault::decryptAssetChunks(
            m_keyAssets, blindId(mediaId), cipher, chunks);
        if (!plain) {
            qWarning() << "asset chunks failed to decrypt" << assetId
                       << "(chunks =" << chunks << ")";
            return;
        }
        const QString path = storeAssetFile(assetId, assetExtensionForMime(mime), *plain);
        if (!path.isEmpty()) {
            // e2e evidence (e2e scripts grep this): the downloaded and
            // decrypted bytes hash exactly like the source file.
            qInfo().noquote() << "ASSETREADY" << assetId << "bytes" << plain->size()
                              << "sha256"
                              << QString::fromLatin1(
                                     QCryptographicHash::hash(*plain, QCryptographicHash::Sha256)
                                         .toHex());
            emit assetReady(assetId, path);
        }
    });
}

QString Workspace::ensureAssetBlocking(const QString &assetId)
{
    ensureAsset(assetId);
    return assetLocalPath(assetId);
}

QVariantMap Workspace::assetInfo(const QString &assetId) const
{
    QVariantMap m;
    Store::AssetMeta meta;
    if (m_store && m_store->assetMeta(assetId, &meta)) {
        m.insert("id", meta.id);
        m.insert("name", meta.name);
        m.insert("mime", meta.mime);
        m.insert("size", double(meta.size));
        m.insert("durationSeconds", meta.durationSeconds);
    }
    m.insert("localPath", assetLocalPath(assetId));
    return m;
}

int Workspace::assetDurationSeconds(const QString &assetId) const
{
    Store::AssetMeta meta;
    if (m_store && m_store->assetMeta(assetId, &meta))
        return meta.durationSeconds;
    return 0;
}

QString Workspace::importFileAsAsset(const QString &localPath)
{
    QFile file(localPath);
    if (!file.open(QIODevice::ReadOnly))
        return {};
    const QByteArray bytes = file.readAll();
    file.close();
    const QString name = QFileInfo(localPath).fileName();
    QString mime = detectMimeForFilename(name);
    if (mime.isEmpty())
        mime = QStringLiteral("application/octet-stream");
    const QString assetId = newUuid();

    // Keep a local copy first: the asset works offline and the upload can resume later .
    if (storeAssetFile(assetId, assetExtensionForMime(mime), bytes).isEmpty())
        return {};
    Store::AssetMeta meta;
    meta.id = assetId;
    meta.name = name;
    meta.mime = mime;
    meta.size = bytes.size();
    meta.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    m_store->putAssetMeta(meta);
    m_assetMime.insert(assetId, mime);

    // The media object carries MIME/name/chunk metadata encrypted (spec/sync.md).
    const QString mediaId = objectIdForMedia(assetId);
    Object media;
    auto existing = m_objects.find(mediaId);
    if (existing != m_objects.end())
        media = existing.value();
    else {
        media.id = mediaId;
        media.createdAt = meta.createdAt;
    }
    media.typeId = normalizeTypeId([name]() {
        const QString t = canonicalTypeIdForFilename(name);
        return t.isEmpty() ? QString(TypeId::Note) : t;
    }());
    media.title = name;
    media.updatedAt = meta.createdAt;
    media.properties.insert(QStringLiteral("asset_id"), assetId);
    media.properties.insert(QStringLiteral("mime"), mime);
    media.properties.insert(QStringLiteral("file_name"), name);
    media.properties.insert(QStringLiteral("size"), double(bytes.size()));
    media.properties.insert(QStringLiteral("chunks"),
                            double((bytes.size() + vault::kAssetChunkBytes - 1)
                                   / vault::kAssetChunkBytes));
    media.properties.insert(QStringLiteral("chunk_size"), double(vault::kAssetChunkBytes));
    media.properties.insert(QStringLiteral("total_plain_size"), double(bytes.size()));
    // image.colors (spec/types.md) from the pixels, locally; a value set by hand is kept.
    if (media.typeId == QLatin1String("image") && !media.properties.contains(QStringLiteral("colors"))) {
        const QStringList colors = dominantColorIds(QImage::fromData(bytes));
        if (!colors.isEmpty())
            media.properties.insert(QStringLiteral("colors"), QJsonArray::fromStringList(colors));
    }
    saveObject(media);

    m_store->queueUpload({ assetId, name, mime, mediaId });
    uploadPendingAssets();
    return assetId;
}

namespace {
// "image" | "audio" | "pdf" for a file name, "" when it is not media YAOP embeds.
QString mediaKindForName(const QString &name)
{
    const QString n = name.toLower();
    static const QRegularExpression image(QStringLiteral("\\.(png|jpe?g|webp|gif|bmp)$"));
    static const QRegularExpression audio(QStringLiteral("\\.(mp3|m4a|wav|ogg|flac|aac)$"));
    if (image.match(n).hasMatch())
        return QStringLiteral("image");
    if (audio.match(n).hasMatch())
        return QStringLiteral("audio");
    if (n.endsWith(QLatin1String(".pdf")))
        return QStringLiteral("pdf");
    return {};
}
} // namespace

bool Workspace::clipboardHasMedia() const
{
    const QMimeData *md = QGuiApplication::clipboard()->mimeData();
    if (!md)
        return false;
    if (md->hasImage())
        return true;
    for (const QUrl &u : md->urls())
        if (u.isLocalFile() && !mediaKindForName(u.fileName()).isEmpty())
            return true;
    return false;
}

QVariantList Workspace::importMediaUrls(const QVariantList &urls)
{
    QVariantList out;
    for (const QVariant &v : urls) {
        const QUrl u = v.toUrl();
        if (!u.isLocalFile())
            continue;
        const QString name = QFileInfo(u.toLocalFile()).fileName();
        const QString kind = mediaKindForName(name);
        if (kind.isEmpty())
            continue;
        const QString id = importFileAsAsset(u.toLocalFile());
        if (!id.isEmpty())
            out << QVariantMap{ { QStringLiteral("assetId"), id },
                                { QStringLiteral("name"), name },
                                { QStringLiteral("kind"), kind } };
    }
    return out;
}

QVariantList Workspace::importClipboardMedia()
{
    const QMimeData *md = QGuiApplication::clipboard()->mimeData();
    if (!md)
        return {};
    QVariantList urls;
    for (const QUrl &u : md->urls())
        urls << u;
    QVariantList out = importMediaUrls(urls);
    if (out.isEmpty() && md->hasImage()) {
        const QImage img = qvariant_cast<QImage>(md->imageData());
        if (!img.isNull()) {
            const QString path = QDir::tempPath() + QStringLiteral("/yaop-paste-")
                + QString::number(QDateTime::currentMSecsSinceEpoch()) + QStringLiteral(".png");
            if (img.save(path, "PNG")) {
                const QString id = importFileAsAsset(path);
                QFile::remove(path);
                if (!id.isEmpty())
                    out << QVariantMap{ { QStringLiteral("assetId"), id },
                                        { QStringLiteral("name"), QStringLiteral("pasted.png") },
                                        { QStringLiteral("kind"), QStringLiteral("image") } };
            }
        }
    }
    return out;
}

// A new or reset server holds none of the media: queue an upload for every asset this device
// has on disk (assets it never downloaded cannot be re-uploaded from here).
void Workspace::requeueAssetUploads()
{
    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        if (!it.key().startsWith(QLatin1String("media_")))
            continue;
        const QString assetId = it.key().mid(6);
        if (!hasAssetFile(assetId))
            continue;
        const Object &media = it.value();
        m_store->queueUpload({ assetId, media.properties.value(QStringLiteral("file_name")).toString(),
                               media.properties.value(QStringLiteral("mime")).toString(), media.id });
    }
    uploadPendingAssets();
}

void Workspace::uploadPendingAssets()
{
    if (!m_store)
        return;
    for (const Store::PendingUpload &u : m_store->pendingUploads()) {
        if (m_uploading.contains(u.assetId))
            continue;
        m_uploading.insert(u.assetId);
        // Ask the server first: do not read and seal a file it already has.
        m_client.assetUploadComplete(blindId(objectIdForMedia(u.assetId)),
                                     [this, u](std::optional<bool> complete) {
            if (complete.value_or(false)) {
                m_uploading.remove(u.assetId);
                m_store->dequeueUpload(u.assetId);
                return;
            }
            startAssetUpload(u);
        });
    }
}

void Workspace::startAssetUpload(const Store::PendingUpload &u)
{
    {
        QFile file(assetLocalPath(u.assetId));
        if (!file.open(QIODevice::ReadOnly)) {
            qWarning() << "pending upload without local file, dropped" << u.assetId;
            m_uploading.remove(u.assetId);
            m_store->dequeueUpload(u.assetId);
            return;
        }
        const QByteArray bytes = file.readAll();
        file.close();
        // Independent sealed chunk frames (spec/sync.md §Assets): chunk n is
        // the body of PUT Upload-Offset:n under the BLIND id of the media
        // object. New nonces per attempt are fine: every frame decrypts alone.
        const QString mediaId = objectIdForMedia(u.assetId);
        const QVector<QByteArray> frames =
            vault::sealAssetChunks(m_keyAssets, quint32(keyEpoch()), blindId(mediaId), bytes);
        m_client.uploadAssetResumable(blindId(mediaId), frames,
                                      [this, u](bool ok, const QJsonObject &) {
            m_uploading.remove(u.assetId);
            if (!ok) {
                qWarning() << "asset upload interrupted, will resume" << u.assetId;
                schedulePush(15000); // the next sync resumes from the server's offset
                return;
            }
            m_store->dequeueUpload(u.assetId);
            schedulePull(300); // other devices learn the media object through ops
        });
    }
}

void Workspace::handleAssetMeta(const QJsonObject &assetJson)
{
    Store::AssetMeta meta;
    meta.id = assetJson.value(QLatin1String("id")).toString();
    meta.objectId = assetJson.value(QLatin1String("object_id")).toString();
    meta.name = assetJson.value(QLatin1String("name")).toString();
    meta.mime = assetJson.value(QLatin1String("mime")).toString();
    meta.size = qlonglong(assetJson.value(QLatin1String("size")).toDouble());
    meta.createdAt = assetJson.value(QLatin1String("created_at")).toString();
    meta.durationSeconds = assetJson.value(QLatin1String("duration_seconds")).toInt();
    if (!meta.id.isEmpty()) {
        m_store->putAssetMeta(meta);
        if (!meta.mime.isEmpty())
            m_assetMime.insert(meta.id, meta.mime);
    }
}

} // namespace pkm
