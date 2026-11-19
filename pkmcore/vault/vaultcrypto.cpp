#include "vaultcrypto.h"

#include <sodium.h>

#include <QDebug>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include <zstd.h>

namespace pkm::vault {

namespace {

bool sodiumReady()
{
    static const bool ok = sodium_init() >= 0;
    return ok;
}

const unsigned char *uc(const QByteArray &b)
{
    return reinterpret_cast<const unsigned char *>(b.constData());
}

unsigned char *uw(QByteArray &b, qsizetype off = 0)
{
    return reinterpret_cast<unsigned char *>(b.data() + off);
}

QByteArray sha256(const QByteArray &data)
{
    QByteArray out(int(crypto_hash_sha256_BYTES), Qt::Uninitialized);
    crypto_hash_sha256(uw(out), uc(data), quint64(data.size()));
    return out;
}

// RFC 2104 HMAC-SHA256 with a key of ANY length (libsodium's
// crypto_auth_hmacsha256 only accepts exactly 32-byte keys — the HKDF salt
// "yaop-vault/1" and passphrases are shorter).
QByteArray hmacSha256(const QByteArray &key, const QByteArray &msg)
{
    const int blockSize = 64;
    QByteArray k = key;
    if (k.size() > blockSize)
        k = sha256(k);
    k.resize(blockSize, '\0');
    QByteArray ipad(blockSize, char(0x36)), opad(blockSize, char(0x5c));
    for (int i = 0; i < blockSize; ++i) {
        ipad.data()[i] = char(ipad.at(i) ^ k.at(i));
        opad.data()[i] = char(opad.at(i) ^ k.at(i)) ;
    }
    return sha256(opad + sha256(ipad + msg));
}

void putU32(QByteArray &out, uint32_t v)
{
    out.append(char(uint8_t(v >> 24)));
    out.append(char(uint8_t(v >> 16)));
    out.append(char(uint8_t(v >> 8)));
    out.append(char(uint8_t(v)));
}

uint32_t readU32(const QByteArray &in, qsizetype off)
{
    return (uint32_t(uint8_t(in.at(off))) << 24) | (uint32_t(uint8_t(in.at(off + 1))) << 16)
           | (uint32_t(uint8_t(in.at(off + 2))) << 8) | uint32_t(uint8_t(in.at(off + 3)));
}

// PBKDF2-HMAC-SHA256 (only for the passphrase-wrapped key file; the vault
// itself never derives keys from a passphrase — spec/crypto.md).
QByteArray pbkdf2Sha256(const QByteArray &password, const QByteArray &salt, int iterations,
                        int length)
{
    QByteArray out;
    int block = 1;
    while (out.size() < length) {
        // U1 = HMAC(pw, salt || INT(block)); Ui = HMAC(pw, U(i-1))
        QByteArray u = hmacSha256(password, salt + QByteArray(1, char(block >> 24))
                                                    + QByteArray(1, char(block >> 16))
                                                    + QByteArray(1, char(block >> 8))
                                                    + QByteArray(1, char(block)));
        QByteArray t = u;
        for (int i = 1; i < iterations; ++i) {
            u = hmacSha256(password, u);
            for (int k = 0; k < t.size(); ++k)
                t[k] = char(t.at(k) ^ u.at(k));
        }
        out += t;
        ++block;
    }
    return out.left(length);
}

} // namespace

// ---------------------------------------------------------------------------
// HKDF / subkeys
// ---------------------------------------------------------------------------

QByteArray hkdfSha256(const QByteArray &ikm, const QByteArray &salt, const QByteArray &info,
                      int length)
{
    // RFC 5869 over HMAC-SHA256 (libsodium has no HKDF; spec/crypto.md asks
    // for exactly this construction).
    const QByteArray prk = hmacSha256(salt, ikm); // extract
    QByteArray out;
    QByteArray prev;
    uint8_t counter = 1;
    while (out.size() < length) {
        QByteArray blockIn = prev + info + QByteArray(1, char(counter));
        prev = hmacSha256(prk, blockIn);
        out += prev;
        ++counter;
    }
    return out.left(length);
}

QByteArray subkey(const QByteArray &vk, const QByteArray &domain, const QByteArray &salt)
{
    return hkdfSha256(vk, salt, domain, kSubkeyBytes);
}

QByteArray randomBytes(int n)
{
    if (!sodiumReady() || n <= 0)
        return {};
    QByteArray out(n, Qt::Uninitialized);
    randombytes_buf(uw(out), size_t(n));
    return out;
}

// ---------------------------------------------------------------------------
// AEAD
// ---------------------------------------------------------------------------

QString hashPin(const QString &pin)
{
    if (!sodiumReady() || pin.isEmpty())
        return {};
    const QByteArray pw = pin.toUtf8();
    char out[crypto_pwhash_STRBYTES];
    // MODERATE: ~0.7 s on a desktop — a PIN is short, so the work factor is what protects it.
    if (crypto_pwhash_str(out, pw.constData(), quint64(pw.size()), crypto_pwhash_OPSLIMIT_MODERATE,
                          crypto_pwhash_MEMLIMIT_MODERATE) != 0)
        return {};
    return QString::fromLatin1(out);
}

bool verifyPin(const QString &pin, const QString &encoded)
{
    if (!sodiumReady() || pin.isEmpty() || encoded.isEmpty())
        return false;
    const QByteArray pw = pin.toUtf8();
    const QByteArray enc = encoded.toLatin1();
    return crypto_pwhash_str_verify(enc.constData(), pw.constData(), quint64(pw.size())) == 0;
}

QByteArray aeadSealWithNonce(const QByteArray &key, const QByteArray &nonce, const QByteArray &plain,
                             const QByteArray &aad)
{
    if (!sodiumReady() || key.size() != int(crypto_aead_xchacha20poly1305_ietf_KEYBYTES)
        || nonce.size() != kAeadNonceBytes)
        return {};
    QByteArray out(plain.size() + int(crypto_aead_xchacha20poly1305_ietf_ABYTES), Qt::Uninitialized);
    unsigned long long clen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_encrypt(
            uw(out), &clen, uc(plain), quint64(plain.size()), uc(aad), quint64(aad.size()),
            nullptr, uc(nonce), uc(key))
        != 0)
        return {};
    out.resize(qsizetype(clen));
    return out;
}

