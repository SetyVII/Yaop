// Workspace: objects, types, properties, sets, tasks, habits and the journal.
#include "workspace.h"
#include "objectops.h"
#include "graph.h"
#include "unlinked.h"
#include "optionview.h"
#include "textstats.h"
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
#include <QClipboard>
#include <QGuiApplication>
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
// Object CRUD
// ---------------------------------------------------------------------------
QString Workspace::createObject(const QString &typeId, const QString &title)
{
    Object obj;
    obj.id = newUuid();
    obj.typeId = normalizeTypeId(typeId.isEmpty() ? QString(TypeId::Note) : typeId);
    obj.title = title;
    obj.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    obj.updatedAt = obj.createdAt;
    obj.hlc = newHlc();
    obj.revision = 0;
    obj.syncStatus = QStringLiteral("local");
    obj.blocks.append(Block::makeParagraph(newUuid()));
    saveObject(obj);
    ++m_listRevision;
    emit objectListChanged();
    return obj.id;
}

QString Workspace::duplicateObject(const QString &id)
{
    auto it = m_objects.constFind(id);
    if (it == m_objects.constEnd())
        return {};
    Object src = it.value();
    if (auto *ed = m_editors.value(id)) { // unsaved edits travel with the copy
        src.blocks = ed->m_blocks;
        src.title = ed->m_title;
    }
    const QString now = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    Object copy = pkm::duplicateObject(src, newUuid(), now, [] { return newUuid(); });
    if (copy.id.isEmpty())
        return {};
    copy.hlc = newHlc();
    saveObject(copy);
    ++m_listRevision;
    emit objectListChanged();
    return copy.id;
}

QStringList Workspace::typePropertyKeys(const QString &typeId) const
{
    QStringList keys;
    for (const QJsonValue &v : m_types.value(normalizeTypeId(typeId)).propertyDefs) {
        const QJsonObject o = v.toObject();
        keys.append(normalizePropertyKey(o.value(QLatin1String("key")).toString(o.value(QLatin1String("id")).toString())));
    }
    return keys;
}

QVariantMap Workspace::previewTypeChange(const QString &id, const QString &toTypeId) const
{
    auto it = m_objects.constFind(id);
    if (it == m_objects.constEnd())
        return { { "error", QStringLiteral("not_found") } };
    const QString to = normalizeTypeId(toTypeId);
    const pkm::TypeChange r = pkm::changeObjectType(it.value(), to, typePropertyKeys(to));
    QVariantList loose;
    for (const QString &k : r.loose)
        loose.append(QVariantMap{ { "key", k } });
    return { { "error", r.error }, { "loose", loose }, { "properties", typeProperties(to) } };
}

bool Workspace::changeObjectType(const QString &id, const QString &toTypeId)
{
    auto it = m_objects.constFind(id);
    if (it == m_objects.constEnd())
        return false;
    Object src = it.value();
    if (auto *ed = m_editors.value(id)) {
        src.blocks = ed->m_blocks;
        src.title = ed->m_title;
    }
    const QString to = normalizeTypeId(toTypeId);
    if (!m_types.contains(to))
        return false;
    const pkm::TypeChange r = pkm::changeObjectType(src, to, typePropertyKeys(to));
    if (!r.error.isEmpty())
        return false;
    if (r.object.typeId == it.value().typeId)
        return true;
    saveObject(r.object); // stamps field_hlc.type_id (spec/merge.md)
    if (auto *ed = m_editors.value(id)) {
        ed->m_object.typeId = r.object.typeId;
        emit ed->typeChanged();
    }
    ++m_listRevision;
    emit objectListChanged();
    emit objectPropertiesChanged(id);
    return true;
}

int Workspace::moveBlocksToObject(const QString &fromId, const QStringList &blockIds, const QString &toId)
{
    auto fit = m_objects.constFind(fromId);
    auto tit = m_objects.constFind(toId);
    if (fit == m_objects.constEnd() || tit == m_objects.constEnd())
        return 0;
    auto withEditor = [this](Object o) {
        if (auto *ed = m_editors.value(o.id)) { // unsaved edits are part of what moves
            o.blocks = ed->m_blocks;
            o.title = ed->m_title;
        }
        return o;
    };
    const pkm::BlockMove r = pkm::moveBlocksToObject(withEditor(fit.value()), withEditor(tit.value()),
                                                     blockIds, [] { return newUuid(); });
    if (r.moved == 0)
        return 0;
    saveObject(r.from);
    saveObject(r.to);
    for (const QString &id : { fromId, toId })
        if (auto *ed = m_editors.value(id))
            ed->load(m_objects.value(id));
    emit objectUpdated(fromId);
    emit objectUpdated(toId);
    return r.moved;
}

QVariantMap Workspace::graphData(const QString &focusId, int depth, const QStringList &types) const
{
    // links are derived from content (spec/object.md); recompute so local edits count at once.
    // stored links are kept current on save (Workspace::saveObject); only objects open in an
    // editor may have unsaved references, so only those are re-derived
    QHash<QString, QStringList> overrides;
    for (auto it = m_editors.constBegin(); it != m_editors.constEnd(); ++it) {
        const auto o = m_objects.constFind(it.key());
        if (o == m_objects.constEnd() || !it.value())
            continue;
        Object c = o.value();
        c.blocks = it.value()->m_blocks;
        overrides.insert(c.id, deriveLinks(c.toJson(true)));
    }
    const QSet<QString> typeSet(types.begin(), types.end());
    const pkm::Graph g = focusId.isEmpty() ? pkm::buildGraph(m_objects, typeSet, overrides)
                                           : pkm::localGraphOf(m_objects, focusId, depth, typeSet, overrides);
    QVariantList nodes, edges;
    for (const pkm::GraphNode &n : g.nodes)
        nodes.append(QVariantMap{ { "id", n.id }, { "typeId", n.typeId }, { "title", n.title }, { "degree", n.degree } });
    for (const pkm::GraphEdge &e : g.edges)
        edges.append(QVariantMap{ { "from", e.from }, { "to", e.to } });
    return { { "nodes", nodes }, { "edges", edges } };
}

// One object as the user sees it now: the stored copy with its open editor's unsaved blocks.
// O(1); use it instead of objectsWithEditors() when only this object is needed.
std::optional<Object> Workspace::currentObject(const QString &id) const
{
    const auto it = m_objects.constFind(id);
    if (it == m_objects.constEnd())
        return std::nullopt;
    Object o = it.value();
    if (auto *ed = m_editors.value(id)) {
        o.blocks = ed->m_blocks;
        o.title = ed->m_title;
    }
    return o;
}

QHash<QString, Object> Workspace::objectsWithEditors() const
{
    QHash<QString, Object> objs = m_objects;
    for (auto it = m_editors.constBegin(); it != m_editors.constEnd(); ++it)
        if (objs.contains(it.key()) && it.value()) {
            objs[it.key()].blocks = it.value()->m_blocks;
            objs[it.key()].title = it.value()->m_title;
        }
    return objs;
}

QVariantMap Workspace::blockReferenceCounts(const QString &objectId) const
{
    QVariantMap out;
    const QHash<QString, int> counts = pkm::blockReferenceCounts(objectsWithEditors(), objectId);
    for (auto it = counts.constBegin(); it != counts.constEnd(); ++it)
        out.insert(it.key(), it.value());
    return out;
}

QVariantList Workspace::unlinkedMentions(const QString &targetId) const
{
    QVariantList out;
    const QHash<QString, Object> objs = objectsWithEditors();
    for (const pkm::UnlinkedMention &m : pkm::findUnlinkedMentions(objs, targetId)) {
        const Object &o = objs.value(m.objectId);
        out.append(QVariantMap{ { "objectId", m.objectId }, { "blockId", m.blockId }, { "runIndex", m.runIndex },
                                { "runOffset", m.runOffset }, { "length", m.length }, { "snippet", m.snippet },
                                { "title", plainTitleFor(o) }, { "typeId", o.typeId } });
    }
    return out;
}

