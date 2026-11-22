#include "collection.h"

#include <algorithm>

namespace pkm::collection {

QStringList add(const QStringList &items, const QString &id)
{
    if (id.isEmpty() || items.contains(id))
        return items;
    QStringList out = items;
    out << id;
    return out;
}

QStringList remove(const QStringList &items, const QString &id)
{
    QStringList out = items;
    out.removeAll(id);
    return out;
}

QStringList move(const QStringList &items, int from, int to)
{
    if (from < 0 || from >= items.size())
        return items;
    QStringList out = items;
    const QString id = out.takeAt(from);
    out.insert(std::clamp(to, 0, int(out.size())), id);
    return out;
}

QStringList clean(const QStringList &items, const std::function<bool(const QString &)> &alive)
{
    QStringList out;
    for (const QString &id : items)
        if (!id.isEmpty() && !out.contains(id) && (!alive || alive(id)))
            out << id;
    return out;
}

} // namespace pkm::collection
