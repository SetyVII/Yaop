#include "remoteops.h"
#include <QJsonDocument>

namespace pkm {

bool RemoteOpRegistry::add(std::unique_ptr<RemoteOpHandler> handler)
{
    for (const QString &k : handler->kinds())
        if (handlerFor(k))
            return false;
    m_handlers.push_back(std::move(handler));
    return true;
}

RemoteOpHandler *RemoteOpRegistry::handlerFor(const QString &kind) const
{
    for (const auto &h : m_handlers)
        if (h->kinds().contains(kind))
            return h.get();
    return nullptr;
}

OpResult RemoteOpPipeline::apply(const RemoteOp &op) const
{
    auto log = [&](const QString &m) { if (m_log) m_log(m); };
    RemoteOpHandler *handler = m_registry.handlerFor(op.kind);
    if (!handler) {
        log(QStringLiteral("no handler for op kind '%1' at seq %2; skipping").arg(op.kind).arg(op.seq));
        return OpResult::Skipped;
    }
    if (!handler->needsPayload())
        return handler->handle(DecodedOp{ op, {} });
    const auto plain = m_opener(op);
    if (!plain) {
        log(QStringLiteral("undecryptable %1 op at seq %2 (wire_id %3); skipping").arg(op.kind).arg(op.seq).arg(op.wireId));
        return OpResult::Skipped;
    }
    const QJsonDocument doc = QJsonDocument::fromJson(*plain);
    if (!doc.isObject()) {
        log(QStringLiteral("non-object payload in %1 op at seq %2; skipping").arg(op.kind).arg(op.seq));
        return OpResult::Skipped;
    }
    return handler->handle(DecodedOp{ op, doc.object() });
}

} // namespace pkm