QVariantMap Workspace::objectStats(const QString &id) const
{
    const std::optional<Object> cur = currentObject(id);
    if (!cur)
        return {};
    const Object &o = *cur;
    const pkm::TextStats s = pkm::textStats(o.blocks);
    return { { "words", s.words }, { "sentences", s.sentences }, { "paragraphs", s.paragraphs },
             { "characters", s.characters }, { "createdAt", o.createdAt }, { "updatedAt", o.updatedAt } };
}

QVariantList Workspace::containedObjects(const QString &id) const
{
    QVariantList out;
    const std::optional<Object> cur = currentObject(id);
    if (!cur)
        return out;
    for (const QString &cid : pkm::containedObjectIds(cur->blocks, id)) {
        const auto it = m_objects.constFind(cid);
        if (it == m_objects.constEnd() || !it->trashedAt.isEmpty())
            continue;
        out.append(QVariantMap{ { "id", cid }, { "title", plainTitleFor(*it) }, { "typeId", it->typeId } });
    }
    return out;
}

QVariantMap Workspace::optionNeighborhood(const QString &typeId, const QString &propKey, const QString &value) const
{
    const QString tid = typeId.isEmpty() ? QString() : normalizeTypeId(typeId);
    const QString key = normalizePropertyKey(propKey);
    // related values come from every select / multi_select of the type, plus tags
    QStringList props;
    QHash<QString, QString> propNames;
    for (const QVariant &pv : typeProperties(tid)) {
        const QVariantMap p = pv.toMap();
        const QString kind = p.value(QStringLiteral("kind")).toString();
        if (kind != QLatin1String("select") && kind != QLatin1String("multi_select"))
            continue;
        const QString k = normalizePropertyKey(p.value(QStringLiteral("key")).toString());
        props << k;
        propNames.insert(k, p.value(QStringLiteral("name")).toString());
    }
    if (!props.contains(QStringLiteral("tags")))
        props << QStringLiteral("tags");
    propNames.insert(QStringLiteral("tags"), tr("Tags"));

    auto describe = [&](const QString &prop, const QString &v) {
        QVariantMap m = prop == QLatin1String("tags")
            ? QVariantMap{ { "id", v }, { "label", v }, { "color", QStringLiteral("tag") } }
            : resolveOption(tid, prop, v);
        m.insert(QStringLiteral("prop"), prop);
        m.insert(QStringLiteral("propName"), propNames.value(prop, prop));
        return m;
    };

    const pkm::OptionNeighborhood n = pkm::optionNeighborhood(m_objects, tid, key, value, props);
    QVariantList objects;
    for (const QString &id : n.objectIds) {
        const Object &o = m_objects[id];
        objects.append(QVariantMap{ { "id", id }, { "title", plainTitleFor(o) }, { "typeId", o.typeId } });
    }
    QVariantList related;
    for (const pkm::RelatedValue &r : n.related) {
        QVariantMap m = describe(r.prop, r.value);
        m.insert(QStringLiteral("objectIds"), r.objectIds);
        related.append(m);
    }
    return { { "typeId", tid }, { "center", describe(key, value) }, { "objects", objects }, { "related", related } };
}

bool Workspace::linkUnlinkedMention(const QString &targetId, const QVariantMap &match)
{
    const QString objectId = match.value(QStringLiteral("objectId")).toString();
    const QHash<QString, Object> objs = objectsWithEditors();
    if (!objs.contains(objectId) || !objs.contains(targetId))
        return false;
    Object next = objs.value(objectId);
    const pkm::UnlinkedMention m{ objectId, match.value(QStringLiteral("blockId")).toString(),
                                  match.value(QStringLiteral("runIndex")).toInt(), match.value(QStringLiteral("runOffset")).toInt(),
                                  match.value(QStringLiteral("length")).toInt(), QString() };
    bool changed = false;
    for (Block &b : next.blocks)
        if (b.id == m.blockId) {
            const Block linked = pkm::linkUnlinkedMention(b, m, targetId);
            changed = linked.runs.size() != b.runs.size();
            b = linked;
        }
    if (!changed)
        return false;
    saveObject(next);
    if (auto *ed = m_editors.value(objectId))
        ed->load(m_objects.value(objectId));
    emit objectUpdated(objectId);
    return true;
}

void Workspace::deleteObject(const QString &id)
{
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return;
    const QString nowIso = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    it.value().updatedAt = nowIso;
    it.value().hlc = newHlc();
    if (it.value().trashedAt.isEmpty()) {
        it.value().trashedAt = nowIso;
        // the tombstone carries its own stamp so a later restore wins (spec/merge.md §Stamp 2)
        it.value().fieldHlc.insert(QStringLiteral("trashed_at"), it.value().hlc);
    }
    ++it.value().revision;
    it.value().syncStatus = QStringLiteral("pending");
    m_store->putObject(it.value());
    // yaop-sync/1 has no delete action: the tombstone IS the trashed
    // object state (kind obj), like on mobile; the merge propagates it.
    m_store->dequeueOpsFor(id);
    m_store->queueOp(newSyncOpId(), it.value().id, it.value().toJson(true));
    m_pendingCount = m_store->pendingOpCount();
    emit syncStateChanged();
    closeObject(id);
    refreshViews();
    ++m_listRevision;
    emit objectListChanged();
    syncNow();
}

void Workspace::restoreObject(const QString &id)
{
    auto it = m_objects.find(id);
    if (it == m_objects.end() || it.value().trashedAt.isEmpty())
        return;
    it.value().trashedAt.clear();
    it.value().updatedAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    it.value().hlc = newHlc();
    it.value().fieldHlc.insert(QStringLiteral("trashed_at"), it.value().hlc);
    ++it.value().revision;
    it.value().syncStatus = QStringLiteral("pending");
    m_store->putObject(it.value());
    m_store->dequeueOpsFor(id);
    m_store->queueOp(newSyncOpId(), it.value().id, it.value().toJson(true));
    m_pendingCount = m_store->pendingOpCount();
    emit syncStateChanged();
    refreshViews();
    ++m_listRevision;
    emit objectListChanged();
    syncNow();
}

// What the query engine reads of an object (spec/query.md): the wire minus the blocks, which
// no operator looks at; skipping them keeps a query over the whole vault cheap.
static QJsonObject queryWireOf(const Object &o)
{
    QJsonObject props = o.properties;
    props.remove(QLatin1String("blocks"));
    if (o.isTemplate())
        props.insert(QStringLiteral("is_template"), true);
    if (o.isPinned())
        props.insert(QStringLiteral("pinned"), true);
    QJsonObject w{ { "id", o.id }, { "type_id", normalizeTypeId(o.typeId) }, { "title", o.title },
                   { "properties", props }, { "links", QJsonArray::fromStringList(o.links) },
                   { "created_at", o.createdAt }, { "updated_at", o.updatedAt } };
    if (!o.propertyLinks.isEmpty())
        w.insert(QStringLiteral("property_links"), QJsonArray::fromStringList(o.propertyLinks));
    if (!o.trashedAt.isEmpty())
        w.insert(QStringLiteral("trashed_at"), o.trashedAt);
    return w;
}

QVariantMap Workspace::runQuery(const QVariantMap &query, const QString &today) const
{
    // `types` is a plain filter on type_id (spec/query.md): apply it before building the wire of
    // every object, which is most of the cost of a query over a large vault.
    QSet<QString> types;
    for (const QVariant &t : query.value(QStringLiteral("types")).toList())
        types.insert(normalizeTypeId(t.toString()));
    QVector<QJsonObject> objects;
    objects.reserve(types.isEmpty() ? m_objects.size() : 256);
    for (const Object &o : m_objects)
        if (o.trashedAt.isEmpty() && (types.isEmpty() || types.contains(normalizeTypeId(o.typeId))))
            objects << queryWireOf(o);
    const QString day = today.isEmpty() ? QDate::currentDate().toString(QStringLiteral("yyyy-MM-dd")) : today;
    const query::Result r = query::evaluate(QJsonObject::fromVariantMap(query), objects, day);
    QVariantMap out;
    if (!r.error.isEmpty()) {
        out.insert(QStringLiteral("error"), r.error);
        return out;
    }
    if (!r.grouped) {
        out.insert(QStringLiteral("ids"), r.ids);
        return out;
    }
    QVariantList groups;
    for (const query::Group &g : r.groups)
        groups << QVariantMap{ { "key", g.key ? QVariant(*g.key) : QVariant() }, { "ids", g.ids } };
    out.insert(QStringLiteral("groups"), groups);
    return out;
}

