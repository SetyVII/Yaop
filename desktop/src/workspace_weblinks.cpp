// Workspace: media objects, web links (metadata and WebP covers), and browser extension capture.
#include "workspace.h"
#include "backup.h"
#include "imagecolors.h"
#include "linkpreview.h"
#include "syncpolicy.h"
#include "query.h"
#include "remoteops.h"
#include "bootstrapsnapshot.h"
#include "nodeops.h"
#include <QBuffer>
#include <QSaveFile>
#include <QRegularExpression>
#include <QImage>
#include <QImageReader>
#include <QImageWriter>
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
#include <QPointer>
#include <QSet>
#include <QSettings>
#include <QStandardPaths>
#include <QUrl>
#ifdef Q_OS_LINUX
#include <malloc.h>
#endif

#include "workspace_internal.h"

namespace pkm {

namespace detail {

EncodedImage encodeImageAsWebP(const QByteArray &rawBytes, int maxSidePx, int quality,
                               bool rejectTiny)
{
    EncodedImage out;
    if (rawBytes.isEmpty())
        return out;

    QBuffer inBuf;
    inBuf.setData(rawBytes);
    inBuf.open(QIODevice::ReadOnly);
    QImageReader reader(&inBuf);
    reader.setAutoTransform(true);
    const QByteArray srcFormat = reader.format().toLower();
    QImage img = reader.read();
    if (img.isNull())
        return out;

    if (rejectTiny && (img.width() < 32 || img.height() < 32))
        return out;

    bool resized = false;
    if (maxSidePx > 0 && (img.width() > maxSidePx || img.height() > maxSidePx)) {
        img = img.scaled(maxSidePx, maxSidePx, Qt::KeepAspectRatio, Qt::SmoothTransformation);
        resized = true;
    }

    // Keep already-WebP bytes directly when no resize is needed (avoids double lossy compression
    // when the source or browser extension already encoded the cover/image as WebP).
    if (srcFormat == QByteArrayLiteral("webp") && !resized) {
        out.bytes = rawBytes;
        out.mime = QStringLiteral("image/webp");
        out.ext = QStringLiteral(".webp");
        out.width = img.width();
        out.height = img.height();
        out.ok = true;
        return out;
    }

    const bool hasWebp = QImageWriter::supportedImageFormats().contains(QByteArrayLiteral("webp"));
    const QByteArray targetFmt = hasWebp ? QByteArrayLiteral("webp") : QByteArrayLiteral("jpeg");

    QByteArray encoded;
    QBuffer outBuf(&encoded);
    outBuf.open(QIODevice::WriteOnly);
    QImageWriter writer(&outBuf, targetFmt);
    writer.setQuality(qBound(1, quality, 100));
    if (!writer.write(img) || encoded.isEmpty())
        return out;

    out.bytes = encoded;
    out.mime = hasWebp ? QStringLiteral("image/webp") : QStringLiteral("image/jpeg");
    out.ext = hasWebp ? QStringLiteral(".webp") : QStringLiteral(".jpg");
    out.width = img.width();
    out.height = img.height();
    out.ok = true;
    return out;
}

} // namespace detail

using namespace detail;

QSize Workspace::imageFileSize(const QString &localPath) const
{
    if (localPath.isEmpty())
        return {};
    QImageReader reader(localPath);
    reader.setAutoTransform(true);
    QSize size = reader.size();
    if (size.isValid() && (reader.transformation() & QImageIOHandler::TransformationRotate90))
        size.transpose();
    return size;
}

QString Workspace::ensureMediaObject(const QString &assetId)
{
    if (assetId.isEmpty())
        return {};
    const QString mediaId = objectIdForMedia(assetId);
    auto it = m_objects.find(mediaId);
    if (it != m_objects.end()) {
        if (!it.value().trashedAt.isEmpty()) {
            it.value().trashedAt.clear();
            saveObject(it.value());
        }
        return mediaId;
    }
    Object media;
    media.id = mediaId;
    media.typeId = TypeId::Image;
    media.title = assetId;
    media.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    media.updatedAt = media.createdAt;
    media.hlc = newHlc();
    media.syncStatus = QStringLiteral("pending");
    media.properties.insert(QStringLiteral("asset_id"), assetId);
    saveObject(media);
    return mediaId;
}

void Workspace::setLinkPreviewFetcher(linkpreview::LinkPreviewFetcher *fetcher)
{
    if (m_linkPreview == fetcher)
        return;
    if (m_linkPreview)
        m_linkPreview->deleteLater();
    m_linkPreview = fetcher;
    if (m_linkPreview && m_linkPreview->parent() != this)
        m_linkPreview->setParent(this);
}

linkpreview::LinkPreviewFetcher *Workspace::ensureLinkPreviewFetcher()
{
    if (!m_linkPreview)
        m_linkPreview = new linkpreview::LinkPreviewFetcher(this);
    return m_linkPreview;
}

QString Workspace::createWebLinkFromUrl(const QString &url, const QString &sourceObjectId,
                                        const QString &sourceBlockId)
{
    Q_UNUSED(sourceObjectId);
    Q_UNUSED(sourceBlockId);
    const QString cleanUrl = url.trimmed();
    if (cleanUrl.isEmpty())
        return {};

    // Reuse an existing active WebLink with the same URL when present.
    QString existingId;
    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty() || o.typeId != TypeId::Web)
            continue;
        if (o.propertyString(QStringLiteral("url")) == cleanUrl) {
            existingId = o.id;
            break;
        }
    }

    const QUrl u = linkpreview::normalizeUrl(cleanUrl);
    const QString host = linkpreview::bareHost(u);

    if (existingId.isEmpty()) {
        Object obj;
        obj.id = newUuid();
        obj.typeId = TypeId::Web;
        obj.title = host.isEmpty() ? cleanUrl : host;
        obj.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
        obj.updatedAt = obj.createdAt;
        obj.hlc = newHlc();
        obj.syncStatus = QStringLiteral("pending");
        obj.properties.insert(QStringLiteral("url"), cleanUrl);
        if (!host.isEmpty())
            obj.properties.insert(QStringLiteral("site_name"), host);
        obj.properties.insert(QStringLiteral("reviewed"), false);
        const QString cat = linkpreview::inferWebCategory(u, QString());
        if (!cat.isEmpty())
            obj.properties.insert(QStringLiteral("category"), cat);
        Block b;
        b.id = newUuid();
        b.type = BlockType::Bookmark;
        b.url = cleanUrl;
        b.title = obj.title;
        b.domain = host;
        b.objectId = obj.id;
        obj.blocks.append(b);
        saveObject(obj);
        existingId = obj.id;
    }

    fetchWebLinkMetadata(cleanUrl, existingId);
    return existingId;
}

