#include "objectops.h"

#include <QHash>
#include <algorithm>
#include <climits>

namespace pkm {

bool isFixedType(const QString &typeId)
{
    static const QStringList kFixed = { "image", "audio", "pdf", "video", "file", "journal", "tag" };
    return kFixed.contains(typeId);
}

Object duplicateObject(const Object &src, const QString &newId, const QString &now,
                       const std::function<QString()> &newBlockId)
{
    if (!canDuplicateType(src.typeId) || newId.isEmpty())
        return {};
    Object out = src;
    out.id = newId;
    out.createdAt = now;
    out.updatedAt = now;
    out.trashedAt.clear();
    out.hlc.clear();
    out.incarnation.clear();
    out.revision = 0;
    out.syncStatus = QStringLiteral("local");
    out.syncError.clear();
    out.fieldHlc = {};
    out.blockHlc = {};
    out.deletedBlocks = {};
    out.blockAnchor = {};
    out.properties.remove(QStringLiteral("pinned"));
    // A group keeps its members together under one new id.
    QHash<QString, QString> groups;
    for (Block &b : out.blocks) {
        b.id = newBlockId();
        if (!b.groupId.isEmpty()) {
            auto it = groups.find(b.groupId);
            if (it == groups.end())
                it = groups.insert(b.groupId, newBlockId());
            b.groupId = it.value();
        }
    }
    return out;
}

BlockMove moveBlocksToObject(const Object &from, const Object &to, const QStringList &ids,
                             const std::function<QString()> &newBlockId)
{
    BlockMove r{ from, to, 0 };
    if (from.id == to.id || ids.isEmpty())
        return r;
    // Mark the selected blocks and every deeper block that follows one of them.
    QVector<bool> take(from.blocks.size(), false);
    for (int i = 0; i < from.blocks.size(); ++i) {
        if (!ids.contains(from.blocks[i].id) || take[i])
            continue;
        take[i] = true;
        for (int j = i + 1; j < from.blocks.size() && from.blocks[j].indentLevel > from.blocks[i].indentLevel; ++j)
            take[j] = true;
    }
    int minIndent = INT_MAX;
    for (int i = 0; i < from.blocks.size(); ++i)
        if (take[i])
            minIndent = std::min(minIndent, from.blocks[i].indentLevel);
    Blocks kept;
    for (int i = 0; i < from.blocks.size(); ++i) {
        if (!take[i]) {
            kept.append(from.blocks[i]);
            continue;
        }
        Block b = from.blocks[i];
        b.indentLevel -= minIndent;
        r.to.blocks.append(b);
        ++r.moved;
    }
    if (r.moved == 0)
        return r;
    // An object is never left without a block to type in (same rule as a new object).
    if (kept.isEmpty())
        kept.append(Block::makeParagraph(newBlockId()));
    r.from.blocks = kept;
    return r;
}

QHash<QString, int> blockReferenceCounts(const QHash<QString, Object> &objects, const QString &objectId)
{
    QHash<QString, int> counts;
    for (const Object &o : objects) {
        if (!o.trashedAt.isEmpty())
            continue;
        for (const Block &b : o.blocks)
            for (const Run &r : b.runs)
                if (r.objectId == objectId && !r.blockId.isEmpty())
                    ++counts[r.blockId];
    }
    return counts;
}

Object applyTemplate(const Object &target, const Object &tpl, const std::function<QString()> &newBlockId)
{
    Object out = target;
    if (out.title.trimmed().isEmpty())
        out.title = tpl.title;
    static const QStringList kSkip = { "blocks", "pinned", "is_template", "icon", "cover", "aliases", "summary", "tags" };
    for (auto it = tpl.properties.constBegin(); it != tpl.properties.constEnd(); ++it)
        if (!kSkip.contains(it.key()) && !out.properties.contains(it.key()))
            out.properties.insert(it.key(), it.value());
    // tags: union, the target's first
    QStringList tags = out.propertyStringList(QStringLiteral("tags"));
    for (const QString &t : tpl.propertyStringList(QStringLiteral("tags")))
        if (!tags.contains(t, Qt::CaseInsensitive))
            tags << t;
    if (!tags.isEmpty())
        out.properties.insert(QStringLiteral("tags"), QJsonArray::fromStringList(tags));
    // blocks: an empty single paragraph is replaced, otherwise the template goes after
    const bool blank = out.blocks.isEmpty()
        || (out.blocks.size() == 1 && out.blocks[0].plainText().trimmed().isEmpty()
            && out.blocks[0].type == QLatin1String(BlockType::Paragraph));
    if (blank)
        out.blocks.clear();
    QHash<QString, QString> groups;
    for (Block b : tpl.blocks) {
        b.id = newBlockId();
        if (!b.groupId.isEmpty()) {
            auto g = groups.find(b.groupId);
            if (g == groups.end())
                g = groups.insert(b.groupId, newBlockId());
            b.groupId = g.value();
        }
        out.blocks << b;
    }
    return out;
}

TypeChange changeObjectType(const Object &src, const QString &to, const QStringList &toKeys)
{
    TypeChange r;
    r.object = src;
    if (isFixedType(src.typeId) || isFixedType(to)) {
        r.error = QStringLiteral("fixed_type");
        return r;
    }
    if (to == src.typeId)
        return r;
    r.object.typeId = to;
    static const QStringList kSpecial = { "blocks", "icon", "cover", "aliases", "summary",
                                          "pinned", "is_template", "tags" };
    for (auto it = src.properties.constBegin(); it != src.properties.constEnd(); ++it)
        if (!kSpecial.contains(it.key()) && !toKeys.contains(it.key()))
            r.loose.append(it.key());
    // QString::operator< compares UTF-16 code units; equal to code point order for the BMP keys we use.
    std::sort(r.loose.begin(), r.loose.end());
    return r;
}

} // namespace pkm