static QJsonArray defaultSetViews()
{
    return QJsonArray{
        QJsonObject{ { "id", "gallery" }, { "kind", "gallery" }, { "cover", true } },
        QJsonObject{ { "id", "table" }, { "kind", "table" },
                     { "columns", QJsonArray{ "@title", "@type", "@updated_at" } } },
        QJsonObject{ { "id", "list" }, { "kind", "list" } },
    };
}

QString Workspace::createSet(const QString &name, const QVariantMap &query)
{
    const QString id = createObject(QStringLiteral("set"), name.trimmed().isEmpty() ? tr("New set") : name.trimmed());
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return id;
    Object next = it.value();
    QJsonObject q = QJsonObject::fromVariantMap(query);
    if (!q.contains(QLatin1String("version")))
        q.insert(QStringLiteral("version"), 1);
    next.properties.insert(QStringLiteral("query"), q);
    next.properties.insert(QStringLiteral("views"), defaultSetViews());
    next.properties.insert(QStringLiteral("active_view"), QStringLiteral("gallery"));
    saveObject(next);
    return id;
}

void Workspace::renameObject(const QString &objectId, const QString &title)
{
    auto it = m_objects.find(objectId);
    const QString t = title.trimmed();
    if (it == m_objects.end() || t.isEmpty() || it.value().title == t)
        return;
    Object next = it.value();
    if (auto *ed = m_editors.value(objectId)) {
        next.blocks = ed->m_blocks;
        ed->setTitle(t);
    }
    next.title = t;
    next.titleRuns = QJsonArray();
    saveObject(next);
    notifyObjectListChanged();
}

QVariantMap Workspace::setInfo(const QString &setId) const
{
    const auto it = m_objects.constFind(setId);
    if (it == m_objects.constEnd())
        return {};
    const QJsonObject p = it.value().properties;
    QJsonArray views = p.value(QLatin1String("views")).toArray();
    if (views.isEmpty())
        views = defaultSetViews();
    const QString active = p.value(QLatin1String("active_view")).toString();
    QJsonObject activeView = views.first().toObject();
    for (const QJsonValue &v : views)
        if (v.toObject().value(QLatin1String("id")).toString() == active)
            activeView = v.toObject();
    QJsonObject q = p.value(QLatin1String("query")).toObject();
    if (q.isEmpty())
        q = QJsonObject{ { "version", 1 } };
    return QVariantMap{ { "id", setId }, { "title", it.value().title }, { "query", q.toVariantMap() },
                        { "views", views.toVariantList() }, { "activeView", activeView.toVariantMap() } };
}

void Workspace::setSetQuery(const QString &setId, const QVariantMap &query)
{
    auto it = m_objects.find(setId);
    if (it == m_objects.end())
        return;
    Object next = it.value();
    QJsonObject q = QJsonObject::fromVariantMap(query);
    q.insert(QStringLiteral("version"), 1);
    if (next.properties.value(QLatin1String("query")).toObject() == q)
        return;
    next.properties.insert(QStringLiteral("query"), q);
    saveObject(next);
    emit objectPropertiesChanged(setId);
}

void Workspace::setSetActiveView(const QString &setId, const QString &kind)
{
    auto it = m_objects.find(setId);
    if (it == m_objects.end())
        return;
    Object next = it.value();
    QJsonArray views = next.properties.value(QLatin1String("views")).toArray();
    if (views.isEmpty())
        views = defaultSetViews();
    QString id;
    for (const QJsonValue &v : views)
        if (v.toObject().value(QLatin1String("kind")).toString() == kind) {
            id = v.toObject().value(QLatin1String("id")).toString();
            break;
        }
    if (id.isEmpty()) {
        id = kind;
        views.append(QJsonObject{ { "id", id }, { "kind", kind } });
    }
    if (next.properties.value(QLatin1String("active_view")).toString() == id
        && next.properties.value(QLatin1String("views")).toArray() == views)
        return;
    next.properties.insert(QStringLiteral("views"), views);
    next.properties.insert(QStringLiteral("active_view"), id);
    saveObject(next);
    emit objectPropertiesChanged(setId);
}

QVariantList Workspace::typeViews(const QString &typeId) const
{
    const QString tid = normalizeTypeId(typeId);
    QVector<const Object *> found;
    for (const Object &o : m_objects)
        if (o.trashedAt.isEmpty() && o.typeId == QLatin1String("set")
            && o.properties.value(QLatin1String("type_view_of")).toString() == tid)
            found << &o;
    std::sort(found.begin(), found.end(), [](const Object *a, const Object *b) {
        return a->createdAt != b->createdAt ? a->createdAt < b->createdAt : a->id < b->id;
    });
    QVariantList out;
    for (const Object *o : found) {
        const QVariantMap info = setInfo(o->id);
        out.append(QVariantMap{ { "id", o->id }, { "title", o->title },
                                { "kind", info.value(QStringLiteral("activeView")).toMap().value(QStringLiteral("kind")) } });
    }
    return out;
}

QString Workspace::createTypeView(const QString &typeId, const QString &name, const QVariantMap &query,
                                  const QString &kind)
{
    const QString tid = normalizeTypeId(typeId);
    QVariantMap q = query;
    q.insert(QStringLiteral("types"), QVariantList{ tid });
    const QString id = createSet(name, q);
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return id;
    Object next = it.value();
    next.properties.insert(QStringLiteral("type_view_of"), tid);
    saveObject(next);
    if (!kind.isEmpty())
        setSetActiveView(id, kind);
    return id;
}

QVariantMap Workspace::runSet(const QString &setId, int limit) const
{
    const auto it = m_objects.constFind(setId);
    if (it == m_objects.constEnd())
        return { { "error", "missing_set" } };
    QJsonObject q = it.value().properties.value(QLatin1String("query")).toObject();
    if (q.isEmpty())
        q = QJsonObject{ { "version", 1 } };
    const QVariantMap r = runQuery(q.toVariantMap());
    QVariantMap out;
    if (r.contains(QStringLiteral("error"))) {
        out.insert(QStringLiteral("error"), r.value(QStringLiteral("error")));
        return out;
    }
    QVariantList items;
    int total = 0;
    for (const QString &id : r.value(QStringLiteral("ids")).toStringList()) {
        if (id == setId)
            continue; // a set never lists itself
        if (limit <= 0 || total < limit)
            items << objectSummary(id);
        ++total;
    }
    out.insert(QStringLiteral("items"), items);
    out.insert(QStringLiteral("total"), total);
    return out;
}

QString Workspace::fieldText(const QString &objectId, const QString &prop) const
{
    const auto it = m_objects.constFind(objectId);
    if (it == m_objects.constEnd())
        return {};
    const Object &o = it.value();
    if (prop == QLatin1String("@title"))
        return o.title;
    if (prop == QLatin1String("@type")) {
        const auto t = m_types.constFind(normalizeTypeId(o.typeId));
        return t != m_types.constEnd() && !t->name.isEmpty() ? t->name : o.typeId;
    }
    if (prop == QLatin1String("@created_at"))
        return o.createdAt.left(10);
    if (prop == QLatin1String("@updated_at"))
        return o.updatedAt.left(10);
    const QJsonValue v = o.properties.value(prop);
    auto one = [this](const QJsonValue &x) -> QString {
        if (x.isString()) {
            const auto ref = m_objects.constFind(x.toString());
            return ref != m_objects.constEnd() ? ref->title : x.toString();
        }
        if (x.isBool())
            return x.toBool() ? QStringLiteral("✓") : QString();
        if (x.isDouble())
            return QString::number(x.toDouble(), 'g', QLocale::FloatingPointShortest);
        if (x.isObject()) {
            const QJsonObject m = x.toObject();
            const QString a = m.value(QLatin1String("start")).toString();
            const QString b = m.value(QLatin1String("end")).toString();
            return b.isEmpty() ? a : a + QStringLiteral(" → ") + b;
        }
        return {};
    };
    if (v.isArray()) {
        QStringList parts;
        for (const QJsonValue &x : v.toArray())
            parts << one(x);
        parts.removeAll(QString());
        return parts.join(QStringLiteral(", "));
    }
    return one(v);
}