void Workspace::setWebCoverUrl(const QString &objectId, const QString &imageUrl)
{
    const QString cleanUrl = imageUrl.trimmed();
    if (objectId.isEmpty() || cleanUrl.isEmpty() || !m_objects.contains(objectId))
        return;
    Object next = m_objects.value(objectId);
    if (auto *ed = m_editors.value(objectId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    next.properties.insert(QStringLiteral("thumbnail_url"), cleanUrl);
    saveObject(next);
    if (auto *ed = m_editors.value(objectId)) {
        ed->m_object.properties = next.properties;
        ed->notifyCoverUpdated();
    }
    downloadWebCoverAsset(objectId, cleanUrl);
}

bool Workspace::storeWebCoverBytes(const QString &targetObjectId, const QByteArray &rawBytes,
                                   const QString &sourceUrl, const QString &thumbnailUrl)
{
    if (targetObjectId.isEmpty() || rawBytes.isEmpty())
        return false;
    auto it = m_objects.find(targetObjectId);
    if (it == m_objects.end())
        return false;

    // Product §8.1: ALWAYS keep a local copy of the cover at original resolution, WebP q90.
    const EncodedImage encoded = encodeImageAsWebP(rawBytes, /*maxSidePx=*/0, /*quality=*/90, /*rejectTiny=*/true);
    if (!encoded.ok)
        return false;

    const QString assetId = QString::fromLatin1(
        QCryptographicHash::hash(encoded.bytes, QCryptographicHash::Sha256).toHex().left(20));
    if (!hasAssetFile(assetId)) {
        if (storeAssetFile(assetId, encoded.ext, encoded.bytes).isEmpty())
            return false;
    }

    const QString nowIso = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    const QString coverFileName = QStringLiteral("cover_") + assetId + encoded.ext;
    if (m_store) {
        Store::AssetMeta meta;
        meta.id = assetId;
        meta.name = coverFileName;
        meta.mime = encoded.mime;
        meta.size = encoded.bytes.size();
        meta.createdAt = nowIso;
        m_store->putAssetMeta(meta);
    }
    m_assetMime.insert(assetId, encoded.mime);

    // Canonical media_<assetId> object carries mime/chunks encrypted for cross-device sync (spec/sync.md).
    const QString mediaId = objectIdForMedia(assetId);
    Object media;
    auto existingMedia = m_objects.find(mediaId);
    if (existingMedia != m_objects.end()) {
        media = existingMedia.value();
        media.trashedAt.clear();
    } else {
        media.id = mediaId;
        media.createdAt = nowIso;
    }
    media.typeId = TypeId::Image;
    if (media.title.isEmpty() || media.title == assetId)
        media.title = it.value().title.isEmpty() ? coverFileName : it.value().title;
    media.updatedAt = nowIso;
    media.properties.insert(QStringLiteral("asset_id"), assetId);
    media.properties.insert(QStringLiteral("mime"), encoded.mime);
    media.properties.insert(QStringLiteral("file_name"), coverFileName);
    media.properties.insert(QStringLiteral("size"), double(encoded.bytes.size()));
    media.properties.insert(QStringLiteral("chunks"),
                            double((encoded.bytes.size() + vault::kAssetChunkBytes - 1)
                                   / vault::kAssetChunkBytes));
    media.properties.insert(QStringLiteral("chunk_size"), double(vault::kAssetChunkBytes));
    media.properties.insert(QStringLiteral("total_plain_size"), double(encoded.bytes.size()));
    if (!sourceUrl.isEmpty())
        media.properties.insert(QStringLiteral("source_url"), sourceUrl);
    if (!media.properties.contains(QStringLiteral("colors"))) {
        const QStringList colors = dominantColorIds(QImage::fromData(encoded.bytes));
        if (!colors.isEmpty())
            media.properties.insert(QStringLiteral("colors"), QJsonArray::fromStringList(colors));
    }
    saveObject(media);

    if (m_store) {
        m_store->queueUpload({ assetId, coverFileName, encoded.mime, mediaId });
        uploadPendingAssets();
    }

    it = m_objects.find(targetObjectId);
    if (it == m_objects.end())
        return false;
    Object next = it.value();
    if (auto *ed = m_editors.value(targetObjectId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    next.properties.insert(QStringLiteral("cover"),
                           QJsonObject{ { "asset_id", assetId }, { "crop_offset_y", 0.5 }, { "is_fit", false } });
    if (!thumbnailUrl.isEmpty())
        next.properties.insert(QStringLiteral("thumbnail_url"), thumbnailUrl);
    saveObject(next);
    if (auto *ed = m_editors.value(targetObjectId)) {
        ed->m_object.properties = next.properties;
        ed->notifyCoverUpdated();
    }
    return true;
}

void Workspace::downloadWebCoverAsset(const QString &targetObjectId, const QString &imageUrl)
{
    const QUrl u(linkpreview::cleanImageUrl(imageUrl));
    if (!u.isValid() || (u.scheme() != QLatin1String("http") && u.scheme() != QLatin1String("https")))
        return;

    QPointer<Workspace> self(this);
    ensureLinkPreviewFetcher()->downloadImage(u, [self, targetObjectId, imageUrl](const QByteArray &bytes) {
        if (!self || self->m_shuttingDown || bytes.isEmpty())
            return;
        QString sourceUrl;
        if (auto it = self->m_objects.constFind(targetObjectId); it != self->m_objects.constEnd())
            sourceUrl = it.value().propertyString(QStringLiteral("url"));
        self->storeWebCoverBytes(targetObjectId, bytes, sourceUrl, imageUrl);
    });
}

void Workspace::applyWebLinkPreview(const QString &weblinkId, const QString &originalUrl,
                                    const linkpreview::LinkPreviewData &preview)
{
    const QUrl origUrl = linkpreview::normalizeUrl(originalUrl);
    const QUrl finalUrl = preview.url.isValid() ? preview.url : origUrl;
    const QString resolvedTitle = !preview.title.isEmpty()
        ? preview.title
        : linkpreview::bareHost(finalUrl);

    // Update all open editors holding bookmarks to this url or weblinkId.
    for (EditorModel *ed : m_editors) {
        if (ed)
            ed->applyBookmarkPreview(weblinkId, originalUrl, resolvedTitle, preview.description,
                                     preview.imageUrl, preview.faviconUrl);
    }

    auto it = m_objects.find(weblinkId);
    if (it == m_objects.end())
        return;

    Object wl = it.value();
    if (auto *ed = m_editors.value(weblinkId)) {
        wl.blocks = ed->m_blocks;
        wl.title = ed->m_title;
    }

    if (!resolvedTitle.isEmpty()
        && linkpreview::shouldReplacePlaceholderTitle(wl.title, origUrl, finalUrl)) {
        wl.title = resolvedTitle;
    }
    if (!preview.description.isEmpty())
        wl.properties.insert(QStringLiteral("description"), preview.description);
    if (!preview.imageUrl.isEmpty())
        wl.properties.insert(QStringLiteral("thumbnail_url"), preview.imageUrl);
    if (!preview.faviconUrl.isEmpty())
        wl.properties.insert(QStringLiteral("favicon_url"), preview.faviconUrl);
    if (!preview.siteName.isEmpty()
        && linkpreview::shouldReplacePlaceholderSiteName(
            wl.propertyString(QStringLiteral("site_name")), origUrl, finalUrl)) {
        wl.properties.insert(QStringLiteral("site_name"), preview.siteName);
    }
    if (wl.propertyString(QStringLiteral("category")).isEmpty()) {
        const QString cat = linkpreview::inferWebCategory(finalUrl, preview.ogType, preview.mediaType);
        if (!cat.isEmpty())
            wl.properties.insert(QStringLiteral("category"), cat);
    }
    if (wl.propertyStringList(QStringLiteral("topics")).isEmpty()) {
        const QStringList inferredTopics = linkpreview::inferWebTopics(
            finalUrl, wl.title, wl.propertyString(QStringLiteral("description")));
        if (!inferredTopics.isEmpty())
            wl.properties.insert(QStringLiteral("topics"), QJsonArray::fromStringList(inferredTopics));
    }

    for (Block &b : wl.blocks) {
        if (b.type == BlockType::Bookmark) {
            if (!resolvedTitle.isEmpty()) b.title = resolvedTitle;
            if (!preview.description.isEmpty()) b.description = preview.description;
            if (!preview.imageUrl.isEmpty()) b.thumbnailUrl = preview.imageUrl;
            if (!preview.faviconUrl.isEmpty()) b.faviconUrl = preview.faviconUrl;
        }
    }

    saveObject(wl);
    if (auto *ed = m_editors.value(weblinkId)) {
        ed->m_object.properties = wl.properties;
        ed->m_object.title = wl.title;
        if (ed->m_title != wl.title) {
            ed->m_title = wl.title;
            emit ed->titleChanged();
        }
        ed->notifyCoverUpdated();
    }
    emit objectPropertiesChanged(weblinkId);

    if (!preview.imageUrl.isEmpty() && wl.coverAssetId().isEmpty())
        downloadWebCoverAsset(weblinkId, preview.imageUrl);
}

void Workspace::fetchWebLinkMetadata(const QString &url, const QString &weblinkId)
{
    const QUrl u = linkpreview::normalizeUrl(url);
    if (!u.isValid())
        return;

    linkpreview::LinkPreviewOptions opts;
    opts.allowThirdPartyProxies =
        QSettings().value(QStringLiteral("network/allow_third_party_link_proxies"), true).toBool();

    QPointer<Workspace> self(this);
    ensureLinkPreviewFetcher()->fetch(
        url, opts, [self, url, weblinkId](const std::optional<linkpreview::LinkPreviewData> &preview) {
            if (!self || self->m_shuttingDown || !preview.has_value())
                return;
            self->applyWebLinkPreview(weblinkId, url, *preview);
        });
}

namespace {

QByteArray decodeBase64Payload(const QString &raw)
{
    QString s = raw.trimmed();
    const qsizetype comma = s.indexOf(QLatin1Char(','));
    if (s.startsWith(QLatin1String("data:"), Qt::CaseInsensitive) && comma > 0)
        s = s.mid(comma + 1);
    return QByteArray::fromBase64(s.toLatin1());
}

QString sanitizeFileSlug(const QString &raw, const QString &fallback)
{
    QString out;
    out.reserve(qMin<qsizetype>(raw.size(), 64));
    bool prevDash = false;
    for (const QChar c : raw.trimmed()) {
        if (out.size() >= 56)
            break;
        if (c.isLetterOrNumber()) {
            out.append(c.toLower());
            prevDash = false;
        } else if (!prevDash && !out.isEmpty()) {
            out.append(QLatin1Char('-'));
            prevDash = true;
        }
    }
    while (out.endsWith(QLatin1Char('-')))
        out.chop(1);
    return out.isEmpty() ? fallback : out;
}

Block makeTextBlock(const QString &type, const QString &text, const QString &href = QString())
{
    Block b;
    b.id = newUuid();
    b.type = type;
    Run r;
    r.text = text;
    if (!href.isEmpty())
        r.href = href;
    b.runs.append(r);
    return b;
}

QStringList cleanStringList(const QJsonValue &val)
{
    QStringList out;
    for (const QJsonValue &v : val.toArray()) {
        const QString s = v.toString().trimmed();
        if (!s.isEmpty() && !out.contains(s))
            out.append(s);
    }
    return out;
}

} // namespace

QJsonObject Workspace::captureMeta() const
{
    QJsonArray tags;
    for (const QVariant &v : allTagsWithCounts()) {
        const QVariantMap m = v.toMap();
        tags.append(QJsonObject{
            { QStringLiteral("name"), m.value(QStringLiteral("name")).toString() },
            { QStringLiteral("color"), m.value(QStringLiteral("color")).toString() },
            { QStringLiteral("count"), m.value(QStringLiteral("count")).toInt() },
        });
    }

    QJsonArray categories;
    QJsonArray topics;
    const QVariantList webProps = typeProperties(QStringLiteral("web"));
    for (const QVariant &pv : webProps) {
        const QVariantMap pm = pv.toMap();
        const QString key = pm.value(QStringLiteral("key")).toString();
        if (key != QLatin1String("category") && key != QLatin1String("topics"))
            continue;
        QJsonArray *target = (key == QLatin1String("category")) ? &categories : &topics;
        for (const QVariant &ov : pm.value(QStringLiteral("options")).toList()) {
            const QVariantMap om = ov.toMap();
            target->append(QJsonObject{
                { QStringLiteral("id"), om.value(QStringLiteral("id")).toString() },
                { QStringLiteral("label"), om.value(QStringLiteral("label")).toString() },
                { QStringLiteral("color"), om.value(QStringLiteral("color")).toString() },
            });
        }
    }

    return QJsonObject{
        { QStringLiteral("ok"), true },
        { QStringLiteral("tags"), tags },
        { QStringLiteral("categories"), categories },
        { QStringLiteral("topics"), topics },
    };
}

QJsonObject Workspace::lookupWebLink(const QString &url) const
{
    const QString cleanUrl = url.trimmed();
    if (cleanUrl.isEmpty())
        return QJsonObject{ { QStringLiteral("ok"), true }, { QStringLiteral("found"), false } };

    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty() || o.typeId != TypeId::Web)
            continue;
        if (o.propertyString(QStringLiteral("url")) == cleanUrl) {
            return QJsonObject{
                { QStringLiteral("ok"), true },
                { QStringLiteral("found"), true },
                { QStringLiteral("id"), o.id },
                { QStringLiteral("title"), o.title },
                { QStringLiteral("description"), o.propertyString(QStringLiteral("description")) },
                { QStringLiteral("category"), o.propertyString(QStringLiteral("category")) },
                { QStringLiteral("topics"), QJsonArray::fromStringList(o.propertyStringList(QStringLiteral("topics"))) },
                { QStringLiteral("tags"), QJsonArray::fromStringList(o.propertyStringList(QStringLiteral("tags"))) },
                { QStringLiteral("reviewed"), o.properties.value(QStringLiteral("reviewed")).toBool(false) },
                { QStringLiteral("has_cover"), !o.coverAssetId().isEmpty() },
            };
        }
    }
    return QJsonObject{ { QStringLiteral("ok"), true }, { QStringLiteral("found"), false } };
}

QJsonObject Workspace::captureWebLink(const QJsonObject &req)
{
    const QString cleanUrl = req.value(QStringLiteral("url")).toString().trimmed();
    const QUrl u(cleanUrl);
    if (cleanUrl.isEmpty() || !u.isValid()
        || (u.scheme() != QLatin1String("http") && u.scheme() != QLatin1String("https"))) {
        return QJsonObject{ { QStringLiteral("ok"), false },
                            { QStringLiteral("error"), QStringLiteral("invalid_url") } };
    }

    QString host = u.host();
    if (host.startsWith(QLatin1String("www.")))
        host = host.mid(4);

    const QString reqTitle = req.value(QStringLiteral("title")).toString().trimmed();
    const QString reqDesc = req.value(QStringLiteral("description")).toString().trimmed();
    const QString reqSite = req.value(QStringLiteral("site_name")).toString().trimmed();
    const QString reqOgType = req.value(QStringLiteral("og_type")).toString().trimmed();
    const QString reqCategory = req.value(QStringLiteral("category")).toString().trimmed();
    const QStringList reqTopics = cleanStringList(req.value(QStringLiteral("topics")));
    const QStringList reqTags = cleanStringList(req.value(QStringLiteral("tags")));
    const QString reqThumbUrl = req.value(QStringLiteral("thumbnail_url")).toString().trimmed();
    const QString reqFaviconUrl = req.value(QStringLiteral("favicon_url")).toString().trimmed();
    const QString reqHighlight = req.value(QStringLiteral("highlight")).toString().trimmed();
    const QString reqNote = req.value(QStringLiteral("note")).toString().trimmed();
    const QString coverB64 = req.value(QStringLiteral("cover_base64")).toString();
    const bool hasReviewed = req.contains(QStringLiteral("reviewed"))
        && req.value(QStringLiteral("reviewed")).isBool();
    const bool reviewedVal = req.value(QStringLiteral("reviewed")).toBool(false);
    const bool fetchFallback = req.value(QStringLiteral("fetch_metadata")).toBool(true);

    QString existingId;
    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty() || o.typeId != TypeId::Web)
            continue;
        if (o.propertyString(QStringLiteral("url")) == cleanUrl) {
            existingId = o.id;
            break;
        }
    }

    const bool existed = !existingId.isEmpty();
    Object obj;
    if (existed) {
        obj = m_objects.value(existingId);
        if (auto *ed = m_editors.value(existingId)) {
            obj.blocks = ed->m_blocks;
            obj.title = ed->m_title;
        }
    } else {
        obj.id = newUuid();
        obj.typeId = TypeId::Web;
        obj.title = !reqTitle.isEmpty() ? reqTitle : (host.isEmpty() ? cleanUrl : host);
        obj.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
        obj.updatedAt = obj.createdAt;
        obj.hlc = newHlc();
        obj.syncStatus = QStringLiteral("pending");
        obj.properties.insert(QStringLiteral("url"), cleanUrl);
        obj.properties.insert(QStringLiteral("reviewed"), reviewedVal);

        Block b;
        b.id = newUuid();
        b.type = BlockType::Bookmark;
        b.url = cleanUrl;
        b.title = obj.title;
        b.description = reqDesc;
        b.domain = !reqSite.isEmpty() ? reqSite : host;
        b.thumbnailUrl = reqThumbUrl;
        b.faviconUrl = reqFaviconUrl;
        b.objectId = obj.id;
        obj.blocks.append(b);
    }

    if (!reqTitle.isEmpty())
        obj.title = reqTitle;
    if (!reqDesc.isEmpty())
        obj.properties.insert(QStringLiteral("description"), reqDesc);
    if (!reqSite.isEmpty())
        obj.properties.insert(QStringLiteral("site_name"), reqSite);
    else if (obj.propertyString(QStringLiteral("site_name")).isEmpty() && !host.isEmpty())
        obj.properties.insert(QStringLiteral("site_name"), host);
    if (!reqThumbUrl.isEmpty())
        obj.properties.insert(QStringLiteral("thumbnail_url"), reqThumbUrl);
    if (!reqFaviconUrl.isEmpty())
        obj.properties.insert(QStringLiteral("favicon_url"), reqFaviconUrl);
    if (hasReviewed)
        obj.properties.insert(QStringLiteral("reviewed"), reviewedVal);

    if (!reqCategory.isEmpty()) {
        obj.properties.insert(QStringLiteral("category"), reqCategory);
    } else if (obj.propertyString(QStringLiteral("category")).isEmpty()) {
        const QString cat = linkpreview::inferWebCategory(u, reqOgType);
        if (!cat.isEmpty())
            obj.properties.insert(QStringLiteral("category"), cat);
    }

    if (!reqTopics.isEmpty()) {
        obj.properties.insert(QStringLiteral("topics"), QJsonArray::fromStringList(reqTopics));
    } else if (obj.propertyStringList(QStringLiteral("topics")).isEmpty()) {
        const QStringList inferred = linkpreview::inferWebTopics(u, obj.title, obj.propertyString(QStringLiteral("description")));
        if (!inferred.isEmpty())
            obj.properties.insert(QStringLiteral("topics"), QJsonArray::fromStringList(inferred));
    }

    // Explicit user-selected tags only (product §4.5: never auto-tag from descriptions).
    if (!reqTags.isEmpty()) {
        QStringList mergedTags = obj.propertyStringList(QStringLiteral("tags"));
        for (const QString &rawTag : reqTags) {
            const QString cleanTag = QString(rawTag).remove(QLatin1Char('#')).trimmed();
            if (cleanTag.isEmpty())
                continue;
            ensureTagObject(cleanTag);
            if (!mergedTags.contains(cleanTag, Qt::CaseInsensitive))
                mergedTags.append(cleanTag);
        }
        obj.properties.insert(QStringLiteral("tags"), QJsonArray::fromStringList(mergedTags));
    }

    for (Block &b : obj.blocks) {
        if (b.type == BlockType::Bookmark) {
            if (!obj.title.isEmpty()) b.title = obj.title;
            if (!reqDesc.isEmpty()) b.description = reqDesc;
            if (!reqThumbUrl.isEmpty()) b.thumbnailUrl = reqThumbUrl;
            if (!reqFaviconUrl.isEmpty()) b.faviconUrl = reqFaviconUrl;
            break;
        }
    }

    if (!reqHighlight.isEmpty())
        obj.blocks.append(makeTextBlock(BlockType::Quote, reqHighlight));
    if (!reqNote.isEmpty()) {
        for (const QString &para : reqNote.split(QRegularExpression(QStringLiteral("\\r?\\n\\s*\\r?\\n")), Qt::SkipEmptyParts)) {
            const QString t = para.trimmed();
            if (!t.isEmpty())
                obj.blocks.append(makeTextBlock(BlockType::Paragraph, t));
        }
    }

    saveObject(obj);
    if (auto *ed = m_editors.value(obj.id)) {
        ed->load(m_objects.value(obj.id));
    }
    notifyObjectListChanged();

    bool coverSaved = false;
    if (!coverB64.isEmpty()) {
        const QByteArray coverBytes = decodeBase64Payload(coverB64);
        if (!coverBytes.isEmpty())
            coverSaved = storeWebCoverBytes(obj.id, coverBytes, cleanUrl, reqThumbUrl);
    } else if (!reqThumbUrl.isEmpty() && obj.coverAssetId().isEmpty()) {
        downloadWebCoverAsset(obj.id, reqThumbUrl);
    } else if (fetchFallback && !existed && reqDesc.isEmpty() && reqThumbUrl.isEmpty()) {
        fetchWebLinkMetadata(cleanUrl, obj.id);
    }

    emit browserCaptureReceived(QStringLiteral("web"), obj.title, obj.id);
    return QJsonObject{
        { QStringLiteral("ok"), true },
        { QStringLiteral("id"), obj.id },
        { QStringLiteral("existing"), existed },
        { QStringLiteral("title"), obj.title },
        { QStringLiteral("cover_saved"), coverSaved },
    };
}

