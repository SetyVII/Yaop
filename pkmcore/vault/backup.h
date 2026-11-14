// .yaop backup container (spec/crypto.md §Backup). Streaming reader/writer; port of
// server/internal/simclient/backup.go, validated against spec/fixtures/backup.
#pragma once
#include <QByteArray>
#include <QIODevice>
#include <QString>
#include <functional>
#include <optional>

namespace pkm::backup {

inline constexpr char kMagic[] = "YBK1";
// Read only: a backup exported by Facultas, the app YAOP grew from. Same container and crypto,
// other magic and HKDF salt; restoring it with the same 24 words moves a vault over
// (spec/crypto.md §Importing a Facultas backup).
inline constexpr char kLegacyMagic[] = "FBK1";
inline constexpr char kLegacySalt[] = "facultas-vault/1";
inline constexpr int kAssetChunk = 4 << 20;
enum Kind : uint8_t { KObj = 1, KType = 2, KAsset = 3, KEnd = 0xFF };

struct Record {
    uint8_t kind = 0;
    QString id;
    quint32 n = 0;
    quint32 total = 0;
    QByteArray data;
};

class Writer {
public:
    // nonce(i) may be null (random). JSON kinds are zstd-compressed iff compress.
    Writer(QIODevice *out, const QByteArray &vk, quint32 keyEpoch, bool compress = true,
           std::function<QByteArray(int)> nonce = nullptr);
    bool write(const Record &r);
    bool finish(); // END record: a file without it is rejected on read
private:
    bool put(const Record &r);
    QIODevice *m_out;
    QByteArray m_key;
    quint32 m_epoch;
    bool m_compress;
    std::function<QByteArray(int)> m_nonce;
    int m_index = 0;
    bool m_done = false;
    bool m_ok = true;
};

// Reads and authenticates every record in order (a .yaop file, or a legacy .facultas one picked by
// its magic); onRecord gets each non-END one. On failure
// returns nullopt and sets *error (wrong phrase, tampered, truncated, reordered).
std::optional<quint32> read(QIODevice *in, const QByteArray &vk,
                            const std::function<void(const Record &)> &onRecord,
                            QString *error = nullptr);

} // namespace pkm::backup
