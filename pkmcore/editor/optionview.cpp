#include "optionview.h"

#include <QMap>
#include <algorithm>

namespace pkm {

QStringList propertyValues(const Object &o, const QString &key)
{
    const QJsonValue v = o.properties.value(normalizePropertyKey(key));
    QStringList out;
    if (v.isString()) {
        if (!v.toString().trimmed().isEmpty())
            out << v.toString().trimmed();
    } else if (v.isArray()) {
        for (const QJsonValue &e : v.toArray())
            if (e.isString() && !e.toString().trimmed().isEmpty())
                out << e.toString().trimmed();
    }
    return out;
}

static bool hasValue(const QStringList &values, const QString &value)
{
    for (const QString &v : values)
        if (v.compare(value, Qt::CaseInsensitive) == 0)
            return true;
    return false;
}

OptionNeighborhood optionNeighborhood(const QHash<QString, Object> &objects, const QString &typeId,
                                      const QString &prop, const QString &value,
                                      const QStringList &otherProps)
{
    OptionNeighborhood out;
    const QString wanted = value.trimmed();
    if (wanted.isEmpty())
        return out;
    // key "prop\nlowercased value" -> (display value, object ids)
    QMap<QString, QPair<QString, QStringList>> rel;
    for (auto it = objects.constBegin(); it != objects.constEnd(); ++it) {
        const Object &o = it.value();
        if (!o.trashedAt.isEmpty() || (!typeId.isEmpty() && o.typeId != typeId))
            continue;
        if (!hasValue(propertyValues(o, prop), wanted))
            continue;
        out.objectIds << o.id;
        for (const QString &p : otherProps) {
            QStringList seen;
            for (const QString &v : propertyValues(o, p)) {
                if (normalizePropertyKey(p) == normalizePropertyKey(prop) && v.compare(wanted, Qt::CaseInsensitive) == 0)
                    continue;
                const QString k = normalizePropertyKey(p) + QLatin1Char('\n') + v.toLower();
                if (seen.contains(k))
                    continue;
                seen << k;
                auto &entry = rel[k];
                if (entry.first.isEmpty())
                    entry.first = v;
                entry.second << o.id;
            }
        }
    }
    std::sort(out.objectIds.begin(), out.objectIds.end());
    for (auto it = rel.constBegin(); it != rel.constEnd(); ++it) {
        RelatedValue r;
        r.prop = it.key().section(QLatin1Char('\n'), 0, 0);
        r.value = it.value().first;
        r.objectIds = it.value().second;
        std::sort(r.objectIds.begin(), r.objectIds.end());
        out.related << r;
    }
    std::stable_sort(out.related.begin(), out.related.end(), [](const RelatedValue &a, const RelatedValue &b) {
        if (a.objectIds.size() != b.objectIds.size())
            return a.objectIds.size() > b.objectIds.size();
        if (a.prop != b.prop)
            return a.prop < b.prop;
        return a.value.toLower() < b.value.toLower();
    });
    return out;
}

} // namespace pkm