int Workspace::purgeExpiredTrash()
{
    if (!m_store)
        return 0;
    const QDateTime now = QDateTime::currentDateTimeUtc();
    const QSet<QString> queued = pendingObjectIds();
    QStringList doomed;
    for (auto it = m_objects.cbegin(); it != m_objects.cend(); ++it) {
        if (syncpolicy::canPurgeTrashed(it.value().trashedAt, it.value().syncStatus, queued.contains(it.key()), now))
            doomed << it.key();
    }
    if (doomed.isEmpty())
        return 0;
    ListBatchScope batch(this);
    for (const QString &id : doomed) {
        closeObject(id);
        m_objects.remove(id);
        m_store->removeObject(id);
    }
    refreshViews();
    notifyObjectListChanged();
    return int(doomed.size());
}

void Workspace::togglePinned(const QString &id)
{
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return;
    Object next = it.value();
    QJsonObject props = next.properties;
    const bool pinned = props.value(QLatin1String("pinned")).toBool();
    if (pinned)
        props.remove(QStringLiteral("pinned"));
    else
        props.insert(QStringLiteral("pinned"), true);
    next.properties = props;
    saveObject(next);
    ++m_listRevision;
    emit objectListChanged();
}

// ---------------------------------------------------------------------------
// Queries for QML (local: search index, backlinks table, in-memory views)
// ---------------------------------------------------------------------------
bool Workspace::taskDoneFor(const Object &o)
{
    const QJsonValue status = o.properties.value(QLatin1String("status"));
    QString s = status.isString() ? status.toString() : QString();
    return s.compare(QLatin1String("done"), Qt::CaseInsensitive) == 0
           || s.compare(QLatin1String("completed"), Qt::CaseInsensitive) == 0;
}

QString Workspace::dueDateFor(const Object &o)
{
    const QJsonValue v = o.properties.value(QLatin1String("due_date"));
    if (v.isString())
        return v.toString();
    const QJsonValue date = o.properties.value(QLatin1String("date"));
    return date.isString() ? date.toString() : QString();
}

Workspace::ObjectView Workspace::viewFor(const Object &o) const
{
    ObjectView v;
    v.id = o.id;
    v.typeId = o.typeId;
    v.title = o.title.isEmpty() ? QStringLiteral("Untitled") : o.title;
    v.updatedAt = o.updatedAt;
    v.pinned = o.isPinned() || o.properties.value(QLatin1String("pinned")).toBool();
    v.trashed = !o.trashedAt.isEmpty();
    v.isTask = o.typeId == TypeId::Task;
    v.taskDone = taskDoneFor(o);
    v.dueDate = dueDateFor(o);
    return v;
}

QVariantMap Workspace::objectSummary(const QString &id) const
{
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return {};
    return objectToVariant(it.value(), viewFor(it.value()), QString());
}

QVariantList Workspace::recentObjects(int limit) const
{
    QVector<QPair<QString, QString>> pairs;
    pairs.reserve(m_objects.size());
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        pairs.append(qMakePair(o.updatedAt, o.id));
    }
    std::sort(pairs.begin(), pairs.end(), std::greater<QPair<QString, QString>>());
    QVariantList out;
    for (int i = 0; i < pairs.size() && i < limit; ++i) {
        auto it = m_objects.find(pairs[i].second);
        if (it != m_objects.end())
            out.append(objectToVariant(it.value(), viewFor(it.value()), QString()));
    }
    return out;
}

QVariantList Workspace::pinnedObjects(int limit) const
{
    QVariantList out;
    for (const Object &o : m_objects) {
        if (out.size() >= limit)
            break;
        if (!o.trashedAt.isEmpty() || !o.isPinned())
            continue;
        out.append(objectToVariant(o, viewFor(o), QString()));
    }
    return out;
}

QVariantList Workspace::trashedObjects() const
{
    QList<Object> list;
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty())
            list.append(o);
    }
    std::sort(list.begin(), list.end(), [](const Object &a, const Object &b) {
        return a.trashedAt > b.trashedAt;
    });
    QVariantList out;
    out.reserve(list.size());
    for (const Object &o : list)
        out.append(objectToVariant(o, viewFor(o), QString()));
    return out;
}

QVariantList Workspace::objectsByType(const QString &typeId, int limit) const
{
    QVariantList out;
    for (const Object &o : m_objects) {
        if (limit > 0 && out.size() >= limit)
            break;
        if (!o.trashedAt.isEmpty() || o.typeId != typeId || o.isTemplate())
            continue; // templates live in their own tab (templatesFor), like queries skip them
        out.append(objectToVariant(o, viewFor(o), QString()));
    }
    return out;
}

QVariantMap Workspace::typeCounts() const
{
    QHash<QString, int> n;
    for (const Object &o : m_objects)
        if (o.trashedAt.isEmpty() && !o.isTemplate())
            ++n[o.typeId];
    QVariantMap out;
    for (auto it = n.constBegin(); it != n.constEnd(); ++it)
        out.insert(it.key(), it.value());
    return out;
}

QVariantList Workspace::allTypes() const
{
    // Stable, meaningful order (QHash iteration order is arbitrary): standard types first in a
    // fixed order, then custom ones alphabetically.
    static const QStringList kOrder = { "note", "task", "project", "person", "journal", "whiteboard",
                                        "collection", "book", "web", "tag", "image", "pdf", "audio" };
    QList<ObjectType> sorted = m_types.values();
    std::sort(sorted.begin(), sorted.end(), [](const ObjectType &a, const ObjectType &b) {
        const int ia = kOrder.indexOf(a.id), ib = kOrder.indexOf(b.id);
        if (ia >= 0 || ib >= 0)
            return (ia < 0 ? 1000 : ia) < (ib < 0 ? 1000 : ib);
        return a.name.toLower() < b.name.toLower();
    });
    QVariantList out;
    for (const ObjectType &t : sorted) {
        QVariantMap m;
        m.insert("id", t.id);
        m.insert("name", t.name);
        m.insert("icon", t.icon);
        m.insert("color", t.color);
        m.insert("standard", t.standard);
        m.insert("propertyCount", t.propertyDefs.size());
        out.append(m);
    }
    return out;
}

QVariantMap Workspace::typeInfo(const QString &typeId) const
{
    const QString norm = normalizeTypeId(typeId);
    QVariantList list = allTypes();
    for (const QVariant &v : list) {
        QVariantMap m = v.toMap();
        if (m.value("id").toString() == norm)
            return m;
    }
    return {};
}

static QString sanitizePropertyKey(const QString &raw)
{
    QString k = raw.trimmed().toLower();
    static const QRegularExpression nonAlnum(QStringLiteral("[^a-z0-9_]+"));
    k.replace(QLatin1Char(' '), QLatin1Char('_'));
    k.replace(nonAlnum, QStringLiteral("_"));
    while (k.startsWith(QLatin1Char('_')))
        k.remove(0, 1);
    while (k.endsWith(QLatin1Char('_')))
        k.chop(1);
    return k.isEmpty() ? QStringLiteral("prop") : k;
}

