#include "syncclient.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRandomGenerator>
#include <QUrlQuery>

#include <cmath>

namespace pkm {

static constexpr int kTimeoutMs = 15000;
static constexpr int kLiveWatchdogMs = 75000; // server keep-alives every ~20 s

int liveBackoffMs(int attempt, double jitter01)
{
    const double base = std::min(60000.0, 1000.0 * std::pow(2.0, std::clamp(attempt, 0, 16)));
    return int(base * (0.8 + 0.4 * std::clamp(jitter01, 0.0, 1.0)));
}

SyncClient::SyncClient(QObject *parent)
    : QObject(parent)
{
    connect(&m_liveReconnect, &QTimer::timeout, this, [this]() {
        if (!m_deviceId.isEmpty())
            startLive(m_deviceId);
    });
    m_liveReconnect.setSingleShot(true);
    m_liveWatchdog.setSingleShot(true);
    connect(&m_liveWatchdog, &QTimer::timeout, this, [this]() {
        qWarning() << "live stream silent for" << kLiveWatchdogMs << "ms, reconnecting";
        if (m_liveReply)
            m_liveReply->abort(); // finished → scheduleLiveReconnect
    });
}

SyncClient::~SyncClient()
{
    blockSignals(true);
    stopLive();
}

void SyncClient::setBaseUrl(QString url)
{
    while (url.endsWith(QLatin1Char('/')))
        url.chop(1);
    m_base = url;
}

void SyncClient::setToken(const QString &token)
{
    m_token = token;
}

QNetworkRequest SyncClient::makeRequest(const QUrl &url) const
{
    QNetworkRequest req(url);
    req.setRawHeader(QByteArrayLiteral("Authorization"), "Bearer " + m_token.toUtf8());
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json; charset=utf-8"));
    req.setTransferTimeout(kTimeoutMs);
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    return req;
}

void SyncClient::health(std::function<void(bool, const QJsonObject &)> cb)
{
    QNetworkReply *reply = m_nam.get(makeRequest(QUrl(m_base + QStringLiteral("/health"))));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        const bool ok = reply->error() == QNetworkReply::NoError;
        const QJsonObject o = QJsonDocument::fromJson(reply->readAll()).object();
        if (cb)
            cb(ok && o.value(QLatin1String("status")).toString() == QLatin1String("ready"), o);
    });
}

void SyncClient::status(std::function<void(const Response &)> cb)
{
    QNetworkReply *reply = m_nam.get(makeRequest(QUrl(m_base + QStringLiteral("/v1/sync/status"))));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        Response r;
        r.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        r.body = QJsonDocument::fromJson(reply->readAll()).object();
        r.ok = reply->error() == QNetworkReply::NoError && r.body.contains(QLatin1String("protocol_version"));
        if (cb)
            cb(r);
    });
}

void SyncClient::push(const QList<SyncOp> &ops, const QString &deviceId,
                      std::function<void(const SyncClient::Response &)> cb)
{
    QJsonArray arr;
    for (const SyncOp &op : ops) {
        arr.append(QJsonObject{
            { "op_id", op.opId },
            { "device_id", deviceId },
            { "kind", op.kind },
            { "wire_id", op.wireId },
            { "key_epoch", double(op.keyEpoch) },
            { "blob", QString::fromLatin1(op.blob) } });
    }
    QNetworkRequest req = makeRequest(QUrl(m_base + QStringLiteral("/v1/sync/push")));
    req.setTransferTimeout(30000);
    QNetworkReply *reply = m_nam.post(req, QJsonDocument(QJsonObject{ { "ops", arr } })
                                              .toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        Response r;
        r.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        r.body = QJsonDocument::fromJson(reply->readAll()).object();
        r.ok = reply->error() == QNetworkReply::NoError
               && r.body.contains(QLatin1String("results"));
        if (cb)
            cb(r);
    });
}

