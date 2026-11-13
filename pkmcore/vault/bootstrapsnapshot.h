// Bootstrap snapshot container (spec/sync.md bootstrap snapshot section): the full state of the vault
// as of covered_seq, so a new device (or one behind the compaction floor) does not replay the log.
// Pure: no network, no database. Port of core/sync/ops/BootstrapSnapshot.kt.
#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <optional>

namespace pkm::bootstrap {

inline constexpr int kVersion = 1;
inline const char *kBlindInput = "bootstrap"; // stored under blindId("bootstrap")

struct Parsed {
    qlonglong keyEpoch = 1;
    qlonglong coveredSeq = 0;
    QJsonArray objects;
    QJsonArray types;
};

inline QJsonObject build(qlonglong keyEpoch, qlonglong coveredSeq, const QJsonArray &objects, const QJsonArray &types)
{
    return QJsonObject{ { "v", kVersion }, { "key_epoch", double(keyEpoch) }, { "covered_seq", double(coveredSeq) },
                        { "objects", objects }, { "types", types } };
}

// nullopt when the payload is not a container (e.g. the snapshot of a single object).
inline std::optional<Parsed> parse(const QJsonObject &payload)
{
    if (!payload.value(QLatin1String("objects")).isArray() || payload.value(QLatin1String("v")).toInt() != kVersion)
        return std::nullopt;
    Parsed p;
    p.keyEpoch = qlonglong(payload.value(QLatin1String("key_epoch")).toDouble(1));
    p.coveredSeq = qlonglong(payload.value(QLatin1String("covered_seq")).toDouble());
    p.objects = payload.value(QLatin1String("objects")).toArray();
    p.types = payload.value(QLatin1String("types")).toArray();
    return p;
}

} // namespace pkm::bootstrap
