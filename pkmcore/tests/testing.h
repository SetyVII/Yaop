#pragma once
// Tiny test harness shared by pkmcore_tests and the desktop --selftest.
// CHECK counts failures instead of aborting, so one run reports everything.

#include <QByteArray>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonValue>
#include <QPair>
#include <QString>
#include <QVector>
#include <cstdio>

#ifndef PKMCORE_SPEC_DIR
#define PKMCORE_SPEC_DIR "spec/fixtures"
#endif

namespace pkm::testing {

inline int failures = 0;

#define CHECK(cond, msg)                                                       \
    do {                                                                       \
        if (!(cond)) {                                                         \
            std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, msg);           \
            ++pkm::testing::failures;                                          \
        }                                                                      \
    } while (0)


// ---------------------------------------------------------------------------
// Contract conformance: same fixtures as Go and Kotlin (spec/fixtures).
// ---------------------------------------------------------------------------
inline QString specDir()
{
    const QByteArray env = qgetenv("PKMCORE_SPEC_DIR");
    return env.isEmpty() ? QStringLiteral(PKMCORE_SPEC_DIR) : QString::fromLocal8Bit(env);
}

inline QVector<QPair<QString, QJsonObject>> loadFixtures(const QString &sub)
{
    QVector<QPair<QString, QJsonObject>> out;
    QDir dir(specDir() + QLatin1Char('/') + sub);
    for (const QString &f : dir.entryList({ QStringLiteral("*.json") }, QDir::Files, QDir::Name)) {
        QFile file(dir.filePath(f));
        if (file.open(QIODevice::ReadOnly))
            out.append({ f, QJsonDocument::fromJson(file.readAll()).object() });
    }
    if (out.isEmpty()) {
        std::printf("FAIL no fixtures in %s\n", qPrintable(dir.path()));
        ++failures;
    }
    return out;
}


// JSON equality on canonical serialize (numbers through the double pipeline,
// keys sorted, null vs {} visible) — the Go canon()/tCan() equivalent.
inline bool jsonEq(const QJsonValue &a, const QJsonValue &b)
{
    const QByteArray ba = QJsonDocument(QJsonArray{ a }).toJson(QJsonDocument::Compact);
    const QByteArray bb = QJsonDocument(QJsonArray{ b }).toJson(QJsonDocument::Compact);
    if (ba != bb) {
        std::printf("  got  %s\n  want %s\n", ba.constData(), bb.constData());
        return false;
    }
    return true;
}

// CacheCipher keeps guarding the sealed media cache  on top of libsodium:
// XChaCha20-Poly1305 with AAD binding, junk-detection prefix.
inline QByteArray readFile(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly))
        return {};
    return f.readAll();
}

// ---------------------------------------------------------------------------
// spec/fixtures/crypto conformance (spec/crypto.md): the same vectors the Go
// reference and the Kotlin runner execute — HKDF domains, blinded ids, BIP39,
// AEAD official vectors, exact envelope bytes, zstd roundtrip, pairing
// transcript.
// ---------------------------------------------------------------------------

inline QJsonObject cryptoFixture(const QString &name, const char *label)
{
    QFile f(specDir() + QLatin1String("/crypto/") + name);
    if (!f.open(QIODevice::ReadOnly)) {
        std::printf("FAIL cannot read crypto fixture %s\n", qPrintable(name));
        ++failures;
        return {};
    }
    const QJsonObject o = QJsonDocument::fromJson(f.readAll()).object();
    if (o.isEmpty()) {
        std::printf("FAIL empty crypto fixture %s (%s)\n", qPrintable(name), label);
        ++failures;
    }
    return o;
}

inline QByteArray hexBytes(const QString &hex)
{
    return QByteArray::fromHex(hex.toLatin1());
}

inline QString hexStr(const QByteArray &bytes)
{
    return QString::fromLatin1(bytes.toHex());
}

} // namespace pkm::testing
