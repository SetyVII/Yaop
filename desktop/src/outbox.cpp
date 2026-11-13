#include "outbox.h"

#include <QHash>

namespace pkm {

bool isQueuedDelete(const PendingOp &op)
{
    return op.payload.contains(QLatin1String("object_id")) && op.payload.size() <= 2;
}

QList<QList<PushOp>> buildPushBatches(const QList<PendingOp> &queue, int maxOpsPerBatch)
{
    QList<PushOp> ops;
    QHash<QString, int> lastPutIndex; // object id -> index in ops of its open put
    for (const PendingOp &q : queue) {
        PushOp op;
        op.opId = q.opId;
        op.objectId = q.objectId;
        op.rowids = { q.rowid };
        op.wire.insert(QStringLiteral("op_id"), q.opId);
        if (isQueuedDelete(q)) {
            op.wire.insert(QStringLiteral("action"), QStringLiteral("delete"));
            op.wire.insert(QStringLiteral("object_id"), q.payload.value(QLatin1String("object_id")));
            lastPutIndex.remove(q.objectId);
            ops.append(op);
            continue;
        }
        op.wire.insert(QStringLiteral("action"), QStringLiteral("put"));
        op.wire.insert(QStringLiteral("object"), q.payload);
        const auto it = lastPutIndex.constFind(q.objectId);
        if (it != lastPutIndex.cend()) {
            PushOp &prev = ops[it.value()];
            op.rowids = prev.rowids + op.rowids;
            prev = op;
            continue;
        }
        lastPutIndex.insert(q.objectId, ops.size());
        ops.append(op);
    }

    QList<QList<PushOp>> batches;
    const int size = qMax(1, maxOpsPerBatch);
    for (int i = 0; i < ops.size(); i += size)
        batches.append(ops.mid(i, size));
    return batches;
}

} // namespace pkm
