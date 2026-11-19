#pragma once

// First-run onboarding and device pairing (spec §15.2/§15.3/§21.1).
//   VaultOnboarding — create a vault (24-word phrase, confirm 3 words) or restore from the
//                     phrase, then persist the VK. QML only renders; the rules live here.
//   PairingFlow     — one pairing transaction over the /v1/pairing relay, as the QR holder
//                     (host: show QR, compare 6-digit code, send the sealed VK) or as the new
//                     device (guest: paste/scan the QR JSON, show the code, receive the VK).
// Nothing here logs or exposes the VK or the phrase except the explicit phrase getter that the
// "show it once" screen needs.

#include <QNetworkAccessManager>
#include <QObject>
#include <QStringList>
#include <QTimer>

#include <optional>

#include "bip39.h"

namespace pkm {

class Workspace;

class VaultOnboarding : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString phrase READ phrase NOTIFY phraseChanged)
    Q_PROPERTY(QVariantList confirmIndices READ confirmIndices NOTIFY phraseChanged)
    Q_PROPERTY(bool committed READ committed NOTIFY committedChanged)
    Q_PROPERTY(bool persistent READ persistent NOTIFY committedChanged)
public:
    enum RestoreError { RestoreOk = 0, WordCount, UnknownWord, Checksum };
    Q_ENUM(RestoreError)

    VaultOnboarding(QString keychainAccount, QString wrappedFile, QObject *parent = nullptr);

    // Does a vault already exist for this profile (keychain / wrapped file)?
    Q_INVOKABLE bool vaultExists() const;

    // Create: generates a fresh VK + its phrase; nothing is stored until commit().
    Q_INVOKABLE void startCreate();
    QString phrase() const { return m_phrase; }
    // 3 distinct ascending 0-based indices (QML shows index+1).
    QVariantList confirmIndices() const;
    // Words typed by the user for confirmIndices(), in the same order.
    Q_INVOKABLE bool checkWords(const QStringList &answers) const;

    // Restore: validates the typed phrase; on success the VK is staged for commit().
    Q_INVOKABLE int restore(const QString &phrase);

    // Stage a VK obtained by pairing (guest flow).
    void adoptVk(const QByteArray &vk, quint32 epoch);

    // Server step (optional): url/token stored in QSettings like --server/--token. Returns
    // whether the vault key was persisted.
    // deviceId: the id a pairing enrolled this device's token under (empty otherwise).
    Q_INVOKABLE bool commit(const QString &serverUrl, const QString &token,
                            const QString &deviceId = QString());
    bool committed() const { return m_committed; }
    // False when no keychain/passphrase could protect the key (QML warns).
    bool persistent() const { return m_persistent; }

    // Pure helpers (selftest).
    static QList<int> pickIndices(int count, int words);
    static int classify(const QString &phrase, const bip39::Codec &codec, QByteArray *entropy);

signals:
    void phraseChanged();
    void committedChanged();
    void finished();

private:
    QString m_account;
    QString m_file;
    std::optional<bip39::Codec> m_codec;
    QByteArray m_vk;
    quint32 m_epoch = 1;
    QString m_phrase;
    QList<int> m_indices;
    bool m_committed = false;
    bool m_persistent = false;
};

class PairingFlow : public QObject {
    Q_OBJECT
    Q_PROPERTY(QString serverUrl READ serverUrl WRITE setServerUrl NOTIFY serverUrlChanged)
    Q_PROPERTY(QString state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString qrPayload READ qrPayload NOTIFY stateChanged)
    Q_PROPERTY(QVariantList qrModules READ qrModules NOTIFY stateChanged)
    Q_PROPERTY(QString code READ code NOTIFY stateChanged)
    Q_PROPERTY(QString error READ error NOTIFY stateChanged)
    Q_PROPERTY(QObject *workspace READ workspaceObject WRITE setWorkspaceObject NOTIFY workspaceChanged)
public:
    explicit PairingFlow(QObject *parent = nullptr);

    QString serverUrl() const { return m_base; }
    void setServerUrl(const QString &url);
    // idle | opening | waiting | compare | sending | done | failed
    QString state() const { return m_state; }
    QString qrPayload() const { return m_qr; }
    // Square module matrix of qrPayload() as a flat list of 0/1 rows ("0110…") for QML Canvas.
    QVariantList qrModules() const;
    QString code() const { return m_code; }
    QString error() const { return m_error; }
    QObject *workspaceObject() const;
    void setWorkspaceObject(QObject *ws);

    // Host (enrolled device): show QR, then approve once the codes match.
    Q_INVOKABLE void hostStart();
    Q_INVOKABLE void hostApprove();
    // Guest (new device): the QR JSON (scanned or pasted).
    Q_INVOKABLE void guestStart(const QString &qrJson);
    Q_INVOKABLE void cancel();

    // Guest result, valid when state == "done".
    QByteArray receivedVk() const { return m_receivedVk; }
    quint32 receivedEpoch() const { return m_receivedEpoch; }
    QString guestServerUrl() const { return m_guestServer; }
    // Own server token the pairing gave this device (empty if the holder offered none).
    Q_INVOKABLE QString guestToken() const { return m_guestToken; }
    Q_INVOKABLE QString guestDeviceId() const { return m_guestDeviceId; }

    // Pure helper (selftest): SHA-256 of an SPKI DER, lowercase hex.
    static QString fingerprintOf(const QByteArray &spkiDer);

signals:
    void serverUrlChanged();
    void stateChanged();
    void workspaceChanged();
    void guestJoined(); // VK received

private:
    void setState(const QString &s, const QString &err = QString());
    void startPolling();
    void pollHostAnswer();
    void pollGuestTransfer();
    void guestEnroll();
    void withTlsFingerprint(const QString &url, std::function<void(const QString &)> done);
    QNetworkReply *post(const QString &path, const QByteArray &body, const QByteArray &type);
    QNetworkReply *get(const QString &path);

    QNetworkAccessManager m_nam;
    QTimer m_poll;
    QString m_base;
    QString m_state = QStringLiteral("idle");
    QString m_qr;
    QString m_code;
    QString m_error;
    QString m_pairingId;
    QByteArray m_priv;
    QByteArray m_shared;
    Workspace *m_ws = nullptr;
    QByteArray m_receivedVk;
    quint32 m_receivedEpoch = 1;
    QString m_guestServer;
    QString m_guestToken;
    QString m_guestDeviceId;
    int m_ticks = 0;
    bool m_isHost = false;
    bool m_busy = false;
};

} // namespace pkm
