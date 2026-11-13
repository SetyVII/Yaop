#pragma once

// Merge + stamp of spec/merge.md (yaop-sync/1, port of the Go reference in
// server/internal/sync/{merge.go,stamp.go}). Everything operates on the WIRE
// JSON of an object, never on the internal Object model, so the fixtures of
// spec/fixtures/{merge,merge_assoc,stamp} pass byte for byte on Go, Kotlin and C++.

#include <QJsonObject>

namespace pkm {

// Wire-level stamp (spec/merge.md §Stamp). `prev` is nullptr for a new object.
// `prevMeta` carries the previous merged meta (field_hlc, block_hlc,
// deleted_blocks and — yaop-sync/1 — block_anchor); `metaOut` receives the
// new meta, with block_anchor present whenever the save produced anchors.
// Returns true when something changed (the caller then adopts `now` as hlc).
bool stampWire(const QJsonObject *prev, const QJsonObject &next, const QJsonObject &prevMeta,
               const QString &now, QJsonObject &metaOut);

// Merge of two wire versions of the same object (spec/merge.md §Merge):
// deterministic and symmetric; associative on anchor-complete (/3) states.
// title/type_id/properties/blocks/order/meta follow the contract; `content`
// and `updated_at` come from the primary side (presentation passthrough).
QJsonObject mergeWire(const QJsonObject &a, const QJsonObject &b);

} // namespace pkm