QJsonObject Workspace::captureImage(const QJsonObject &req)
{
    const QByteArray rawBytes = decodeBase64Payload(req.value(QStringLiteral("image_base64")).toString());
    if (rawBytes.isEmpty()) {
        return QJsonObject{ { QStringLiteral("ok"), false },
                            { QStringLiteral("error"), QStringLiteral("empty_image") } };
    }

    const int maxSide = qBound(0, req.value(QStringLiteral("max_side")).toInt(1920), 4096);
    const int quality = qBound(40, req.value(QStringLiteral("quality")).toInt(82), 98);
    const EncodedImage encoded = encodeImageAsWebP(rawBytes, maxSide, quality, /*rejectTiny=*/false);
    if (!encoded.ok) {
        return QJsonObject{ { QStringLiteral("ok"), false },
                            { QStringLiteral("error"), QStringLiteral("invalid_image") } };
    }

    const QString reqTitle = req.value(QStringLiteral("title")).toString().trimmed();
    const QString sourceUrl = req.value(QStringLiteral("source_url")).toString().trimmed();
    const QString imageUrl = req.value(QStringLiteral("image_url")).toString().trimmed();
    const QString reqNote = req.value(QStringLiteral("note")).toString().trimmed();
    const QStringList reqTags = cleanStringList(req.value(QStringLiteral("tags")));
    const bool reviewed = req.value(QStringLiteral("reviewed")).toBool(false);

    const QString assetId = newUuid();
    const QString slug = sanitizeFileSlug(reqTitle, QStringLiteral("web-image"));
    const QString fileName = slug + encoded.ext;
    if (storeAssetFile(assetId, encoded.ext, encoded.bytes).isEmpty()) {
        return QJsonObject{ { QStringLiteral("ok"), false },
                            { QStringLiteral("error"), QStringLiteral("asset_store_failed") } };
    }

    const QString nowIso = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    if (m_store) {
        Store::AssetMeta meta;
        meta.id = assetId;
        meta.name = fileName;
        meta.mime = encoded.mime;
        meta.size = encoded.bytes.size();
        meta.createdAt = nowIso;
        m_store->putAssetMeta(meta);
    }
    m_assetMime.insert(assetId, encoded.mime);

    const QString mediaId = objectIdForMedia(assetId);
    Object media;
    media.id = mediaId;
    media.typeId = TypeId::Image;
    media.title = reqTitle.isEmpty() ? fileName : reqTitle;
    media.createdAt = nowIso;
    media.updatedAt = nowIso;
    media.properties.insert(QStringLiteral("asset_id"), assetId);
    media.properties.insert(QStringLiteral("mime"), encoded.mime);
    media.properties.insert(QStringLiteral("file_name"), fileName);
    media.properties.insert(QStringLiteral("size"), double(encoded.bytes.size()));
    media.properties.insert(QStringLiteral("chunks"),
                            double((encoded.bytes.size() + vault::kAssetChunkBytes - 1)
                                   / vault::kAssetChunkBytes));
    media.properties.insert(QStringLiteral("chunk_size"), double(vault::kAssetChunkBytes));
    media.properties.insert(QStringLiteral("total_plain_size"), double(encoded.bytes.size()));
    media.properties.insert(QStringLiteral("reviewed"), reviewed);
    if (!sourceUrl.isEmpty())
        media.properties.insert(QStringLiteral("source_url"), sourceUrl);
    if (!imageUrl.isEmpty())
        media.properties.insert(QStringLiteral("image_url"), imageUrl);

    const QStringList colors = dominantColorIds(QImage::fromData(encoded.bytes));
    if (!colors.isEmpty())
        media.properties.insert(QStringLiteral("colors"), QJsonArray::fromStringList(colors));

    if (!reqTags.isEmpty()) {
        QStringList cleanTags;
        for (const QString &rawTag : reqTags) {
            const QString cleanTag = QString(rawTag).remove(QLatin1Char('#')).trimmed();
            if (cleanTag.isEmpty())
                continue;
            ensureTagObject(cleanTag);
            if (!cleanTags.contains(cleanTag, Qt::CaseInsensitive))
                cleanTags.append(cleanTag);
        }
        media.properties.insert(QStringLiteral("tags"), QJsonArray::fromStringList(cleanTags));
    }

    if (!reqNote.isEmpty()) {
        for (const QString &para : reqNote.split(QRegularExpression(QStringLiteral("\\r?\\n\\s*\\r?\\n")), Qt::SkipEmptyParts)) {
            const QString t = para.trimmed();
            if (!t.isEmpty())
                media.blocks.append(makeTextBlock(BlockType::Paragraph, t));
        }
    }

    saveObject(media);
    notifyObjectListChanged();

    if (m_store) {
        m_store->queueUpload({ assetId, fileName, encoded.mime, mediaId });
        uploadPendingAssets();
    }

    emit browserCaptureReceived(QStringLiteral("image"), media.title, media.id);
    return QJsonObject{
        { QStringLiteral("ok"), true },
        { QStringLiteral("id"), media.id },
        { QStringLiteral("asset_id"), assetId },
        { QStringLiteral("title"), media.title },
        { QStringLiteral("mime"), encoded.mime },
        { QStringLiteral("size"), int(encoded.bytes.size()) },
        { QStringLiteral("width"), encoded.width },
        { QStringLiteral("height"), encoded.height },
    };
}

