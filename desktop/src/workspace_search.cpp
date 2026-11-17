// Workspace: local search, @-mentions, tag suggestions and backlinks.
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
#include "mentionquery.h"

namespace pkm {

using namespace detail;

// Local search over the FTS/inverted index inside the encrypted DB (spec §17.4).
QVariantList Workspace::searchObjects(const QString &query, int limit)
{
    QVariantList out;
    const QString needle = query.trimmed();
    if (needle.isEmpty() || !m_store)
        return out;
    for (const QString &id : m_store->searchIds(needle, limit)) {
        auto it = m_objects.find(id);
        if (it == m_objects.end())
            continue;
        if (!it.value().trashedAt.isEmpty())
            continue;
        out.append(objectToVariant(it.value(), viewFor(it.value()), QString()));
    }
    emit searchResultsReady(out);
    return out;
}

// Spellings accepted before `/` besides each type's id and name (see mentionquery.h).
static const QHash<QString, QString> &typeAliases()
{
    static const QHash<QString, QString> kAliases = {
        { QStringLiteral("nota"), QStringLiteral("note") },
        { QStringLiteral("notas"), QStringLiteral("note") },
        { QStringLiteral("tarea"), QStringLiteral("task") },
        { QStringLiteral("tareas"), QStringLiteral("task") },
        { QStringLiteral("proyecto"), QStringLiteral("project") },
        { QStringLiteral("persona"), QStringLiteral("person") },
        { QStringLiteral("personas"), QStringLiteral("person") },
        { QStringLiteral("diario"), QStringLiteral("journal") },
        { QStringLiteral("daily"), QStringLiteral("journal") },
        { QStringLiteral("pizarra"), QStringLiteral("whiteboard") },
        { QStringLiteral("coleccion"), QStringLiteral("collection") },
        { QStringLiteral("libro"), QStringLiteral("book") },
        { QStringLiteral("weblink"), QStringLiteral("web") },
        { QStringLiteral("enlace"), QStringLiteral("web") },
        { QStringLiteral("imagen"), QStringLiteral("image") },
        { QStringLiteral("etiqueta"), QStringLiteral("tag") },
    };
    return kAliases;
}

// `@` popup entries, in order: types matching the token (only before `/`), up to [limit]
// objects (title matches first, then most recent), and "create <title> in <type>".
// A type entry is {kind:"type", typeId, title (name), slug}; picking it writes `@slug/`.
QVariantList Workspace::mentionSearch(const QString &rawQuery, int limit)
{
    const QString trimmed = rawQuery.trimmed();
    const QList<ObjectType> types = m_types.values();
    QString typeFilter;
    QString titleQuery = trimmed;

    const int slashPos = trimmed.indexOf(QLatin1Char('/'));
    if (slashPos >= 0) {
        typeFilter = mention::resolveTypeToken(trimmed.left(slashPos), types, typeAliases());
        if (!typeFilter.isEmpty())
            titleQuery = trimmed.mid(slashPos + 1).trimmed();
    }

    QList<const Object *> matches;
    QHash<QString, QString> latestByType; // typeId -> newest updatedAt, for "recent types first"
    const QString lowerNeedle = titleQuery.toLower();
    bool exactMatch = false;

    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        if (o.updatedAt > latestByType.value(o.typeId))
            latestByType.insert(o.typeId, o.updatedAt);
        if (!typeFilter.isEmpty() && o.typeId != typeFilter)
            continue;
        const QString plainTitle = plainTitleFor(o);
        if (!lowerNeedle.isEmpty()) {
            const bool inTitle = plainTitle.contains(lowerNeedle, Qt::CaseInsensitive);
            const bool inSummary = o.summary().contains(lowerNeedle, Qt::CaseInsensitive);
            if (!inTitle && !inSummary)
                continue;
            if (plainTitle.compare(titleQuery, Qt::CaseInsensitive) == 0)
                exactMatch = true;
        }
        matches.append(&o);
    }

    std::sort(matches.begin(), matches.end(), [&](const Object *a, const Object *b) {
        if (!lowerNeedle.isEmpty()) {
            const bool aStarts = plainTitleFor(*a).startsWith(lowerNeedle, Qt::CaseInsensitive);
            const bool bStarts = plainTitleFor(*b).startsWith(lowerNeedle, Qt::CaseInsensitive);
            if (aStarts != bStarts)
                return aStarts;
        }
        return a->updatedAt > b->updatedAt;
    });