QString Workspace::createCustomType(const QString &name, const QString &colorNameOrHex,
                                    const QVariantList &propDefs, const QString &icon)
{
    const QString cleanName = name.trimmed();
    if (cleanName.isEmpty())
        return {};
    QString baseId = QStringLiteral("type_") + sanitizePropertyKey(cleanName);
    QString id = baseId;
    int n = 2;
    while (m_types.contains(id))
        id = baseId + QLatin1Char('_') + QString::number(n++);

    ObjectType t;
    t.id = id;
    t.name = cleanName;
    t.icon = icon.trimmed().isEmpty() ? QStringLiteral("box") : icon.trimmed();
    t.color = colorNameOrHex.trimmed().isEmpty() ? QStringLiteral("blue") : colorNameOrHex.trimmed();
    t.standard = false;
    for (const QVariant &v : propDefs) {
        const QVariantMap pm = v.toMap();
        const QString pName = pm.value(QStringLiteral("name")).toString().trimmed();
        if (pName.isEmpty())
            continue;
        const QString pKind = pm.value(QStringLiteral("kind"), QStringLiteral("text")).toString().trimmed();
        const QString pKey = sanitizePropertyKey(pName);
        t.propertyDefs.append(QJsonObject{
            { "id", QStringLiteral("prop_") + pKey },
            { "key", pKey },
            { "name", pName },
            { "kind", pKind.isEmpty() ? QStringLiteral("text") : pKind },
        });
    }
    m_types.insert(t.id, t);
    if (m_store) {
        m_store->putType(t);
        m_store->dequeueOpsFor(QStringLiteral("type:") + t.id);
        m_store->queueOp(newSyncOpId(), QStringLiteral("type:") + t.id, t.toJson());
        m_pendingCount = m_store->pendingOpCount();
        emit syncStateChanged();
        schedulePush(1200);
    }
    emit typesChanged();
    ++m_listRevision;
    emit objectListChanged();
    return t.id;
}

bool Workspace::addPropertyToType(const QString &typeId, const QString &propName, const QString &kind)
{
    const QString norm = normalizeTypeId(typeId);
    const QString clean = propName.trimmed();
    if (clean.isEmpty() || !m_types.contains(norm))
        return false;
    ObjectType t = m_types.value(norm);
    const QString pKey = sanitizePropertyKey(clean);
    for (const QJsonValue &existing : t.propertyDefs) {
        const QJsonObject eo = existing.toObject();
        if (normalizePropertyKey(eo.value(QLatin1String("key")).toString()) == pKey)
            return false;
    }
    const QString k = kind.trimmed().isEmpty() ? QStringLiteral("text") : kind.trimmed().toLower();
    t.propertyDefs.append(QJsonObject{
        { "id", QStringLiteral("prop_") + pKey },
        { "key", pKey },
        { "name", clean },
        { "kind", k },
    });
    m_types.insert(t.id, t);
    if (m_store) {
        m_store->putType(t);
        m_store->dequeueOpsFor(QStringLiteral("type:") + t.id);
        m_store->queueOp(newSyncOpId(), QStringLiteral("type:") + t.id, t.toJson());
        m_pendingCount = m_store->pendingOpCount();
        emit syncStateChanged();
        schedulePush(1200);
    }
    emit typesChanged();
    return true;
}

bool Workspace::addOptionToProperty(const QString &typeId, const QString &propKey,
                                    const QString &label, const QString &color)
{
    const QString norm = normalizeTypeId(typeId);
    const QString cleanKey = normalizePropertyKey(propKey);
    const QString cleanLabel = label.trimmed();
    if (cleanKey.isEmpty() || cleanLabel.isEmpty() || !m_types.contains(norm))
        return false;
    ObjectType t = m_types.value(norm);
    const QString optId = sanitizePropertyKey(cleanLabel);
    if (optId.isEmpty())
        return false;

    bool changed = false;
    QJsonArray nextDefs;
    for (const QJsonValue &v : t.propertyDefs) {
        QJsonObject def = v.toObject();
        const QString k = normalizePropertyKey(def.value(QLatin1String("key")).toString(def.value(QLatin1String("id")).toString()));
        if (k == cleanKey) {
            QJsonArray opts = def.value(QLatin1String("options")).toArray();
            for (const QJsonValue &ov : opts) {
                const QJsonObject oo = ov.toObject();
                if (oo.value(QLatin1String("id")).toString().compare(optId, Qt::CaseInsensitive) == 0
                    || oo.value(QLatin1String("label")).toString().compare(cleanLabel, Qt::CaseInsensitive) == 0)
                    return false;
            }
            opts.append(QJsonObject{
                { "id", optId },
                { "label", cleanLabel },
                { "color", color.trimmed().isEmpty() ? QStringLiteral("blue") : color.trimmed() },
            });
            def.insert(QStringLiteral("options"), opts);
            changed = true;
        }
        nextDefs.append(def);
    }
    if (!changed)
        return false;
    t.propertyDefs = nextDefs;
    m_types.insert(t.id, t);
    if (m_store) {
        m_store->putType(t);
        m_store->dequeueOpsFor(QStringLiteral("type:") + t.id);
        m_store->queueOp(newSyncOpId(), QStringLiteral("type:") + t.id, t.toJson());
        m_pendingCount = m_store->pendingOpCount();
        emit syncStateChanged();
        schedulePush(1200);
    }
    emit typesChanged();
    return true;
}

bool Workspace::removeOptionFromProperty(const QString &typeId, const QString &propKey, const QString &optionId)
{
    const QString norm = normalizeTypeId(typeId);
    const QString cleanKey = normalizePropertyKey(propKey);
    const QString cleanOpt = optionId.trimmed();
    if (cleanKey.isEmpty() || cleanOpt.isEmpty() || !m_types.contains(norm))
        return false;
    ObjectType t = m_types.value(norm);
    bool changed = false;
    QJsonArray nextDefs;
    for (const QJsonValue &v : t.propertyDefs) {
        QJsonObject def = v.toObject();
        const QString k = normalizePropertyKey(def.value(QLatin1String("key")).toString(def.value(QLatin1String("id")).toString()));
        if (k == cleanKey) {
            QJsonArray opts = def.value(QLatin1String("options")).toArray();
            QJsonArray kept;
            for (const QJsonValue &ov : opts) {
                const QJsonObject oo = ov.toObject();
                if (oo.value(QLatin1String("id")).toString() == cleanOpt) {
                    changed = true;
                    continue;
                }
                kept.append(ov);
            }
            def.insert(QStringLiteral("options"), kept);
        }
        nextDefs.append(def);
    }
    if (!changed)
        return false;
    t.propertyDefs = nextDefs;
    m_types.insert(t.id, t);
    if (m_store) {
        m_store->putType(t);
        m_store->dequeueOpsFor(QStringLiteral("type:") + t.id);
        m_store->queueOp(newSyncOpId(), QStringLiteral("type:") + t.id, t.toJson());
        m_pendingCount = m_store->pendingOpCount();
        emit syncStateChanged();
        schedulePush(1200);
    }
    emit typesChanged();
    return true;
}

QVariantMap Workspace::resolveOption(const QString &typeId, const QString &propKey, const QString &optionId) const
{
    const QString cleanOpt = optionId.trimmed();
    if (cleanOpt.isEmpty())
        return {};
    const ObjectType t = m_types.value(normalizeTypeId(typeId));
    const QString cleanKey = normalizePropertyKey(propKey);
    for (const QJsonValue &v : t.propertyDefs) {
        const QJsonObject def = v.toObject();
        const QString k = normalizePropertyKey(def.value(QLatin1String("key")).toString(def.value(QLatin1String("id")).toString()));
        if (k != cleanKey)
            continue;
        for (const QJsonValue &ov : def.value(QLatin1String("options")).toArray()) {
            const QJsonObject oo = ov.toObject();
            const QString id = oo.value(QLatin1String("id")).toString();
            const QString label = oo.value(QLatin1String("label")).toString(id);
            if (id.compare(cleanOpt, Qt::CaseInsensitive) == 0 || label.compare(cleanOpt, Qt::CaseInsensitive) == 0) {
                return QVariantMap{
                    { "id", id },
                    { "label", label },
                    { "color", oo.value(QLatin1String("color")).toString(QStringLiteral("blue")) },
                };
            }
        }
    }
    return QVariantMap{ { "id", cleanOpt }, { "label", cleanOpt }, { "color", QStringLiteral("gray") } };
}