QByteArray aeadSeal(const QByteArray &key, const QByteArray &plain, const QByteArray &aad)
{
    return aeadSealWithNonce(key, randomBytes(kAeadNonceBytes), plain, aad);
}

std::optional<QByteArray> aeadOpen(const QByteArray &key, const QByteArray &nonce,
                                   const QByteArray &cipher, const QByteArray &aad)
{
    if (!sodiumReady() || key.size() != int(crypto_aead_xchacha20poly1305_ietf_KEYBYTES)
        || nonce.size() != kAeadNonceBytes || cipher.size() < int(crypto_aead_xchacha20poly1305_ietf_ABYTES))
        return std::nullopt;
    QByteArray out(cipher.size() - int(crypto_aead_xchacha20poly1305_ietf_ABYTES), Qt::Uninitialized);
    unsigned long long plen = 0;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(
            uw(out), &plen, nullptr, uc(cipher), quint64(cipher.size()), uc(aad),
            quint64(aad.size()), uc(nonce), uc(key))
        != 0)
        return std::nullopt;
    out.resize(qsizetype(plen));
    return out;
}

QByteArray hchacha20(const QByteArray &key, const QByteArray &nonce16)
{
    if (!sodiumReady() || key.size() != int(crypto_core_hchacha20_KEYBYTES)
        || nonce16.size() != int(crypto_core_hchacha20_INPUTBYTES))
        return {};
    QByteArray out(int(crypto_core_hchacha20_OUTPUTBYTES), Qt::Uninitialized);
    crypto_core_hchacha20(uw(out), uc(nonce16), uc(key), nullptr);
    return out;
}

// ---------------------------------------------------------------------------
// Blinded ids
// ---------------------------------------------------------------------------

QString wireId(const QByteArray &idsKey, const QString &realId)
{
    const QByteArray mac = hmacSha256(idsKey, realId.toUtf8());
    return QString::fromLatin1(mac.toHex()).left(32);
}

QString derivedWireId(const QByteArray &idsKey, const QString &objectId, const QString &kind)
{
    return wireId(idsKey, derivedBlindInput(objectId, kind));
}