    QVariantList out;
    if (slashPos < 0) {
        QStringList recentTypeIds = latestByType.keys();
        std::sort(recentTypeIds.begin(), recentTypeIds.end(), [&](const QString &a, const QString &b) {
            return latestByType.value(a) > latestByType.value(b);
        });
        const int typeCap = trimmed.isEmpty() ? 4 : 6;
        for (const ObjectType &t : mention::matchingTypes(trimmed, types, typeAliases(), recentTypeIds)) {
            if (out.size() >= typeCap)
                break;
            out.append(QVariantMap{ { "kind", "type" }, { "typeId", t.id }, { "title", t.name },
                                    { "slug", mention::typeSlug(t.name).isEmpty() ? t.id : mention::typeSlug(t.name) },
                                    { "id", QString() }, { "assetId", QString() }, { "coverAssetId", QString() } });
        }
    }

    const int cap = qMax(1, limit);
    int objectCount = 0;
    for (const Object *o : matches) {
        if (objectCount++ >= cap)
            break;
        out.append(objectToVariant(*o, viewFor(*o), QString()));
    }

    if (!titleQuery.isEmpty() && !exactMatch) {
        const QString createType = typeFilter.isEmpty() ? QString(TypeId::Note) : typeFilter;
        const ObjectType t = m_types.value(createType);
        QVariantMap createItem;
        createItem.insert(QStringLiteral("createNew"), true);
        createItem.insert(QStringLiteral("typeId"), createType);
        createItem.insert(QStringLiteral("typeName"), t.name.isEmpty() ? createType.toUpper() : t.name);
        createItem.insert(QStringLiteral("title"), titleQuery);
        createItem.insert(QStringLiteral("id"), QString());
        createItem.insert(QStringLiteral("assetId"), QString());
        createItem.insert(QStringLiteral("coverAssetId"), QString());
        // Nothing matched: put "create" first so Enter creates it.
        if (matches.isEmpty())
            out.prepend(createItem);
        else
            out.append(createItem);
    }
    return out;
}

QVariantList Workspace::tagSuggestions(const QString &query, int limit) const
{
    const QString cleanQuery = QString(query).remove(QLatin1Char('#')).trimmed();
    const QString lower = cleanQuery.toLower();

    QHash<QString, QString> byKey; // tagKey -> display name
    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty())
            continue;
        if (o.typeId == TypeId::Tag) {
            const QString name = o.title.trimmed();
            if (!name.isEmpty())
                byKey.insert(tagKey(name), name);
        }
        for (const QString &t : o.propertyStringList(QStringLiteral("tags"))) {
            const QString c = QString(t).remove(QLatin1Char('#')).trimmed();
            if (!c.isEmpty() && !byKey.contains(tagKey(c)))
                byKey.insert(tagKey(c), c);
        }
        for (const Block &b : o.blocks) {
            for (const Run &r : b.runs) {
                if (!r.tagName.isEmpty() && !byKey.contains(tagKey(r.tagName)))
                    byKey.insert(tagKey(r.tagName), r.tagName);
            }
        }
    }

    QStringList names = byKey.values();
    std::sort(names.begin(), names.end(), [](const QString &a, const QString &b) {
        return a.toLower() < b.toLower();
    });

    QVariantList out;
    bool exactExists = false;
    const QString wantedKey = cleanQuery.isEmpty() ? QString() : tagKey(cleanQuery);
    for (const QString &n : names) {
        if (tagKey(n) == wantedKey)
            exactExists = true;
        if (!lower.isEmpty() && !n.contains(lower, Qt::CaseInsensitive))
            continue;
        out.append(QVariantMap{ { QStringLiteral("name"), n }, { QStringLiteral("createNew"), false } });
        if (out.size() >= qMax(1, limit))
            break;
    }

    if (!cleanQuery.isEmpty() && !exactExists) {
        out.prepend(QVariantMap{ { QStringLiteral("name"), cleanQuery }, { QStringLiteral("createNew"), true } });
    }
    return out;
}

QString Workspace::ensureTagObject(const QString &tagName)
{
    const QString clean = QString(tagName).remove(QLatin1Char('#')).trimmed();
    if (clean.isEmpty())
        return {};
    const QString id = tagKey(clean);
    auto it = m_objects.find(id);
    if (it != m_objects.end() && it.value().trashedAt.isEmpty())
        return id;

    Object obj = (it != m_objects.end()) ? it.value() : Object();
    const QString nowIso = QDateTime::currentDateTimeUtc().toString(Qt::ISODateWithMs);
    obj.id = id;
    obj.typeId = TypeId::Tag;
    obj.title = clean;
    obj.trashedAt.clear();
    if (obj.createdAt.isEmpty())
        obj.createdAt = nowIso;
    obj.updatedAt = nowIso;
    obj.hlc = newHlc();
    obj.syncStatus = QStringLiteral("pending");
    saveObject(obj);
    return id;
}

