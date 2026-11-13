// Workspace: the sync loop (push, pull, live stream, bootstrap snapshots).
#include "workspace.h"
#include "backup.h"
#include "syncpolicy.h"
#include "query.h"
#include "remoteops.h"
#include "bootstrapsnapshot.h"
#include "nodeops.h"
#include <QSaveFile>
#include <QRegularExpression>
#include <QImage>
#include <QMimeData>
#include <QGuiApplication>
#include <QClipboard>
#include "bip39.h"
#include "outbox.h"
#include "merge.h"
#include <QSysInfo>

#include <QCoreApplication>
#include <QDate>
#include <QLocale>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QCryptographicHash>
#include <QJsonDocument>
#include <QNetworkInformation>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSet>
#include <QSettings>
#include <QStandardPaths>
#include <QUrl>
#ifdef Q_OS_LINUX
#include <malloc.h>
#endif

#include "workspace_internal.h"

namespace pkm {

using namespace detail;

// ---------------------------------------------------------------------------
// Sync internals (yaop-sync/1, blind server: push sealed ops, pull all
// ops since the cursor, decrypt → merge → save, re-push when it differs)
// ---------------------------------------------------------------------------
void Workspace::schedulePush(int ms)
{
    // While an editor is actively being typed into, hold pushes back longer.
    const int effective = m_activeEditingId.isEmpty() ? ms : qMax(ms, 6000);
    m_pushDebounce.start(effective);
}

void Workspace::schedulePull(int ms)
{
    m_pullDebounce.start(ms);
}

void Workspace::syncNow()
{
    if (m_syncing) {
        // Edits queued during this sync must not wait for the next trigger.
        m_resyncRequested = true;
        return;
    }
    m_resyncRequested = false;
    uploadPendingAssets();
    if (!serverConfigured()) {
        m_connected = false;
        return; // local-first: nothing to sync
    }
    if (m_protocolRejected) {
        return; // explicit /2 vs 3 mismatch: stop hammering, surface the error
    }
    // Plan S5: the token pasted in Settings may be the master; trade it for a
    // device token once (devices/tokens endpoints stay, spec/sync.md).
    const QString enrolledFor = m_store->kv(QStringLiteral("device_token_for"));
    if (!m_client.token().isEmpty()
        && enrolledFor != m_client.baseUrl() + QLatin1Char('|') + m_deviceId && !m_enrolling) {
        m_enrolling = true;
        m_client.enrollDevice(m_deviceId, QSysInfo::machineHostName(), [this](int status, const QString &token) {
            m_enrolling = false;
            if (status == 200 && !token.isEmpty()) {
                storeToken(token);
                m_client.setToken(token);
                m_store->setKv(QStringLiteral("device_token_for"),
                               m_client.baseUrl() + QLatin1Char('|') + m_deviceId);
                emit configChanged();
            } else if (status == 403) { // already a device token of THIS device
                m_store->setKv(QStringLiteral("device_token_for"),
                               m_client.baseUrl() + QLatin1Char('|') + m_deviceId);
            }
            syncNow();
        });
        return;
    }
    m_syncing = true;
    m_lastError.clear();
    emit syncStateChanged();

    m_client.health([this](bool ok, const QJsonObject &) {
        m_connected = ok;
        emit syncStateChanged();
    });

    // 1. Push pending ops (queued locally, idempotent op_ids), then 2. pull.
    pushBatches(buildPushBatches(m_store->pendingOps()), 0);
}

SyncOp Workspace::buildSyncOp(const QString &opId, const QString &objectId, const QJsonObject &payload)
{
    SyncOp op;
    op.opId = opId;
    if (objectId.startsWith(QLatin1String("type:"))) {
        op.kind = QStringLiteral("type");
        op.wireId = blindId(objectId.mid(5));
    } else {
        op.kind = objectId.startsWith(QLatin1String("media_"))
            ? QStringLiteral("asset_meta") : QStringLiteral("obj");
        op.wireId = blindId(objectId);
    }
    op.keyEpoch = quint32(keyEpoch());
    const QByteArray json = QJsonDocument(payload).toJson(QJsonDocument::Compact);
    // JSON payloads of sync kinds travel zstd-compressed inside the envelope
    // (version 0x02, level 3 — spec/crypto.md §Envelope).
    const QByteArray env =
        vault::sealEnvelope(m_keyObjects, quint32(keyEpoch()), QByteArrayLiteral("objects"),
                            op.wireId, json, true);
    op.blob = env.toBase64();
    return op;
}

void Workspace::pushBatches(const QList<QList<PushOp>> &batches, int index)
{
    if (index >= batches.size()) {
        startPull(m_lastSeq);
        return;
    }
    QList<SyncOp> ops;
    QSet<QString> skip;
    for (const PushOp &p : batches[index]) {
        const QJsonObject payload = p.wire.value(QLatin1String("object")).toObject();
        if (payload.isEmpty()) {
            // A row without a full state cannot be pushed: drop it.
            for (qlonglong row : p.rowids)
                m_store->dequeueOp(row);
            continue;
        }
        ops.append(buildSyncOp(p.opId, p.objectId, payload));
    }
    if (ops.isEmpty()) {
        pushBatches(batches, index + 1);
        return;
    }
    m_client.push(ops, m_deviceId,
                  [this, batches, index](const SyncClient::Response &r) {
        if (!r.ok) {
            handlePushFailure(r);
            startPull(m_lastSeq); // keep applying whatever arrives
            return;
        }
        // Results carry the seq per op_id (idempotent retries return the
        // original seq) — dequeue exactly the rows the server accepted.
        QHash<QString, qlonglong> seqOf;
        for (const QJsonValue &v : r.body.value(QLatin1String("results")).toArray()) {
            const QJsonObject res = v.toObject();
            seqOf.insert(res.value(QLatin1String("op_id")).toString(),
                         qlonglong(res.value(QLatin1String("seq")).toDouble()));
        }
        for (const PushOp &op : batches[index]) {
            const auto it = seqOf.constFind(op.opId);
            if (it == seqOf.constEnd())
                continue; // not accepted (rate limited, bad blob…): keep queued
            for (qlonglong row : op.rowids)
                m_store->dequeueOp(row);
        }
        // Objects with nothing left in the queue are no longer pending .
        const QSet<QString> still = pendingObjectIds();
        for (const PushOp &op : batches[index]) {
            auto it = m_objects.find(op.objectId);
            if (it != m_objects.end() && !still.contains(op.objectId)
                && it.value().syncStatus != QLatin1String("synced")) {
                it.value().syncStatus = QStringLiteral("synced");
                m_store->putObject(it.value());
            }
        }
        m_pendingCount = m_store->pendingOps().size();
        pushBatches(batches, index + 1);
    });
}

void Workspace::handlePushFailure(const SyncClient::Response &r)
{
    const QJsonObject err = r.body.value(QLatin1String("error")).toObject();
    const QString code = err.value(QLatin1String("code")).toString();
    if (r.httpStatus == 426 || code == QLatin1String("protocol_mismatch")) {
        m_protocolRejected = true;
        syncError(QStringLiteral("protocol_mismatch: server speaks '%1', app speaks '1' — "
                                 "update the server or the app")
                      .arg(err.value(QLatin1String("server_protocol_version")).toString()));
        return;
    }
    if (r.httpStatus == 429 || code == QLatin1String("rate_limited"))
        syncError(QStringLiteral("rate_limited: reintento en %1 s")
                      .arg(err.value(QLatin1String("retry_after")).toInt()));
    else
        syncError(QStringLiteral("push failed (HTTP %1 %2)").arg(r.httpStatus).arg(code));
}

QSet<QString> Workspace::pendingObjectIds() const
{
    QSet<QString> ids;
    for (const PendingOp &op : m_store->pendingOps())
        ids.insert(op.objectId);
    return ids;
}

void Workspace::startPull(qlonglong since)
{
    m_client.pull(since, [this](const SyncClient::Response &r) {
        markSyncIdle();
        if (!r.ok) {
            m_connected = false;
            const QJsonObject err = r.body.value(QLatin1String("error")).toObject();
            const QString code = err.value(QLatin1String("code")).toString();
            if (r.httpStatus == 426 || code == QLatin1String("protocol_mismatch")) {
                m_protocolRejected = true;
                syncError(QStringLiteral("protocol_mismatch: server speaks '%1', app speaks '1'")
                              .arg(err.value(QLatin1String("server_protocol_version")).toString()));
            } else if (code == QLatin1String("oplog_compacted")) {
                // Behind the compaction floor: restart from the bootstrap snapshot, then keep pulling.
                startFromBootstrap([this](bool ok) {
                    if (ok)
                        startPull(m_lastSeq);
                    else
                        syncError(QStringLiteral("oplog_compacted and no usable bootstrap snapshot"));
                });
                return;
            } else {
                syncError(QStringLiteral("pull failed (HTTP %1 %2)")
                              .arg(r.httpStatus).arg(code.isEmpty() ? QString() : code));
            }
            emit syncStateChanged();
            return;
        }
        m_connected = true;
        syncError(QString());
        const QJsonArray ops = r.body.value(QLatin1String("ops")).toArray();
        {
            ListBatchScope batch(this);
            for (const QJsonValue &v : ops)
                applyRemoteOp(v.toObject());
        }
        qlonglong last = m_lastSeq;
        for (const QJsonValue &v : ops)
            last = qMax(last, qlonglong(v.toObject().value(QLatin1String("seq")).toDouble()));
        const qlonglong head = qlonglong(r.body.value(QLatin1String("head_seq")).toDouble());
        last = qMax(last, head);
        if (last > m_lastSeq) {
            m_lastSeq = last;
            m_store->setKvLong(QStringLiteral("last_seq"), last);
        }
        m_lastSyncText = QDateTime::currentDateTime().toString(QStringLiteral("HH:mm"));
        emit syncStateChanged();
#ifdef Q_OS_LINUX
        if (ops.size() >= 10)
            malloc_trim(0);
#endif
        if (r.body.value(QLatin1String("has_more")).toBool()) {
            startPull(m_lastSeq); // consume the log in order, page by page
        } else {
            produceBootstrapIfDue(head);
            purgeExpiredTrash();
        }
    });
}

bool Workspace::wireConverged(const QJsonObject &merged, const QJsonObject &remote)
{
    for (const char *key : kConvergedKeys) {
        const QString k = QString::fromLatin1(key);
        QJsonValue a = merged.value(k);
        QJsonValue b = remote.value(k);
        if (a.isUndefined())
            a = QJsonValue(QJsonValue::Null);
        if (b.isUndefined())
            b = QJsonValue(QJsonValue::Null);
        if (a != b) {
            const QByteArray ca = QJsonDocument(QJsonObject{ { "v", a } }).toJson(QJsonDocument::Compact);
            const QByteArray cb = QJsonDocument(QJsonObject{ { "v", b } }).toJson(QJsonDocument::Compact);
            if (ca != cb)
                return false;
        }
    }
    return true;
}

QByteArray Workspace::keyForDomain(const QByteArray &domain) const
{
    if (domain == "objects") return m_keyObjects;
    if (domain == "ops") return m_keyOps;
    if (domain == "snapshots") return m_keySnapshots;
    if (domain == "assets") return m_keyAssets;
    if (domain == "thumbs") return m_keyThumbs;
    if (domain == "derived") return m_keyDerived;
    return {};
}

// The ops this client understands. A new kind is added HERE (one handler), never in the pull loop.
void Workspace::buildOpRegistry()
{
    m_opRegistry = std::make_unique<RemoteOpRegistry>();
    m_opRegistry->add(std::make_unique<FunctionOpHandler>(
        QStringList{ QStringLiteral("obj"), QStringLiteral("asset_meta") },
        [this](const DecodedOp &d) {
            applyRemoteObject(d.payload, d.op.kind);
            return OpResult::Applied;
        }));
    // `snapshot`: the bootstrap container of the whole vault, or the snapshot of one object. The
    // objects of a container are merged like any other state: applying one over existing data loses nothing.
    m_opRegistry->add(std::make_unique<FunctionOpHandler>(
        QStringList{ QStringLiteral("snapshot") },
        [this](const DecodedOp &d) {
            const auto boot = bootstrap::parse(d.payload);
            if (!boot) {
                applyRemoteObject(d.payload, d.op.kind);
                return OpResult::Applied;
            }
            for (const QJsonValue &t : boot->types) {
                const ObjectType ft = ObjectType::fromJson(t.toObject());
                if (!ft.id.isEmpty()) {
                    m_types.insert(ft.id, ft);
                    m_store->putType(ft);
                }
            }
            for (const QJsonValue &o : boot->objects)
                applyRemoteObject(o.toObject(), QStringLiteral("snapshot"));
            return OpResult::Applied;
        }));
    m_opRegistry->add(std::make_unique<FunctionOpHandler>(
        QStringList{ QStringLiteral("type") },
        [this](const DecodedOp &d) {
            const ObjectType t = ObjectType::fromJson(d.payload);
            if (!t.id.isEmpty()) {
                m_types.insert(t.id, t);
                m_store->putType(t);
                emit typesChanged();
            }
            return OpResult::Applied;
        }));
    // Artifacts produced by the trust node, one file per blind id (cache/derived).
    m_derivedSink = std::make_unique<FileDerivedSink>(
        QStandardPaths::writableLocation(QStandardPaths::AppDataLocation) + QStringLiteral("/cache/derived"));
    m_opRegistry->add(makeDerivedOpHandler(m_derivedSink.get()));
    // This client is not the trust node (a future trusted node wires a sink): requests are seen and skipped.
    m_opRegistry->add(makeNodeRequestOpHandler(nullptr));
    m_opPipeline = std::make_unique<RemoteOpPipeline>(
        *m_opRegistry,
        [this](const RemoteOp &op) -> std::optional<QByteArray> {
            if (op.keyEpoch != quint32(keyEpoch())) {
                // Epoch rotation is bootstrap: blobs of another epoch wait for the rotation work.
                qWarning().noquote() << "sync: op of key_epoch" << op.keyEpoch << "(ours:" << keyEpoch()
                                     << ") — waiting for rotation support";
                return std::nullopt;
            }
            const QByteArray domain = vault::domainForKind(op.kind);
            return vault::openEnvelope(keyForDomain(domain), op.blob, domain, op.wireId);
        },
        [](const QString &m) { qWarning().noquote() << "sync:" << m; });
}

void Workspace::startFromBootstrap(std::function<void(bool)> done)
{
    m_client.bootstrap([this, done](const SyncClient::Response &r) {
        if (!r.ok) {
            done(false);
            return;
        }
        if (!m_opPipeline)
            buildOpRegistry();
        RemoteOp op;
        op.kind = QStringLiteral("snapshot");
        op.wireId = blindId(QString::fromLatin1(bootstrap::kBlindInput));
        op.keyEpoch = quint32(keyEpoch());
        op.seq = qlonglong(r.body.value(QLatin1String("seq")).toDouble());
        op.blob = QByteArray::fromBase64(r.body.value(QLatin1String("blob")).toString().toLatin1());
        OpResult applied = OpResult::Skipped;
        {
            ListBatchScope batch(this);
            applied = m_opPipeline->apply(op);
        }
        if (applied != OpResult::Applied) {
            done(false);
            return;
        }
        m_lastSeq = qlonglong(r.body.value(QLatin1String("covered_seq")).toDouble());
        m_store->setKvLong(QStringLiteral("last_seq"), m_lastSeq);
        qInfo().noquote() << "sync: started from the bootstrap snapshot, covered_seq" << m_lastSeq;
        notifyObjectListChanged();
#ifdef Q_OS_LINUX
        malloc_trim(0);
#endif
        done(true);
    });
}

void Workspace::produceBootstrapIfDue(qlonglong head)
{
    if (m_producingBootstrap
        || !syncpolicy::shouldProduceBootstrap(m_lastSeq, head, m_bootstrapSeq, int(m_pendingCount),
                                               qEnvironmentVariableIntValue("YAOP_BOOTSTRAP_EVERY") > 0
                                                   ? qEnvironmentVariableIntValue("YAOP_BOOTSTRAP_EVERY")
                                                   : syncpolicy::kBootstrapEveryOps))
        return;
    m_producingBootstrap = true;
    QJsonArray objects, types;
    for (auto it = m_objects.constBegin(); it != m_objects.constEnd(); ++it)
        objects.append(it.value().toJson(true));
    for (const ObjectType &t : m_types)
        types.append(t.toJson());
    const QByteArray payload = QJsonDocument(bootstrap::build(keyEpoch(), head, objects, types))
                                   .toJson(QJsonDocument::Compact);
    const QString wire = blindId(QString::fromLatin1(bootstrap::kBlindInput));
    SyncOp op;
    op.opId = newSyncOpId();
    op.kind = QStringLiteral("snapshot");
    op.wireId = wire;
    op.keyEpoch = quint32(keyEpoch());
    op.blob = vault::sealEnvelope(m_keySnapshots, op.keyEpoch, QByteArrayLiteral("snapshots"), wire, payload, true).toBase64();
    m_client.push({ op }, m_deviceId, [this, op, head](const SyncClient::Response &r) {
        if (!r.ok) {
            m_producingBootstrap = false;
            return;
        }
        m_client.promoteBootstrap(op.opId, head, [this, head](const SyncClient::Response &p) {
            m_producingBootstrap = false;
            if (p.ok) {
                m_bootstrapSeq = head;
                qInfo().noquote() << "sync: uploaded a bootstrap snapshot covering seq" << head;
            }
        });
    });
}

void Workspace::applyRemoteOp(const QJsonObject &op)
{
    if (!m_opPipeline)
        buildOpRegistry();
    RemoteOp r;
    r.seq = qint64(op.value(QLatin1String("seq")).toDouble());
    r.kind = op.value(QLatin1String("kind")).toString();
    r.wireId = op.value(QLatin1String("wire_id")).toString();
    r.deviceId = op.value(QLatin1String("device_id")).toString();
    r.keyEpoch = quint32(op.value(QLatin1String("key_epoch")).toInt());
    r.blob = QByteArray::fromBase64(op.value(QLatin1String("blob")).toString().toLatin1());
    m_opPipeline->apply(r);
}

void Workspace::applyRemoteObject(const QJsonObject &remote, const QString &kind)
{
    Q_UNUSED(kind);
    const QString id = remote.value(QLatin1String("id")).toString();
    if (id.isEmpty())
        return;
    const int theirSchema = remote.value(QLatin1String("schema_version")).toInt(0);
    if (theirSchema > 1) {
        // spec/object.md: never decode a schema you do not know.
        syncError(QStringLiteral("object %1 carries schema_version %2 > 1 known")
                      .arg(id.simplified().left(24), QString::number(theirSchema)));
        return;
    }
    const QString remoteHlc = remote.value(QLatin1String("hlc")).toString();
    if (!remoteHlc.isEmpty())
        hlcReceive(remoteHlc);

    QJsonObject merged = remote;
    const auto it = m_objects.constFind(id);
    const bool haveLocal = it != m_objects.constEnd();
    if (haveLocal) {
        merged = mergeWire(it.value().toJson(true), remote); // trashed_at too (spec/merge.md §2b)
    }
    // Expired tombstone (30+ days in the trash, spec §3.4): the server already holds it, so there
    // is nothing to keep locally unless a local change of ours still has to travel.
    if (syncpolicy::trashExpired(merged.value(QLatin1String("trashed_at")).toString(),
                                 QDateTime::currentDateTimeUtc())
        && (!haveLocal || (it.value().syncStatus == QLatin1String("synced") && !pendingObjectIds().contains(id)))) {
        if (haveLocal) {
            closeObject(id);
            m_objects.remove(id);
            m_store->removeObject(id);
            notifyObjectListChanged();
        }
        return;
    }
    Object next = Object::fromJson(merged);
    next.syncStatus = QStringLiteral("synced");
    m_objects.insert(id, next);
    m_store->putObject(next);

    const bool editing = m_activeEditingId == id;
    if (m_editors.contains(id)) {
        if (!editing)
            m_editors.value(id)->load(next);
        else
            emit editorReloadRequested(id); // the editor merges on its next save
    }
    emit objectUpdated(id);
    for (const QString &assetId : next.referencedAssets())
        ensureAsset(assetId);
    if (haveLocal && !wireConverged(merged, remote)) {
        // Converged state differs from what the server holds: re-push it
        // (spec/sync.md §pull). Any queued local op would carry the stale
        // pre-merge state (and a deduplicated op_id), so it is replaced here
        // with the merged state under a fresh op_id.
        m_store->dequeueOpsFor(id);
        m_store->queueOp(newSyncOpId(), id, merged);
        m_pendingCount = m_store->pendingOps().size();
        emit syncStateChanged();
        schedulePush(1500);
    }
    notifyObjectListChanged();
}

void Workspace::onLiveEvent(const QJsonObject &event)
{
    // yaop-sync/1 stream frames: {"v":1,"event":"hello"|"op",…}.
    const QString type = event.value(QLatin1String("event")).toString();
    if (type == QLatin1String("hello") || type == QLatin1String("ping"))
        return;
    if (type == QLatin1String("op")) {
        const QJsonObject op = event.value(QLatin1String("op")).toObject();
        // e2e evidence : the live stream delivered this op in real time.
        const bool self = op.value(QLatin1String("device_id")).toString() == m_deviceId;
        qInfo().noquote() << "LIVEOP seq" << op.value(QLatin1String("seq")).toDouble()
                          << "kind" << op.value(QLatin1String("kind")).toString()
                          << "wire_id" << op.value(QLatin1String("wire_id")).toString()
                          << (self ? "self" : "remote");
        if (self)
            return; // our own echo comes back through the pull anyway
        schedulePull(1200);
        return;
    }
    schedulePull(1200);
}

void Workspace::onLiveConnected(bool connected)
{
    if (m_live == connected)
        return;
    m_live = connected;
    emit syncStateChanged();
    // Events sent while the stream was down are lost: catch up with a pull .
    if (connected)
        schedulePull(300);
}

void Workspace::markSyncIdle()
{
    m_syncing = false;
    emit syncStateChanged();
    if (m_resyncRequested)
        schedulePush(500);
}

// ---------------------------------------------------------------------------
// debugPullCheck: parse correctness report over the live snapshot
// ---------------------------------------------------------------------------
int Workspace::debugPullCheck()
{
    int objectCount = 0, trashed = 0, templateCount = 0;
    int blockCount = 0;
    QHash<QString, int> byType;
    int mentions = 0, tags = 0, dates = 0, mathRuns = 0, imageRuns = 0, links = 0;
    int objectsWithImages = 0, objectsWithAudio = 0, objectsWithPdf = 0, objectsWithCode = 0,
        objectsWithMath = 0, objectsWithTables = 0, objectsWithGroups = 0, objectsWithToggles = 0;
    QStringList broken;

    for (const Object &o : m_objects) {
        ++objectCount;
        if (!o.trashedAt.isEmpty())
            ++trashed;
        if (o.isTemplate())
            ++templateCount;
        if (o.blocks.isEmpty() && !o.content.isEmpty())
            broken.append(o.id);
        bool hasImage = false, hasAudio = false, hasPdf = false, hasCode = false, hasMath = false,
             hasTable = false, hasGroup = false, hasToggle = false;
        for (const Block &b : o.blocks) {
            ++blockCount;
            byType[b.type]++;
            if (b.type == BlockType::Image)
                hasImage = true;
            else if (b.type == BlockType::Audio)
                hasAudio = true;
            else if (b.type == BlockType::Pdf)
                hasPdf = true;
            else if (b.type == BlockType::Code)
                hasCode = true;
            else if (b.type == BlockType::Math)
                hasMath = true;
            else if (b.type == BlockType::Table)
                hasTable = true;
            else if (b.type == BlockType::Toggle)
                hasToggle = true;
            if (!b.groupId.isEmpty())
                hasGroup = true;
            for (const Run &r : b.runs) {
                if (!r.objectId.isEmpty())
                    ++mentions;
                else if (!r.tagId.isEmpty())
                    ++tags;
                else if (!r.date.isEmpty())
                    ++dates;
                else if (r.isMath)
                    ++mathRuns;
                else if (!r.imageName.isEmpty())
                    ++imageRuns;
                else if (!r.href.isEmpty())
                    ++links;
            }
        }
        objectsWithImages += hasImage;
        objectsWithAudio += hasAudio;
        objectsWithPdf += hasPdf;
        objectsWithCode += hasCode;
        objectsWithMath += hasMath;
        objectsWithTables += hasTable;
        objectsWithGroups += hasGroup;
        objectsWithToggles += hasToggle;
    }

    std::printf("PULLCHECK objects=%d (trashed=%d templates=%d) blocks=%d types=%d\n",
                objectCount, trashed, templateCount, blockCount, int(m_types.size()));
    std::printf("PULLCHECK blocks by type:");
    for (auto it = byType.constBegin(); it != byType.constEnd(); ++it)
        std::printf(" %s=%d", qPrintable(it.key()), it.value());
    std::printf("\n");
    std::printf("PULLCHECK runs mentions=%d tags=%d dates=%d math=%d inlineImages=%d links=%d\n",
                mentions, tags, dates, mathRuns, imageRuns, links);
    std::printf("PULLCHECK objects images=%d audio=%d pdf=%d code=%d math=%d tables=%d groups=%d toggles=%d\n",
                objectsWithImages, objectsWithAudio, objectsWithPdf, objectsWithCode, objectsWithMath,
                objectsWithTables, objectsWithGroups, objectsWithToggles);
    if (!broken.isEmpty()) {
        std::printf("PULLCHECK WARN objects without parsed blocks: %s\n",
                    qPrintable(broken.join(',').left(300)));
        return 1;
    }
    return objectCount > 0 ? 0 : 2;
}

} // namespace pkm
