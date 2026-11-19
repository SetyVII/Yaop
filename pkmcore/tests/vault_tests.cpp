#include "testing.h"
#include "backup.h"
#include "bip39.h"
#include "bootstrapsnapshot.h"
#include "model.h"
#include "nodeops.h"
#include "remoteops.h"
#include "syncpolicy.h"
#include "vaultcrypto.h"
#include <QElapsedTimer>
#include <QClipboard>
#include <QGuiApplication>
#include <QMimeData>
#include <QBuffer>
#include <QtEndian>
#include <algorithm>
#include <QCryptographicHash>
#include <QJsonArray>
#include <QDir>
#include <QRegularExpression>
#include <QTemporaryDir>
#include <QFile>
#include <QJsonDocument>
#include <QTextCharFormat>
#include <QTextCursor>
#include <QTextDocument>
#include <QtGlobal>
#include <cstdio>

namespace pkm::testing {


static void testCryptoConformance()
{
    // ---- HKDF domains: 7 subkeys derive from the VK with the fixed salt
    {
        const QJsonObject f = cryptoFixture(QStringLiteral("hkdf_domains.json"), "hkdf");
        CHECK(f.value(QLatin1String("salt")).toString() == QLatin1String(vault::kHkdfSalt),
              "crypto: hkdf salt contract");
        CHECK(f.value(QLatin1String("len")).toInt() == vault::kSubkeyBytes,
              "crypto: hkdf subkey length contract");
        const QByteArray vk = hexBytes(f.value(QLatin1String("vk")).toString());
        const QJsonObject subkeys = f.value(QLatin1String("subkeys")).toObject();
        CHECK(subkeys.size() == 7, "crypto: hkdf 7 domains");
        for (auto it = subkeys.begin(); it != subkeys.end(); ++it) {
            const QString got = hexStr(vault::subkey(vk, it.key().toUtf8()));
            if (got != it.value().toString())
                CHECK(false, qPrintable(QStringLiteral("crypto: hkdf subkey ") + it.key()));
        }
    }

    // ---- Blinded ids: HMAC ids-subkey, composite derived/<objectId>/<kind>
    {
        const QJsonObject f = cryptoFixture(QStringLiteral("wire_id.json"), "wire_id");
        const QByteArray vk = hexBytes(f.value(QLatin1String("vk")).toString());
        const QByteArray idsKey = hexBytes(f.value(QLatin1String("ids_subkey")).toString());
        CHECK(hexStr(vault::subkey(vk, QByteArrayLiteral("ids"))) == f.value(QLatin1String("ids_subkey")).toString(),
              "crypto: ids subkey derives from VK");
        QHash<QString, QString> seen;
        for (const QJsonValue &v : f.value(QLatin1String("cases")).toArray()) {
            const QJsonObject c = v.toObject();
            const QString got = vault::wireId(idsKey, c.value(QLatin1String("id")).toString());
            if (got != c.value(QLatin1String("wire_id")).toString())
                CHECK(false, qPrintable(QStringLiteral("crypto: wire_id ") + got));
            const QString id = c.value(QLatin1String("id")).toString();
            if (seen.contains(id) && seen.value(id) != got)
                CHECK(false, "crypto: wire_id not deterministic across cases");
            seen.insert(id, got);
        }
        for (const QJsonValue &v : f.value(QLatin1String("derived_cases")).toArray()) {
            const QJsonObject c = v.toObject();
            const QString objectId = c.value(QLatin1String("object_id")).toString();
            const QString kind = c.value(QLatin1String("kind")).toString();
            CHECK(vault::derivedBlindInput(objectId, kind)
                      == c.value(QLatin1String("input")).toString(),
                  "crypto: derived blind input composite");
            if (vault::derivedWireId(idsKey, objectId, kind)
                != c.value(QLatin1String("wire_id")).toString())
                CHECK(false, qPrintable(QStringLiteral("crypto: derived wire_id ") + kind));
        }
    }

    // ---- BIP39: official wordlist bytes, entropy<->mnemonic, seed, invalids
    {
        const QJsonObject f = cryptoFixture(QStringLiteral("bip39.json"), "bip39");
        const QJsonObject wl = f.value(QLatin1String("wordlist")).toObject();
        QFile wlFile(specDir() + QLatin1String("/crypto/bip39_english.txt"));
        CHECK(wlFile.open(QIODevice::ReadOnly), "crypto: bip39 wordlist file readable");
        const QByteArray wlBytes = wlFile.readAll();
        const QByteArray wlSum = QCryptographicHash::hash(wlBytes, QCryptographicHash::Sha256);
        CHECK(hexStr(wlSum) == wl.value(QLatin1String("sha256")).toString(),
              "crypto: bip39 wordlist sha256 matches the official file");
        const auto codec = bip39::Codec::make(bip39::embeddedWordlist());
        CHECK(codec.has_value(), "crypto: bip39 embedded wordlist loads");
        CHECK(codec->words().size() == wl.value(QLatin1String("words")).toInt(),
              "crypto: bip39 2048 words embedded");
        CHECK(codec->wordlistSha256() == wlSum,
              "crypto: embedded wordlist hashes to the same bytes");
        for (const QJsonValue &v : f.value(QLatin1String("valid")).toArray()) {
            const QJsonObject c = v.toObject();
            const QByteArray entropy = hexBytes(c.value(QLatin1String("entropy_hex")).toString());
            const QString mnemonic = codec->entropyToMnemonic(entropy);
            if (mnemonic != c.value(QLatin1String("mnemonic")).toString())
                CHECK(false, qPrintable(QStringLiteral("crypto: bip39 mnemonic ") + mnemonic));
            const auto back = codec->mnemonicToEntropy(c.value(QLatin1String("mnemonic")).toString());
            if (!back.has_value() || *back != entropy)
                CHECK(false, qPrintable(QStringLiteral("crypto: bip39 entropy roundtrip ") + mnemonic));
            const QByteArray seed =
                bip39::seedNoPassphrase(c.value(QLatin1String("mnemonic")).toString());
            if (hexStr(seed) != c.value(QLatin1String("seed_no_passphrase_hex")).toString())
                CHECK(false, qPrintable(QStringLiteral("crypto: bip39 seed ") + mnemonic));
        }
        for (const QJsonValue &v : f.value(QLatin1String("invalid")).toArray()) {
            const QJsonObject c = v.toObject();
            CHECK(!codec->mnemonicToEntropy(c.value(QLatin1String("mnemonic")).toString()).has_value(),
                  "crypto: bip39 invalid phrase rejected");
        }
    }

    // ---- AEAD XChaCha20-Poly1305: official draft-irtf-cfrg-xchacha-03 vectors
    {
        const QJsonObject f =
            cryptoFixture(QStringLiteral("aead_xchacha20poly1305.json"), "aead");
        const QJsonObject hch = f.value(QLatin1String("hchacha20")).toObject();
        const QByteArray hk = hexBytes(hch.value(QLatin1String("key_hex")).toString());
        const QByteArray hn = hexBytes(hch.value(QLatin1String("nonce_hex")).toString());
        CHECK(hn.size() == 16, "crypto: hchacha20 takes the first 16 nonce bytes");
        CHECK(hexStr(vault::hchacha20(hk, hn)) == hch.value(QLatin1String("subkey_hex")).toString(),
              "crypto: hchacha20 official subkey");
        for (const QJsonValue &v : f.value(QLatin1String("cases")).toArray()) {
            const QJsonObject c = v.toObject();
            const QByteArray key = hexBytes(c.value(QLatin1String("key_hex")).toString());
            const QByteArray nonce = hexBytes(c.value(QLatin1String("nonce_hex")).toString());
            const QByteArray aad = hexBytes(c.value(QLatin1String("aad_hex")).toString());
            const QByteArray plain = c.value(QLatin1String("plaintext_ascii")).toString().toUtf8();
            const QByteArray want = hexBytes(c.value(QLatin1String("ciphertext_plus_tag_hex")).toString());
            const QByteArray got = vault::aeadSealWithNonce(key, nonce, plain, aad);
            if (got != want)
                CHECK(false, qPrintable(QStringLiteral("crypto: aead ct+tag %1")
                                             .arg(c.value(QLatin1String("name")).toString())));
            const auto open = vault::aeadOpen(key, nonce, want, aad);
            CHECK(open.has_value() && *open == plain, "crypto: aead open roundtrip");
            QByteArray bad = want;
            bad[3] = char(quint8(bad[3]) ^ 0xff);
            CHECK(!vault::aeadOpen(key, nonce, bad, aad).has_value(),
                  "crypto: aead tampered ct rejected");
            CHECK(!vault::aeadOpen(key, nonce, want, QByteArrayLiteral("other-aad")).has_value(),
                  "crypto: aead wrong aad rejected");
            QByteArray flipKey = key;
            flipKey[0] = char(quint8(flipKey[0]) ^ 1);
            CHECK(!vault::aeadOpen(flipKey, nonce, want, aad).has_value(),
                  "crypto: aead wrong key rejected");
        }
        // production seal: fresh random nonce every time (spec/crypto.md)
        {
            const QByteArray key = vault::subkey(
                hexBytes(QStringLiteral("11223344556677889900aabbccddeeff00112233445566778899aabbccddeeff")),
                QByteArrayLiteral("objects"));
            const QByteArray payload = QStringLiteral("payload de producci\u00f3n").toUtf8();
            const QByteArray aad = vault::aadFor(QByteArrayLiteral("objects"), QStringLiteral("xyz")).toUtf8();
            const QByteArray one = vault::aeadSeal(key, payload, aad);
            const QByteArray two = vault::aeadSeal(key, payload, aad);
            CHECK(!one.isEmpty() && one != two, "crypto: random nonce never repeats");
        }
    }

    // ---- Envelope v0x01: exact bytes, key_epoch, negatives
    {
        const QJsonObject f = cryptoFixture(QStringLiteral("envelope.json"), "envelope");
        const QByteArray vk = hexBytes(f.value(QLatin1String("vk")).toString());
        const QJsonArray cases = f.value(QLatin1String("cases")).toArray();
        CHECK(cases.size() == 3, "crypto: envelope fixture carries 3 cases");
        for (const QJsonValue &v : cases) {
            const QJsonObject c = v.toObject();
            const QString name = c.value(QLatin1String("name")).toString();
            const QByteArray domain = c.value(QLatin1String("domain")).toString().toUtf8();
            const QString wireIdStr = c.value(QLatin1String("wire_id")).toString();
            const QByteArray key = vault::subkey(vk, domain);
            if (hexStr(key) != c.value(QLatin1String("key")).toString())
                CHECK(false, qPrintable(QStringLiteral("crypto: envelope key domain mismatch ") + name));
            QByteArray payload;
            if (c.contains(QLatin1String("payload_hex"))) {
                payload = hexBytes(c.value(QLatin1String("payload_hex")).toString());
            } else {
                payload = c.value(QLatin1String("payload")).toString().toUtf8();
            }
            const QByteArray nonce = hexBytes(c.value(QLatin1String("nonce")).toString());
            const QByteArray env = vault::sealEnvelope(
                key, uint32_t(c.value(QLatin1String("key_epoch")).toInt()), domain, wireIdStr,
                payload, false, &nonce);
            if (env != hexBytes(c.value(QLatin1String("envelope_hex")).toString()))
                CHECK(false, qPrintable(QStringLiteral("crypto: envelope bytes ") + name));
            // AAD contract: "<domain>/<wire_id>"; assets chunks append /chunk/<n>
            const bool isChunk = c.contains(QLatin1String("chunk_index"));
            const QString wantAad = isChunk
                ? vault::chunkAad(wireIdStr, c.value(QLatin1String("chunk_index")).toInt())
                : vault::aadFor(domain, wireIdStr);
            CHECK(wantAad == c.value(QLatin1String("aad")).toString(),
                  "crypto: envelope aad contract");
            uint32_t epoch = 0;
            uint8_t version = 0;
            const auto opened = vault::openEnvelope(
                key, env, domain, wireIdStr, &epoch, &version);
            CHECK(opened.has_value() && *opened == payload && version == vault::kEnvRaw
                      && epoch == uint32_t(c.value(QLatin1String("key_epoch")).toInt()),
                  "crypto: envelope open roundtrip");
            QByteArray tampered = env;
            tampered[tampered.size() - 1] = char(quint8(tampered[tampered.size() - 1]) ^ 0x01);
            CHECK(!vault::openEnvelope(key, tampered, domain, wireIdStr).has_value(),
                  "crypto: tampered envelope rejected");
            CHECK(!vault::openEnvelope(key, env, domain, QStringLiteral("ffffffffffffffff")).has_value(),
                  "crypto: envelope wrong aad rejected");
            QByteArray otherVk = vk;
            otherVk[0] = char(quint8(otherVk[0]) ^ 1);
            CHECK(!vault::openEnvelope(vault::subkey(otherVk, domain), env, domain, wireIdStr).has_value(),
                  "crypto: envelope other epoch key rejected");
        }
    }

    // ---- Envelope v0x02: zstd roundtrip (never exact bytes)
    {
        const QJsonObject f = cryptoFixture(QStringLiteral("envelope_zstd_roundtrip.json"),
                                            "zstd");
        const QByteArray vk = hexBytes(f.value(QLatin1String("vk_hex")).toString());
        const QJsonArray cases = f.value(QLatin1String("cases")).toArray();
        CHECK(cases.size() == 2, "crypto: zstd fixture carries 2 cases");
        for (const QJsonValue &v : cases) {
            const QJsonObject c = v.toObject();
            const QString name = c.value(QLatin1String("name")).toString();
            const QByteArray domain = c.value(QLatin1String("domain")).toString().toUtf8();
            const QString wireIdStr = c.value(QLatin1String("wire_id")).toString();
            const QByteArray key = vault::subkey(vk, c.value(QLatin1String("key_subkey")).toString().toUtf8());
            const QByteArray payload = c.value(QLatin1String("payload")).toString().toUtf8();
            const QByteArray nonce = hexBytes(c.value(QLatin1String("nonce_hex")).toString());
            const QByteArray env = vault::sealEnvelope(
                key, uint32_t(c.value(QLatin1String("key_epoch")).toInt()), domain, wireIdStr,
                payload, true, &nonce);
            CHECK(quint8(env[0]) == vault::kEnvZstd, "crypto: compress marks version 0x02");
            uint32_t epoch = 0;
            uint8_t version = 0;
            const auto opened = vault::openEnvelope(key, env, domain, wireIdStr, &epoch, &version);
            CHECK(opened.has_value() && *opened == payload && version == vault::kEnvZstd
                      && epoch == uint32_t(c.value(QLatin1String("key_epoch")).toInt()),
                  "crypto: zstd envelope roundtrip");
            // version 0x01 (raw) of the same payload is always valid too
            const QByteArray env1 = vault::sealEnvelope(
                key, uint32_t(c.value(QLatin1String("key_epoch")).toInt()), domain, wireIdStr,
                payload, false, &nonce);
            const auto opened1 = vault::openEnvelope(key, env1, domain, wireIdStr);
            CHECK(opened1.has_value() && *opened1 == payload,
                  "crypto: raw envelope always opens");
        }
    }

    // ---- Pairing transcript: X25519 + short code + wrapped VK, all 6 steps
    {
        const QJsonObject f = cryptoFixture(QStringLiteral("pairing_transcript.json"), "pairing");
        QHash<int, QJsonObject> steps;
        for (const QJsonValue &v : f.value(QLatin1String("steps")).toArray())
            steps.insert(v.toObject().value(QLatin1String("n")).toInt(), v.toObject());
        const QJsonObject s1 = steps.value(1);
        const QJsonObject s2 = steps.value(2);
        const QJsonObject s3 = steps.value(3);
        const QJsonObject s4 = steps.value(4);
        const QJsonObject s5 = steps.value(5);
        const QJsonObject s6 = steps.value(6);

        const auto pubA = vault::x25519Pub(hexBytes(s1.value(QLatin1String("priv_a")).toString()));
        CHECK(pubA.has_value()
                  && hexStr(*pubA) == s1.value(QLatin1String("pub_a")).toString(),
              "crypto: pairing pub from scalar (desk)");
        CHECK(vault::encodePub(*pubA) == s1.value(QLatin1String("pub_a_b64url")).toString(),
              "crypto: pub encoded b64url no padding");
        const auto qr = vault::decodePairingQr(s2.value(QLatin1String("qr_json")).toString());
        CHECK(qr.has_value() && qr->v == 1, "crypto: pairing qr decodes");
        CHECK(qr->ephPub == vault::encodePub(*pubA)
                  && qr->pairingId == s6.value(QLatin1String("pairing_id")).toString(),
              "crypto: qr carries the live ephemeral pub");
        const auto pubB = vault::x25519Pub(hexBytes(s3.value(QLatin1String("priv_b")).toString()));
        CHECK(pubB.has_value() && hexStr(*pubB) == s3.value(QLatin1String("pub_b")).toString(),
              "crypto: pairing pub from scalar (mobile)");
        const auto sharedA = vault::x25519Shared(hexBytes(s1.value(QLatin1String("priv_a")).toString()), *pubB);
        const auto sharedB = vault::x25519Shared(hexBytes(s3.value(QLatin1String("priv_b")).toString()), *pubA);
        CHECK(sharedA.has_value() && sharedB.has_value() && sharedA == sharedB
                  && hexStr(*sharedA) == s4.value(QLatin1String("shared_hex")).toString(),
              "crypto: x25519 shared secret agrees on both sides");
        CHECK(vault::pairingCode(*sharedA) == s5.value(QLatin1String("short_code")).toString(),
              "crypto: 6-digit short code");
        const QString enrollKey = steps.value(7).value(QLatin1String("enroll_key")).toString();
        CHECK(!enrollKey.isEmpty() && vault::pairingEnrollKey(*sharedA) == enrollKey
                  && vault::pairingEnrollKey(*sharedB) == enrollKey,
              "crypto: enroll key (step 7) agrees on both sides");
        CHECK(hexStr(vault::pairingVkKey(*sharedA))
                  == s6.value(QLatin1String("vk_transfer_key")).toString(),
              "crypto: vk transfer key");
        const QByteArray env6 = hexBytes(s6.value(QLatin1String("envelope_v1_hex")).toString());
        uint32_t epoch = 0;
        const auto vk = vault::openPairingVk(*sharedA, s6.value(QLatin1String("pairing_id")).toString(),
                                             env6, &epoch);
        CHECK(vk.has_value() && hexStr(*vk) == s6.value(QLatin1String("vk")).toString()
                  && epoch == uint32_t(s6.value(QLatin1String("key_epoch")).toInt()),
              "crypto: vk unwraps with the key_epoch");
        const QByteArray canonicalNonce = hexBytes(s6.value(QLatin1String("nonce")).toString());
        const QByteArray reSealed = vault::sealPairingVk(
            *sharedA, s6.value(QLatin1String("pairing_id")).toString(), hexBytes(s6.value(QLatin1String("vk")).toString()),
            uint32_t(s6.value(QLatin1String("key_epoch")).toInt()), &canonicalNonce);
        CHECK(reSealed == env6, "crypto: vk envelope bytes are canonical");
    }
}

// ---------------------------------------------------------------------------
// spec/fixtures/sync conformance (spec/sync.md): executable model of the
// blind server semantics — seq assignment, op_id idempotency, bootstrap
// compaction, pull windows and the exact error bodies. blind server's real endpoints
// must behave like this model.
// ---------------------------------------------------------------------------

namespace syncproto {

inline constexpr int kMaxOpsPerPush = 500;
inline constexpr int kPullLimitDefault = 200;
inline constexpr int kPullLimitMax = 1000;
inline constexpr auto kProtocolVersion = "1";

struct Op {
    QString opId;
    QString deviceId;
    QString kind;
    QString wireId;
    quint32 keyEpoch = 1;
    QString blob;
};

struct LoggedOp {
    quint64 seq = 0;
    Op op;
    QString receivedAt;
};

struct SyncError {
    QString code;
    QString message;
    QJsonObject extra; // merged into the error body (compact_floor, ...)

