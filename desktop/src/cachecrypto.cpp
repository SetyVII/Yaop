#include "cachecrypto.h"

#include <QDebug>
#include <QEventLoop>
#include <QFile>
#include <QFileInfo>
#include <QDir>

#include <qt6keychain/keychain.h>
#include <sodium.h>

namespace pkm {

namespace {
const QByteArray kMagic = QByteArrayLiteral("YC1");
constexpr int kNonceBytes = crypto_aead_xchacha20poly1305_ietf_NPUBBYTES;

// --profile: tests run under a separate keychain service ("YAOP-<profile>") so they can
// never read or overwrite the real user's entries. Unset → the normal "YAOP" service.
QString keychainService()
{
    const QByteArray env = qgetenv("YAOP_KEYCHAIN_SERVICE");
    return env.isEmpty() ? QStringLiteral("YAOP") : QString::fromUtf8(env);
}

bool sodiumReady()
{
    static const bool ok = sodium_init() >= 0;
    return ok;
}

struct JobResult {
    QKeychain::Error error = QKeychain::OtherError;
    QByteArray data;
    QString message;
};

JobResult runRead(const QString &account)
{
    QKeychain::ReadPasswordJob job(keychainService());
    job.setAutoDelete(false);
    job.setKey(account);
    QEventLoop loop;
    QObject::connect(&job, &QKeychain::Job::finished, &loop, &QEventLoop::quit);
    job.start();
    loop.exec();
    return { job.error(), job.binaryData(), job.errorString() };
}

JobResult runWrite(const QString &account, const QByteArray &data)
{
    QKeychain::WritePasswordJob job(keychainService());
    job.setAutoDelete(false);
    job.setKey(account);
    job.setBinaryData(data);
    QEventLoop loop;
    QObject::connect(&job, &QKeychain::Job::finished, &loop, &QEventLoop::quit);
    job.start();
    loop.exec();
    return { job.error(), {}, job.errorString() };
}

QByteArray readKeyFile(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return {};
    const QByteArray key = QByteArray::fromBase64(f.readAll().trimmed());
    return key.size() == CacheCipher::kKeyBytes ? key : QByteArray();
}

bool writeKeyFile(const QString &path, const QByteArray &key)
{
    QDir().mkpath(QFileInfo(path).absolutePath());
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return false;
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    return f.write(key.toBase64()) > 0;
}
} // namespace

bool CacheCipher::setKey(const QByteArray &key)
{
    if (key.size() != kKeyBytes || !sodiumReady())
        return false;
    m_key = key;
    return true;
}

QByteArray CacheCipher::randomKey()
{
    if (!sodiumReady())
        return {};
    QByteArray key(kKeyBytes, Qt::Uninitialized);
    randombytes_buf(key.data(), size_t(key.size()));
    return key;
}

bool CacheCipher::isSealed(const QByteArray &value)
{
    return value.size() >= kMagic.size() + kNonceBytes + int(crypto_aead_xchacha20poly1305_ietf_ABYTES)
        && value.startsWith(kMagic);
}

QByteArray CacheCipher::seal(const QByteArray &plain, const QByteArray &aad) const
{
    if (!ready())
        return {};
    QByteArray out(kMagic.size() + kNonceBytes + plain.size() + int(crypto_aead_xchacha20poly1305_ietf_ABYTES),
                   Qt::Uninitialized);
    memcpy(out.data(), kMagic.constData(), size_t(kMagic.size()));
    auto *nonce = reinterpret_cast<unsigned char *>(out.data() + kMagic.size());
    randombytes_buf(nonce, kNonceBytes);
    unsigned long long clen = 0;
    crypto_aead_xchacha20poly1305_ietf_encrypt(
        nonce + kNonceBytes, &clen, reinterpret_cast<const unsigned char *>(plain.constData()),
        (unsigned long long)plain.size(), reinterpret_cast<const unsigned char *>(aad.constData()),
        (unsigned long long)aad.size(), nullptr, nonce,
        reinterpret_cast<const unsigned char *>(m_key.constData()));
    out.resize(kMagic.size() + kNonceBytes + int(clen));
    return out;
}

std::optional<QByteArray> CacheCipher::open(const QByteArray &sealed, const QByteArray &aad) const
{
    if (!ready() || !isSealed(sealed))
        return std::nullopt;
    const auto *nonce = reinterpret_cast<const unsigned char *>(sealed.constData() + kMagic.size());
    const auto *ct = nonce + kNonceBytes;
    const qsizetype ctLen = sealed.size() - kMagic.size() - kNonceBytes;
    QByteArray plain(ctLen - int(crypto_aead_xchacha20poly1305_ietf_ABYTES), Qt::Uninitialized);
    unsigned long long plen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(
            reinterpret_cast<unsigned char *>(plain.data()), &plen, nullptr, ct, (unsigned long long)ctLen,
            reinterpret_cast<const unsigned char *>(aad.constData()), (unsigned long long)aad.size(), nonce,
            reinterpret_cast<const unsigned char *>(m_key.constData()))
        != 0)
        return std::nullopt;
    plain.resize(qsizetype(plen));
    return plain;
}

