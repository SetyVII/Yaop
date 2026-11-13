#include "backup.h"
#include "vaultcrypto.h"
#include <QtEndian>

namespace pkm::backup {
namespace {
const QByteArray kDomain = QByteArrayLiteral("snapshots");
QString wireIdFor(qint64 i) { return QStringLiteral("backup/") + QString::number(i); }

QByteArray encodeRecord(const Record &r)
{
    const QByteArray id = r.id.toUtf8();
    QByteArray out;
    out.reserve(11 + id.size() + r.data.size());
    out.append(char(r.kind));
    char b2[2], b4[4];
    qToBigEndian<quint16>(quint16(id.size()), b2);
    out.append(b2, 2).append(id);
    qToBigEndian<quint32>(r.n, b4);
    out.append(b4, 4);
    qToBigEndian<quint32>(r.total, b4);
    out.append(b4, 4);
    return out.append(r.data);
}

bool decodeRecord(const QByteArray &p, Record *r)
{
    if (p.size() < 11)
        return false;
    const uchar *d = reinterpret_cast<const uchar *>(p.constData());
    const int idLen = qFromBigEndian<quint16>(d + 1);
    if (p.size() < 3 + idLen + 8)
        return false;
    r->kind = d[0];
    r->id = QString::fromUtf8(p.constData() + 3, idLen);
    r->n = qFromBigEndian<quint32>(d + 3 + idLen);
    r->total = qFromBigEndian<quint32>(d + 7 + idLen);
    r->data = p.mid(11 + idLen);
    return true;
}

bool readExact(QIODevice *in, char *dst, qint64 n)
{
    qint64 got = 0;
    while (got < n) {
        const qint64 k = in->read(dst + got, n - got);
        if (k <= 0 && !in->waitForReadyRead(0))
            return false;
        if (k > 0)
            got += k;
    }
    return true;
}
} // namespace

Writer::Writer(QIODevice *out, const QByteArray &vk, quint32 keyEpoch, bool compress,
               std::function<QByteArray(int)> nonce)
    : m_out(out), m_key(vault::subkey(vk, kDomain)), m_epoch(keyEpoch), m_compress(compress),
      m_nonce(std::move(nonce))
{
    m_ok = m_out->write(kMagic, 4) == 4;
}

bool Writer::put(const Record &r)
{
    const bool comp = m_compress && (r.kind == KObj || r.kind == KType);
    QByteArray nonce;
    if (m_nonce)
        nonce = m_nonce(m_index);
    const QByteArray env = vault::sealEnvelope(m_key, m_epoch, kDomain, wireIdFor(m_index),
                                               encodeRecord(r), comp, nonce.isEmpty() ? nullptr : &nonce);
    if (env.isEmpty())
        return m_ok = false;
    char len[4];
    qToBigEndian<quint32>(quint32(env.size()), len);
    m_ok = m_ok && m_out->write(len, 4) == 4 && m_out->write(env) == env.size();
    ++m_index;
    return m_ok;
}

bool Writer::write(const Record &r)
{
    return !m_done && m_ok && put(r);
}

bool Writer::finish()
{
    if (m_done)
        return false;
    Record end;
    end.kind = KEnd;
    end.n = quint32(m_index);
    const bool ok = m_ok && put(end);
    m_done = true;
    return ok;
}

std::optional<quint32> read(QIODevice *in, const QByteArray &vk,
                            const std::function<void(const Record &)> &onRecord, QString *error)
{
    auto fail = [&](const char *msg) -> std::optional<quint32> {
        if (error)
            *error = QString::fromLatin1(msg);
        return std::nullopt;
    };
    char magic[4];
    if (!readExact(in, magic, 4) || memcmp(magic, kMagic, 4) != 0)
        return fail("bad magic");
    const QByteArray key = vault::subkey(vk, kDomain);
    qint64 count = 0;
    for (;;) {
        char lenb[4];
        if (!readExact(in, lenb, 4))
            return fail("truncated (no END record)");
        const quint32 len = qFromBigEndian<quint32>(reinterpret_cast<const uchar *>(lenb));
        if (len < quint32(vault::kEnvHeaderLen) || len > quint32(kAssetChunk + (1 << 20)))
            return fail("bad record length");
        QByteArray env(int(len), Qt::Uninitialized);
        if (!readExact(in, env.data(), len))
            return fail("truncated record");
        uint32_t epoch = 0;
        const auto plain = vault::openEnvelope(key, env, kDomain, wireIdFor(count), &epoch);
        if (!plain)
            return fail("record does not authenticate (wrong phrase, tampered or reordered)");
        Record r;
        if (!decodeRecord(*plain, &r))
            return fail("malformed record");
        if (r.kind == KEnd) {
            if (qint64(r.n) != count)
                return fail("END count mismatch");
            char extra;
            if (in->read(&extra, 1) > 0)
                return fail("trailing bytes after END");
            return quint32(epoch);
        }
        onRecord(r);
        ++count;
    }
}

} // namespace pkm::backup