// Local references view (spec §4.5): origin title + the snippet of the block
// that holds the reference. No server.
QVariantList Workspace::backlinksFor(const QString &id)
{
    QVariantList out;
    if (m_store) {
        for (const search::SourceRef &r : m_store->backlinkRows(id)) {
            auto it = m_objects.find(r.src);
            if (it == m_objects.end() || !it.value().trashedAt.isEmpty())
                continue;
            const Object &src = it.value();
            QString snippet;
            if (!r.blockId.isEmpty()) {
                for (const Block &b : src.blocks) {
                    if (b.id == r.blockId) {
                        snippet = b.plainText();
                        break;
                    }
                }
            }
            if (snippet.isEmpty())
                snippet = src.summary().isEmpty() ? src.title : src.summary();
            QVariantMap m = objectToVariant(src, viewFor(src), QString());
            m.insert("snippet", snippet.left(160));
            m.insert("blockId", r.blockId);
            out.append(m);
        }
    }
    emit backlinksReady(id, out);
    return out;
}

QVariantList Workspace::objectsCreatedOn(const QString &isoDate) const
{
    QList<const Object *> matches;
    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        if (o.typeId == TypeId::Journal && o.id == QStringLiteral("daily-") + isoDate)
            continue;
        QString day;
        QDateTime dt = QDateTime::fromString(o.createdAt, Qt::ISODateWithMs);
        if (!dt.isValid())
            dt = QDateTime::fromString(o.createdAt, Qt::ISODate);
        if (dt.isValid())
            day = dt.toLocalTime().date().toString(QStringLiteral("yyyy-MM-dd"));
        else
            day = o.createdAt.left(10);
        if (day == isoDate)
            matches.append(&o);
    }
    std::sort(matches.begin(), matches.end(), [](const Object *a, const Object *b) {
        return a->createdAt > b->createdAt;
    });
    QVariantList out;
    out.reserve(matches.size());
    for (const Object *o : matches)
        out.append(objectToVariant(*o, viewFor(*o), QString()));
    return out;
}

QVariantMap Workspace::dayMarks(const QString &fromIso, const QString &toIso) const
{
    QHash<QString, QVariantMap> days;
    auto bump = [&](const QString &day, const char *key) {
        if (day < fromIso || day > toIso)
            return;
        QVariantMap &m = days[day];
        m.insert(QLatin1String(key), m.value(QLatin1String(key)).toInt() + 1);
    };
    for (const Object &o : m_objects) {
        if (!o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        if (o.typeId == TypeId::Journal) {
            const QString day = o.propertyString(QStringLiteral("date"));
            const bool hasText = std::any_of(o.blocks.cbegin(), o.blocks.cend(),
                                             [](const Block &b) { return !b.plainText().trimmed().isEmpty(); });
            if (hasText)
                bump(day, "note");
            continue;
        }
        if (o.typeId == TypeId::Task) {
            const ObjectView v = viewFor(o);
            if (!v.taskDone && !v.dueDate.isEmpty())
                bump(v.dueDate.left(10), "tasks");
        }
        QDateTime dt = QDateTime::fromString(o.createdAt, Qt::ISODateWithMs);
        if (!dt.isValid())
            dt = QDateTime::fromString(o.createdAt, Qt::ISODate);
        bump(dt.isValid() ? dt.toLocalTime().date().toString(QStringLiteral("yyyy-MM-dd")) : o.createdAt.left(10),
             "created");
    }
    if (m_store) {
        const QDate from = QDate::fromString(fromIso, Qt::ISODate);
        const QDate to = QDate::fromString(toIso, Qt::ISODate);
        for (QDate d = from; d.isValid() && d <= to && from.daysTo(d) < 62; d = d.addDays(1)) {
            const QString iso = d.toString(QStringLiteral("yyyy-MM-dd"));
            int n = 0;
            for (const search::SourceRef &r : m_store->backlinkRows(QStringLiteral("daily-") + iso)) {
                auto it = m_objects.find(r.src);
                if (it != m_objects.end() && it.value().trashedAt.isEmpty())
                    ++n;
            }
            if (n > 0)
                days[iso].insert(QStringLiteral("refs"), n);
        }
    }
    QVariantMap out;
    for (auto it = days.cbegin(); it != days.cend(); ++it)
        out.insert(it.key(), it.value());
    return out;
}

} // namespace pkm