QVariantMap Workspace::resolvePersonByValue(const QString &raw) const
{
    const QString clean = raw.trimmed();
    if (clean.isEmpty())
        return {};
    if (auto it = m_objects.find(clean); it != m_objects.end() && it->trashedAt.isEmpty() && it->typeId == TypeId::Person) {
        return QVariantMap{ { "id", it->id }, { "title", it->title } };
    }
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.typeId != TypeId::Person)
            continue;
        if (o.title.trimmed().compare(clean, Qt::CaseInsensitive) == 0)
            return QVariantMap{ { "id", o.id }, { "title", o.title } };
    }
    return {};
}

QVariantList Workspace::collectionsForObject(const QString &objectId) const
{
    QVariantList out;
    if (objectId.isEmpty())
        return out;
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.isTemplate() || o.typeId != TypeId::Collection)
            continue;
        bool has = o.propertyStringList(QStringLiteral("items")).contains(objectId);
        if (!has) {
            for (const Block &b : o.blocks) {
                if (b.objectId == objectId) {
                    has = true;
                    break;
                }
                for (const Run &r : b.runs) {
                    if (r.objectId == objectId) {
                        has = true;
                        break;
                    }
                }
                if (has)
                    break;
            }
        }
        if (has)
            out.append(QVariantMap{ { "id", o.id }, { "title", o.title } });
    }
    return out;
}

