#pragma once

// Local persistent store (SQLCipher, C API — spec §15.5): objects,
// types, kv cursor, offline push queue and the local indexes (FTS5 search +
// backlinks, searchindex.h) live in ONE encrypted database; nothing sensitive
// is ever written in clear. Indexes are updated inside the save transaction.
//
// An unreadable database file (plaintext or another key) is never migrated: an older clear or
// differently-sealed database file is moved aside ("<name>.legacy-vN.bak") and
// a fresh encrypted one is created; the server copy rehydrates the desktop.

#include "model.h"
#include "searchindex.h"

#include <QByteArray>
#include <QList>
#include <QString>

struct sqlite3;

namespace pkm {

struct PendingOp {
    qlonglong rowid = 0;
    QString opId;
    QString objectId;
    QJsonObject payload; // full wire object (kind resolved at push time)
};

class Store {
public:
    explicit Store(const QString &dbPath);
    ~Store();

    // 32-byte SQLCipher key (raw-key pragma). Required before open().
    bool setKey(const QByteArray &key32);
    bool open();
    void close();
    bool isOpen() const { return m_db != nullptr; }
    // True when data at rest is encrypted (key set, on-disk database).
    bool encryptedAtRest() const;

    // objects
    bool hasObject(const QString &id) const;
    std::optional<Object> object(const QString &id) const;
    QList<Object> allObjects() const;                 // all, including trashed
    QList<Object> activeObjects() const;              // not trashed / not template
    void putObject(const Object &o);                  // transactional + index update
    void removeObject(const QString &id);               // hard drop of local record + index

    // types
    QList<ObjectType> types() const;
    void putType(const ObjectType &t);

    // kv cursor + config
    QString kv(const QString &key, const QString &def = {}) const;
    void setKv(const QString &key, const QString &value);
    qlonglong kvLong(const QString &key, qlonglong def = 0) const;
    void setKvLong(const QString &key, qlonglong value);

    // offline pending push queue (payloads live encrypted inside this DB)
    QList<PendingOp> pendingOps() const;
    // How many ops wait to be pushed, without reading them (called after every save).
    int pendingOpCount() const;
    void queueOp(const QString &opId, const QString &objectId, const QJsonObject &payload);
    void dequeueOp(qlonglong rowid);
    void dequeueOpsFor(const QString &objectId);

    // assets imported here and not yet on the server (retried and resumed on each sync)
    struct PendingUpload {
        QString assetId;
        QString name;
        QString mime;
        QString objectId;
    };
    QList<PendingUpload> pendingUploads() const;
    void queueUpload(const PendingUpload &u);
    void dequeueUpload(const QString &assetId);

    struct AssetMeta {
        QString id;
        QString objectId;
        QString name;
        QString mime;
        qlonglong size = 0;
        QString createdAt;
        int durationSeconds = 0;
    };
    bool assetMeta(const QString &id, AssetMeta *out) const;
    void putAssetMeta(const AssetMeta &a);

    // version history (product §16.3): compressed snapshots, newest first
    struct SnapshotRow {
        qint64 id = 0;
        QString createdAt;
        QByteArray compressed; // empty in allSnapshotTimes()
    };
    void putSnapshot(const QString &objectId, const QString &createdAt, const QByteArray &compressed);
    QVector<SnapshotRow> snapshots(const QString &objectId) const;
    QVector<SnapshotRow> allSnapshotTimes() const;
    void deleteSnapshots(const QVector<qint64> &ids);

    // local search + backlinks (searchindex.h; inside this encrypted DB)
    bool usingFts5() const { return m_fts5; }
    QStringList searchIds(const QString &query, int limit);
    QList<search::SourceRef> backlinkRows(const QString &dst) const;

private:
    bool ensureOpened() const;
    bool openDatabase(bool fresh);
    void moveLegacyAside(const QString &why);
    void exec(const char *sql);
    // the byte string stored for an object
    static QByteArray objectBytes(const Object &o);
    static search::Doc docFor(const Object &o);
    static QVector<search::LinkRef> linksFor(const Object &o);

    QString m_path;
    QByteArray m_key;      // raw SQLCipher key bytes (hexlified at open)
    sqlite3 *m_db = nullptr;
    bool m_fts5 = false;
    mutable bool m_keyReady = false;
};

} // namespace pkm