// ---------------------------------------------------------------------------
// Envelope
// ---------------------------------------------------------------------------

QByteArray zstdCompress(const QByteArray &plain)
{
    const size_t bound = ZSTD_compressBound(size_t(plain.size()));
    QByteArray out(int(bound) + 16, Qt::Uninitialized);
    const size_t n = ZSTD_compress(uw(out), bound, uc(plain), size_t(plain.size()), kZstdLevel);
    if (ZSTD_isError(n))
        return {};
    out.resize(qsizetype(n));
    return out;
}

std::optional<QByteArray> zstdDecompress(const QByteArray &frame)
{
    const unsigned long long hint = ZSTD_getFrameContentSize(uc(frame), size_t(frame.size()));
    size_t cap = hint == ZSTD_CONTENTSIZE_UNKNOWN || hint == ZSTD_CONTENTSIZE_ERROR
        ? size_t(frame.size()) * 32 + 1024
        : size_t(hint) + 64;
    for (int attempt = 0; attempt < 4; ++attempt) {
        QByteArray out(int(cap), Qt::Uninitialized);
        const size_t n = ZSTD_decompress(uw(out), size_t(cap), uc(frame), size_t(frame.size()));
        if (!ZSTD_isError(n)) {
            out.resize(qsizetype(n));
            return out;
        }
        if (ZSTD_getErrorCode(n) != ZSTD_error_memory_allocation
            && cap > size_t(qMax(1 << 30, frame.size() * 64)))
            break;
        cap *= 4;
    }
    return std::nullopt;
}

QByteArray sealEnvelope(const QByteArray &key, uint32_t keyEpoch, const QByteArray &domain,
                        const QString &wireIdStr, const QByteArray &payload, bool compress,
                        const QByteArray *fixedNonce)
{
    if (!sodiumReady())
        return {};
    QByteArray frame = payload;
    uint8_t version = kEnvRaw;
    if (compress) {
        frame = zstdCompress(payload);
        if (frame.isEmpty())
            return {};
        version = kEnvZstd;
    }
    QByteArray nonce(kAeadNonceBytes, Qt::Uninitialized);
    if (fixedNonce) {
        if (fixedNonce->size() != kAeadNonceBytes)
            return {};
        nonce = *fixedNonce;
    } else {
        randombytes_buf(uw(nonce), size_t(nonce.size()));
    }
    const QByteArray ct =
        aeadSealWithNonce(key, nonce, frame, aadFor(domain, wireIdStr).toUtf8());
    if (ct.isEmpty())
        return {};
    QByteArray out;
    out.reserve(kEnvHeaderLen + ct.size());
    out.append(char(version));
    putU32(out, keyEpoch);
    out.append(nonce);
    out.append(ct);
    return out;
}

std::optional<QByteArray> openEnvelope(const QByteArray &key, const QByteArray &env,
                                       const QByteArray &domain, const QString &wireIdStr,
                                       uint32_t *keyEpochOut, uint8_t *versionOut)
{
    if (env.size() < kEnvHeaderLen + kAeadTagBytes)
        return std::nullopt;
    const uint8_t version = uint8_t(env.at(0));
    if (version != kEnvRaw && version != kEnvZstd)
        return std::nullopt;
    const uint32_t epoch = readU32(env, 1);
    const QByteArray nonce = env.mid(5, kAeadNonceBytes);
    const QByteArray ct = env.mid(5 + kAeadNonceBytes);
    auto plain = aeadOpen(key, nonce, ct, aadFor(domain, wireIdStr).toUtf8());
    if (!plain)
        return std::nullopt;
    QByteArray payload = *plain;
    if (version == kEnvZstd) {
        auto decompressed = zstdDecompress(*plain);
        if (!decompressed)
            return std::nullopt;
        payload = *decompressed;
    }
    if (keyEpochOut)
        *keyEpochOut = epoch;
    if (versionOut)
        *versionOut = version;
    return payload;
}

// ---------------------------------------------------------------------------
// Asset chunks
// ---------------------------------------------------------------------------

