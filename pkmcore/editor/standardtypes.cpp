#include "standardtypes.h"

#include <QFile>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QSet>

namespace pkm {

namespace {

QString pickLabel(const QJsonObject &labels, const QString &lang)
{
    const QString l = labels.value(lang).toString();
    return l.isEmpty() ? labels.value(QLatin1String("en")).toString() : l;
}

QJsonObject typeEntry(const QString &typeId)
{
    for (const QJsonValue &v : standardTypesDoc().value(QLatin1String("types")).toArray()) {
        const QJsonObject t = v.toObject();
        if (t.value(QLatin1String("id")).toString() == typeId)
            return t;
    }
    return {};
}

} // namespace

const QJsonObject &standardTypesDoc()
{
    static const QJsonObject doc = [] {
        QFile f(QStringLiteral(":/types/standard.json"));
        if (!f.open(QIODevice::ReadOnly))
            return QJsonObject();
        return QJsonDocument::fromJson(f.readAll()).object();
    }();
    return doc;
}

QStringList standardTypeIds()
{
    QStringList out;
    for (const QJsonValue &v : standardTypesDoc().value(QLatin1String("types")).toArray())
        out.append(v.toObject().value(QLatin1String("id")).toString());
    return out;
}

QString standardTypeLabel(const QString &typeId, const QString &lang)
{
    return pickLabel(typeEntry(typeId).value(QLatin1String("labels")).toObject(), lang);
}

QJsonArray standardPropertyDefs(const QString &typeId, const QString &lang)
{
    QJsonArray out;
    for (const QJsonValue &pv : typeEntry(typeId).value(QLatin1String("properties")).toArray()) {
        const QJsonObject p = pv.toObject();
        const QString key = p.value(QLatin1String("key")).toString();
        QJsonObject def{
            { "id", QStringLiteral("prop_") + key },
            { "key", key },
            { "name", pickLabel(p.value(QLatin1String("labels")).toObject(), lang) },
            { "kind", p.value(QLatin1String("kind")).toString() },
        };
        if (p.value(QLatin1String("system")).toBool())
            def.insert(QStringLiteral("system"), true);
        if (p.contains(QLatin1String("options"))) {
            QJsonArray opts;
            for (const QJsonValue &ov : p.value(QLatin1String("options")).toArray()) {
                const QJsonObject o = ov.toObject();
                opts.append(QJsonObject{
                    { "id", o.value(QLatin1String("id")).toString() },
                    { "label", pickLabel(o.value(QLatin1String("labels")).toObject(), lang) },
                    { "color", o.value(QLatin1String("color")).toString() },
                });
            }
            def.insert(QStringLiteral("options"), opts);
        }
        out.append(def);
    }
    return out;
}

QJsonArray mergeStandardPropertyDefs(const QJsonArray &standard, const QJsonArray &extra)
{
    QJsonArray out = standard;
    QSet<QString> keys;
    for (const QJsonValue &v : standard)
        keys.insert(v.toObject().value(QLatin1String("key")).toString());
    for (const QJsonValue &v : extra) {
        const QJsonObject o = v.toObject();
        const QString key = o.value(QLatin1String("key")).toString(o.value(QLatin1String("id")).toString());
        if (key.isEmpty() || keys.contains(key))
            continue;
        keys.insert(key);
        out.append(o);
    }
    return out;
}

QStringList validateStandardTypes(const QJsonObject &doc)
{
    static const QSet<QString> kKinds = { "text", "number", "date", "select", "multi_select",
                                          "checkbox", "url", "object", "media" };
    QStringList problems;
    QSet<QString> palette;
    for (const QJsonValue &v : doc.value(QLatin1String("palette")).toArray())
        palette.insert(v.toString());
    QSet<QString> typeIds;
    for (const QJsonValue &tv : doc.value(QLatin1String("types")).toArray()) {
        const QJsonObject t = tv.toObject();
        const QString tid = t.value(QLatin1String("id")).toString();
        if (tid.isEmpty() || typeIds.contains(tid))
            problems.append(QStringLiteral("type id missing or repeated: ") + tid);
        typeIds.insert(tid);
        if (t.value(QLatin1String("labels")).toObject().value(QLatin1String("en")).toString().isEmpty())
            problems.append(tid + QStringLiteral(": no English label"));
        QSet<QString> keys;
        for (const QJsonValue &pv : t.value(QLatin1String("properties")).toArray()) {
            const QJsonObject p = pv.toObject();
            const QString key = p.value(QLatin1String("key")).toString();
            const QString kind = p.value(QLatin1String("kind")).toString();
            const QString where = tid + QLatin1Char('.') + key;
            if (key.isEmpty() || keys.contains(key))
                problems.append(QStringLiteral("property key missing or repeated: ") + where);
            keys.insert(key);
            if (!kKinds.contains(kind))
                problems.append(where + QStringLiteral(": unknown kind ") + kind);
            const bool hasOptions = p.contains(QLatin1String("options"));
            const bool isSelect = kind == QLatin1String("select") || kind == QLatin1String("multi_select");
            if (hasOptions && !isSelect)
                problems.append(where + QStringLiteral(": options on a non-select kind"));
            QSet<QString> ids;
            for (const QJsonValue &ov : p.value(QLatin1String("options")).toArray()) {
                const QJsonObject o = ov.toObject();
                const QString id = o.value(QLatin1String("id")).toString();
                static const QRegularExpression kIdRe(QStringLiteral("^[a-z0-9_]+$"));
                if (!kIdRe.match(id).hasMatch() || ids.contains(id))
                    problems.append(where + QStringLiteral(": bad or repeated option id ") + id);
                ids.insert(id);
                if (!palette.contains(o.value(QLatin1String("color")).toString()))
                    problems.append(where + QLatin1Char('.') + id + QStringLiteral(": color not in palette"));
                if (o.value(QLatin1String("labels")).toObject().value(QLatin1String("en")).toString().isEmpty())
                    problems.append(where + QLatin1Char('.') + id + QStringLiteral(": no English label"));
            }
        }
    }
    return problems;
}

} // namespace pkm
