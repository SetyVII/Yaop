#pragma once

// Builds push batches from the offline queue .
// - Consecutive puts of the same object collapse into the last one: each put carries the full
//   stamped object, so the last one supersedes the earlier ones (spec/merge.md).
// - Each object keeps the position of its first queued op, so an object goes before anything
//   queued later that links to it, and a delete is never reordered against that object's puts.
// - Batches stay under the server's per-push limit (500 ops).

#include "store.h"

#include <QJsonArray>
#include <QList>

namespace pkm {

struct PushOp {
    QString opId;
    QString objectId;
    QJsonObject wire;          // {op_id, action, object | object_id}
    QList<qlonglong> rowids;   // queue rows this op covers (dequeued when it is applied)
};

bool isQueuedDelete(const PendingOp &op);
QList<QList<PushOp>> buildPushBatches(const QList<PendingOp> &queue, int maxOpsPerBatch = 200);

} // namespace pkm
