#pragma once

// Transport of spec/sync.md (yaop-sync/1, blind server): /v1 status/push/
// pull/stream over Qt Network. Content never travels in clear: the caller
// (Workspace) builds sealed envelopes (vaultcrypto) and this class only moves
// bytes. Explicit protocol_mismatch surfacing for servers on another protocol, SSE with the
// same backoff/watchdog, resumable asset upload of already-ENCRYPTED chunk
// streams (no MIME/name headers: those live inside the encrypted media object).

#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QObject>
#include <QTimer>

namespace pkm {

// Reconnect delay for the live stream : 1 s doubling up to 60 s, ±20 %
// jitter so several devices do not reconnect in lockstep. jitter01 in [0, 1).
int liveBackoffMs(int attempt, double jitter01);

// One operation of spec/sync.md; `blob` is the base64 of a sealed envelope.
struct SyncOp {
    QString opId;    // 32 hex chars, random per queued op (idempotency key)
    QString kind;    // obj | type | asset_meta | derived | snapshot | node_request
    QString wireId;  // blinded id of the target
    quint32 keyEpoch = 1;
    QByteArray blob; // base64(sobre)
};

struct SyncState {
    bool connected = false;
    bool syncing = false;
    qlonglong lastSeq = 0;
    qlonglong headSeq = 0;
    QString lastError;
    QString lastSyncTime;
    int queued = 0;
    bool live = false;
};

class SyncClient : public QObject {
    Q_OBJECT
public:
    explicit SyncClient(QObject *parent = nullptr);
    ~SyncClient() override;

    void setBaseUrl(QString url);
    void setToken(const QString &token);
    QString baseUrl() const { return m_base; }
    QString token() const { return m_token; }

    // Response surface: ok = HTTP 2xx AND well-formed JSON body; httpStatus
    // carries 426 (protocol_mismatch) and friends so callers can show the
    // exact spec error instead of retrying blindly.
    struct Response {
        bool ok = false;
        int httpStatus = 0;
        QJsonObject body;
    };

    void health(std::function<void(bool ok, const QJsonObject &)> cb);
    // GET /v1/sync/status — first handshake: protocol_version must be "1".
    void status(std::function<void(const Response &)> cb);
    // POST /v1/sync/push {ops:[…]} — at most 500 per request (spec/sync.md).
    void push(const QList<SyncOp> &ops, const QString &deviceId,
              std::function<void(const Response &)> cb);
    // POST /v1/sync/pull {since_seq, limit}.
    // Bootstrap snapshot: GET the latest (httpStatus 404 when none) and declare what an uploaded one covers.
    void bootstrap(std::function<void(const Response &)> cb);
    void promoteBootstrap(const QString &opId, qlonglong coveredSeq, std::function<void(const Response &)> cb);
    void pull(qlonglong sinceSeq, std::function<void(const Response &)> cb);

    // Exchange the master token for this device's own token : the
    // /v1 devices endpoint stays (spec/sync.md: devices/tokens unchanged).
    void enrollDevice(const QString &deviceId, const QString &name,
                      std::function<void(int status, const QString &token)> cb);

    // GET /v1/devices (any authenticated caller) and DELETE /v1/devices/{id} (master).
    void listDevices(std::function<void(int status, const QJsonObject &body)> cb);
    void revokeDevice(const QString &deviceId, std::function<void(int status)> cb);

    // Resumable upload of the sealed chunk frames under the BLIND asset id
    // (spec/sync.md §Assets: GET reports the resume point in chunks, PUT
    // carries one ciphertext chunk per Upload-Offset: n, Upload-Length: N).
    // No MIME/name on the wire: they travel encrypted inside the media object.
    // Cheap check before reading/sealing a file: nullopt on network error.
    void assetUploadComplete(const QString &wireId, std::function<void(std::optional<bool>)> cb);
    void uploadAssetResumable(const QString &wireId, const QVector<QByteArray> &frames,
                              std::function<void(bool ok, const QJsonObject &)> cb);
    // The concatenated ciphertext (GET /content, byte ranges supported server side).
    void downloadAsset(const QString &wireId,
                       std::function<void(bool ok, const QByteArray &cipherBytes)> cb);

    // SSE live stream (GET /v1/sync/stream)
    void startLive(const QString &deviceId);
    void stopLive();
    bool liveConnected() const { return m_liveUp; }
    // Network came back or the machine resumed: reconnect now.
    void reconnectLiveNow();

signals:
    void liveEvent(const QJsonObject &event);
    void liveConnectedChanged(bool connected);

private:
    QNetworkRequest makeRequest(const QUrl &url) const;
    void onLiveReadyRead();
    void scheduleLiveReconnect();
    void uploadChunkFrom(const QString &wireId, const QVector<QByteArray> &frames, int chunkIndex,
                         int guard, std::function<void(bool ok, const QJsonObject &)> cb);

    QString m_base;
    QString m_token;
    QNetworkAccessManager m_nam;
    QNetworkReply *m_liveReply = nullptr;
    QByteArray m_liveBuffer;
    QTimer m_liveReconnect;
    QTimer m_liveWatchdog;     // no byte (not even a ping) for too long → stream is dead
    int m_liveAttempt = 0;
    bool m_liveUp = false;
    QString m_deviceId;
};

} // namespace pkm
