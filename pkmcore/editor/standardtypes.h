#pragma once
// Standard types and their properties (spec/types.md). Built from spec/fixtures/types/standard.json,
// which is baked into the binary as :/types/standard.json. Not synced: every client has the same.

#include <QJsonArray>
#include <QJsonObject>
#include <QString>
#include <QStringList>

namespace pkm {

// The parsed file. Empty object if the resource is missing.
const QJsonObject &standardTypesDoc();

// Ids of the standard types, in file order.
QStringList standardTypeIds();

// Display name of a standard type in `lang` ("es", "en"…), falling back to English.
QString standardTypeLabel(const QString &typeId, const QString &lang);

// Property definitions of a standard type in the shape custom types use on the wire:
// [{id, key, name, kind, system?, options?: [{id, label, color}]}], labels resolved for `lang`.
QJsonArray standardPropertyDefs(const QString &typeId, const QString &lang);

// Standard definitions first, then the `extra` ones (e.g. a synced copy of the type) whose key is
// not a standard key (spec/types.md rule 5).
QJsonArray mergeStandardPropertyDefs(const QJsonArray &standard, const QJsonArray &extra);

// Checks spec/types.md rules 1–3 on a parsed file. Returns the problems found (empty = valid).
QStringList validateStandardTypes(const QJsonObject &doc);

} // namespace pkm