QJsonObject Workspace::captureNote(const QJsonObject &req)
{
    const QString text = req.value(QStringLiteral("text")).toString().trimmed();
    QString title = req.value(QStringLiteral("title")).toString().trimmed();
    const QString sourceUrl = req.value(QStringLiteral("source_url")).toString().trimmed();
    const QStringList reqTags = cleanStringList(req.value(QStringLiteral("tags")));
    const bool asQuote = req.value(QStringLiteral("as_quote")).toBool(false);
    const bool reviewed = req.value(QStringLiteral("reviewed")).toBool(false);

    if (text.isEmpty() && title.isEmpty()) {
        return QJsonObject{ { QStringLiteral("ok"), false },
                            { QStringLiteral("error"), QStringLiteral("empty_note") } };
    }
    if (title.isEmpty())
        title = text.section(QLatin1Char('\n'), 0, 0).left(80).trimmed();

    Object note;
    note.id = newUuid();
    note.typeId = TypeId::Note;
    note.title = title;
    note.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    note.updatedAt = note.createdAt;
    note.hlc = newHlc();
    note.syncStatus = QStringLiteral("pending");
    note.properties.insert(QStringLiteral("reviewed"), reviewed);
    if (!sourceUrl.isEmpty())
        note.properties.insert(QStringLiteral("source_url"), sourceUrl);

    if (!reqTags.isEmpty()) {
        QStringList cleanTags;
        for (const QString &rawTag : reqTags) {
            const QString cleanTag = QString(rawTag).remove(QLatin1Char('#')).trimmed();
            if (cleanTag.isEmpty())
                continue;
            ensureTagObject(cleanTag);
            if (!cleanTags.contains(cleanTag, Qt::CaseInsensitive))
                cleanTags.append(cleanTag);
        }
        note.properties.insert(QStringLiteral("tags"), QJsonArray::fromStringList(cleanTags));
    }

    if (!text.isEmpty()) {
        const QString blockType = asQuote ? QString::fromLatin1(BlockType::Quote)
                                          : QString::fromLatin1(BlockType::Paragraph);
        for (const QString &para : text.split(QRegularExpression(QStringLiteral("\\r?\\n\\s*\\r?\\n")), Qt::SkipEmptyParts)) {
            const QString t = para.trimmed();
            if (!t.isEmpty())
                note.blocks.append(makeTextBlock(blockType, t));
        }
    }
    if (!sourceUrl.isEmpty()) {
        const QUrl u(sourceUrl);
        if (u.isValid() && (u.scheme() == QLatin1String("http") || u.scheme() == QLatin1String("https")))
            note.blocks.append(makeTextBlock(BlockType::Paragraph, sourceUrl, sourceUrl));
    }

    saveObject(note);
    notifyObjectListChanged();
    emit browserCaptureReceived(QStringLiteral("note"), note.title, note.id);
    return QJsonObject{
        { QStringLiteral("ok"), true },
        { QStringLiteral("id"), note.id },
        { QStringLiteral("title"), note.title },
    };
}

} // namespace pkm
