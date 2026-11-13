#pragma once

// BIP-0039 recovery phrase of 24 words (spec/crypto.md §BIP39). The 256-bit
// entropy IS the Vault Key: there is no passphrase, and the PBKDF2 seed
// exists only for standard compatibility checks (never as vault key
// material). The official English wordlist ships embedded in the binary
// (resource :/crypto/bip39_english.txt).

#include <QByteArray>
#include <QHash>
#include <QString>
#include <QStringList>

#include <optional>

namespace pkm::bip39 {

inline constexpr int kEntropyBytes = 32; // 256 bits
inline constexpr int kPhraseWords = 24;

class Codec {
public:
    // `words` must be the exact 2048-word standard list (sorted, unique).
    // The sha256 of the loaded list must match the documented one.
    static std::optional<Codec> make(const QStringList &words);

    // 32-byte entropy -> 24-word phrase (checksum = first SHA-256 byte).
    QString entropyToMnemonic(const QByteArray &entropy, bool *ok = nullptr) const;
    // 24 words -> 32 bytes; fails closed on checksum or unknown words or count.
    std::optional<QByteArray> mnemonicToEntropy(const QString &phrase) const;

    const QStringList &words() const { return m_words; }
    QByteArray wordlistSha256() const { return m_wordlistHash; }

private:
    QStringList m_words;
    QHash<QString, int> m_index;
    QByteArray m_wordlistHash;
};

// Embedded wordlist (qrc); falls back to spec/fixtures/crypto when the
// resource is absent (development trees). Empty on failure.
QStringList embeddedWordlist();

// Standard BIP39 seed: PBKDF2-HMAC-SHA512(phrase, "mnemonic", 2048, 64).
// Compatibility only — the vault never uses it as key material.
QByteArray seedNoPassphrase(const QString &phrase);

} // namespace pkm::bip39
