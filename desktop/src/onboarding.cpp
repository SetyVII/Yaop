#include "onboarding.h"

#include "../third_party/qrcodegen/qrcodegen.hpp"
#include "vaultcrypto.h"
#include "workspace.h"

#include <QCryptographicHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QSettings>
#include <QSslCertificate>
#include <QSslKey>
#include <QSslSocket>
#include <QUrl>

#include <algorithm>
#include <random>

namespace pkm {

namespace {
constexpr int kConfirmWords = 3;
constexpr int kPollMs = 2000;
constexpr int kMaxTicks = 300; // ~10 min: the relay row lives PairingTTL
const QString kNoTls = QString(64, QLatin1Char('0'));

QString nowToken(const QByteArray &b) { return QString::fromLatin1(b.toHex()); }
} // namespace

// ---------------------------------------------------------------------------
// VaultOnboarding
// ---------------------------------------------------------------------------

VaultOnboarding::VaultOnboarding(QString keychainAccount, QString wrappedFile, QObject *parent)
    : QObject(parent)
    , m_account(std::move(keychainAccount))
    , m_file(std::move(wrappedFile))
    , m_codec(bip39::Codec::make(bip39::embeddedWordlist()))
{
}

bool VaultOnboarding::vaultExists() const
{
    return !vault::loadVaultKey(keychainSecrets(), m_account, m_file).vk.isEmpty();
}

QList<int> VaultOnboarding::pickIndices(int count, int words)
{
    std::random_device rd;
    QList<int> out;
    while (out.size() < count) {
        const int i = int(rd() % uint(words));
        if (!out.contains(i))
            out.append(i);
    }
    std::sort(out.begin(), out.end());
    return out;
}

void VaultOnboarding::startCreate()
{
    if (!m_codec)
        return;
    m_vk = vault::randomBytes(32);
    m_epoch = 1;
    m_phrase = m_codec->entropyToMnemonic(m_vk);
    m_indices = pickIndices(kConfirmWords, bip39::kPhraseWords);
    emit phraseChanged();
}

QVariantList VaultOnboarding::confirmIndices() const
{
    QVariantList l;
    for (int i : m_indices)
        l << i;
    return l;
}

bool VaultOnboarding::checkWords(const QStringList &answers) const
{
    const QStringList words = m_phrase.split(QLatin1Char(' '));
    if (m_phrase.isEmpty() || answers.size() != m_indices.size())
        return false;
    for (int i = 0; i < m_indices.size(); ++i)
        if (answers.at(i).trimmed().toLower() != words.at(m_indices.at(i)))
            return false;
    return true;
}

int VaultOnboarding::classify(const QString &phrase, const bip39::Codec &codec, QByteArray *entropy)
{
    const QStringList parts = phrase.toLower().simplified().split(QLatin1Char(' '), Qt::SkipEmptyParts);
    if (parts.size() != bip39::kPhraseWords)
        return WordCount;
    for (const QString &w : parts)
        if (!codec.words().contains(w))
            return UnknownWord;
    const auto e = codec.mnemonicToEntropy(parts.join(QLatin1Char(' ')));
    if (!e)
        return Checksum;
    if (entropy)
        *entropy = *e;
    return RestoreOk;
}

int VaultOnboarding::restore(const QString &phrase)
{
    if (!m_codec)
        return WordCount;
    QByteArray e;
    const int r = classify(phrase, *m_codec, &e);
    if (r == RestoreOk) {
        m_vk = e;
        m_epoch = 1;
        m_phrase.clear();
    }
    return r;
}

void VaultOnboarding::adoptVk(const QByteArray &vk, quint32 epoch)
{
    m_vk = vk;
    m_epoch = epoch;
}

bool VaultOnboarding::commit(const QString &serverUrl, const QString &token)
{
    if (m_vk.size() != 32)
        return false;
    const auto keys = vault::storeVaultKey(keychainSecrets(), m_account, m_file, m_vk, m_epoch);
    m_persistent = keys.persistent();
    if (!m_persistent)
        return false;
    QSettings s;
    if (!serverUrl.trimmed().isEmpty()) {
        s.setValue(QStringLiteral("server/url"), serverUrl.trimmed());
        if (!token.trimmed().isEmpty())
            s.setValue(QStringLiteral("server/token"), token.trimmed());
    }
    m_vk.fill(0);
    m_committed = true;
    emit committedChanged();
    emit finished();
    return true;
}

// ---------------------------------------------------------------------------
// PairingFlow
// ---------------------------------------------------------------------------

PairingFlow::PairingFlow(QObject *parent)
    : QObject(parent)
{
    m_poll.setInterval(kPollMs);
    connect(&m_poll, &QTimer::timeout, this, [this] {
        if (++m_ticks > kMaxTicks) {
            setState(QStringLiteral("failed"), tr("The pairing expired."));
            return;
        }
        if (m_state == QLatin1String("waiting") && m_isHost)
            pollHostAnswer();
        else if (m_state == QLatin1String("compare") && !m_isHost)
            pollGuestTransfer();
    });
}

void PairingFlow::setServerUrl(const QString &url)
{
    const QString u = url.trimmed();
    if (u == m_base)
        return;
    m_base = u;
    while (m_base.endsWith(QLatin1Char('/')))
        m_base.chop(1);
    emit serverUrlChanged();
}

QObject *PairingFlow::workspaceObject() const { return m_ws; }
void PairingFlow::setWorkspaceObject(QObject *ws)
{
    m_ws = qobject_cast<Workspace *>(ws);
    emit workspaceChanged();
}

void PairingFlow::setState(const QString &s, const QString &err)
{
    m_state = s;
    m_error = err;
    if (s == QLatin1String("done") || s == QLatin1String("failed") || s == QLatin1String("idle"))
        m_poll.stop();
    emit stateChanged();
}

QString PairingFlow::fingerprintOf(const QByteArray &spkiDer)
{
    return QString::fromLatin1(QCryptographicHash::hash(spkiDer, QCryptographicHash::Sha256).toHex());
}

void PairingFlow::withTlsFingerprint(const QString &url, std::function<void(const QString &)> done)
{
    if (!url.startsWith(QLatin1String("https://"))) {
        done(kNoTls);
        return;
    }
    const QUrl u(url);
    auto *sock = new QSslSocket(this);
    connect(sock, &QSslSocket::encrypted, this, [sock, done] {
        const QString fp = fingerprintOf(sock->peerCertificate().publicKey().toDer());
        sock->disconnectFromHost();
        sock->deleteLater();
        done(fp);
    });
    connect(sock, &QSslSocket::errorOccurred, this, [sock, done] {
        sock->deleteLater();
        done(QString());
    });
    sock->connectToHostEncrypted(u.host(), quint16(u.port(443)));
}

QNetworkReply *PairingFlow::post(const QString &path, const QByteArray &body, const QByteArray &type)
{
    QNetworkRequest r(QUrl(m_base + path));
    r.setHeader(QNetworkRequest::ContentTypeHeader, type);
    r.setTransferTimeout(8000);
    return m_nam.post(r, body);
}

QNetworkReply *PairingFlow::get(const QString &path)
{
    QNetworkRequest r(QUrl(m_base + path));
    r.setTransferTimeout(8000);
    return m_nam.get(r);
}

QVariantList PairingFlow::qrModules() const
{
    QVariantList rows;
    if (m_qr.isEmpty())
        return rows;
    const auto qr = qrcodegen::QrCode::encodeText(m_qr.toUtf8().constData(), qrcodegen::QrCode::Ecc::LOW);
    for (int y = 0; y < qr.getSize(); ++y) {
        QString row;
        for (int x = 0; x < qr.getSize(); ++x)
            row += qr.getModule(x, y) ? QLatin1Char('1') : QLatin1Char('0');
        rows << row;
    }
    return rows;
}

void PairingFlow::cancel()
{
    m_poll.stop();
    m_priv.clear();
    m_shared.clear();
    m_qr.clear();
    m_code.clear();
    setState(QStringLiteral("idle"));
}

void PairingFlow::hostStart()
{
    m_isHost = true;
    m_ticks = 0;
    m_code.clear();
    if (m_base.isEmpty()) {
        setState(QStringLiteral("failed"), tr("Configure a server first: pairing goes through it."));
        return;
    }
    setState(QStringLiteral("opening"));
    withTlsFingerprint(m_base, [this](const QString &fp) {
        if (fp.isEmpty()) {
            setState(QStringLiteral("failed"), tr("Could not reach the server."));
            return;
        }
        m_priv = vault::randomBytes(32);
        const auto pub = vault::x25519Pub(m_priv);
        if (!pub) {
            setState(QStringLiteral("failed"), tr("Key generation failed."));
            return;
        }
        m_pairingId = nowToken(vault::randomBytes(16));
        const QString pubB64 = vault::encodePub(*pub);
        vault::PairingQr q;
        q.serverUrl = m_base;
        q.tlsFp = fp;
        q.ephPub = pubB64;
        q.pairingId = m_pairingId;
        const QString payload = vault::encodePairingQr(q);
        const QJsonObject body{ { QStringLiteral("pairing_id"), m_pairingId },
                                { QStringLiteral("eph_pub"), pubB64 } };
        auto *reply = post(QStringLiteral("/v1/pairing"), QJsonDocument(body).toJson(QJsonDocument::Compact),
                           "application/json");
        connect(reply, &QNetworkReply::finished, this, [this, reply, payload] {
            const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            reply->deleteLater();
            if (status != 201) {
                setState(QStringLiteral("failed"), tr("The server refused the pairing."));
                return;
            }
            m_qr = payload;
            setState(QStringLiteral("waiting"));
            m_poll.start();
        });
    });
}

void PairingFlow::pollHostAnswer()
{
    if (m_busy)
        return;
    m_busy = true;
    auto *reply = get(QStringLiteral("/v1/pairing/%1/answer").arg(m_pairingId));
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        m_busy = false;
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const auto obj = QJsonDocument::fromJson(reply->readAll()).object();
        reply->deleteLater();
        if (status != 200 || obj.value(QStringLiteral("b_pub")).isNull() || m_state != QLatin1String("waiting"))
            return;
        const auto bPub = vault::decodePub(obj.value(QStringLiteral("b_pub")).toString());
        const auto shared = bPub ? vault::x25519Shared(m_priv, *bPub) : std::nullopt;
        if (!shared) {
            setState(QStringLiteral("failed"), tr("Invalid key from the new device."));
            return;
        }
        m_shared = *shared;
        m_code = vault::pairingCode(m_shared);
        setState(QStringLiteral("compare"));
    });
}