void SyncClient::bootstrap(std::function<void(const Response &)> cb)
{
    QNetworkReply *reply = m_nam.get(makeRequest(QUrl(m_base + QStringLiteral("/v1/sync/bootstrap"))));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        Response r;
        r.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        r.body = QJsonDocument::fromJson(reply->readAll()).object();
        r.ok = r.httpStatus == 200 && r.body.contains(QLatin1String("blob"));
        if (cb)
            cb(r);
    });
}

void SyncClient::promoteBootstrap(const QString &opId, qlonglong coveredSeq,
                                  std::function<void(const Response &)> cb)
{
    QNetworkRequest req = makeRequest(QUrl(m_base + QStringLiteral("/v1/sync/bootstrap")));
    QNetworkReply *reply = m_nam.post(req, QJsonDocument(QJsonObject{ { "op_id", opId }, { "covered_seq", double(coveredSeq) } })
                                              .toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        Response r;
        r.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        r.body = QJsonDocument::fromJson(reply->readAll()).object();
        r.ok = r.httpStatus == 200;
        if (cb)
            cb(r);
    });
}

void SyncClient::pull(qlonglong sinceSeq, std::function<void(const SyncClient::Response &)> cb)
{
    QJsonObject body{ { "since_seq", double(sinceSeq) }, { "limit", 200.0 } };
    QNetworkReply *reply = m_nam.post(makeRequest(QUrl(m_base + QStringLiteral("/v1/sync/pull"))),
                                      QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        Response r;
        r.httpStatus = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        r.body = QJsonDocument::fromJson(reply->readAll()).object();
        r.ok = reply->error() == QNetworkReply::NoError && r.body.contains(QLatin1String("ops"));
        if (cb)
            cb(r);
    });
}

void SyncClient::enrollDevice(const QString &deviceId, const QString &name,
                              std::function<void(int, const QString &)> cb)
{
    QNetworkRequest req = makeRequest(QUrl(m_base + QStringLiteral("/v1/devices/enroll")));
    req.setTransferTimeout(15000);
    const QJsonObject body{ { "device_id", deviceId }, { "name", name } };
    QNetworkReply *reply = m_nam.post(req, QJsonDocument(body).toJson(QJsonDocument::Compact));
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QString token =
            QJsonDocument::fromJson(reply->readAll()).object().value(QLatin1String("token")).toString();
        cb(status, token);
    });
}

void SyncClient::listDevices(std::function<void(int, const QJsonObject &)> cb)
{
    QNetworkRequest req = makeRequest(QUrl(m_base + QStringLiteral("/v1/devices")));
    req.setTransferTimeout(10000);
    QNetworkReply *reply = m_nam.get(req);
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        cb(status, QJsonDocument::fromJson(reply->readAll()).object());
    });
}

void SyncClient::revokeDevice(const QString &deviceId, std::function<void(int)> cb)
{
    QNetworkRequest req = makeRequest(
        QUrl(m_base + QStringLiteral("/v1/devices/") + QString::fromLatin1(QUrl::toPercentEncoding(deviceId))));
    req.setTransferTimeout(10000);
    QNetworkReply *reply = m_nam.deleteResource(req);
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        cb(reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt());
    });
}

// ---------------------------------------------------------------------------
// Assets: resumable upload of one sealed chunk at a time under the BLIND id
// (spec/sync.md §Assets). GET reports the resume point in CHUNKS
// {"stored_chunks", "next_chunk", "total_chunks", "complete", "total_size"};
// PUT carries the ciphertext of chunk n with Upload-Offset:<n> and
// Upload-Length:<N>, answering 204 + Upload-Offset:<next> (or 201 with
// {"complete":true,...} on the declared last chunk). Resuming starts at the
// server's next_chunk: chunks already stored keep their past seals and still
// decrypt on their own — each envelope carries its own nonce.
// ---------------------------------------------------------------------------

void SyncClient::assetUploadComplete(const QString &wireId, std::function<void(std::optional<bool>)> cb)
{
    const QUrl url(m_base
                   + QStringLiteral("/v1/assets/%1/upload")
                         .arg(QString(QUrl::toPercentEncoding(wireId))));
    QNetworkReply *reply = m_nam.get(makeRequest(url));
    connect(reply, &QNetworkReply::finished, this, [=, this]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            cb(std::nullopt);
            return;
        }
        const QJsonObject st = QJsonDocument::fromJson(reply->readAll()).object();
        cb(st.value(QLatin1String("complete")).toBool());
    });
}

