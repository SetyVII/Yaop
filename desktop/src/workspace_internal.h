#pragma once
// Helpers shared by the workspace_*.cpp files (one Workspace class, split by concern).

#include "workspace.h"
#include <QString>
#include <QVariantMap>

namespace pkm::detail {

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
    Q_UNUSED(localPath);
    return m;
}

} // namespace pkm::detail