QVariantMap Workspace::projectProgress(const QString &projectId) const
{
    int done = 0, total = 0, notes = 0, people = 0;
    if (projectId.isEmpty() || !m_objects.contains(projectId))
        return QVariantMap{ { "done", 0 }, { "total", 0 }, { "notes", 0 }, { "people", 0 } };
    QSet<QString> linkedIds;
    // Outgoing mentions & embeds from the project itself
    const Object &proj = m_objects.value(projectId);
    for (const Block &b : proj.blocks) {
        if (!b.objectId.isEmpty())
            linkedIds.insert(b.objectId);
        for (const Run &r : b.runs)
            if (!r.objectId.isEmpty())
                linkedIds.insert(r.objectId);
    }
    // Incoming links from other objects mentioning this project
    for (const Object &o : m_objects) {
        if (o.id == projectId || !o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        bool mentionsProj = false;
        for (const Block &b : o.blocks) {
            if (b.objectId == projectId) {
                mentionsProj = true;
                break;
            }
            for (const Run &r : b.runs) {
                if (r.objectId == projectId) {
                    mentionsProj = true;
                    break;
                }
            }
            if (mentionsProj)
                break;
        }
        if (mentionsProj)
            linkedIds.insert(o.id);
    }
    for (const QString &id : linkedIds) {
        auto it = m_objects.find(id);
        if (it == m_objects.end() || !it->trashedAt.isEmpty() || it->isTemplate())
            continue;
        if (it->typeId == TypeId::Task) {
            ++total;
            if (viewFor(*it).taskDone)
                ++done;
        } else if (it->typeId == TypeId::Note) {
            ++notes;
        } else if (it->typeId == TypeId::Person) {
            ++people;
        }
    }
    return QVariantMap{ { "done", done }, { "total", total }, { "notes", notes }, { "people", people } };
}

QString Workspace::clipboardText() const
{
    if (const QClipboard *cb = QGuiApplication::clipboard())
        return cb->text().trimmed();
    return {};
}

QVariantList Workspace::typeProperties(const QString &typeId) const
{
    QVariantList out;
    const ObjectType t = m_types.value(normalizeTypeId(typeId));
    for (const QJsonValue &v : t.propertyDefs) {
        const QJsonObject o = v.toObject();
        QVariantMap m;
        const QString key = normalizePropertyKey(o.value(QLatin1String("key")).toString(o.value(QLatin1String("id")).toString()));
        m.insert("key", key);
        m.insert("name", o.value(QLatin1String("name")).toString(key));
        m.insert("kind", o.value(QLatin1String("kind")).toString(QStringLiteral("text")));
        m.insert("system", o.value(QLatin1String("system")).toBool());
        m.insert("options", o.value(QLatin1String("options")).toArray().toVariantList());
        out.append(m);
    }
    return out;
}

// Option labels for a select value: known ids get their label, unknown ids stay as they came
// (spec/types.md "Values on the wire").
QVariantList pkm::detail::selectedOptions(const QJsonValue &val, const QJsonArray &options)
{
    QStringList ids;
    if (val.isArray()) {
        for (const QJsonValue &v : val.toArray())
            if (!v.toString().isEmpty())
                ids.append(v.toString());
    } else if (!val.toString().isEmpty()) {
        ids.append(val.toString());
    }
    QVariantList out;
    for (const QString &id : std::as_const(ids)) {
        QVariantMap m{ { "id", id }, { "label", id }, { "color", QString() } };
        for (const QJsonValue &ov : options) {
            const QJsonObject o = ov.toObject();
            if (o.value(QLatin1String("id")).toString() == id) {
                m.insert("label", o.value(QLatin1String("label")).toString(id));
                m.insert("color", o.value(QLatin1String("color")).toString());
                break;
            }
        }
        out.append(m);
    }
    return out;
}

void Workspace::toggleOption(const QString &objectId, const QString &key, const QString &optionId, bool multi)
{
    auto it = m_objects.constFind(objectId);
    if (it == m_objects.constEnd() || key.isEmpty() || optionId.isEmpty())
        return;
    const QJsonValue cur = it.value().properties.value(key);
    if (!multi) {
        const bool same = cur.toString() == optionId;
        setObjectProperty(objectId, key, same ? QString() : optionId, QStringLiteral("select"));
        return;
    }
    QStringList ids;
    for (const QJsonValue &v : cur.toArray())
        ids.append(v.toString());
    if (ids.contains(optionId))
        ids.removeAll(optionId);
    else
        ids.append(optionId);
    setObjectProperty(objectId, key, ids.join(QLatin1Char(',')), QStringLiteral("multi_select"));
}

QVariantList Workspace::objectPropertyEntries(const QString &objectId) const
{
    QVariantList out;
    auto it = m_objects.constFind(objectId);
    if (it == m_objects.constEnd())
        return out;
    const Object &obj = it.value();
    const ObjectType t = m_types.value(normalizeTypeId(obj.typeId));
    QSet<QString> seen;

    auto appendEntry = [&](const QString &key, const QString &name, const QString &kind,
                           const QJsonArray &options = {}) {
        if (key.isEmpty() || seen.contains(key))
            return;
        seen.insert(key);
        QVariantMap entry;
        entry.insert("key", key);
        entry.insert("name", name);
        const QJsonValue val = obj.properties.value(key);
        QString resolvedKind = kind;
        QString displayVal;
        bool boolVal = false;
        if (val.isBool()) {
            resolvedKind = QStringLiteral("checkbox");
            boolVal = val.toBool();
            displayVal = boolVal ? QStringLiteral("true") : QStringLiteral("false");
        } else if (val.isDouble()) {
            if (resolvedKind.isEmpty())
                resolvedKind = QStringLiteral("number");
            displayVal = QString::number(val.toDouble());
        } else if (val.isArray()) {
            QStringList items;
            for (const QJsonValue &av : val.toArray())
                items.append(av.toString());
            displayVal = items.join(QStringLiteral(", "));
        } else if (val.isObject()) {
            const QJsonObject vo = val.toObject();
            if (vo.contains(QLatin1String("start")))
                displayVal = vo.value(QLatin1String("start")).toString();
            else
                displayVal = QString::fromUtf8(QJsonDocument(vo).toJson(QJsonDocument::Compact));
        } else {
            displayVal = val.toString();
        }
        entry.insert("kind", resolvedKind.isEmpty() ? QStringLiteral("text") : resolvedKind);
        if (resolvedKind == QLatin1String("select") || resolvedKind == QLatin1String("multi_select")) {
            const QVariantList sel = pkm::detail::selectedOptions(val, options);
            QStringList labels;
            for (const QVariant &sv : sel)
                labels.append(sv.toMap().value(QStringLiteral("label")).toString());
            displayVal = labels.join(QStringLiteral(", "));
            entry.insert("selected", sel);
            entry.insert("options", options.toVariantList());
        }
        entry.insert("value", displayVal);
        entry.insert("checked", boolVal);
        out.append(entry);
    };

    for (const QJsonValue &v : t.propertyDefs) {
        const QJsonObject o = v.toObject();
        if (o.value(QLatin1String("system")).toBool())
            continue; // filled by the app (file, url, tags): shown where it belongs, not as a field
        const QString k = normalizePropertyKey(o.value(QLatin1String("key")).toString(o.value(QLatin1String("id")).toString()));
        appendEntry(k, o.value(QLatin1String("name")).toString(k), o.value(QLatin1String("kind")).toString(QStringLiteral("text")),
                    o.value(QLatin1String("options")).toArray());
    }

    static const QSet<QString> kHidden = {
        QStringLiteral("blocks"), QStringLiteral("pinned"), QStringLiteral("is_template"),
        QStringLiteral("summary"), QStringLiteral("icon"), QStringLiteral("cover"),
        QStringLiteral("aliases"), QStringLiteral("asset_id"), QStringLiteral("mime"),
        QStringLiteral("file_name"), QStringLiteral("size"), QStringLiteral("chunks"),
        QStringLiteral("chunk_size"), QStringLiteral("total_plain_size"),
        QStringLiteral("thumbnail_url"), QStringLiteral("favicon_url"),
        QStringLiteral("tags"),
    };
    QStringList objKeys = obj.properties.keys();
    std::sort(objKeys.begin(), objKeys.end());
    const int definedCount = out.size();
    for (const QString &k : objKeys) {
        if (kHidden.contains(k))
            continue;
        appendEntry(k, k, QString());
    }
    // Keys the type does not define are loose (spec/schema.md rule 4): shown apart, removable.
    for (int i = definedCount; i < out.size(); ++i) {
        QVariantMap m = out[i].toMap();
        m.insert("loose", true);
        out[i] = m;
    }
    return out;
}

void Workspace::setObjectProperty(const QString &objectId, const QString &key, const QVariant &value,
                                  const QString &kind, const QString &label, bool addToType)
{
    auto it = m_objects.find(objectId);
    if (it == m_objects.end())
        return;
    const QString cleanKey = sanitizePropertyKey(key);
    if (cleanKey.isEmpty())
        return;
    Object next = it.value();
    if (auto *ed = m_editors.value(objectId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    const QString normKind = kind.trimmed().toLower();
    if (normKind == QLatin1String("checkbox") || value.typeId() == QMetaType::Bool) {
        next.properties.insert(cleanKey, value.toBool());
    } else if (normKind == QLatin1String("number")) {
        bool ok = false;
        const double d = value.toString().trimmed().toDouble(&ok);
        if (ok)
            next.properties.insert(cleanKey, d);
        else
            next.properties.remove(cleanKey);
    } else if (normKind == QLatin1String("multi_select") || cleanKey == QLatin1String("tags")) {
        QJsonArray arr;
        QStringList tagIds;
        for (const QString &part : value.toString().split(QLatin1Char(','), Qt::SkipEmptyParts)) {
            const QString p = part.trimmed();
            if (!p.isEmpty()) {
                arr.append(p);
                if (cleanKey == QLatin1String("tags"))
                    tagIds.append(tagKey(p));
            }
        }
        next.properties.insert(cleanKey, arr);
        if (cleanKey == QLatin1String("tags")) {
            QStringList kept;
            for (const QString &l : std::as_const(next.propertyLinks))
                if (!l.startsWith(QLatin1String("tag_")))
                    kept.append(l);
            for (const QString &tid : tagIds)
                if (!kept.contains(tid))
                    kept.append(tid);
            next.propertyLinks = kept;
        }
    } else {
        const QString s = value.toString();
        if (s.trimmed().isEmpty())
            next.properties.remove(cleanKey);
        else
            next.properties.insert(cleanKey, s.trimmed());
    }
    if (addToType)
        addPropertyToType(next.typeId, label.trimmed().isEmpty() ? cleanKey : label.trimmed(),
                          normKind.isEmpty() ? QStringLiteral("text") : normKind);
    saveObject(next);
    if (auto *ed = m_editors.value(objectId)) {
        ed->m_object.properties = next.properties;
        ed->m_object.propertyLinks = next.propertyLinks;
        ed->notifyCoverUpdated();
    }
    emit objectPropertiesChanged(objectId);
}

void Workspace::removeObjectProperty(const QString &objectId, const QString &key)
{
    auto it = m_objects.find(objectId);
    if (it == m_objects.end() || key.trimmed().isEmpty())
        return;
    Object next = it.value();
    if (auto *ed = m_editors.value(objectId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    if (!next.properties.contains(key))
        return;
    next.properties.remove(key);
    saveObject(next);
    if (auto *ed = m_editors.value(objectId))
        ed->m_object.properties = next.properties;
    emit objectPropertiesChanged(objectId);
}

QVariantList Workspace::inbox() const
{
    QVariantList out;
    for (const Object &o : m_objects) {
        const QJsonValue v = o.properties.value(QStringLiteral("reviewed"));
        if (!v.isBool() || v.toBool() || !o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        QVariantMap m = objectSummary(o.id);
        m.insert(QStringLiteral("url"), o.propertyString(QStringLiteral("url")));
        m.insert(QStringLiteral("domain"), QUrl(o.propertyString(QStringLiteral("url"))).host());
        m.insert(QStringLiteral("description"), o.propertyString(QStringLiteral("description")));
        m.insert(QStringLiteral("category"), o.propertyString(QStringLiteral("category")));
        m.insert(QStringLiteral("reviewed"), false);
        out.append(m);
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("updatedAt")).toString()
             > b.toMap().value(QStringLiteral("updatedAt")).toString();
    });
    return out;
}

QVariantList Workspace::reviewedItems(int limit) const
{
    QVariantList out;
    for (const Object &o : m_objects) {
        const QJsonValue v = o.properties.value(QStringLiteral("reviewed"));
        if (!v.isBool() || !v.toBool() || !o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        QVariantMap m = objectSummary(o.id);
        m.insert(QStringLiteral("url"), o.propertyString(QStringLiteral("url")));
        m.insert(QStringLiteral("domain"), QUrl(o.propertyString(QStringLiteral("url"))).host());
        m.insert(QStringLiteral("description"), o.propertyString(QStringLiteral("description")));
        m.insert(QStringLiteral("category"), o.propertyString(QStringLiteral("category")));
        m.insert(QStringLiteral("reviewed"), true);
        out.append(m);
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        return a.toMap().value(QStringLiteral("updatedAt")).toString()
             > b.toMap().value(QStringLiteral("updatedAt")).toString();
    });
    if (limit > 0 && out.size() > limit)
        out = out.mid(0, limit);
    return out;
}

QVariantList Workspace::allTagsWithCounts() const
{
    struct TagEntry {
        QString id;
        QString name;
        int count = 0;
    };
    // Pass 1: register ONLY real Tag objects (o.typeId == TypeId::Tag).
    // Never invent phantom tags from web-link descriptions, legacy properties["tags"] strings,
    // or inline #hashtags (Tags.dc.html: "Cada etiqueta es un objeto").
    QHash<QString, TagEntry> entries; // keyed by tagKey(name)
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.isTemplate() || o.typeId != TypeId::Tag)
            continue;
        const QString clean = QString(o.title).remove(QLatin1Char('#')).trimmed();
        if (clean.isEmpty())
            continue;
        const QString k = tagKey(clean);
        TagEntry &e = entries[k];
        e.id = o.id;
        e.name = clean;
    }

    // Pass 2: count non-trashed objects referencing each registered Tag object.
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.isTemplate() || o.typeId == TypeId::Tag)
            continue;
        QSet<QString> seenInObj;
        for (const QString &raw : o.propertyStringList(QStringLiteral("tags"))) {
            const QString clean = QString(raw).remove(QLatin1Char('#')).trimmed();
            if (clean.isEmpty())
                continue;
            const QString k = tagKey(clean);
            if (entries.contains(k))
                seenInObj.insert(k);
        }
        for (const Block &b : o.blocks) {
            for (const Run &r : b.runs) {
                const QString clean = QString(r.tagName).remove(QLatin1Char('#')).trimmed();
                if (clean.isEmpty())
                    continue;
                const QString k = tagKey(clean);
                if (entries.contains(k))
                    seenInObj.insert(k);
            }
        }
        for (const QString &k : seenInObj) {
            auto it = entries.find(k);
            if (it != entries.end())
                ++it->count;
        }
    }

    QVariantList out;
    out.reserve(entries.size());
    for (auto it = entries.constBegin(); it != entries.constEnd(); ++it) {
        out.append(QVariantMap{
            { "id", it->id },
            { "name", it->name },
            { "color", tagColor(it->name) },
            { "count", it->count },
        });
    }
    std::sort(out.begin(), out.end(), [](const QVariant &a, const QVariant &b) {
        const auto am = a.toMap(), bm = b.toMap();
        if (am.value("count").toInt() != bm.value("count").toInt())
            return am.value("count").toInt() > bm.value("count").toInt();
        return am.value("name").toString().compare(bm.value("name").toString(), Qt::CaseInsensitive) < 0;
    });
    return out;
}

