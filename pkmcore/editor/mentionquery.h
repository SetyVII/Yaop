#pragma once

// `@type/name` mentions: how a type is written and which types a typed prefix suggests.
// A type is written as its slug: lowercase, no accents, spaces and `_` become `-`
// ("Web Links" → `web-links`, "Modelos de IA" → `modelos-de-ia`). Matching ignores the
// separators, so `weblinks`, `web_links` and `web-links` are the same type.

#include "model.h"

#include <QHash>
#include <QList>
#include <QStringList>

namespace pkm::mention {

QString typeSlug(const QString &name);
QString typeKey(const QString &name); // slug without separators, for comparisons

// Type id for the token before `/`: exact id, name or alias first, then a prefix (2+ chars).
// [aliases] maps an alias (any spelling) to a type id. Empty when nothing matches.
QString resolveTypeToken(const QString &token, const QList<ObjectType> &types,
                         const QHash<QString, QString> &aliases);

// Types whose id, name, alias or any word of the name starts with [prefix] (all of them when
// empty), the most recently used first ([recentTypeIds], most recent first), then by name.
QList<ObjectType> matchingTypes(const QString &prefix, const QList<ObjectType> &types,
                                const QHash<QString, QString> &aliases,
                                const QStringList &recentTypeIds);

} // namespace pkm::mention
