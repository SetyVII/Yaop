#include "nodeops.h"
#include <QFile>
#include <QJsonDocument>
#include <QRegularExpression>

namespace pkm {

std::optional<DerivedArtifact> DerivedArtifact::parse(const QJsonObject &p)
{
    if (p.value(QLatin1String("v")).toInt() != 1)
        return std::nullopt;
    DerivedArtifact a;
    a.kind = p.value(QLatin1String("kind")).toString();
    a.sourceHash = p.value(QLatin1String("source_hash")).toString();
    a.producer = p.value(QLatin1String("producer")).toString();
    if (a.kind.isEmpty() || a.sourceHash.isEmpty() || !p.contains(QLatin1String("data")))
        return std::nullopt;
    const QJsonValue d = p.value(QLatin1String("data"));
    a.data = d.isObject() ? d.toObject() : QJsonObject{ { "value", d } };
    return a;
}

QJsonObject DerivedArtifact::toJson() const
{
    return QJsonObject{ { "v", 1 }, { "kind", kind }, { "source_hash", sourceHash },
                        { "producer", producer }, { "data", data } };
}

QString FileDerivedSink::pathFor(const QString &wireId) const
{
    static const QRegularExpression safe(QStringLiteral("^[0-9a-f]{1,64}$"));
    return safe.match(wireId).hasMatch() ? m_dir + QLatin1Char('/') + wireId + QStringLiteral(".json") : QString();
}

bool FileDerivedSink::put(const QString &wireId, const DerivedArtifact &artifact)
{
    const QString path = pathFor(wireId);
    if (path.isEmpty() || !QDir().mkpath(m_dir))
        return false;
    QFile f(path);
    return f.open(QIODevice::WriteOnly | QIODevice::Truncate)
        && f.write(QJsonDocument(artifact.toJson()).toJson(QJsonDocument::Compact)) > 0;
}

std::optional<DerivedArtifact> FileDerivedSink::get(const QString &wireId) const
{
    const QString path = pathFor(wireId);
    QFile f(path);
    if (path.isEmpty() || !f.open(QIODevice::ReadOnly))
        return std::nullopt;
    return DerivedArtifact::parse(QJsonDocument::fromJson(f.readAll()).object());
}

std::unique_ptr<RemoteOpHandler> makeDerivedOpHandler(DerivedSink *sink)
{
    return std::make_unique<FunctionOpHandler>(QStringList{ QStringLiteral("derived") }, [sink](const DecodedOp &d) {
        const auto a = DerivedArtifact::parse(d.payload);
        // Last writer wins: ops arrive in seq order, a newer producer simply overwrites.
        return a && sink->put(d.op.wireId, *a) ? OpResult::Applied : OpResult::Skipped;
    });
}

std::optional<NodeRequest> NodeRequest::parse(const QJsonObject &p)
{
    if (p.value(QLatin1String("v")).toInt() != 1)
        return std::nullopt;
    NodeRequest r{ p.value(QLatin1String("object_id")).toString(), p.value(QLatin1String("kind")).toString() };
    if (r.objectId.isEmpty() || r.kind.isEmpty())
        return std::nullopt;
    return r;
}

std::unique_ptr<RemoteOpHandler> makeNodeRequestOpHandler(NodeRequestSink *sink)
{
    return std::make_unique<FunctionOpHandler>(QStringList{ QStringLiteral("node_request") }, [sink](const DecodedOp &d) {
        const auto r = NodeRequest::parse(d.payload);
        if (!r || !sink)
            return OpResult::Skipped;
        sink->enqueue(*r);
        return OpResult::Applied;
    });
}

} // namespace pkm