    QJsonObject body() const
    {
        QJsonObject err;
        err.insert(QStringLiteral("code"), code);
        err.insert(QStringLiteral("message"), message);
        for (auto it = extra.begin(); it != extra.end(); ++it)
            if (!err.contains(it.key()))
                err.insert(it.key(), it.value());
        QJsonObject out;
        out.insert(QStringLiteral("error"), err);
        return out;
    }
    int httpStatus() const
    {
        if (code == QLatin1String("oplog_compacted"))
            return 409;
        if (code == QLatin1String("not_found"))
            return 404;
        return 400; // bad_request, bad_kind, ...
    }
};

static bool validKind(const QString &kind)
{
    return kind == QLatin1String("obj") || kind == QLatin1String("type")
        || kind == QLatin1String("asset_meta") || kind == QLatin1String("derived")
        || kind == QLatin1String("snapshot") || kind == QLatin1String("node_request");
}

static QString receivedAtFor(quint64 seq)
{
    // abstract clock of the model: matches the fixtures' received_at field
    return QStringLiteral("2026-11-09T12:%1:%2Z")
        .arg((seq - 1) / 60 % 60, 2, 10, QLatin1Char('0'))
        .arg((seq - 1) % 60, 2, 10, QLatin1Char('0'));
}

class OpLog {
public:
    struct PushResult {
        QString opId;
        quint64 seq = 0;
    };

