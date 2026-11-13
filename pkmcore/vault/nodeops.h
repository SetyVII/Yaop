// `derived` artifacts and `node_request`s (spec/sync.md derived artifacts, spec §23).
// Consumed through small sinks: storage can change, and a device that is not the trust node keeps
// requests without acting on them. Port of core/sync/ops/NodeOps.kt.
#pragma once
#include "remoteops.h"
#include <QDir>
#include <optional>

namespace pkm {

struct DerivedArtifact {
    QString kind;
    QString sourceHash;
    QString producer;
    QJsonObject data;

    // nullopt when the payload does not follow the version-1 manifest.
    static std::optional<DerivedArtifact> parse(const QJsonObject &payload);
    QJsonObject toJson() const;
};

class DerivedSink {
public:
    virtual ~DerivedSink() = default;
    virtual bool put(const QString &wireId, const DerivedArtifact &artifact) = 0;
    virtual std::optional<DerivedArtifact> get(const QString &wireId) const = 0;
};

// One JSON file per artifact; the blind wire id (lowercase hex) is a safe file name.
class FileDerivedSink : public DerivedSink {
public:
    explicit FileDerivedSink(QString dir) : m_dir(std::move(dir)) {}
    bool put(const QString &wireId, const DerivedArtifact &artifact) override;
    std::optional<DerivedArtifact> get(const QString &wireId) const override;
private:
    QString pathFor(const QString &wireId) const; // empty if the id is not safe
    QString m_dir;
};

std::unique_ptr<RemoteOpHandler> makeDerivedOpHandler(DerivedSink *sink);

struct NodeRequest {
    QString objectId;
    QString kind;
    static std::optional<NodeRequest> parse(const QJsonObject &payload);
};

class NodeRequestSink {
public:
    virtual ~NodeRequestSink() = default;
    virtual void enqueue(const NodeRequest &request) = 0;
};

// Only the trust node acts on requests : with sink == nullptr they are seen and skipped.
std::unique_ptr<RemoteOpHandler> makeNodeRequestOpHandler(NodeRequestSink *sink);

} // namespace pkm