QByteArray loadOrCreateCacheKey(const QString &account, const QString &keyFileFallback, KeySource *source)
{
    auto set = [source](KeySource s) {
        if (source)
            *source = s;
    };
    // A key file means an earlier run had no keychain: keep using it.
    const QByteArray fromFile = readKeyFile(keyFileFallback);
    if (!fromFile.isEmpty()) {
        set(KeySource::KeyFile);
        return fromFile;
    }
    const JobResult read = runRead(account);
    if (read.error == QKeychain::NoError && read.data.size() == CacheCipher::kKeyBytes) {
        set(KeySource::Keychain);
        return read.data;
    }
    if (read.error == QKeychain::EntryNotFound) {
        const QByteArray key = CacheCipher::randomKey();
        if (runWrite(account, key).error == QKeychain::NoError) {
            set(KeySource::Keychain);
            return key;
        }
    } else if (read.error != QKeychain::NoBackendAvailable && read.error != QKeychain::NotImplemented
               && read.error != QKeychain::OtherError) {
        // Access denied / locked keychain: never replace a key that may exist.
        qCritical().noquote() << "cache key: keychain refused access:" << read.message;
        set(KeySource::Failed);
        return {};
    }
    qWarning().noquote() << "cache key: no system keychain (" << read.message
                         << "), using a 0600 key file — weaker protection";
    const QByteArray key = CacheCipher::randomKey();
    if (!writeKeyFile(keyFileFallback, key)) {
        set(KeySource::Failed);
        return {};
    }
    set(KeySource::KeyFile);
    return key;
}

QByteArray readBinarySecret(const QString &account, bool *available)
{
    const JobResult r = runRead(account);
    if (available)
        *available = r.error == QKeychain::NoError || r.error == QKeychain::EntryNotFound;
    return r.error == QKeychain::NoError ? r.data : QByteArray();
}

bool writeBinarySecret(const QString &account, const QByteArray &data)
{
    return runWrite(account, data).error == QKeychain::NoError;
}

QString readSecret(const QString &account, bool *available)
{
    const JobResult r = runRead(account);
    if (available)
        *available = r.error == QKeychain::NoError || r.error == QKeychain::EntryNotFound;
    return r.error == QKeychain::NoError ? QString::fromUtf8(r.data) : QString();
}

bool writeSecret(const QString &account, const QString &value)
{
    return runWrite(account, value.toUtf8()).error == QKeychain::NoError;
}

vault::SecretStore keychainSecrets()
{
    return { [](const QString &account, bool *available) { return readBinarySecret(account, available); },
             [](const QString &account, const QByteArray &data) { return writeBinarySecret(account, data); } };
}

} // namespace pkm
