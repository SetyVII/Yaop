#pragma once

// Local query engine of spec/query.md (yaop-query/1), port of the Go reference
// server/internal/simclient/query.go. Pure: it evaluates over WIRE JSON objects (type ids
// already normalized) and passes spec/fixtures/query byte for byte, like Go and Kotlin.

#include <QJsonArray>
#include <QJsonObject>
#include <QString>
#include <QStringList>
#include <QVector>
#include <optional>

namespace pkm::query {

struct Group {
    std::optional<QString> key; // nullopt = the "null" group (empty values), always last
    QStringList ids;
};

struct Result {
    QString error;        // non-empty = contract error code (unsupported_version, unknown_op, …)
    bool grouped = false; // true when the query has `group`
    QStringList ids;
    QVector<Group> groups;
};

// today = the device's local day "YYYY-MM-DD" (spec §20).
Result evaluate(const QJsonObject &query, const QVector<QJsonObject> &objects, const QString &today);

// Helpers shared with callers and tests.
QString lower(const QString &s);                      // simple per-code-point lowercase
int comparePoints(const QString &a, const QString &b); // by Unicode code point

} // namespace pkm::query