// One sealed asset chunk n (version 0x01, domain "assets", AAD
// "assets/<wire_id>/chunk/<n>", random nonce) — the PUT body of the upload.
static QByteArray sealAssetChunk(const QByteArray &assetsKey, uint32_t keyEpoch,
                                 const QString &wireIdStr, int n, const QByteArray &chunk)
{
    QByteArray nonce(kAeadNonceBytes, Qt::Uninitialized);
    randombytes_buf(uw(nonce), size_t(nonce.size()));
    const QByteArray ct =
        aeadSealWithNonce(assetsKey, nonce, chunk, chunkAad(wireIdStr, n).toUtf8());
    if (ct.isEmpty())
        return {};
    QByteArray frame;
    frame.reserve(kEnvHeaderLen + ct.size());
    frame.append(char(kEnvRaw));
    putU32(frame, keyEpoch);
    frame.append(nonce);
    frame.append(ct);
    return frame;
}

// Sealed chunks ready for the /v1/assets/{wire_id}/upload transport (spec/
// spec/sync.md §Assets): `out[n]` is the ciphertext frame the client PUTs with
// Upload-Offset n. Independent frames → a resumed upload can seal the
// remaining chunks again (fresh nonces) and the concatenated download still
// decrypts chunk by chunk.
QVector<QByteArray> sealAssetChunks(const QByteArray &assetsKey, uint32_t keyEpoch,
                                    const QString &wireIdStr, const QByteArray &plain)
{
    QVector<QByteArray> out;
    int n = 0;
    for (int off = 0; off < plain.size(); off += kAssetChunkBytes, ++n)
        out.append(sealAssetChunk(assetsKey, keyEpoch, wireIdStr, n,
                                  plain.mid(off, kAssetChunkBytes)));
    return out;
}

QByteArray encryptAssetChunks(const QByteArray &assetsKey, uint32_t keyEpoch,
                              const QString &wireIdStr, const QByteArray &plain)
{
    // Independent sealed chunks (spec/crypto.md §Envelope and §Assets): version 0x01
    // (media is already compressed), AAD "assets/<wire_id>/chunk/<n>".
    const QVector<QByteArray> frames = sealAssetChunks(assetsKey, keyEpoch, wireIdStr, plain);
    QByteArray out;
    for (const QByteArray &frame : frames)
        out.append(frame);
    return out;
}

std::optional<QByteArray> decryptAssetChunks(const QByteArray &assetsKey, const QString &wireIdStr,
                                             const QByteArray &cipher, int chunks)
{
    if (chunks <= 0 || !sodiumReady())
        return std::nullopt;
    QByteArray out;
    qsizetype off = 0;
    for (int n = 0; n < chunks; ++n) {
        if (off + kEnvHeaderLen + kAeadTagBytes > cipher.size())
            return std::nullopt;
        if (uint8_t(cipher.at(off)) != kEnvRaw)
            return std::nullopt; // media chunks are always raw (spec/crypto.md)
        const uint32_t epoch = readU32(cipher, off + 1); // same epoch for the whole vault copy
        Q_UNUSED(epoch);
        const QByteArray nonce = cipher.mid(off + 5, kAeadNonceBytes);
        const qsizetype maxCt = cipher.size() - (off + kEnvHeaderLen);
        // Plain chunk sizes are fixed by construction: kAssetChunkBytes for
        // every chunk except the last, which holds the remainder.
        if (n + 1 >= chunks && maxCt > kAssetChunkBytes + kAeadTagBytes)
            return std::nullopt;
        const qsizetype expectPlain = (n + 1 >= chunks) ? -1 : kAssetChunkBytes;
        const qsizetype ctLen = expectPlain < 0 ? maxCt : expectPlain + kAeadTagBytes;
        if (kEnvHeaderLen + ctLen + off > cipher.size())
            return std::nullopt;
        auto plain = aeadOpen(assetsKey, nonce, cipher.mid(off + kEnvHeaderLen, ctLen),
                              chunkAad(wireIdStr, n).toUtf8());
        if (!plain)
            return std::nullopt;
        out.append(*plain);
        off += kEnvHeaderLen + ctLen;
    }
    if (off != cipher.size())
        return std::nullopt; // trailing garbage: fail closed
    return out;
}

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