    // fills *headOut with the head seq, or the exact sync error on *err
    QVector<PushResult> push(const QVector<Op> &ops, quint64 *headOut, SyncError *err)
    {
        if (ops.size() > kMaxOpsPerPush) {
            *err = SyncError{
                QStringLiteral("bad_request"),
                QStringLiteral("push carries %1 ops, max %2").arg(ops.size()).arg(kMaxOpsPerPush),
                {}
            };
            return {};
        }
        QVector<PushResult> results;
        for (const Op &op : ops) {
            const bool kindOk = validKind(op.kind);
            const bool idOk = !op.opId.isEmpty() && op.opId.size() == 32;
            // exact message shapes of spec/sync.md (checked by the fixtures)
            if (!kindOk) {
                *err = SyncError{ QStringLiteral("bad_kind"),
                                  QStringLiteral("unknown kind \"%1\"").arg(op.kind), {} };
                return {};
            }
            if (!idOk) {
                *err = SyncError{ QStringLiteral("bad_request"),
                                  QStringLiteral("op_id must be 32 hex chars"), {} };
                return {};
            }
            if (const auto it = m_byOpId.constFind(op.opId); it != m_byOpId.constEnd()) {
                results.append({ op.opId, it.value() }); // retry: same seq
                continue;
            }
            ++m_headSeq;
            LoggedOp logged;
            logged.seq = m_headSeq;
            logged.op = op;
            logged.receivedAt = receivedAtFor(m_headSeq);
            m_ops.append(logged);
            m_byOpId.insert(op.opId, m_headSeq);
            results.append({ op.opId, m_headSeq });
        }
        *headOut = m_headSeq;
        return results;
    }

