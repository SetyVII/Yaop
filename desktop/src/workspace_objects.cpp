// Workspace: objects, types, properties, sets, tasks, habits and the journal.
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
    m_pendingCount = m_store->pendingOps().size();
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
    m_pendingCount = m_store->pendingOps().size();
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
    QVector<QJsonObject> objects;
    objects.reserve(m_objects.size());
    for (const Object &o : m_objects)
        if (o.trashedAt.isEmpty())
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

QVariantMap Workspace::runSet(const QString &setId) const
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
    for (const QString &id : r.value(QStringLiteral("ids")).toStringList()) {
        if (id == setId)
            continue; // a set never lists itself
        items << objectSummary(id);
    }
    out.insert(QStringLiteral("items"), items);
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
        if (!o.trashedAt.isEmpty() || o.typeId != typeId)
            continue;
        out.append(objectToVariant(o, viewFor(o), QString()));
    }
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

QString Workspace::createCustomType(const QString &name, const QString &colorNameOrHex, const QVariantList &propDefs)
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
    t.icon = QStringLiteral("box");
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
        m_pendingCount = m_store->pendingOps().size();
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
        m_pendingCount = m_store->pendingOps().size();
        emit syncStateChanged();
        schedulePush(1200);
    }
    emit typesChanged();
    return true;
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
        out.append(m);
    }
    return out;
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

    auto appendEntry = [&](const QString &key, const QString &name, const QString &kind) {
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
        entry.insert("value", displayVal);
        entry.insert("checked", boolVal);
        out.append(entry);
    };

    for (const QJsonValue &v : t.propertyDefs) {
        const QJsonObject o = v.toObject();
        const QString k = normalizePropertyKey(o.value(QLatin1String("key")).toString(o.value(QLatin1String("id")).toString()));
        appendEntry(k, o.value(QLatin1String("name")).toString(k), o.value(QLatin1String("kind")).toString(QStringLiteral("text")));
    }

    static const QSet<QString> kHidden = {
        QStringLiteral("blocks"), QStringLiteral("pinned"), QStringLiteral("is_template"),
        QStringLiteral("summary"), QStringLiteral("icon"), QStringLiteral("cover"),
        QStringLiteral("aliases"), QStringLiteral("asset_id"), QStringLiteral("mime"),
        QStringLiteral("file_name"), QStringLiteral("size"), QStringLiteral("chunks"),
        QStringLiteral("chunk_size"), QStringLiteral("total_plain_size"),
        QStringLiteral("thumbnail_url"), QStringLiteral("favicon_url"),
    };
    QStringList objKeys = obj.properties.keys();
    std::sort(objKeys.begin(), objKeys.end());
    for (const QString &k : objKeys) {
        if (kHidden.contains(k))
            continue;
        appendEntry(k, k, QString());
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
        for (const QString &part : value.toString().split(QLatin1Char(','), Qt::SkipEmptyParts)) {
            const QString p = part.trimmed();
            if (!p.isEmpty())
                arr.append(p);
        }
        next.properties.insert(cleanKey, arr);
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
        QVariantMap m;
        m.insert(QStringLiteral("id"), o.id);
        m.insert(QStringLiteral("title"), o.title);
        m.insert(QStringLiteral("typeId"), o.typeId);
        out.append(m);
    }
    return out;
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
