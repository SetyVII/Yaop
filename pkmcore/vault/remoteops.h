// Extension point of the sync cycle (spec/sync.md §Operation): one handler per op kind plus a
// pipeline that decodes once and dispatches. A new kind = a new handler registered in
// Workspace::buildOpRegistry(); the pull loop is never edited.
#pragma once
#include <QByteArray>
#include <QJsonObject>
#include <QString>
#include <QStringList>
#include <functional>
#include <memory>
#include <optional>
#include <vector>

namespace pkm {

struct RemoteOp {
    qint64 seq = 0;
    QString kind;
    QString wireId;
    QString deviceId;
    quint32 keyEpoch = 0;
    QByteArray blob; // the sealed envelope
};

struct DecodedOp {
    const RemoteOp &op;
    QJsonObject payload;
};

enum class OpResult {
    Applied, // handled
    Skipped, // not applicable or unreadable: the cursor still advances (local state is never lost)
    Pause,   // stop the pull WITHOUT advancing; the op is delivered again later (merge is idempotent)
};

class RemoteOpHandler {
public:
    virtual ~RemoteOpHandler() = default;
    virtual QStringList kinds() const = 0; // a kind belongs to exactly one handler
    virtual bool needsPayload() const { return true; } // false: nothing is decrypted for it
    virtual OpResult handle(const DecodedOp &op) = 0;
};

// Handler built from a function: the usual way to register a kind without a class per kind.
class FunctionOpHandler : public RemoteOpHandler {
public:
    FunctionOpHandler(QStringList kinds, std::function<OpResult(const DecodedOp &)> fn, bool needsPayload = true)
        : m_kinds(std::move(kinds)), m_fn(std::move(fn)), m_needsPayload(needsPayload) {}
    QStringList kinds() const override { return m_kinds; }
    bool needsPayload() const override { return m_needsPayload; }
    OpResult handle(const DecodedOp &op) override { return m_fn(op); }
private:
    QStringList m_kinds;
    std::function<OpResult(const DecodedOp &)> m_fn;
    bool m_needsPayload;
};

class RemoteOpRegistry {
public:
    // Returns false (and registers nothing) if a kind already has a handler.
    bool add(std::unique_ptr<RemoteOpHandler> handler);
    RemoteOpHandler *handlerFor(const QString &kind) const;
private:
    std::vector<std::unique_ptr<RemoteOpHandler>> m_handlers;
};

// The only place that knows about envelopes and keys: payload bytes, or nullopt (wrong epoch,
// corruption).
using OpOpener = std::function<std::optional<QByteArray>(const RemoteOp &)>;

class RemoteOpPipeline {
public:
    RemoteOpPipeline(const RemoteOpRegistry &registry, OpOpener opener,
                     std::function<void(const QString &)> log = nullptr)
        : m_registry(registry), m_opener(std::move(opener)), m_log(std::move(log)) {}
    OpResult apply(const RemoteOp &op) const;
private:
    const RemoteOpRegistry &m_registry;
    OpOpener m_opener;
    std::function<void(const QString &)> m_log;
};

} // namespace pkm