void SyncClient::uploadAssetResumable(const QString &wireId, const QVector<QByteArray> &frames,
                                      std::function<void(bool, const QJsonObject &)> cb)
{
    if (frames.isEmpty()) {
        cb(false, {});
        return;
    }
    const QUrl url(m_base
                   + QStringLiteral("/v1/assets/%1/upload")
                         .arg(QString(QUrl::toPercentEncoding(wireId))));
    QNetworkReply *reply = m_nam.get(makeRequest(url));
    connect(reply, &QNetworkReply::finished, this, [=, this]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError) {
            cb(false, {});
            return;
        }
        const QJsonObject st = QJsonDocument::fromJson(reply->readAll()).object();
        if (st.value(QLatin1String("complete")).toBool()) {
            cb(true, st);
            return;
        }
        // A session under this wire_id with a DIFFERENT chunk count means a
        // different blob: never overwrite silently, surface the failure.
        const int declared = int(st.value(QLatin1String("total_chunks")).toDouble());
        if (declared > 0 && declared != frames.size()) {
            qWarning().noquote() << "asset session" << wireId << "declares" << declared
                                 << "chunks, this upload carries" << frames.size();
            cb(false, {});
            return;
        }
        const int next = int(st.value(QLatin1String("next_chunk")).toDouble());
        if (next < 0 || next >= frames.size()) {
            qWarning().noquote() << "asset session" << wireId << "reports bogus next_chunk"
                             << next;
            cb(false, {});
            return;
        }
        uploadChunkFrom(wireId, frames, next, 0, cb);
    });
}

void SyncClient::uploadChunkFrom(const QString &wireId, const QVector<QByteArray> &frames,
                                 int chunkIndex, int guard,
                                 std::function<void(bool, const QJsonObject &)> cb)
{
    if (guard > 10000) {
        cb(false, {});
        return;
    }
    QNetworkRequest req = makeRequest(
        QUrl(m_base + QStringLiteral("/v1/assets/%1/upload")
                       .arg(QString(QUrl::toPercentEncoding(wireId)))));
    req.setHeader(QNetworkRequest::ContentTypeHeader,
                  QStringLiteral("application/offset+octet-stream"));
    req.setRawHeader(QByteArrayLiteral("Upload-Offset"), QByteArray::number(chunkIndex));
    req.setRawHeader(QByteArrayLiteral("Upload-Length"), QByteArray::number(frames.size()));
    req.setTransferTimeout(45000);
    QNetworkReply *reply = m_nam.put(req, frames.at(chunkIndex));
    connect(reply, &QNetworkReply::finished, this, [=, this]() {
        reply->deleteLater();
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray body = reply->readAll();
        if (status == 201) { // the declared N chunks are all in
            cb(true, QJsonDocument::fromJson(body).object());
            return;
        }
        if (status == 204 || status == 200) { // chunk stored; resume pointer in the header
            bool okNext = false;
            const qlonglong next =
                reply->rawHeader(QByteArrayLiteral("Upload-Offset")).toLongLong(&okNext);
            if (!okNext || next < 0) {
                cb(false, {});
                return;
            }
            if (next >= frames.size()) { // server considers it complete already
                cb(true, QJsonDocument::fromJson(body).object());
                return;
            }
            uploadChunkFrom(wireId, frames, int(next), guard + 1, cb);
            return;
        }
        qWarning().noquote() << "asset" << wireId << "chunk" << chunkIndex << "upload stopped:"
                         << "HTTP" << status;
        cb(false, {});
    });
}