    SyncError promoteBootstrap(const QString &opId, quint64 coveredSeq)
    {
        const auto it = m_byOpId.constFind(opId);
        if (it == m_byOpId.constEnd())
            return { QStringLiteral("not_found"),
                     QStringLiteral("op %1 not in the log").arg(opId), {} };
        const quint64 seq = it.value();
        if (coveredSeq > seq)
            return { QStringLiteral("bad_request"),
                     QStringLiteral("covered_seq %1 exceeds the snapshot seq %2")
                         .arg(coveredSeq)
                         .arg(seq),
                     {} };
        if (coveredSeq < m_compactFloor)
            return { QStringLiteral("bad_request"),
                     QStringLiteral("covered_seq %1 would lower the floor %2")
                         .arg(coveredSeq)
                         .arg(m_compactFloor),
                     {} };
        m_compactFloor = coveredSeq;
        m_bootstrapSeq = seq;
        return {};
    }

    void compact()
    {
        QVector<LoggedOp> kept;
        kept.reserve(m_ops.size());
        for (const LoggedOp &op : m_ops) {
            if (op.seq <= m_compactFloor && m_bootstrapSeq != 0 && op.seq < m_bootstrapSeq)
                continue;
            kept.append(op);
        }
        m_ops = kept;
    }

    // ops with seq > sinceSeq, ascending, limit-capped; error when the window
    // fell under the compaction floor.
    QVector<LoggedOp> pull(quint64 sinceSeq, int limit, bool *hasMoreOut, quint64 *headOut,
                           SyncError *err)
    {
        if (limit <= 0)
            limit = kPullLimitDefault;
        if (limit > kPullLimitMax)
            limit = kPullLimitMax;
        if (sinceSeq < m_compactFloor) {
            QJsonObject boot;
            boot.insert(QStringLiteral("covered_seq"), double(m_compactFloor));
            boot.insert(QStringLiteral("seq"), double(m_bootstrapSeq));
            QJsonObject extra;
            extra.insert(QStringLiteral("compact_floor"), double(m_compactFloor));
            extra.insert(QStringLiteral("bootstrap"), boot);
            *err = SyncError{ QStringLiteral("oplog_compacted"),
                              QStringLiteral("sync from seq %1 not available; bootstrap first")
                                  .arg(sinceSeq),
                              extra };
            return {};
        }
        QVector<LoggedOp> out;
        for (const LoggedOp &op : m_ops) {
            if (op.seq > sinceSeq && op.seq <= m_headSeq) {
                out.append(op);
                if (out.size() >= limit)
                    break;
            }
        }
        std::sort(out.begin(), out.end(),
                  [](const LoggedOp &a, const LoggedOp &b) { return a.seq < b.seq; });
        *hasMoreOut = false;
        if (out.size() == limit && out.constLast().seq < m_headSeq)
            *hasMoreOut = true;
        *headOut = m_headSeq;
        return out;
    }