namespace {
QString jsonString(const QString &value)
{
    // Compact, spec-compatible JSON string escaping.
    const QByteArray raw = QJsonDocument(QJsonArray{ value }).toJson(QJsonDocument::Compact);
    return QString::fromUtf8(raw.mid(1, raw.size() - 2));
}
} // namespace

QString encodePairingQr(const PairingQr &q)
{
    return QStringLiteral("{\"v\":1,\"server_url\":") + jsonString(q.serverUrl)
         + QStringLiteral(",\"tls_fp\":") + jsonString(q.tlsFp)
         + QStringLiteral(",\"eph_pub\":") + jsonString(q.ephPub)
         + QStringLiteral(",\"pairing_id\":") + jsonString(q.pairingId) + QLatin1Char('}');
}

std::optional<PairingQr> decodePairingQr(const QString &json)
{
    const QJsonDocument doc = QJsonDocument::fromJson(json.toUtf8());
    if (!doc.isObject())
        return std::nullopt;
    const QJsonObject o = doc.object();
    if (o.size() != 5)
        return std::nullopt; // strict: no unknown fields (matches the Go decoder)
    PairingQr q;
    q.v = o.value(QLatin1String("v")).toInt(0);
    q.serverUrl = o.value(QLatin1String("server_url")).toString();
    q.tlsFp = o.value(QLatin1String("tls_fp")).toString();
    q.ephPub = o.value(QLatin1String("eph_pub")).toString();
    q.pairingId = o.value(QLatin1String("pairing_id")).toString();
    if (q.v != 1 || q.serverUrl.isEmpty() || q.tlsFp.isEmpty() || q.pairingId.isEmpty())
        return std::nullopt;
    if (!decodePub(q.ephPub) || q.pairingId.size() != 32
        || q.pairingId != q.pairingId.toLower()
        || QByteArray::fromHex(q.pairingId.toLatin1()).size() != 16)
        return std::nullopt;
    if (q.tlsFp.size() != 64 || QByteArray::fromHex(q.tlsFp.toLatin1()).size() != 32)
        return std::nullopt;
    return q;
}

QString encodePub(const QByteArray &pub)
{
    return QString::fromLatin1(pub.toBase64(QByteArray::Base64UrlEncoding
                                            | QByteArray::OmitTrailingEquals));
}

std::optional<QByteArray> decodePub(const QString &b64)
{
    const QByteArray raw = QByteArray::fromBase64(b64.toLatin1(),
                                                  QByteArray::Base64UrlEncoding
                                                      | QByteArray::OmitTrailingEquals);
    if (raw.size() != int(crypto_scalarmult_curve25519_BYTES))
        return std::nullopt;
    return raw;
}

std::optional<QByteArray> x25519Pub(const QByteArray &priv)
{
    if (!sodiumReady() || priv.size() != int(crypto_scalarmult_curve25519_SCALARBYTES))
        return std::nullopt;
    QByteArray pub(int(crypto_scalarmult_curve25519_BYTES), Qt::Uninitialized);
    if (crypto_scalarmult_base(uw(pub), uc(priv)) != 0)
        return std::nullopt;
    return pub;
}
std::optional<QByteArray> x25519Shared(const QByteArray &priv, const QByteArray &peerPub)
{
    if (!sodiumReady() || priv.size() != int(crypto_scalarmult_curve25519_SCALARBYTES)
        || peerPub.size() != int(crypto_scalarmult_curve25519_BYTES))
        return std::nullopt;
    QByteArray shared(int(crypto_scalarmult_curve25519_BYTES), Qt::Uninitialized);
    if (crypto_scalarmult(uw(shared), uc(priv), uc(peerPub)) != 0)
        return std::nullopt;
    return shared;
}

QString pairingCode(const QByteArray &shared)
{
    const QByteArray derived =
        hkdfSha256(shared, QByteArray(kPairingSalt), QByteArrayLiteral("short-code"), 4);
    const uint32_t v = readU32(derived, 0);
    return QStringLiteral("%1").arg(v % 1000000, 6, 10, QLatin1Char('0'));
}

QByteArray pairingVkKey(const QByteArray &shared)
{
    return hkdfSha256(shared, QByteArray(kPairingSalt), QByteArrayLiteral("vk-transfer"),
                     kSubkeyBytes);
}