QVariantList Workspace::objectsForTag(const QString &tagName) const
{
    const QString clean = QString(tagName).remove(QLatin1Char('#')).trimmed();
    if (clean.isEmpty())
        return {};
    const QString wantKey = tagKey(clean);
    QList<Object> hits;
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.isTemplate() || o.typeId == TypeId::Tag)
            continue;
        bool matched = false;
        for (const QString &raw : o.propertyStringList(QStringLiteral("tags"))) {
            if (tagKey(QString(raw).remove(QLatin1Char('#')).trimmed()) == wantKey) {
                matched = true;
                break;
            }
        }
        if (!matched) {
            for (const Block &b : o.blocks) {
                for (const Run &r : b.runs) {
                    const QString c = QString(r.tagName).remove(QLatin1Char('#')).trimmed();
                    if (!c.isEmpty() && tagKey(c) == wantKey) {
                        matched = true;
                        break;
                    }
                }
                if (matched)
                    break;
            }
        }
        if (matched)
            hits.append(o);
    }
    std::sort(hits.begin(), hits.end(), [](const Object &a, const Object &b) {
        return a.updatedAt > b.updatedAt;
    });
    QVariantList out;
    out.reserve(hits.size());
    for (const Object &o : hits)
        out.append(objectToVariant(o, viewFor(o), QString()));
    return out;
}

void Workspace::copyTextToClipboard(const QString &text) const
{
    if (QClipboard *cb = QGuiApplication::clipboard())
        cb->setText(text);
}

bool Workspace::copyBlockReference(const QString &objectId, const QString &blockId) const
{
    if (objectId.isEmpty() || blockId.isEmpty())
        return false;
    copyTextToClipboard(QStringLiteral("yaop://object/") + objectId + QLatin1Char('#') + blockId);
    return true;
}

void Workspace::markReviewed(const QString &id)
{
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return;
    Object next = it.value();
    if (auto *ed = m_editors.value(id)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    next.properties.insert(QStringLiteral("reviewed"), true);
    saveObject(next);
    if (auto *ed = m_editors.value(id))
        ed->m_object.properties = next.properties;
    emit objectPropertiesChanged(id);
}

QVariantList Workspace::habits() const
{
    QVariantList out;
    for (const Object &o : m_objects) {
        if (o.typeId != QLatin1String("habit") || !o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        QVariantMap h;
        h.insert(QStringLiteral("id"), o.id);
        h.insert(QStringLiteral("title"), o.title);
        h.insert(QStringLiteral("emoji"), o.icon().emoji);
        QVariantMap rec;
        for (auto it = o.properties.constBegin(); it != o.properties.constEnd(); ++it) {
            const QString k = it.key();
            if (k.startsWith(QLatin1String("d:")))
                rec.insert(k.mid(2), it.value().toVariant());
            else if (k.startsWith(QLatin1String("habit_")))
                h.insert(k.mid(6), it.value().toVariant());
        }
        h.insert(QStringLiteral("rec"), rec);
        out.append(h);
    }
    return out;
}

void Workspace::setHabitDay(const QString &habitId, const QString &isoDate, const QVariant &value)
{
    auto it = m_objects.find(habitId);
    if (it == m_objects.end() || it.value().typeId != QLatin1String("habit"))
        return;
    Object next = it.value();
    if (auto *ed = m_editors.value(habitId)) {
        next.blocks = ed->m_blocks;
        next.title = ed->m_title;
    }
    const QString key = QStringLiteral("d:") + isoDate;
    if (!value.isValid() || value.isNull())
        next.properties.remove(key);
    else if (value.typeId() == QMetaType::Bool)
        next.properties.insert(key, value.toBool());
    else if (value.typeId() == QMetaType::QString)
        next.properties.insert(key, value.toString());
    else
        next.properties.insert(key, value.toDouble());
    saveObject(next);
    if (auto *ed = m_editors.value(habitId))
        ed->m_object.properties = next.properties;
    emit objectPropertiesChanged(habitId);
}

QVariantList Workspace::tasksFor(const QString &scope) const
{
    QVariantList out;
    const QString today = todayIso();
    for (const Object &o : m_objects) {
        if (o.typeId != TypeId::Task || !o.trashedAt.isEmpty())
            continue;
        const ObjectView v = viewFor(o);
        if (scope == QLatin1String("done") && !v.taskDone)
            continue;
        if (scope != QLatin1String("done") && v.taskDone)
            continue;
        if (scope == QLatin1String("today") && v.dueDate > today)
            continue;
        if (scope == QLatin1String("upcoming") && v.dueDate <= today)
            continue;
        out.append(objectToVariant(o, v, QString()));
    }
    return out;
}

QString Workspace::journalLocalFor(const QString &isoDate)
{
    for (const Object &o : m_objects) {
        if (o.typeId == TypeId::Journal
            && o.propertyString(QStringLiteral("date")) == isoDate)
            return o.id;
    }
    return {};
}

QString Workspace::journalFor(const QString &isoDate)
{
    const QString local = journalLocalFor(isoDate);
    if (!local.isEmpty())
        return local;
    // Deterministic id (spec §15.1 in spirit): two devices offline create the
    // same daily and the merge reconciles them.
    Object j;
    j.id = QStringLiteral("daily-") + isoDate;
    j.typeId = TypeId::Journal;
    j.title = QStringLiteral("Journal ") + isoDate;
    j.properties.insert(QStringLiteral("date"), isoDate);
    j.createdAt = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    j.updatedAt = j.createdAt;
    j.blocks.append(Block::makeParagraph(newUuid()));
    saveObject(j);
    emit journalReady(j.id, isoDate);
    return j.id;
}

void Workspace::completeTask(const QString &id)
{
    auto it = m_objects.find(id);
    if (it == m_objects.end())
        return;
    Object next = it.value();
    QJsonObject props = next.properties;
    props.insert(QStringLiteral("status"), QJsonValue(QStringLiteral("done")));
    next.properties = props;
    saveObject(next);
    emit taskCompleted(id);
    ++m_listRevision;
    emit objectListChanged();
}

} // namespace pkm