void SyncClient::downloadAsset(const QString &wireId,
                               std::function<void(bool, const QByteArray &)> cb)
{
    QNetworkRequest req = makeRequest(
        QUrl(m_base + QStringLiteral("/v1/assets/%1/content")
                       .arg(QString(QUrl::toPercentEncoding(wireId)))));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setTransferTimeout(45000);
    QNetworkReply *reply = m_nam.get(req);
    connect(reply, &QNetworkReply::finished, this, [reply, cb]() {
        reply->deleteLater();
        const bool ok = reply->error() == QNetworkReply::NoError;
        cb(ok, reply->readAll());
    });
}

// ---------------------------------------------------------------------------
// SSE live stream (GET /v1/sync/stream)
// ---------------------------------------------------------------------------

void SyncClient::startLive(const QString &deviceId)
{
    if (m_liveReply)
        return;
    m_deviceId = deviceId;
    QUrl url(m_base + QStringLiteral("/v1/sync/stream"));
    QUrlQuery q;
    q.addQueryItem(QStringLiteral("device_id"), deviceId);
    q.addQueryItem(QStringLiteral("token"), m_token);
    url.setQuery(q);

    QNetworkRequest req(url);
    req.setRawHeader(QByteArrayLiteral("Accept"), QByteArrayLiteral("text/event-stream"));
    req.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    req.setTransferTimeout(0); // no timeout: stream stays open

    m_liveReply = m_nam.get(req);
    m_liveBuffer.clear();
    connect(m_liveReply, &QNetworkReply::readyRead, this, &SyncClient::onLiveReadyRead);
    connect(m_liveReply, &QNetworkReply::finished, this, [this]() {
        if (m_liveReply) {
            m_liveReply->deleteLater();
            m_liveReply = nullptr;
        }
        m_liveWatchdog.stop();
        if (m_liveUp) {
            m_liveUp = false;
            emit liveConnectedChanged(false);
        }
        scheduleLiveReconnect();
    });
    m_liveWatchdog.start(kLiveWatchdogMs);
    connect(m_liveReply, &QNetworkReply::sslErrors, this, [this](const QList<QSslError> &) {
        if (m_liveReply)
            m_liveReply->ignoreSslErrors();
    });
}

void SyncClient::scheduleLiveReconnect()
{
    const int ms = liveBackoffMs(m_liveAttempt, QRandomGenerator::global()->generateDouble());
    ++m_liveAttempt;
    qInfo().noquote() << "live stream down, retry" << m_liveAttempt << "in" << ms << "ms";
    m_liveReconnect.start(ms);
}

void SyncClient::reconnectLiveNow()
{
    if (m_deviceId.isEmpty())
        return;
    m_liveAttempt = 0;
    m_liveReconnect.stop();
    stopLive();
    startLive(m_deviceId);
}

void SyncClient::stopLive()
{
    m_liveWatchdog.stop();
    if (m_liveUp) {
        m_liveUp = false;
        emit liveConnectedChanged(false);
    }
    if (m_liveReply) {
        disconnect(m_liveReply, nullptr, this, nullptr);
        m_liveReply->abort();
        m_liveReply->deleteLater();
        m_liveReply = nullptr;
    }
}

void SyncClient::onLiveReadyRead()
{
    if (!m_liveReply)
        return;
    m_liveWatchdog.start(kLiveWatchdogMs);
    if (!m_liveUp) {
        // Connected (again): reset the backoff; the workspace pulls what it
        // missed (parity with Android).
        m_liveUp = true;
        qInfo().noquote() << "live stream up after" << m_liveAttempt << "retries";
        m_liveAttempt = 0;
        emit liveConnectedChanged(true);
    }
    m_liveBuffer += m_liveReply->readAll();
    int sep;
    while ((sep = m_liveBuffer.indexOf("\n\n")) >= 0) {
        const QByteArray chunk = m_liveBuffer.left(sep);
        m_liveBuffer.remove(0, sep + 2);
        for (const QByteArray &line : chunk.split('\n')) {
            if (!line.startsWith("data:"))
                continue;
            const QByteArray data = line.mid(5).trimmed();
            if (data.isEmpty())
                continue;
            const QJsonObject o = QJsonDocument::fromJson(data).object();
            if (o.isEmpty())
                continue;
            emit liveEvent(o);
        }
    }
}

} // namespace pkm