QString pairingEnrollKey(const QByteArray &shared)
{
    return QString::fromLatin1(
        hkdfSha256(shared, QByteArray(kPairingSalt), QByteArrayLiteral("enroll"), 32).toHex());
}

QByteArray sealPairingVk(const QByteArray &shared, const QString &pairingId, const QByteArray &vk,
                         uint32_t keyEpoch, const QByteArray *fixedNonce)
{
    return sealEnvelope(pairingVkKey(shared), keyEpoch, QByteArrayLiteral("pairing"), pairingId,
                        vk, false, fixedNonce);
}

std::optional<QByteArray> openPairingVk(const QByteArray &shared, const QString &pairingId,
                                        const QByteArray &env, uint32_t *keyEpochOut)
{
    uint8_t version = 0;
    auto payload = openEnvelope(pairingVkKey(shared), env, QByteArrayLiteral("pairing"), pairingId,
                                keyEpochOut, &version);
    if (!payload || version != kEnvRaw || payload->size() != 32)
        return std::nullopt;
    return payload;
}

// ---------------------------------------------------------------------------
// VaultKey storage
// ---------------------------------------------------------------------------

namespace {
constexpr auto kWrapMagic = "VKW1";           // + 16B salt + 24B nonce + ct(vk|epoch)
constexpr int kWrapSaltBytes = 16;
constexpr int kWrapIterations = 600000;         // PBKDF2-HMAC-SHA256 (file fallback only)
constexpr auto kWrapAad = "yaop-vault-wrap/1";
constexpr int kVkBlobBytes = 36;               // 32-byte VK + 4-byte epoch (BE)

QByteArray readSecret(const SecretStore &s, const QString &account, bool *available)
{
    *available = false;
    return s.read ? s.read(account, available) : QByteArray();
}

bool writeSecret(const SecretStore &s, const QString &account, const QByteArray &data)
{
    return s.write && s.write(account, data);
}
} // namespace

static bool writeWrappedFile(const QString &path, const QByteArray &passphrase,
                             const QByteArray &vk, uint32_t epoch)
{
    const QByteArray salt = randomBytes(kWrapSaltBytes);
    const QByteArray kek =
        pbkdf2Sha256(passphrase, salt, kWrapIterations, kSubkeyBytes);
    if (kek.isEmpty())
        return false;
    QByteArray blob = vk;
    putU32(blob, epoch);
    // The nonce must travel with the file: readWrappedFile() expects magic|salt|nonce|ct.
    const QByteArray nonce = randomBytes(kAeadNonceBytes);
    const QByteArray ct = aeadSealWithNonce(kek, nonce, blob, QByteArray(kWrapAad));
    if (ct.isEmpty())
        return false;
    QFile f(path);
    if (!f.open(QIODevice::WriteOnly | QIODevice::Truncate))
        return false;
    f.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    const QByteArray file = QByteArray(kWrapMagic) + salt + nonce + ct;
    return f.write(file) == file.size();
}

static std::optional<VaultKeys> readWrappedFile(const QString &path, const QByteArray &passphrase)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return std::nullopt;
    const QByteArray data = f.readAll();
    if (data.size() < 4 + kWrapSaltBytes + kAeadNonceBytes + kAeadTagBytes
        || !data.startsWith(QByteArray(kWrapMagic)))
        return std::nullopt;
    const QByteArray salt = data.mid(4, kWrapSaltBytes);
    const QByteArray nonce = data.mid(4 + kWrapSaltBytes, kAeadNonceBytes);
    const QByteArray ct = data.mid(4 + kWrapSaltBytes + kAeadNonceBytes);
    const QByteArray kek =
        pbkdf2Sha256(passphrase, salt, kWrapIterations, kSubkeyBytes);
    auto blob = aeadOpen(kek, nonce, ct, QByteArray(kWrapAad));
    if (!blob || blob->size() != kVkBlobBytes)
        return std::nullopt;
    VaultKeys out;
    out.vk = blob->left(32);
    out.epoch = readU32(*blob, 32);
    out.source = VaultKeys::Source::WrappedFile;
    return out;
}

