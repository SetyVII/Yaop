#pragma once

// E2EE core of spec/crypto.md (port of the Go reference in
// server/internal/simclient): HKDF subkeys per domain, XChaCha20-Poly1305
// AEAD, blinded ids (wire_id), envelopes (version 0x01 raw / 0x02 zstd niv 3),
// device pairing (X25519 + short code + wrapped VK) and the local VaultKey
// storage: system keychain first (QtKeychain, service "YAOP"), wrapped
// passphrase file fallback, ephemeral key when neither exists (pre-onboarding).
//
// The desktop also derives two LOCAL-ONLY subkeys (never on the wire) out of
// the HKDF spine: "local-db" keys the SQLCipher database and "local-files" keys
// the sealed media cache files (cachecrypto.h CacheCipher).

#include <QByteArray>
#include <QString>

#include <cstdint>
#include <functional>
#include <optional>

namespace pkm::vault {

// HKDF salt fixed by spec/crypto.md §Subkeys.
inline constexpr auto kHkdfSalt = "yaop-vault/1";
inline constexpr auto kPairingSalt = "yaop-pairing/1";
inline constexpr int kSubkeyBytes = 32;

// Contract domains (spec/crypto.md). `ids` is a MAC key, never an AEAD key.
QByteArray hkdfSha256(const QByteArray &ikm, const QByteArray &salt, const QByteArray &info,
                      int length);
QByteArray subkey(const QByteArray &vk, const QByteArray &domain);

// ---------------------------------------------------------------------------
// AEAD: XChaCha20-Poly1305 (libsodium), 24-byte random nonces, AAD-bound.
// ---------------------------------------------------------------------------
inline constexpr int kAeadNonceBytes = 24;
inline constexpr int kAeadTagBytes = 16;

QByteArray randomBytes(int n);

// App-lock PIN (spec §15.5): argon2id (libsodium crypto_pwhash_str); the encoded string carries
// salt and cost, never the PIN. verifyPin is constant-time inside libsodium.
QString hashPin(const QString &pin);
bool verifyPin(const QString &pin, const QString &encoded);
// seal with a fresh random nonce (production). Empty result on failure.
QByteArray aeadSeal(const QByteArray &key, const QByteArray &plain, const QByteArray &aad);
// Deterministic variant for conformance vectors: caller-supplied 24-byte nonce.
QByteArray aeadSealWithNonce(const QByteArray &key, const QByteArray &nonce, const QByteArray &plain,
                             const QByteArray &aad);
// Authenticates; nullopt on wrong key / AAD / tampered bytes.
std::optional<QByteArray> aeadOpen(const QByteArray &key, const QByteArray &nonce,
                                    const QByteArray &cipher, const QByteArray &aad);
// HChaCha20 subkey of the XChaCha20 construction (draft-irtf-cfrg-xchacha-03
// §2.2.1). Exposed for the conformance vector only.
QByteArray hchacha20(const QByteArray &key, const QByteArray &nonce16);

// ---------------------------------------------------------------------------
// Blinded ids (spec/crypto.md blinded ids section): first 16 bytes of
// HMAC-SHA256(ids-subkey, real id) as lowercase hex.
// ---------------------------------------------------------------------------
QString wireId(const QByteArray &idsKey, const QString &realId);
inline QString derivedBlindInput(const QString &objectId, const QString &kind)
{
    return QStringLiteral("derived/") + objectId + QLatin1Char('/') + kind;
}
QString derivedWireId(const QByteArray &idsKey, const QString &objectId, const QString &kind);

// ---------------------------------------------------------------------------
// Envelope: version(1) | key_epoch(u32 BE) | nonce(24) | ciphertext+tag
// ---------------------------------------------------------------------------
inline constexpr uint8_t kEnvRaw = 0x01;    // payload stored as-is (always valid)
inline constexpr uint8_t kEnvZstd = 0x02;  // payload zstd-compressed BEFORE sealing
inline constexpr int kEnvHeaderLen = 1 + 4 + kAeadNonceBytes;
inline constexpr int kZstdLevel = 3; // production compression level

// Contract domain of each op kind (spec/sync.md §Operation); empty for an unknown kind.
// The single place that maps kind -> domain: callers never repeat this table.
inline QByteArray domainForKind(const QString &kind)
{
    if (kind == QLatin1String("obj") || kind == QLatin1String("type") || kind == QLatin1String("asset_meta"))
        return QByteArrayLiteral("objects");
    if (kind == QLatin1String("derived"))
        return QByteArrayLiteral("derived");
    if (kind == QLatin1String("snapshot"))
        return QByteArrayLiteral("snapshots");
    if (kind == QLatin1String("node_request"))
        return QByteArrayLiteral("ops");
    return {};
}

inline QString aadFor(const QByteArray &domain, const QString &wireIdStr)
{
    return QString::fromUtf8(domain) + QLatin1Char('/') + wireIdStr;
}
inline QString chunkAad(const QString &wireIdStr, int n)
{
    return QStringLiteral("assets/") + wireIdStr + QStringLiteral("/chunk/")
         + QString::number(n);
}

struct OpenedEnvelope {
    QByteArray payload;
    uint32_t keyEpoch = 0;
    uint8_t version = 0;
};

QByteArray zstdCompress(const QByteArray &plain);         // level kZstdLevel
std::optional<QByteArray> zstdDecompress(const QByteArray &frame);

// `domain` is the contract domain ("objects", "assets", ...); the AAD is
// "<domain>/<wireId>" (assets chunks use chunkAad → pass it as wireId+flag).
QByteArray sealEnvelope(const QByteArray &key, uint32_t keyEpoch, const QByteArray &domain,
                        const QString &wireId, const QByteArray &payload, bool compress,
                        const QByteArray *fixedNonce = nullptr);
// `cachedVersion` optionally receives the envelope version byte.
std::optional<QByteArray> openEnvelope(const QByteArray &key, const QByteArray &env,
                                       const QByteArray &domain, const QString &wireId,
                                       uint32_t *keyEpochOut = nullptr, uint8_t *versionOut = nullptr);

// ---------------------------------------------------------------------------
// Assets: independent 4 MiB plain chunks, each sealed alone (version 0x01,
// domain "assets", AAD "assets/<wire_id>/chunk/<n>") and concatenated. The
// media object carries chunks/total_plain_size, so boundaries are derived.
// ---------------------------------------------------------------------------
inline constexpr int kAssetChunkBytes = 4 << 20; // 4 MiB
// Chunk n sealed alone — the body of PUT Upload-Offset:n (spec/sync.md).
QVector<QByteArray> sealAssetChunks(const QByteArray &assetsKey, uint32_t keyEpoch,
                                    const QString &wireId, const QByteArray &plain);
// The same chunks concatenated: the full ciphertext stream of /content.
QByteArray encryptAssetChunks(const QByteArray &assetsKey, uint32_t keyEpoch,
                              const QString &wireId, const QByteArray &plain);
// chunks = number of sealed chunks (chunks row of the media object).
std::optional<QByteArray> decryptAssetChunks(const QByteArray &assetsKey, const QString &wireId,
                                             const QByteArray &cipher, int chunks);

// ---------------------------------------------------------------------------
// Pairing (spec/crypto.md §Pairing)
// ---------------------------------------------------------------------------
struct PairingQr {
    int v = 1;
    QString serverUrl;
    QString tlsFp;
    QString ephPub; // base64url no padding
    QString pairingId; // 32 hex chars
};
// Canonical compact JSON, exact key order of the spec.
QString encodePairingQr(const PairingQr &q);
std::optional<PairingQr> decodePairingQr(const QString &json);

QString encodePub(const QByteArray &pub);
std::optional<QByteArray> decodePub(const QString &b64);
std::optional<QByteArray> x25519Pub(const QByteArray &priv);
std::optional<QByteArray> x25519Shared(const QByteArray &priv, const QByteArray &peerPub);
// 6-digit zero-padded short code both devices show.
QString pairingCode(const QByteArray &shared);
QByteArray pairingVkKey(const QByteArray &shared);
QByteArray sealPairingVk(const QByteArray &shared, const QString &pairingId, const QByteArray &vk,
                         uint32_t keyEpoch, const QByteArray *fixedNonce = nullptr);
std::optional<QByteArray> openPairingVk(const QByteArray &shared, const QString &pairingId,
                                       const QByteArray &env, uint32_t *keyEpochOut = nullptr);

// ---------------------------------------------------------------------------
// VaultKey storage (spec/crypto.md §VK): keychain entry = 32-byte VK + 4-byte
// epoch (BE). Without a keychain: a passphrase-wrapped file (YAOP_VAULT_
// PASSPHRASE pre-onboarding; onboarding gives this a UI). Without either: an
// ephemeral in-memory key — nothing persistent is written unencrypted.
// ---------------------------------------------------------------------------

struct VaultKeys {
    QByteArray vk; // 32 bytes (never logged, never on the wire unwrapped)
    uint32_t epoch = 1;
    enum class Source { Keychain, WrappedFile, Ephemeral, Failed } source = Source::Failed;
    bool persistent() const { return source == Source::Keychain || source == Source::WrappedFile; }
};

// Where the host app keeps small secrets (the desktop uses the system keychain).
// Either function may be empty: that store is then treated as unavailable.
struct SecretStore {
    std::function<QByteArray(const QString &account, bool *available)> read;
    std::function<bool(const QString &account, const QByteArray &data)> write;
};

VaultKeys loadOrCreateVaultKey(const SecretStore &secrets, const QString &keychainAccount,
                               const QString &wrappedKeyFile);
// Existing VK only (keychain, then the env-passphrase wrapped file); never creates one.
// source == Failed when there is none: the onboarding  decides what to do.
VaultKeys loadVaultKey(const SecretStore &secrets, const QString &keychainAccount,
                       const QString &wrappedKeyFile);
// Persists an onboarding/pairing VK: keychain, else the passphrase-wrapped file when
// YAOP_VAULT_PASSPHRASE is set. source == Failed when nothing can protect it.
VaultKeys storeVaultKey(const SecretStore &secrets, const QString &keychainAccount,
                        const QString &wrappedKeyFile,
                        const QByteArray &vk, uint32_t epoch);
// Local-only HKDF derivations (extra info names, off the contracted domains).
QByteArray deriveDbKey(const QByteArray &vk); // SQLCipher key (hex-encoded on use)
QByteArray deriveFileKey(const QByteArray &vk); // media cache sealing (CacheCipher)

} // namespace pkm::vault
