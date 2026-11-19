// Workspace: media objects and web links (metadata and covers).
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
#include <QImageReader>
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

namespace {
QString decodeHtmlEntities(QString s)
{
    s.replace(QLatin1String("&amp;"), QLatin1String("&"));
    s.replace(QLatin1String("&lt;"), QLatin1String("<"));
    s.replace(QLatin1String("&gt;"), QLatin1String(">"));
    s.replace(QLatin1String("&quot;"), QLatin1String("\""));
    s.replace(QLatin1String("&#39;"), QLatin1String("'"));
    s.replace(QLatin1String("&apos;"), QLatin1String("'"));
    s.replace(QLatin1String("&nbsp;"), QLatin1String(" "));
    return s.simplified();
}

struct OgMeta {
    QString title;
    QString description;
    QString thumbnailUrl;
    QString faviconUrl;
};

OgMeta parseOgFromHtml(const QByteArray &raw, const QUrl &baseUrl)
{
    OgMeta meta;
    const QString html = QString::fromUtf8(raw.left(512 * 1024));
    static const QRegularExpression metaTagRe(
        QStringLiteral("<meta\\b([^>]+)>"), QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression attrRe(
        QStringLiteral("([a-zA-Z0-9_:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"));

    auto extractAttrs = [&](const QString &tagBody) {
        QHash<QString, QString> map;
        auto it = attrRe.globalMatch(tagBody);
        while (it.hasNext()) {
            const auto m = it.next();
            const QString k = m.captured(1).toLower();
            QString v = m.captured(2);
            if (v.isEmpty()) v = m.captured(3);
            if (v.isEmpty()) v = m.captured(4);
            map.insert(k, v);
        }
        return map;
    };

    auto mIt = metaTagRe.globalMatch(html);
    while (mIt.hasNext()) {
        const auto attrs = extractAttrs(mIt.next().captured(1));
        const QString prop = attrs.value(QStringLiteral("property")).toLower();
        const QString name = attrs.value(QStringLiteral("name")).toLower();
        const QString content = decodeHtmlEntities(attrs.value(QStringLiteral("content")));
        if (content.isEmpty())
            continue;
        if ((prop == QLatin1String("og:title") || name == QLatin1String("twitter:title")) && meta.title.isEmpty())
            meta.title = content;
        else if ((prop == QLatin1String("og:description") || name == QLatin1String("description")
                  || name == QLatin1String("twitter:description")) && meta.description.isEmpty())
            meta.description = content;
        else if ((prop == QLatin1String("og:image") || prop == QLatin1String("og:image:secure_url")
                  || name == QLatin1String("twitter:image")) && meta.thumbnailUrl.isEmpty())
            meta.thumbnailUrl = baseUrl.resolved(QUrl(content)).toString();
    }

    if (meta.title.isEmpty()) {
        static const QRegularExpression titleRe(
            QStringLiteral("<title[^>]*>([^<]+)</title>"), QRegularExpression::CaseInsensitiveOption);
        const auto tm = titleRe.match(html);
        if (tm.hasMatch())
            meta.title = decodeHtmlEntities(tm.captured(1));
    }

    static const QRegularExpression linkRe(
        QStringLiteral("<link\\b([^>]+)>"), QRegularExpression::CaseInsensitiveOption);
    auto lIt = linkRe.globalMatch(html);
    while (lIt.hasNext()) {
        const auto attrs = extractAttrs(lIt.next().captured(1));
        const QString rel = attrs.value(QStringLiteral("rel")).toLower();
        const QString href = attrs.value(QStringLiteral("href"));
        if (!href.isEmpty() && (rel.contains(QLatin1String("icon")))) {
            meta.faviconUrl = baseUrl.resolved(QUrl(decodeHtmlEntities(href))).toString();
            break;
        }
    }
    if (meta.faviconUrl.isEmpty() && baseUrl.isValid() && !baseUrl.host().isEmpty())
        meta.faviconUrl = QStringLiteral("%1://%2/favicon.ico").arg(baseUrl.scheme(), baseUrl.host());

    return meta;
}
} // namespace

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