VaultKeys loadOrCreateVaultKey(const SecretStore &secrets, const QString &keychainAccount,
                               const QString &wrappedKeyFile)
{
    // 1. The keychain entry wins when it exists and is intact.
    bool keychainAvailable = false;
    const QByteArray stored = readSecret(secrets, keychainAccount, &keychainAvailable);
    if (!stored.isEmpty() && stored.size() == kVkBlobBytes) {
        VaultKeys out;
        out.vk = stored.left(32);
        out.epoch = readU32(stored, 32);
        if (out.vk.size() == 32 && out.epoch >= 1) {
            out.source = VaultKeys::Source::Keychain;
            return out;
        }
    }

    // 2. Wrapped passphrase file (pre-onboarding: YAOP_VAULT_PASSPHRASE gives the
    //    passphrase until onboarding replaces this with UI).
    const QByteArray passphrase = qgetenv("YAOP_VAULT_PASSPHRASE");
    if (!passphrase.isEmpty()) {
        if (auto wrapped = readWrappedFile(wrappedKeyFile, passphrase))
            return *wrapped;
        VaultKeys fresh;
        fresh.vk = randomBytes(32);
        fresh.epoch = 1;
        if (writeWrappedFile(wrappedKeyFile, passphrase, fresh.vk, fresh.epoch)) {
            fresh.source = VaultKeys::Source::WrappedFile;
            return fresh;
        }
    }

    // 3. Keychain works (or could work) for a brand new entry.
    if (stored.isEmpty() && keychainAvailable) {
        VaultKeys fresh;
        fresh.vk = randomBytes(32);
        fresh.epoch = 1;
        QByteArray blob = fresh.vk;
        putU32(blob, fresh.epoch);
        if (writeSecret(secrets, keychainAccount, blob)) {
            fresh.source = VaultKeys::Source::Keychain;
            return fresh;
        }
    }

    // 4. Nothing can protect a persistent copy: run on an ephemeral key this
    //    session. Nothing reaches disk unencrypted (spec §15.2: no raw key
    //    files ever again); the session stays usable but not persistent.
    qCritical().noquote() << "vault: no keychain and no passphrase: using an in-memory vault key"
                          << "(this session cannot persist encrypted local data)";
    VaultKeys out;
    out.vk = randomBytes(32);
    out.epoch = 1;
    out.source = VaultKeys::Source::Ephemeral;
    return out;
}

VaultKeys loadVaultKey(const SecretStore &secrets, const QString &keychainAccount,
                       const QString &wrappedKeyFile)
{
    bool keychainAvailable = false;
    const QByteArray stored = readSecret(secrets, keychainAccount, &keychainAvailable);
    if (stored.size() == kVkBlobBytes) {
        VaultKeys out;
        out.vk = stored.left(32);
        out.epoch = readU32(stored, 32);
        if (out.epoch >= 1) {
            out.source = VaultKeys::Source::Keychain;
            return out;
        }
    }
    const QByteArray passphrase = qgetenv("YAOP_VAULT_PASSPHRASE");
    if (!passphrase.isEmpty()) {
        if (auto wrapped = readWrappedFile(wrappedKeyFile, passphrase))
            return *wrapped;
    }
    return {};
}

VaultKeys storeVaultKey(const SecretStore &secrets, const QString &keychainAccount,
                        const QString &wrappedKeyFile,
                        const QByteArray &vk, uint32_t epoch)
{
    VaultKeys out;
    if (vk.size() != 32 || epoch < 1)
        return out;
    out.vk = vk;
    out.epoch = epoch;
    const QByteArray passphrase = qgetenv("YAOP_VAULT_PASSPHRASE");
    if (!passphrase.isEmpty()) {
        if (writeWrappedFile(wrappedKeyFile, passphrase, vk, epoch)) {
            out.source = VaultKeys::Source::WrappedFile;
            return out;
        }
    }
    QByteArray blob = vk;
    putU32(blob, epoch);
    if (writeSecret(secrets, keychainAccount, blob)) {
        out.source = VaultKeys::Source::Keychain;
        return out;
    }
    out.vk.clear();
    return out;
}

QByteArray deriveDbKey(const QByteArray &vk)
{
    return subkey(vk, QByteArrayLiteral("local-db")); // local-only info name
}

QByteArray deriveFileKey(const QByteArray &vk)
{
    return subkey(vk, QByteArrayLiteral("local-files")); // local-only info name
}

} // namespace pkm::vault
