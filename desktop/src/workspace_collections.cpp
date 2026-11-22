// Workspace: collections (product §19, manual ordered lists in `properties.items`) and type
// templates (product §4.3, objects with `is_template`). The decisions live in
// pkmcore/editor/collection and objectops; this file reads and saves objects.
#include "workspace.h"
#include "collection.h"
#include "editormodel.h"
#include "objectops.h"
#include "workspace_internal.h"

#include <QJsonArray>
#include <algorithm>

namespace pkm {

QVariantList Workspace::collections() const
{
    QVector<const Object *> found;
    for (const Object &o : m_objects)
        if (o.trashedAt.isEmpty() && !o.isTemplate() && o.typeId == QLatin1String(TypeId::Collection))
            found << &o;
    std::sort(found.begin(), found.end(), [](const Object *a, const Object *b) {
        return a->title.compare(b->title, Qt::CaseInsensitive) < 0;
    });
    QVariantList out;
    for (const Object *o : found)
        out.append(QVariantMap{ { "id", o->id }, { "title", plainTitleFor(*o) },
                                { "count", int(o->propertyStringList(QStringLiteral("items")).size()) } });
    return out;
}

QVariantList Workspace::collectionItems(const QString &collectionId) const
{
    QVariantList out;
    const auto it = m_objects.constFind(collectionId);
    if (it == m_objects.constEnd())
        return out;
    const QStringList ids = collection::clean(it->propertyStringList(QStringLiteral("items")), [this](const QString &id) {
        const auto o = m_objects.constFind(id);
        return o != m_objects.constEnd() && o->trashedAt.isEmpty();
    });
    for (const QString &id : ids)
        out << objectSummary(id);
    return out;
}

bool Workspace::writeCollection(const QString &collectionId, const QStringList &items)
{
    auto it = m_objects.find(collectionId);
    if (it == m_objects.end() || it->typeId != QLatin1String(TypeId::Collection))
        return false;
    if (it->propertyStringList(QStringLiteral("items")) == items)
        return false;
    Object next = it.value();
    if (auto *ed = m_editors.value(collectionId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    next.properties.insert(QStringLiteral("items"), QJsonArray::fromStringList(items));
    saveObject(next);
    emit objectPropertiesChanged(collectionId);
    return true;
}

bool Workspace::addToCollection(const QString &collectionId, const QString &objectId)
{
    if (objectId == collectionId || !m_objects.contains(objectId))
        return false;
    const auto it = m_objects.constFind(collectionId);
    if (it == m_objects.constEnd())
        return false;
    return writeCollection(collectionId, collection::add(it->propertyStringList(QStringLiteral("items")), objectId));
}

bool Workspace::removeFromCollection(const QString &collectionId, const QString &objectId)
{
    const auto it = m_objects.constFind(collectionId);
    if (it == m_objects.constEnd())
        return false;
    return writeCollection(collectionId, collection::remove(it->propertyStringList(QStringLiteral("items")), objectId));
}

bool Workspace::moveInCollection(const QString &collectionId, int from, int to)
{
    const auto it = m_objects.constFind(collectionId);
    if (it == m_objects.constEnd())
        return false;
    // indexes are those of collectionItems (dead ids left out), so clean first
    const QStringList live = collection::clean(it->propertyStringList(QStringLiteral("items")), [this](const QString &id) {
        const auto o = m_objects.constFind(id);
        return o != m_objects.constEnd() && o->trashedAt.isEmpty();
    });
    return writeCollection(collectionId, collection::move(live, from, to));
}

QString Workspace::createCollection(const QString &title)
{
    return createObject(QStringLiteral("collection"), title.trimmed().isEmpty() ? tr("New collection") : title.trimmed());
}

QVariantList Workspace::templatesFor(const QString &typeId) const
{
    const QString tid = normalizeTypeId(typeId);
    QVector<const Object *> found;
    for (const Object &o : m_objects)
        if (o.trashedAt.isEmpty() && o.isTemplate() && o.typeId == tid)
            found << &o;
    std::sort(found.begin(), found.end(), [](const Object *a, const Object *b) { return a->createdAt < b->createdAt; });
    QVariantList out;
    for (const Object *o : found)
        out.append(QVariantMap{ { "id", o->id }, { "title", plainTitleFor(*o) }, { "blockCount", int(o->blocks.size()) } });
    return out;
}

void Workspace::setIsTemplate(const QString &objectId, bool on)
{
    auto it = m_objects.find(objectId);
    if (it == m_objects.end() || it->isTemplate() == on || isFixedType(it->typeId))
        return;
    Object next = it.value();
    if (auto *ed = m_editors.value(objectId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    if (on)
        next.properties.insert(QStringLiteral("is_template"), true);
    else
        next.properties.remove(QStringLiteral("is_template"));
    saveObject(next);
    emit objectPropertiesChanged(objectId);
}

bool Workspace::applyTemplate(const QString &objectId, const QString &templateId)
{
    const auto t = m_objects.constFind(templateId);
    if (t == m_objects.constEnd() || !m_objects.contains(objectId))
        return false;
    if (auto *ed = m_editors.value(objectId))
        ed->flushNow();
    const Object next = pkm::applyTemplate(m_objects.value(objectId), t.value(), [] { return newUuid(); });
    saveObject(next);
    if (auto *ed = m_editors.value(objectId))
        ed->load(m_objects.value(objectId));
    emit objectPropertiesChanged(objectId);
    return true;
}

} // namespace pkm