    const QUrl u(cleanUrl);
    QString host = u.host();
    if (host.startsWith(QLatin1String("www.")))
        host = host.mid(4);

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

void Workspace::downloadWebCoverAsset(const QString &targetObjectId, const QString &imageUrl)
{
    const QUrl u(imageUrl);
    if (!u.isValid() || (u.scheme() != QLatin1String("http") && u.scheme() != QLatin1String("https")))
        return;
    if (!m_webNet)
        m_webNet = new QNetworkAccessManager(this);
    QNetworkRequest req(u);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Mozilla/5.0 YAOP/1.0"));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setTransferTimeout(6000);
    QNetworkReply *reply = m_webNet->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, targetObjectId, imageUrl]() {
        reply->deleteLater();
        if (m_shuttingDown || reply->error() != QNetworkReply::NoError)
            return;
        const QByteArray bytes = reply->read(8 * 1024 * 1024);
        if (bytes.isEmpty())
            return;
        QImage img;
        if (!img.loadFromData(bytes))
            return;
        const QString contentType = reply->header(QNetworkRequest::ContentTypeHeader).toString().section(QLatin1Char(';'), 0, 0).trimmed().toLower();
        QString ext = assetExtensionForMime(contentType);
        QString mime = contentType;
        if (ext.isEmpty()) {
            ext = QStringLiteral(".jpg");
            mime = QStringLiteral("image/jpeg");
        }
        const QString assetId = QString::fromLatin1(
            QCryptographicHash::hash(bytes, QCryptographicHash::Sha256).toHex().left(20));
        if (!hasAssetFile(assetId))
            storeAssetFile(assetId, ext, bytes);
        m_assetMime.insert(assetId, mime);
        m_store->queueUpload({ assetId, QStringLiteral("cover") + ext, mime, objectIdForMedia(assetId) });
        uploadPendingAssets();

        auto it = m_objects.find(targetObjectId);
        if (it == m_objects.end())
            return;
        Object next = it.value();
        if (auto *ed = m_editors.value(targetObjectId)) {
            next.blocks = ed->m_blocks;
            next.title = ed->m_title;
        }
        next.properties.insert(QStringLiteral("cover"),
                               QJsonObject{ { "asset_id", assetId }, { "crop_offset_y", 0.5 }, { "is_fit", false } });
        next.properties.insert(QStringLiteral("thumbnail_url"), imageUrl);
        saveObject(next);
        if (auto *ed = m_editors.value(targetObjectId)) {
            ed->m_object.properties = next.properties;
            ed->notifyCoverUpdated();
        }
    });
}

void Workspace::fetchWebLinkMetadata(const QString &url, const QString &weblinkId)
{
    const QUrl u(url);
    if (!u.isValid() || (u.scheme() != QLatin1String("http") && u.scheme() != QLatin1String("https")))
        return;
    if (!m_webNet)
        m_webNet = new QNetworkAccessManager(this);
    QNetworkRequest req(u);
    req.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Mozilla/5.0 YAOP/1.0"));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setTransferTimeout(6000);
    QNetworkReply *reply = m_webNet->get(req);
    connect(reply, &QNetworkReply::finished, this, [this, reply, url, weblinkId]() {
        reply->deleteLater();
        if (m_shuttingDown || reply->error() != QNetworkReply::NoError)
            return;
        const QByteArray bytes = reply->read(512 * 1024);
        if (bytes.isEmpty())
            return;
        const OgMeta meta = parseOgFromHtml(bytes, reply->url());
        const QString title = meta.title.isEmpty() ? reply->url().host() : meta.title;

        // Update all open editors holding bookmarks to this url or weblinkId
        for (EditorModel *ed : m_editors) {
            if (ed)
                ed->applyBookmarkPreview(weblinkId, url, title, meta.description,
                                         meta.thumbnailUrl, meta.faviconUrl);
        }

        // Update bookmark blocks inside stored objects + the weblink object itself
        auto it = m_objects.find(weblinkId);
        if (it != m_objects.end()) {
            Object wl = it.value();
            if (auto *ed = m_editors.value(weblinkId)) {
                wl.blocks = ed->m_blocks;
                wl.title = ed->m_title;
            }
            if (!title.isEmpty() && (wl.title.isEmpty() || wl.title == url || wl.title == reply->url().host() || wl.title.startsWith(QLatin1String("http"))))
                wl.title = title;
            if (!meta.description.isEmpty())
                wl.properties.insert(QStringLiteral("description"), meta.description);
            if (!meta.thumbnailUrl.isEmpty())
                wl.properties.insert(QStringLiteral("thumbnail_url"), meta.thumbnailUrl);
            if (!meta.faviconUrl.isEmpty())
                wl.properties.insert(QStringLiteral("favicon_url"), meta.faviconUrl);
            for (Block &b : wl.blocks) {
                if (b.type == BlockType::Bookmark) {
                    if (!title.isEmpty()) b.title = title;
                    if (!meta.description.isEmpty()) b.description = meta.description;
                    if (!meta.thumbnailUrl.isEmpty()) b.thumbnailUrl = meta.thumbnailUrl;
                    if (!meta.faviconUrl.isEmpty()) b.faviconUrl = meta.faviconUrl;
                }
            }
            saveObject(wl);
            if (auto *ed = m_editors.value(weblinkId)) {
                ed->m_object.properties = wl.properties;
                if (ed->m_title != wl.title) {
                    ed->m_title = wl.title;
                    emit ed->titleChanged();
                }
                ed->notifyCoverUpdated();
            }
            if (!meta.thumbnailUrl.isEmpty())
                downloadWebCoverAsset(weblinkId, meta.thumbnailUrl);
        }
    });
}

} // namespace pkm