    QJsonObject status(const QString &vault, const QString &deviceId) const
    {
        QJsonObject out;
        out.insert(QStringLiteral("protocol_version"), QLatin1String(kProtocolVersion));
        out.insert(QStringLiteral("vault"), vault);
        out.insert(QStringLiteral("head_seq"), double(m_headSeq));
        out.insert(QStringLiteral("compact_floor"), double(m_compactFloor));
        out.insert(QStringLiteral("bootstrap_seq"),
                   m_bootstrapSeq != 0 ? QJsonValue(double(m_bootstrapSeq)) : QJsonValue(QJsonValue::Null));
        out.insert(QStringLiteral("device_id"), deviceId);
        return out;
    }

private:
    quint64 m_headSeq = 0;
    QVector<LoggedOp> m_ops;
    QHash<QString, quint64> m_byOpId;
    quint64 m_compactFloor = 0;
    quint64 m_bootstrapSeq = 0;
};

} // namespace syncproto

static void testSyncProtoConformance()
{
    for (const auto &fx : loadFixtures(QStringLiteral("sync"))) {
        const QString name = fx.first;
        syncproto::OpLog log;
        const QJsonArray steps = fx.second.value(QLatin1String("steps")).toArray();
        for (int i = 0; i < steps.size(); ++i) {
            const QJsonObject step = steps.at(i).toObject();
            const QString action = step.value(QLatin1String("action")).toString();
            const QString label = name + QStringLiteral(" step %1 (%2)").arg(i).arg(action);
            if (action == QLatin1String("push")) {
                QVector<syncproto::Op> ops;
                for (const QJsonValue &v : step.value(QLatin1String("ops")).toArray()) {
                    const QJsonObject o = v.toObject();
                    syncproto::Op op;
                    op.opId = o.value(QLatin1String("op_id")).toString();
                    op.deviceId = o.value(QLatin1String("device_id")).toString();
                    op.kind = o.value(QLatin1String("kind")).toString();
                    op.wireId = o.value(QLatin1String("wire_id")).toString();
                    op.keyEpoch = uint32_t(o.value(QLatin1String("key_epoch")).toInt());
                    op.blob = o.value(QLatin1String("blob")).toString();
                    ops.append(op);
                }
                syncproto::SyncError err;
                quint64 head = 0;
                const QVector<syncproto::OpLog::PushResult> results = log.push(ops, &head, &err);
                if (step.contains(QLatin1String("expect_error"))) {
                    if (err.code.isEmpty())
                        CHECK(false, qPrintable(label + QStringLiteral(": expected error, got ok")));
                    const QJsonObject want = step.value(QLatin1String("expect_error")).toObject();
                    const QJsonObject gotBody = err.body().value(QLatin1String("error")).toObject();
                    if (want.contains(QLatin1String("http_status")))
                        CHECK(err.httpStatus() == want.value(QLatin1String("http_status")).toInt(),
                              qPrintable(label + QStringLiteral(" http_status")));
                    for (auto it = want.begin(); it != want.end(); ++it) {
                        if (it.key() == QLatin1String("http_status")
                            || it.key() == QLatin1String("note"))
                            continue;
                        if (!jsonEq(gotBody.value(it.key()), it.value()))
                            CHECK(false, qPrintable(label + QStringLiteral(" error.") + it.key()));
                    }
                    continue;
                }
                if (!err.code.isEmpty()) {
                    CHECK(false, qPrintable(label + QStringLiteral(": unexpected error %1").arg(err.message)));
                    continue;
                }
                const QJsonObject expect = step.value(QLatin1String("expect")).toObject();
                const QJsonArray wantResults = expect.value(QLatin1String("results")).toArray();
                if (results.size() != wantResults.size())
                    CHECK(false, qPrintable(label + QStringLiteral(" result count")));
                for (int r = 0; r < results.size() && r < wantResults.size(); ++r) {
                    const QJsonObject w = wantResults.at(r).toObject();
                    if (results[r].opId != w.value(QLatin1String("op_id")).toString()
                        || results[r].seq != quint64(w.value(QLatin1String("seq")).toDouble()))
                        CHECK(false, qPrintable(label + QStringLiteral(" result %1").arg(r)));
                }
                CHECK(head == quint64(expect.value(QLatin1String("head_seq")).toDouble()),
                      qPrintable(label + QStringLiteral(" head_seq")));
            } else if (action == QLatin1String("pull")) {
                const quint64 since = quint64(step.value(QLatin1String("since_seq")).toDouble());
                syncproto::SyncError err;
                bool hasMore = false;
                quint64 head = 0;
                const QVector<syncproto::LoggedOp> ops =
                    log.pull(since, syncproto::kPullLimitDefault, &hasMore, &head, &err);
                if (step.contains(QLatin1String("expect_error"))) {
                    if (err.code.isEmpty())
                        CHECK(false, qPrintable(label + QStringLiteral(": expected error, got ok")));
                    const QJsonObject want = step.value(QLatin1String("expect_error")).toObject();
                    const QJsonObject gotBody = err.body().value(QLatin1String("error")).toObject();
                    for (auto it = want.begin(); it != want.end(); ++it) {
                        if (it.key() == QLatin1String("http_status")
                            || it.key() == QLatin1String("note"))
                            continue;
                        if (!jsonEq(gotBody.value(it.key()), it.value()))
                            CHECK(false, qPrintable(label + QStringLiteral(" error.") + it.key()));
                    }
                    continue;
                }
                if (!err.code.isEmpty()) {
                    CHECK(false, qPrintable(label + QStringLiteral(": unexpected error %1").arg(err.message)));
                    continue;
                }
                const QJsonObject expect = step.value(QLatin1String("expect")).toObject();
                QJsonArray seqs, opIds;
                for (const syncproto::LoggedOp &op : ops) {
                    seqs.append(double(op.seq));
                    opIds.append(op.op.opId);
                }
                if (!jsonEq(seqs, expect.value(QLatin1String("seqs")).toArray(QJsonArray())))
                    CHECK(false, qPrintable(label + QStringLiteral(" seqs")));
                if (!jsonEq(opIds, expect.value(QLatin1String("op_ids")).toArray(QJsonArray())))
                    CHECK(false, qPrintable(label + QStringLiteral(" op_ids")));
                CHECK(head == quint64(expect.value(QLatin1String("head_seq")).toDouble()),
                      qPrintable(label + QStringLiteral(" head_seq")));
                CHECK(hasMore == expect.value(QLatin1String("has_more")).toBool(),
                      qPrintable(label + QStringLiteral(" has_more")));
            } else if (action == QLatin1String("status")) {
                const QJsonObject expect = step.value(QLatin1String("expect")).toObject();
                const QJsonObject got =
                    log.status(QStringLiteral("vault_test"), QStringLiteral("dev_tablet"));
                for (auto it = expect.begin(); it != expect.end(); ++it) {
                    if (!jsonEq(got.value(it.key()), it.value()))
                        CHECK(false, qPrintable(label + QStringLiteral(" status.") + it.key()));
                }
            } else if (action == QLatin1String("promote_bootstrap")) {
                const syncproto::SyncError err =
                    log.promoteBootstrap(step.value(QLatin1String("op_id")).toString(),
                                         quint64(step.value(QLatin1String("covered_seq")).toDouble()));
                CHECK(err.code.isEmpty(),
                      qPrintable(label + QStringLiteral(": promote: %1").arg(err.message)));
            } else if (action == QLatin1String("compact")) {
                log.compact();
            } else {
                CHECK(false, qPrintable(label + QStringLiteral(": unknown action")));
            }
        }
    }
}


// spec/fixtures/backup: the bytes Go wrote must open here and be reproduced exactly.
static void testBackupConformance()
{
    QFile meta(specDir() + QLatin1String("/backup/small.json"));
    QFile bin(specDir() + QLatin1String("/backup/small.yaop"));
    if (!meta.open(QIODevice::ReadOnly) || !bin.open(QIODevice::ReadOnly)) {
        std::printf("FAIL backup fixtures missing\n");
        ++failures;
        return;
    }
    const QJsonObject m = QJsonDocument::fromJson(meta.readAll()).object();
    const QByteArray file = bin.readAll();
    const QByteArray vk = hexBytes(m.value(QLatin1String("vk_hex")).toString());
    const quint32 epoch = quint32(m.value(QLatin1String("key_epoch")).toInt());
    const QJsonArray exp = m.value(QLatin1String("records")).toArray();

    QVector<backup::Record> got;
    QBuffer in;
    in.setData(file);
    in.open(QIODevice::ReadOnly);
    QString err;
    const auto ep = backup::read(&in, vk, [&](const backup::Record &r) { got.append(r); }, &err);
    CHECK(ep && *ep == epoch, "backup: fixture opens with its epoch");
    bool same = got.size() == exp.size();
    for (int i = 0; same && i < exp.size(); ++i) {
        const QJsonObject e = exp[i].toObject();
        same = got[i].kind == e.value(QLatin1String("kind")).toInt()
            && got[i].id == e.value(QLatin1String("id")).toString()
            && got[i].n == quint32(e.value(QLatin1String("n")).toInt())
            && got[i].total == quint32(e.value(QLatin1String("total")).toInt())
            && got[i].data == hexBytes(e.value(QLatin1String("data_hex")).toString());
    }
    CHECK(same, "backup: fixture records match the contract");

    QBuffer out;
    out.open(QIODevice::WriteOnly);
    backup::Writer w(&out, vk, epoch, false, [](int i) { return QByteArray(24, char(0x20 + i)); });
    for (const auto &r : got)
        w.write(r);
    CHECK(w.finish() && out.data() == file, "backup: writer reproduces the fixture byte for byte");

    auto rejects = [&](const QByteArray &bytes, const QByteArray &key) {
        QBuffer b;
        b.setData(bytes);
        b.open(QIODevice::ReadOnly);
        return !backup::read(&b, key, [](const backup::Record &) {}, nullptr).has_value();
    };
    QByteArray flipped = file;
    flipped[flipped.size() / 2] = char(flipped[flipped.size() / 2] ^ 0xff);
    const quint32 first = qFromBigEndian<quint32>(reinterpret_cast<const uchar *>(file.constData() + 4));
    // A backup exported by Facultas (same records, magic FBK1, salt facultas-vault/1) opens too.
    QFile legacyBin(specDir() + QLatin1String("/backup/legacy-small.facultas"));
    if (legacyBin.open(QIODevice::ReadOnly)) {
        QVector<backup::Record> legacyGot;
        QBuffer lin;
        lin.setData(legacyBin.readAll());
        lin.open(QIODevice::ReadOnly);
        const auto lep = backup::read(&lin, vk, [&](const backup::Record &r) { legacyGot.append(r); }, &err);
        bool legacySame = lep && *lep == epoch && legacyGot.size() == got.size();
        for (int i = 0; legacySame && i < got.size(); ++i)
            legacySame = legacyGot[i].id == got[i].id && legacyGot[i].data == got[i].data;
        CHECK(legacySame, "backup: a Facultas backup opens with the same records");
        QByteArray relabeled = file; // YAOP bytes under the Facultas magic: wrong salt, refused
        relabeled.replace(0, 4, QByteArray(backup::kLegacyMagic, 4));
        CHECK(rejects(relabeled, vk), "backup: the magic picks the salt (no mixing)");
    } else {
        CHECK(false, "backup: legacy fixture missing");
    }
    CHECK(rejects(file, QByteArray(32, 7)), "backup: wrong VK is refused");
    CHECK(rejects(file.left(file.size() - 5), vk), "backup: truncated file is refused");
    CHECK(rejects(flipped, vk), "backup: tampered file is refused");
    CHECK(rejects(QByteArrayLiteral("XXXX") + file.mid(4), vk), "backup: bad magic is refused");
    CHECK(rejects(file.left(4) + file.mid(8 + first), vk), "backup: dropped record is refused");
    CHECK(rejects(file + QByteArray(1, 0), vk), "backup: trailing bytes are refused");

    QBuffer zout;
    zout.open(QIODevice::WriteOnly);
    backup::Writer zw(&zout, vk, epoch, true);
    for (const auto &r : got)
        zw.write(r);
    zw.finish();
    QBuffer zin;
    zin.setData(zout.data());
    zin.open(QIODevice::ReadOnly);
    int n = 0;
    CHECK(backup::read(&zin, vk, [&](const backup::Record &) { ++n; }).has_value() && n == got.size(),
          "backup: compressed roundtrip");
}

static void testNodeOps()
{
    using namespace pkm;
    QTemporaryDir tmp;
    FileDerivedSink sink(tmp.path());
    RemoteOpRegistry reg;
    reg.add(makeDerivedOpHandler(&sink));
    QVector<NodeRequest> queued;
    struct Q : NodeRequestSink { QVector<NodeRequest> *v; void enqueue(const NodeRequest &r) override { v->append(r); } } q;
    q.v = &queued;
    reg.add(makeNodeRequestOpHandler(&q));
    QByteArray body;
    RemoteOpPipeline p(reg, [&](const RemoteOp &) -> std::optional<QByteArray> { return body; });
    RemoteOp op; op.kind = QStringLiteral("derived"); op.wireId = QStringLiteral("0a1b");
    body = QByteArrayLiteral("{\"v\":1,\"kind\":\"pdf.text\",\"source_hash\":\"h1\",\"producer\":\"node/1\",\"data\":{\"text\":\"hola\"}}");
    CHECK(p.apply(op) == OpResult::Applied && sink.get(QStringLiteral("0a1b")) && sink.get(QStringLiteral("0a1b"))->sourceHash == QStringLiteral("h1"),
          "node: a valid derived artifact is stored under its blind id");
    body = QByteArrayLiteral("{\"v\":1,\"kind\":\"pdf.text\",\"source_hash\":\"h1\",\"producer\":\"node/2\",\"data\":{}}");
    p.apply(op);
    CHECK(sink.get(QStringLiteral("0a1b"))->producer == QStringLiteral("node/2"), "node: a newer producer overwrites");
    body = QByteArrayLiteral("{\"v\":2,\"kind\":\"x\",\"source_hash\":\"h\",\"data\":{}}");
    CHECK(p.apply(op) == OpResult::Skipped, "node: unknown manifest version is skipped");
    op.wireId = QStringLiteral("../evil");
    body = QByteArrayLiteral("{\"v\":1,\"kind\":\"x\",\"source_hash\":\"h\",\"data\":{}}");
    CHECK(p.apply(op) == OpResult::Skipped, "node: an unsafe wire id never becomes a file name");
    op.kind = QStringLiteral("node_request");
    body = QByteArrayLiteral("{\"v\":1,\"object_id\":\"obj1\",\"kind\":\"pdf.text\"}");
    CHECK(p.apply(op) == OpResult::Applied && queued.size() == 1 && queued[0].kind == QStringLiteral("pdf.text"),
          "node: a request reaches the sink");
    RemoteOpRegistry noNode;
    noNode.add(makeNodeRequestOpHandler(nullptr));
    RemoteOpPipeline p2(noNode, [&](const RemoteOp &) -> std::optional<QByteArray> { return body; });
    CHECK(p2.apply(op) == OpResult::Skipped, "node: without a sink (not the trust node) requests are skipped");
}

static void testBootstrapSnapshot()
{
    using namespace pkm;
    const QJsonObject built = bootstrap::build(2, 4900, QJsonArray{ QJsonObject{ { "id", "a" } }, QJsonObject{ { "id", "b" } } },
                                               QJsonArray{ QJsonObject{ { "id", "note" } } });
    const auto p = bootstrap::parse(QJsonDocument::fromJson(QJsonDocument(built).toJson()).object());
    CHECK(p && p->keyEpoch == 2 && p->coveredSeq == 4900 && p->objects.size() == 2 && p->types.size() == 1,
          "bootstrap: container roundtrip");
    CHECK(!bootstrap::parse(QJsonObject{ { "id", "a" }, { "title", "t" } }), "bootstrap: one object is not a container");
    CHECK(!bootstrap::parse(QJsonObject{ { "v", 9 }, { "objects", QJsonArray() } }), "bootstrap: unknown version refused");
    using namespace pkm::syncpolicy;
    CHECK(shouldProduceBootstrap(2000, 2000, 0, 0) && !shouldProduceBootstrap(2000, 2000, 0, 3)
              && !shouldProduceBootstrap(1990, 2000, 0, 0) && !shouldProduceBootstrap(2000, 2000, 1500, 0)
              && !shouldProduceBootstrap(0, 0, 0, 0),
          "bootstrap: produced only when converged and the log grew");
    CHECK(shouldStartFromBootstrap(0, true, 0) && shouldStartFromBootstrap(50, true, 400)
              && !shouldStartFromBootstrap(500, true, 400) && !shouldStartFromBootstrap(0, false, 0),
          "bootstrap: started from when new or behind the floor");
}

static void testRemoteOpPipeline()
{
    using namespace pkm;
    QStringList seen;
    RemoteOpRegistry reg;
    CHECK(reg.add(std::make_unique<FunctionOpHandler>(QStringList{ QStringLiteral("obj") },
                  [&](const DecodedOp &d) { seen << d.payload.value(QLatin1String("id")).toString(); return OpResult::Applied; })),
          "ops: first handler for a kind registers");
    CHECK(!reg.add(std::make_unique<FunctionOpHandler>(QStringList{ QStringLiteral("obj") },
                   [](const DecodedOp &) { return OpResult::Applied; })),
          "ops: a second handler for the same kind is refused");
    bool opened = false;
    reg.add(std::make_unique<FunctionOpHandler>(QStringList{ QStringLiteral("derived") },
            [](const DecodedOp &) { return OpResult::Skipped; }, false));
    reg.add(std::make_unique<FunctionOpHandler>(QStringList{ QStringLiteral("pause") },
            [](const DecodedOp &) { return OpResult::Pause; }));
    QByteArray body = QByteArrayLiteral("{\"id\":\"x\"}");
    RemoteOpPipeline p(reg, [&](const RemoteOp &) -> std::optional<QByteArray> { opened = true; return body; });
    RemoteOp op; op.kind = QStringLiteral("obj");
    CHECK(p.apply(op) == OpResult::Applied && seen == QStringList{ QStringLiteral("x") }, "ops: decoded payload reaches its handler");
    op.kind = QStringLiteral("from_the_future");
    CHECK(p.apply(op) == OpResult::Skipped, "ops: unknown kind is skipped, never fatal");
    opened = false; op.kind = QStringLiteral("derived");
    CHECK(p.apply(op) == OpResult::Skipped && !opened, "ops: a no-payload kind is never decrypted");
    op.kind = QStringLiteral("pause");
    CHECK(p.apply(op) == OpResult::Pause, "ops: pause is propagated");
    body = QByteArrayLiteral("not json"); op.kind = QStringLiteral("obj");
    CHECK(p.apply(op) == OpResult::Skipped, "ops: non-object payload is skipped");
    RemoteOpPipeline bad(reg, [](const RemoteOp &) -> std::optional<QByteArray> { return std::nullopt; });
    CHECK(bad.apply(op) == OpResult::Skipped, "ops: undecryptable op is skipped");
    CHECK(vault::domainForKind(QStringLiteral("asset_meta")) == "objects" && vault::domainForKind(QStringLiteral("nope")).isEmpty(),
          "ops: kind to domain table");
}

static void testSyncPolicy()
{
    using pkm::syncpolicy::needsFullResend;
    CHECK(needsFullResend(QString(), QStringLiteral("A"), 0, 0), "resend: new empty server after working locally");
    CHECK(needsFullResend(QStringLiteral("A"), QStringLiteral("B"), 5, 900), "resend: another server even if its log looks ahead");
    CHECK(needsFullResend(QStringLiteral("A"), QStringLiteral("A"), 50, 3), "resend: server log behind the cursor");
    CHECK(!needsFullResend(QStringLiteral("A"), QStringLiteral("A"), 10, 40), "resend: same server, normal case, no resend");

    using pkm::syncpolicy::canPurgeTrashed;
    using pkm::syncpolicy::trashExpired;
    const QDateTime now = QDateTime::fromString(QStringLiteral("2026-12-31T12:00:00.000Z"), Qt::ISODateWithMs);
    const QString old = QStringLiteral("2026-12-01T12:00:00.000Z"); // exactly 30 days
    const QString recent = QStringLiteral("2026-12-01T12:00:01Z");
    CHECK(trashExpired(old, now), "trash: 30 days in the trash expires");
    CHECK(!trashExpired(recent, now), "trash: one second short of 30 days does not expire");
    CHECK(!trashExpired(QString(), now) && !trashExpired(QStringLiteral("garbage"), now),
          "trash: empty or unparseable dates never expire");
    CHECK(canPurgeTrashed(old, QStringLiteral("synced"), false, now), "trash: expired and synced is purged");
    CHECK(!canPurgeTrashed(old, QStringLiteral("pending"), false, now), "trash: a pending tombstone is kept until pushed");
    CHECK(!canPurgeTrashed(old, QStringLiteral("synced"), true, now), "trash: a queued op keeps the object");
}

int runVaultTests()
{
    std::printf("== vault (crypto, syncproto, backup) ==\n");
    const int before = failures;
    testCryptoConformance();
    testSyncProtoConformance();
    testBackupConformance();
    testNodeOps();
    testBootstrapSnapshot();
    testRemoteOpPipeline();
    testSyncPolicy();
    if (failures == before)
        std::printf("ok vault (crypto, syncproto, backup)\n");
    return failures - before;
}

} // namespace pkm::testing
