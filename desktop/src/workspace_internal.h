#pragma once
// Helpers shared by the workspace_*.cpp files (one Workspace class, split by concern).

#include "workspace.h"
#include <QByteArray>
#include <QHash>
#include <QJsonArray>
#include <QJsonValue>
#include <QLocale>
#include <QString>
#include <QVariantMap>

namespace pkm::detail {

struct EncodedImage {
    QByteArray bytes;
    QString mime;
    QString ext;
    int width = 0;
    int height = 0;
    bool ok = false;
};

// Decodes raw image bytes (EXIF auto-transform), optionally rejects tiny tracking pixels (<32px),
// downscales proportionally to maxSidePx (when >0), and encodes as WebP (JPEG fallback only if the
// Qt runtime lacks the webp plugin). Preserves already-WebP bytes when no resize is needed.
EncodedImage encodeImageAsWebP(const QByteArray &rawBytes, int maxSidePx, int quality,
                               bool rejectTiny = false);

// Language for standard-type labels (spec/types.md): Spanish or English, from the system locale.
inline QString uiLanguage()
{
    return QLocale::system().language() == QLocale::Spanish ? QStringLiteral("es") : QStringLiteral("en");
}

inline QString assetExtensionForMime(const QString &mime)
{
    if (mime.startsWith(QLatin1String("image/jpeg")))
        return QStringLiteral(".jpg");
    if (mime.startsWith(QLatin1String("image/png")))
        return QStringLiteral(".png");
    if (mime.startsWith(QLatin1String("image/webp")))
        return QStringLiteral(".webp");
    if (mime.startsWith(QLatin1String("image/gif")))
        return QStringLiteral(".gif");
    if (mime == QLatin1String("application/pdf"))
        return QStringLiteral(".pdf");
    if (mime == QLatin1String("audio/mpeg"))
        return QStringLiteral(".mp3");
    if (mime == QLatin1String("audio/mp4"))
        return QStringLiteral(".m4a");
    if (mime == QLatin1String("audio/wav"))
        return QStringLiteral(".wav");
    if (mime == QLatin1String("audio/ogg"))
        return QStringLiteral(".ogg");
    return {};
}

// Adds the standard types (spec/types.md) to `types`: missing ones are created, a synced copy
// keeps its fields and gets the standard properties first (rule 5).
void seedStandardTypes(QHash<QString, ObjectType> &types, const QString &lang);

// [{id, label, color}] for a select / multi_select value; unknown ids keep their raw id as label.
QVariantList selectedOptions(const QJsonValue &val, const QJsonArray &options);

// Wire fields whose equality means "the merge changes nothing that converges";
// presentation passthrough (content/updated_at/revision/…) never forces a
// re-push (spec/merge.md §Merge 6).
inline const char *const kConvergedKeys[] = {
    "id", "type_id", "schema_version", "title", "title_runs",
    "properties", "links", "property_links", "hlc",
    "field_hlc", "block_hlc", "deleted_blocks", "block_anchor",
    "trashed_at", "created_at",
};

// QML view of one object (lists, cards, search results).
inline QVariantMap objectToVariant(const Object &o, const Workspace::ObjectView &v, const QString &localPath)
{
    QVariantMap m;
    m.insert("id", v.id);
    m.insert("typeId", v.typeId);
    m.insert("title", v.title);
    m.insert("updatedAt", v.updatedAt);
    m.insert("pinned", v.pinned);
    m.insert("trashed", v.trashed);
    m.insert("isTask", v.isTask);
    m.insert("taskDone", v.taskDone);
    m.insert("dueDate", v.dueDate);
    m.insert("createdAt", o.createdAt);
    m.insert("iconEmoji", o.icon().emoji);
    m.insert("summary", !o.summary().isEmpty() ? o.summary() : QString());
    m.insert("assetId", o.propertyString(QStringLiteral("asset_id")));
    m.insert("coverAssetId", o.coverAssetId());
    m.insert("blockCount", int(o.blocks.size()));
    m.insert("snippet", runsToPlainText(o.blocks.isEmpty() ? QVector<Run>() : o.blocks.first().runs)
                             .left(120));
    // Web link preview fields (from the first bookmark block, or object props).
    QString url = o.propertyString(QStringLiteral("url"));
    QString domain;
    QString thumbnailUrl = o.propertyString(QStringLiteral("thumbnail_url"));
    QString faviconUrl = o.propertyString(QStringLiteral("favicon_url"));
    for (const Block &b : o.blocks) {
        if (b.type == BlockType::Bookmark) {
            if (url.isEmpty())
                url = b.url;
            if (domain.isEmpty())
                domain = b.domain;
            if (thumbnailUrl.isEmpty())
                thumbnailUrl = b.thumbnailUrl;
            if (faviconUrl.isEmpty())
                faviconUrl = b.faviconUrl;
            break;
        }
    }
    if (domain.isEmpty() && !url.isEmpty()) {
        domain = QUrl(url).host();
        if (domain.startsWith(QLatin1String("www.")))
            domain = domain.mid(4);
    }
    m.insert("url", url);
    m.insert("domain", domain);
    m.insert("thumbnailUrl", thumbnailUrl);
    m.insert("faviconUrl", faviconUrl);
    m.insert("description", o.propertyString(QStringLiteral("description")));
    m.insert("tags", QVariant::fromValue(o.propertyStringList(QStringLiteral("tags"))));
    m.insert("category", o.propertyString(QStringLiteral("category")));
    m.insert("topics", QVariant::fromValue(o.propertyStringList(QStringLiteral("topics"))));
    if (o.properties.value(QStringLiteral("reviewed")).isBool())
        m.insert("reviewed", o.properties.value(QStringLiteral("reviewed")).toBool());
    m.insert("author", o.propertyString(QStringLiteral("author")));
    m.insert("status", o.propertyString(QStringLiteral("status")));
    const QJsonValue rv = o.properties.value(QStringLiteral("rating"));
    m.insert("rating", rv.isDouble() ? rv.toInt() : rv.toString().toInt());
    m.insert("duration", o.properties.value(QStringLiteral("duration")).toDouble());
    m.insert("pageCount", o.properties.value(QStringLiteral("page_count")).toInt());
    m.insert("lastReadPage", o.properties.value(QStringLiteral("last_read_page")).toInt());
    Q_UNUSED(localPath);
    return m;
}

} // namespace pkm::detail
