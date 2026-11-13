#include "model.h"
#include <QSet>
#include <functional>

#include <QJsonArray>
#include <QJsonObject>

namespace pkm {

bool Object::isPinned() const
{
    return properties.value(QLatin1String("pinned")).toBool();
}

bool Object::isTemplate() const
{
    return properties.value(QLatin1String("is_template")).toBool();
}

QString Object::summary() const
{
    return properties.value(QLatin1String("summary")).toString();
}

QStringList Object::aliases() const
{
    const QJsonValue v = properties.value(QLatin1String("aliases"));
    QStringList out;
    if (v.isArray())
        for (const QJsonValue &e : v.toArray())
            if (e.isString())
                out.append(e.toString());
    return out;
}

QString Object::propertyString(const QString &key) const
{
    const QString canonical = normalizePropertyKey(key);
    const QJsonValue v = properties.value(canonical);
    if (v.isString())
        return v.toString();
    if (v.isDouble())
        return QString::number(v.toDouble());
    if (v.isBool())
        return v.toBool() ? QStringLiteral("true") : QStringLiteral("false");
    return {};
}

QStringList Object::propertyStringList(const QString &key) const
{
    const QString canonical = normalizePropertyKey(key);
    const QJsonValue v = properties.value(canonical);
    QStringList out;
    if (v.isArray())
        for (const QJsonValue &e : v.toArray())
            if (e.isString() && !e.toString().isEmpty())
                out.append(e.toString());
    return out;
}

ObjectIcon Object::icon() const
{
    ObjectIcon ic;
    const QJsonValue v = properties.value(QLatin1String("icon"));
    if (v.isObject()) {
        const QJsonObject o = v.toObject();
        ic.emoji = o.value(QLatin1String("emoji")).toString();
        ic.iconName = o.value(QLatin1String("icon_name")).toString();
        ic.colorHex = o.value(QLatin1String("color_hex")).toString();
    }
    return ic;
}

QString Object::coverAssetId() const
{
    const QJsonValue v = properties.value(QLatin1String("cover"));
    QString id;
    if (v.isObject())
        id = v.toObject().value(QLatin1String("asset_id")).toString();
    if (id.startsWith(QLatin1String("cover_")))
        return {};
    return id;
}

// Block keys this codec understands; anything else is passed through untouched (spec/text.md).
static const QSet<QString> &knownBlockKeys()
{
    static const QSet<QString> k = {
        "id", "type", "indent", "block_color", "group_id", "text", "runs", "children", "level", "number",
        "checked", "expanded", "callout_type", "emoji", "language", "latex", "display", "align", "rows",
        "object_id", "view_mode", "asset_id", "width_percent", "duration_seconds", "page_count",
        "last_read_page", "file_name", "url", "title", "description", "domain", "thumbnail_url",
        "favicon_url", "canvas_data" };
    return k;
}

QJsonObject Object::toJson(bool forWire) const
{
    QJsonObject props = properties;
    // Raw blocks as last received: their unknown keys survive export by block id.
    QHash<QString, QJsonObject> rawById;
    std::function<void(const QJsonArray &)> index = [&](const QJsonArray &arr) {
        for (const QJsonValue &v : arr) {
            const QJsonObject b = v.toObject();
            rawById.insert(b.value(QLatin1String("id")).toString(), b);
            if (b.contains(QLatin1String("children")))
                index(b.value(QLatin1String("children")).toArray());
        }
    };
    index(props.value(QLatin1String("blocks")).toArray());
    props.remove(QLatin1String("blocks"));
    QJsonArray outBlocks = QJsonDocument::fromJson(blocksToWireJson(blocks).toUtf8()).array();
    for (int i = 0; i < outBlocks.size(); ++i) {
        QJsonObject b = outBlocks.at(i).toObject();
        const auto raw = rawById.constFind(b.value(QLatin1String("id")).toString());
        if (raw == rawById.constEnd())
            continue;
        bool touched = false;
        for (auto it = raw->begin(); it != raw->end(); ++it)
            if (!knownBlockKeys().contains(it.key()) && !b.contains(it.key())) {
                b.insert(it.key(), it.value());
                touched = true;
            }
        if (touched)
            outBlocks[i] = b;
    }
    props.insert("blocks", outBlocks);
    if (isTemplate())
        props.insert("is_template", true);
    if (isPinned())
        props.insert("pinned", true);
    if (!summary().isEmpty())
        props.insert("summary", summary());

    QJsonObject o;
    o.insert("id", id);
    o.insert("type_id", typeId);
    o.insert("title", title);
    // Rich title survives desktop round trips; if the plain title was edited here, the runs
    // are rebuilt as a single plain run so title and title_runs never disagree.
    // title_runs only when the title carries more than plain text (spec/object.md); an edit of
    // the plain title here drops the stale runs instead of guessing new ones.
    const bool plainRuns = titleRuns.size() == 1 && titleRuns.at(0).toObject().keys() == QStringList{ QStringLiteral("text") };
    if (!titleRuns.isEmpty() && !plainRuns && runsToPlainText(runsFromJson(titleRuns)) == title)
        o.insert("title_runs", titleRuns);
    o.insert("content", forWire ? blocksToMarkdown(blocks) : content);
    o.insert("properties", props);
    if (!propertyLinks.isEmpty())
        o.insert("property_links", QJsonArray::fromStringList(propertyLinks));
    o.insert("links", QJsonArray::fromStringList(links));
    o.insert("created_at", createdAt);
    o.insert("updated_at", updatedAt);
    o.insert("revision", double(revision));
    o.insert("sync_status", QStringLiteral("synced"));
    o.insert("hlc", hlc);
    if (!syncError.isEmpty())
        o.insert("sync_error", syncError);
    if (!incarnation.isEmpty())
        o.insert("incarnation", incarnation);
    if (!trashedAt.isEmpty())
        o.insert("trashed_at", trashedAt);
    if (!fieldHlc.isEmpty())
        o.insert("field_hlc", fieldHlc);
    if (!blockHlc.isEmpty())
        o.insert("block_hlc", blockHlc);
    if (!deletedBlocks.isEmpty())
        o.insert("deleted_blocks", deletedBlocks);
    if (!blockAnchor.isEmpty())
        o.insert("block_anchor", blockAnchor);
    if (schemaVersion > 0)
        o.insert("schema_version", schemaVersion);
    if (forWire)
        o.insert("links", QJsonArray::fromStringList(deriveLinks(o)));
    return o;
}

Object Object::fromJson(const QJsonObject &o)
{
    Object obj;
    obj.id = o.value(QLatin1String("id")).toString();
    obj.typeId = normalizeTypeId(o.value(QLatin1String("type_id")).toString(TypeId::Note));
    obj.title = o.value(QLatin1String("title")).toString();
    obj.content = o.value(QLatin1String("content")).toString();
    obj.properties = o.value(QLatin1String("properties")).toObject();
    const QJsonValue blocksVal = obj.properties.value(QLatin1String("blocks"));
    // Never invent blocks here (a random placeholder id would look like a new block on every
    // save); the editor adds its own empty paragraph when it needs one.
    if (blocksVal.isArray() && blocksVal.toArray().size() > 0)
        obj.blocks = blocksFromWireJson(blocksVal.toArray());
    obj.titleRuns = o.value(QLatin1String("title_runs")).toArray();
    for (const QJsonValue &e : o.value(QLatin1String("property_links")).toArray())
        if (e.isString())
            obj.propertyLinks.append(e.toString());
    const QJsonValue linksVal = o.value(QLatin1String("links"));
    if (linksVal.isArray())
        for (const QJsonValue &e : linksVal.toArray())
            if (e.isString())
                obj.links.append(e.toString());
    obj.createdAt = o.value(QLatin1String("created_at")).toString();
    obj.updatedAt = o.value(QLatin1String("updated_at")).toString();
    obj.hlc = o.value(QLatin1String("hlc")).toString();
    obj.incarnation = o.value(QLatin1String("incarnation")).toString();
    obj.trashedAt = o.value(QLatin1String("trashed_at")).toString();
    obj.fieldHlc = o.value(QLatin1String("field_hlc")).toObject();
    obj.blockHlc = o.value(QLatin1String("block_hlc")).toObject();
    obj.deletedBlocks = o.value(QLatin1String("deleted_blocks")).toObject();
    obj.blockAnchor = o.value(QLatin1String("block_anchor")).toObject();
    obj.schemaVersion = o.value(QLatin1String("schema_version")).toInt(0);
    obj.revision = quint64(o.value(QLatin1String("revision")).toDouble());
    obj.syncStatus = o.value(QLatin1String("sync_status")).toString(QStringLiteral("synced"));
    obj.syncError = o.value(QLatin1String("sync_error")).toString();
    return obj;
}

QStringList Object::referencedAssets(QVector<QPair<QString, QString>> *withMime) const
{
    QStringList ids;
    const auto add = [&ids, withMime](const QString &id, const QString &mime) {
        if (id.isEmpty())
            return;
        if (!ids.contains(id))
            ids.append(id);
        if (withMime)
            withMime->append(qMakePair(id, mime));
    };

    add(coverAssetId(), QStringLiteral("image/webp"));
    add(propertyString(QStringLiteral("asset_id")), propertyString(QStringLiteral("mime")));
    for (const Block &b : blocks) {
        if (!b.assetId.isEmpty()) {
            QString mime = QStringLiteral("application/octet-stream");
            if (b.type == BlockType::Image)
                mime = QStringLiteral("image/webp");
            else if (b.type == BlockType::Audio)
                mime = QStringLiteral("audio/mp4");
            else if (b.type == BlockType::Pdf)
                mime = QStringLiteral("application/pdf");
            add(b.assetId, mime);
        }
    }
    return ids;
}

ObjectType ObjectType::fromJson(const QJsonObject &o)
{
    ObjectType t;
    t.id = normalizeTypeId(o.value(QLatin1String("id")).toString());
    t.name = o.value(QLatin1String("name")).toString();
    t.icon = o.value(QLatin1String("icon")).toString();
    t.color = o.value(QLatin1String("color")).toString();
    t.propertyDefs = o.value(QLatin1String("properties")).toArray();
    t.defaultTemplateId = o.value(QLatin1String("default_template_id")).toString();
    return t;
}

QJsonObject ObjectType::toJson() const
{
    QJsonObject o{
        { "id", id }, { "name", name }, { "icon", icon }, { "color", color },
        { "properties", propertyDefs }
    };
    if (!defaultTemplateId.isEmpty())
        o.insert("default_template_id", defaultTemplateId);
    return o;
}

QString plainTitleFor(const Object &o)
{
    return o.title.isEmpty() ? QStringLiteral("Untitled") : o.title;
}

QString objectIdForMedia(const QString &assetId)
{
    return QStringLiteral("media_") + assetId;
}

QString detectMimeForFilename(const QString &filename)
{
    const QString ext = filename.mid(filename.lastIndexOf(QLatin1Char('.'))).toLower();
    if (ext == QLatin1String(".mp3"))
        return QStringLiteral("audio/mpeg");
    if (ext == QLatin1String(".wav"))
        return QStringLiteral("audio/wav");
    if (ext == QLatin1String(".m4a"))
        return QStringLiteral("audio/mp4");
    if (ext == QLatin1String(".ogg") || ext == QLatin1String(".opus"))
        return QStringLiteral("audio/ogg");
    if (ext == QLatin1String(".flac"))
        return QStringLiteral("audio/flac");
    if (ext == QLatin1String(".aac"))
        return QStringLiteral("audio/aac");
    if (ext == QLatin1String(".jpg") || ext == QLatin1String(".jpeg"))
        return QStringLiteral("image/jpeg");
    if (ext == QLatin1String(".png"))
        return QStringLiteral("image/png");
    if (ext == QLatin1String(".webp"))
        return QStringLiteral("image/webp");
    if (ext == QLatin1String(".gif"))
        return QStringLiteral("image/gif");
    if (ext == QLatin1String(".pdf"))
        return QStringLiteral("application/pdf");
    return {};
}

QString canonicalTypeIdForFilename(const QString &filename)
{
    const QString mime = detectMimeForFilename(filename);
    if (mime.startsWith(QLatin1String("image/")))
        return TypeId::Image;
    if (mime == QLatin1String("application/pdf"))
        return TypeId::Pdf;
    if (mime.startsWith(QLatin1String("audio/")))
        return TypeId::Audio;
    return {};
}


// ---------------------------------------------------------------------------
// Stamp moved to merge.cpp (yaop-sync/1 port with block_anchor).
// ---------------------------------------------------------------------------
QString tagKey(const QString &name)
{
    QString n = name.trimmed();
    if (n.startsWith(QLatin1Char('#')))
        n = n.mid(1);
    n = n.trimmed().toLower().replace(QLatin1Char(' '), QLatin1Char('_'));
    QString out = QStringLiteral("tag_");
    for (const QChar ch : n)
        if (ch.isLetter() || ch.isDigit() || ch == QLatin1Char('_'))
            out += ch;
    return out;
}

QStringList deriveLinks(const QJsonObject &wire)
{
    const QString self = wire.value(QLatin1String("id")).toString();
    QSet<QString> set;
    auto add = [&](const QString &id) {
        if (!id.isEmpty() && id != self)
            set.insert(id);
    };
    auto walkRuns = [&](const QJsonArray &runs) {
        for (const QJsonValue &v : runs) {
            const QJsonObject r = v.toObject();
            const QString oid = r.value(QLatin1String("object_id")).toString();
            if (!oid.isEmpty()) {
                add(oid);
                continue;
            }
            const QString tagId = r.value(QLatin1String("tag_id")).toString();
            QString tagName = r.value(QLatin1String("tag_name")).toString();
            if (!tagId.isEmpty()) {
                add(tagId);
                continue;
            }
            if (!tagName.isEmpty()) {
                add(tagKey(tagName));
                continue;
            }
            const QString date = r.value(QLatin1String("date")).toString();
            if (date.size() >= 10)
                add(QStringLiteral("daily-") + date.left(10));
        }
    };
    std::function<void(const QJsonArray &)> walkBlocks = [&](const QJsonArray &blocks) {
        for (const QJsonValue &v : blocks) {
            const QJsonObject b = v.toObject();
            walkRuns(b.value(QLatin1String("runs")).toArray());
            const QString type = b.value(QLatin1String("type")).toString();
            if (type == QLatin1String("embed") || type == QLatin1String("bookmark"))
                add(b.value(QLatin1String("object_id")).toString());
            else if (type == QLatin1String("image") || type == QLatin1String("audio") || type == QLatin1String("pdf")) {
                const QString a = b.value(QLatin1String("asset_id")).toString();
                if (!a.isEmpty())
                    add(QStringLiteral("media_") + a);
            }
            if (b.contains(QLatin1String("children")))
                walkBlocks(b.value(QLatin1String("children")).toArray());
        }
    };
    walkRuns(wire.value(QLatin1String("title_runs")).toArray());
    walkBlocks(wire.value(QLatin1String("properties")).toObject().value(QLatin1String("blocks")).toArray());
    QStringList out(set.begin(), set.end());
    out.sort();
    return out;
}

bool Object::stamp(const Object *prev, const QString &now)
{
    QJsonObject prevMeta;
    QJsonObject prevWire;
    if (prev) {
        prevMeta.insert(QStringLiteral("field_hlc"), prev->fieldHlc);
        prevMeta.insert(QStringLiteral("block_hlc"), prev->blockHlc);
        prevMeta.insert(QStringLiteral("deleted_blocks"), prev->deletedBlocks);
        prevMeta.insert(QStringLiteral("block_anchor"), prev->blockAnchor);
        prevWire = prev->toJson(true);
    }
    QJsonObject meta;
    const bool changed = stampWire(prev ? &prevWire : nullptr, toJson(true), prevMeta, now, meta);
    fieldHlc = meta.value(QLatin1String("field_hlc")).toObject();
    blockHlc = meta.value(QLatin1String("block_hlc")).toObject();
    deletedBlocks = meta.value(QLatin1String("deleted_blocks")).toObject();
    blockAnchor = meta.value(QLatin1String("block_anchor")).toObject();
    schemaVersion = qMax(schemaVersion, 1); // we save with the modern contracts (spec/object.md)
    if (changed)
        hlc = now;
    else if (prev)
        hlc = prev->hlc;
    return changed;
}

} // namespace pkm
