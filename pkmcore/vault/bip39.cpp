#include "bip39.h"

#include <QCryptographicHash>
#include <QFile>
#include <QRegularExpression>

#include <sodium.h>

#include <cmath>

namespace pkm::bip39 {

namespace {

QByteArray sha512(const QByteArray &data)
{
    unsigned char out[crypto_hash_sha512_BYTES];
    crypto_hash_sha512(out, reinterpret_cast<const unsigned char *>(data.constData()),
                        quint64(data.size()));
    return QByteArray(reinterpret_cast<const char *>(out), int(crypto_hash_sha512_BYTES));
}

// RFC 2104 HMAC-SHA512 with a key of ANY length (BIP39 PBKDF2 uses the fixed
// short salt "mnemonic"; sodium's one-shot hmacsha512 only takes 32-byte keys).
QByteArray hmacSha512(const QByteArray &key, const QByteArray &msg)
{
    const int blockSize = 128;
    QByteArray k = key;
    if (k.size() > blockSize)
        k = sha512(k);
    k.resize(blockSize, '\0');
    QByteArray ipad(blockSize, char(0x36)), opad(blockSize, char(0x5c));
    for (int i = 0; i < blockSize; ++i) {
        ipad.data()[i] = char(ipad.at(i) ^ k.at(i));
        opad.data()[i] = char(opad.at(i) ^ k.at(i));
    }
    return sha512(opad + sha512(ipad + msg));
}

QByteArray sha256(const QByteArray &data)
{
    return QCryptographicHash::hash(data, QCryptographicHash::Sha256);
}

} // namespace

QStringList embeddedWordlist()
{
    // Embedded by pkmcore/CMakeLists.txt (qt_add_resources).
    QFile f(QStringLiteral(":/crypto/bip39_english.txt"));
    if (f.open(QIODevice::ReadOnly))
        return QString::fromUtf8(f.readAll())
            .split(QRegularExpression(QStringLiteral("\\s+")), Qt::SkipEmptyParts);
    return {};
}

std::optional<Codec> Codec::make(const QStringList &words)
{
    if (words.size() != 2048)
        return std::nullopt;
    Codec c;
    c.m_words = words;
    for (int i = 0; i < words.size(); ++i) {
        if (i > 0 && words.at(i - 1) >= words.at(i))
            return std::nullopt; // unsorted (or duplicated): reject, never guess
        c.m_index.insert(words.at(i), i);
    }
    QByteArray joined;
    joined.reserve(2048 * 6);
    for (const QString &w : words)
        joined += w.toUtf8() + '\n';
    c.m_wordlistHash = sha256(joined);
    return c;
}

QString Codec::entropyToMnemonic(const QByteArray &entropy, bool *ok) const
{
    if (ok)
        *ok = false;
    if (entropy.size() != kEntropyBytes)
        return {};
    const QByteArray payload = entropy + sha256(entropy).left(1);
    QString out;
    for (int i = 0; i < kPhraseWords; ++i) {
        int idx = 0;
        for (int k = 0; k < 11; ++k) {
            const int bitPos = i * 11 + k;
            if (payload.at(bitPos / 8) & char(1 << (7 - bitPos % 8)))
                idx |= 1 << (10 - k);
        }
        if (idx < 0 || idx >= m_words.size())
            return {};
        if (i > 0)
            out += QLatin1Char(' ');
        out += m_words.at(idx);
    }
    if (ok)
        *ok = true;
    return out;
}

std::optional<QByteArray> Codec::mnemonicToEntropy(const QString &phrase) const
{
    const QStringList parts =
        phrase.split(QRegularExpression(QStringLiteral("\\s+")), Qt::SkipEmptyParts);
    if (parts.size() != kPhraseWords)
        return std::nullopt; // "phrase must have 24 words"
    std::vector<bool> bits(kPhraseWords * 11, false);
    for (int i = 0; i < parts.size(); ++i) {
        const auto it = m_index.constFind(parts.at(i));
        if (it == m_index.constEnd() || it.value() < 0 || it.value() >= 2048)
            return std::nullopt; // "word not in the list"
        int idx = it.value();
        for (int k = 10; k >= 0; --k) {
            bits[size_t(i * 11 + (10 - k))] = (idx & (1 << k)) != 0;
        }
    }
    QByteArray entropy(kEntropyBytes, char(0));
    for (int i = 0; i < kEntropyBytes * 8; ++i)
        if (bits[size_t(i)])
            entropy.data()[i / 8] = char(entropy.at(i / 8) | char(1 << (7 - i % 8)));
    char checksum = 0;
    for (int i = 0; i < 8; ++i)
        if (bits[size_t(kEntropyBytes * 8 + i)])
            checksum = char(checksum | char(1 << (7 - i)));
    if (sha256(entropy).at(0) != checksum)
        return std::nullopt; // "checksum mismatch"
    return entropy;
}

QByteArray seedNoPassphrase(const QString &phrase)
{
    // PBKDF2-HMAC-SHA512, 2048 iterations, salt "mnemonic", 64 bytes, no
    // passphrase (the standard seed; provided for compatibility vectors).
    const QByteArray pw = phrase.toUtf8();
    const QByteArray salt = QByteArrayLiteral("mnemonic");
    QByteArray out;
    int block = 1;
    while (out.size() < 64) {
        const QByteArray blockSalt =
            salt + QByteArray(1, char(block >> 24)) + QByteArray(1, char(block >> 16))
            + QByteArray(1, char(block >> 8)) + QByteArray(1, char(block));
        QByteArray u = hmacSha512(pw, blockSalt);
        QByteArray t = u;
        for (int i = 1; i < 2048; ++i) {
            u = hmacSha512(pw, u);
            for (int k = 0; k < t.size(); ++k)
                t[k] = char(t.at(k) ^ u.at(k));
        }
        out += t;
        ++block;
    }
    return out.left(64);
}

} // namespace pkm::bip39