void PairingFlow::hostApprove()
{
    if (m_state != QLatin1String("compare") || !m_isHost || !m_ws || m_ws->vaultKeyBytes().size() != 32) {
        setState(QStringLiteral("failed"), tr("Nothing to approve."));
        return;
    }
    setState(QStringLiteral("sending"));
    const QByteArray env = vault::sealPairingVk(m_shared, m_pairingId, m_ws->vaultKeyBytes(),
                                                m_ws->vaultEpoch());
    auto *reply = post(QStringLiteral("/v1/pairing/%1/transfer").arg(m_pairingId), env, "application/octet-stream");
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        reply->deleteLater();
        if (status == 201)
            setState(QStringLiteral("done"));
        else
            setState(QStringLiteral("failed"), tr("The transfer failed or expired."));
    });
}

void PairingFlow::guestStart(const QString &qrJson)
{
    m_isHost = false;
    m_ticks = 0;
    const auto qr = vault::decodePairingQr(qrJson.trimmed());
    if (!qr) {
        setState(QStringLiteral("failed"), tr("That code is not valid."));
        return;
    }
    setServerUrl(qr->serverUrl);
    m_guestServer = m_base;
    m_pairingId = qr->pairingId;
    setState(QStringLiteral("opening"));
    withTlsFingerprint(m_base, [this, qr = *qr](const QString &fp) {
        if (m_base.startsWith(QLatin1String("https://")) && fp != qr.tlsFp) {
            setState(QStringLiteral("failed"), tr("The server certificate does not match the code."));
            return;
        }
        const auto aPub = vault::decodePub(qr.ephPub);
        m_priv = vault::randomBytes(32);
        const auto myPub = vault::x25519Pub(m_priv);
        const auto shared = (aPub && myPub) ? vault::x25519Shared(m_priv, *aPub) : std::nullopt;
        if (!shared) {
            setState(QStringLiteral("failed"), tr("That code is not valid."));
            return;
        }
        m_shared = *shared;
        const QJsonObject body{ { QStringLiteral("b_pub"), vault::encodePub(*myPub) } };
        auto *reply = post(QStringLiteral("/v1/pairing/%1/join").arg(m_pairingId),
                           QJsonDocument(body).toJson(QJsonDocument::Compact), "application/json");
        connect(reply, &QNetworkReply::finished, this, [this, reply] {
            const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            reply->deleteLater();
            if (status != 202) {
                setState(QStringLiteral("failed"), tr("Could not reach the server."));
                return;
            }
            m_code = vault::pairingCode(m_shared);
            setState(QStringLiteral("compare"));
            m_poll.start();
        });
    });
}

void PairingFlow::pollGuestTransfer()
{
    if (m_busy)
        return;
    m_busy = true;
    auto *reply = get(QStringLiteral("/v1/pairing/%1/transfer").arg(m_pairingId));
    connect(reply, &QNetworkReply::finished, this, [this, reply] {
        m_busy = false;
        const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
        const QByteArray env = reply->readAll();
        reply->deleteLater();
        if (status != 200 || m_state != QLatin1String("compare"))
            return;
        uint32_t epoch = 1;
        const auto vk = vault::openPairingVk(m_shared, m_pairingId, env, &epoch);
        if (!vk) {
            setState(QStringLiteral("failed"), tr("The transfer could not be verified."));
            return;
        }
        m_receivedVk = *vk;
        m_receivedEpoch = epoch;
        setState(QStringLiteral("done"));
        emit guestJoined();
    });
}

} // namespace pkm
