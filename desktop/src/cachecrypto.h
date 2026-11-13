#pragma once

// Local cache encryption (spec §15.2/§15.5).
// Every sensitive value of the desktop cache (objects, types, offline queue, file names, media
// files) is sealed with XChaCha20-Poly1305 (libsodium) under a 256-bit cache key, with an AAD
// that binds it to its row (`objects/<id>`, `asset-file/<id>`…), like the server's vault.go.
// The key lives in the system keychain (Secret Service via QtKeychain); without one, it falls
// back to a 0600 key file and says so in the log.

#include <QByteArray>
#include <QString>
#include <optional>
#include "vaultcrypto.h"

namespace pkm {

class CacheCipher {
public:
    static constexpr int kKeyBytes = 32;

    bool setKey(const QByteArray &key); // false unless exactly kKeyBytes
    bool ready() const { return m_key.size() == kKeyBytes; }

    // "YC1" + 24-byte nonce + ciphertext (with the 16-byte tag).
    QByteArray seal(const QByteArray &plain, const QByteArray &aad) const;
    std::optional<QByteArray> open(const QByteArray &sealed, const QByteArray &aad) const;
    static bool isSealed(const QByteArray &value);

    static QByteArray randomKey();

private:
    QByteArray m_key;
};

// Where the cache key came from (for logs and the settings screen).
enum class KeySource { Keychain, KeyFile, Failed };

// Loads the cache key for this data directory, creating it on first use.
// `keyFileFallback` is the 0600 file used only when no keychain is available.
QByteArray loadOrCreateCacheKey(const QString &account, const QString &keyFileFallback, KeySource *source);

// Small secrets (the device token) kept in the keychain; empty string when absent/unavailable.
QString readSecret(const QString &account, bool *available = nullptr);
bool writeSecret(const QString &account, const QString &value);
// Binary variants (: the VaultKey blob travels as raw bytes).
QByteArray readBinarySecret(const QString &account, bool *available = nullptr);
bool writeBinarySecret(const QString &account, const QByteArray &data);

// The keychain as a vault::SecretStore (what pkmcore_vault uses to persist the VaultKey).
vault::SecretStore keychainSecrets();

} // namespace pkm
