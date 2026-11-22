#pragma once
// Collections (product §19): a manual, ordered list of object ids in `properties.items`.
// Pure list operations; the caller saves the object. No id appears twice.

#include <QStringList>
#include <functional>

namespace pkm::collection {

// Appends `id` (no-op if it is already there or empty).
QStringList add(const QStringList &items, const QString &id);
QStringList remove(const QStringList &items, const QString &id);
// Moves the item at `from` to index `to` (clamped). Out-of-range `from` changes nothing.
QStringList move(const QStringList &items, int from, int to);
// Drops repeats (first one wins) and ids `alive` rejects.
QStringList clean(const QStringList &items, const std::function<bool(const QString &)> &alive);

} // namespace pkm::collection
