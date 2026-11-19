#include "store.h"

#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>

#include <sqlite3.h>

#include <algorithm>

namespace pkm {

namespace {

// Tiny RAII statement wrapper over the SQLCipher C API.
struct Stmt {
    sqlite3 *db = nullptr;
    sqlite3_stmt *s = nullptr;
    ~Stmt()
    {
        if (s)
            sqlite3_finalize(s);
    }
    bool prepare(sqlite3 *db_, const char *sql)
    {
        db = db_;
        return sqlite3_prepare_v2(db_, sql, -1, &s, nullptr) == SQLITE_OK;
    }
    bool step()
    {
        return sqlite3_step(s) == SQLITE_ROW;
    }
    bool done()
    {
        const int rc = sqlite3_step(s);
        if (rc != SQLITE_DONE && rc != SQLITE_ROW)
            qWarning("store: step failed: %s", sqlite3_errmsg(db));
        return rc == SQLITE_DONE || rc == SQLITE_ROW;
    }
    void bind(int i, const QString &v)
    {
        const QByteArray u = v.toUtf8();
        sqlite3_bind_text(s, i, u.constData(), int(u.size()), SQLITE_TRANSIENT);
    }
    void bind(int i, qlonglong v)
    {
        sqlite3_bind_int64(s, i, sqlite3_int64(v));
    }
    void bindBytes(int i, const QByteArray &v)
    {
        sqlite3_bind_text(s, i, v.constData(), int(v.size()), SQLITE_TRANSIENT);
    }
    QString text(int col) const
    {
        const char *raw = reinterpret_cast<const char *>(sqlite3_column_text(s, col));
        return raw ? QString::fromUtf8(raw, int(sqlite3_column_bytes(s, col))) : QString();
    }
    QString textCol(int col) const // alias kept for readability
    {
        return text(col);
    }
    qlonglong number(int col) const
    {
        return sqlite3_column_int64(s, col);
    }
    QByteArray bytes(int col) const
    {
        const char *raw = reinterpret_cast<const char *>(sqlite3_column_text(s, col));
        return raw ? QByteArray(raw, int(sqlite3_column_bytes(s, col))) : QByteArray();
    }
};

struct Tx {
    sqlite3 *db = nullptr;
    bool started = false;
    bool begin(sqlite3 *db_)
    {
        db = db_;
        started = sqlite3_exec(db, "BEGIN IMMEDIATE", nullptr, nullptr, nullptr) == SQLITE_OK;
        return started;
    }
    bool commit()
    {
        if (!started)
            return false;
        started = false;
        return sqlite3_exec(db, "COMMIT", nullptr, nullptr, nullptr) == SQLITE_OK;
    }
    ~Tx()
    {
        if (started)
            sqlite3_exec(db, "ROLLBACK", nullptr, nullptr, nullptr);
    }
};

} // namespace

// the byte string stored for an object (local JSON: no wire-only shadows)
QByteArray Store::objectBytes(const Object &o)
{
    return QJsonDocument(o.toJson(false)).toJson(QJsonDocument::Compact);
}

Store::Store(const QString &dbPath)
    : m_path(dbPath)
{
}

Store::~Store()
{
    close();
}

bool Store::setKey(const QByteArray &key32)
{
    if (key32.size() != 32)
        return false;
    m_key = key32;
    m_keyReady = true;
    return true;
}

bool Store::encryptedAtRest() const
{
    return m_keyReady && m_db && m_path != QLatin1String(":memory:");
}

void Store::exec(const char *sql)
{
    char *err = nullptr;
    sqlite3_exec(m_db, sql, nullptr, nullptr, &err);
    if (err)
        sqlite3_free(err);
}

// ---------------------------------------------------------------------------
// Open / schema
// ---------------------------------------------------------------------------

bool Store::open()
{
    if (m_db)
        return true;
    if (m_path == QLatin1String(":memory:"))
        return openDatabase(true);
    if (!QFile::exists(m_path))
        return openDatabase(true);
    // On-disk: SQLCipher only. A file that fails the key probe is either
    // plaintext legacy data or a differently keyed database: move it aside
    // (never delete user data) and
    // create a fresh encrypted one; the server copy rehydrates the desktop.
    bool readable = false;
    int probeRc = SQLITE_ERROR;
    if (sqlite3_open_v2(m_path.toUtf8().constData(), &m_db,
                        SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr)
        == SQLITE_OK) {
        if (m_keyReady) {
            const QByteArray hex = m_key.toHex();
            sqlite3_exec(m_db, QStringLiteral("PRAGMA key = \"x'%1'\";")
                                 .arg(QString::fromLatin1(hex))
                                 .toUtf8()
                                 .constData(),
                         nullptr, nullptr, nullptr);
            sqlite3_stmt *probe = nullptr;
            sqlite3_prepare_v2(m_db, "SELECT count(*) FROM sqlite_master", -1, &probe, nullptr);
            probeRc = sqlite3_step(probe);
            sqlite3_finalize(probe);
            readable = probeRc == SQLITE_ROW;
        }
        if (!readable) {
            sqlite3_close(m_db);
            m_db = nullptr;
        }
    } else if (m_db) {
        sqlite3_close(m_db);
        m_db = nullptr;
    }
    if (!readable) {
        moveLegacyAside(probeRc == SQLITE_CORRUPT ? QStringLiteral("corrupt")
                                                  : QStringLiteral("not openable with this key"));
    }
    return openDatabase(true);
}

void Store::moveLegacyAside(const QString &why)
{
    // Rename the old file (and its WAL/SHM siblings) so nothing is destroyed
    // while the fresh database takes its place.
    for (const QString &suffix : { QString(), QStringLiteral("-wal"), QStringLiteral("-shm") }) {
        const QString from = m_path + suffix;
        if (!QFile::exists(from))
            continue;
        int n = 1;
        QString to = m_path + suffix + QStringLiteral(".legacy-v1.bak");
        while (QFile::exists(to))
            to = m_path + suffix + QStringLiteral(".legacy-v%1.bak").arg(++n);
        if (!QFile::rename(from, to))
            qWarning("store: could not move %s aside", qPrintable(from));
    }
    qWarning().noquote() << "store: previous database moved aside (" << why
                         << "); not migrated: the fresh encrypted"
                            " database re-syncs from the server";
}

bool Store::openDatabase(bool fresh)
{
    Q_UNUSED(fresh);
    if (!m_db) {
        const int rc = sqlite3_open_v2(m_path.toUtf8().constData(), &m_db,
                                       SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
                                           | SQLITE_OPEN_FULLMUTEX,
                                       nullptr);
        if (rc != SQLITE_OK) {
            qWarning("store: cannot open %s (%s)", qPrintable(m_path),
                     m_db ? sqlite3_errmsg(m_db) : "unknown");
            if (m_db) {
                sqlite3_close(m_db);
                m_db = nullptr;
            }
            return false;
        }
    }
    sqlite3_busy_timeout(m_db, 8000);
    if (m_keyReady) {
        const QByteArray hex = m_key.toHex();
        const QByteArray pragma =
            QStringLiteral("PRAGMA key = \"x'%1'\";").arg(QString::fromLatin1(hex)).toUtf8();
        char *err = nullptr;
        if (sqlite3_exec(m_db, pragma.constData(), nullptr, nullptr, &err) != SQLITE_OK) {
            qWarning("store: key pragma failed: %s", err ? err : "-");
            if (err)
                sqlite3_free(err);
            sqlite3_close(m_db);
            m_db = nullptr;
            return false;
        }
    } else if (m_path != QLatin1String(":memory:")) {
        // Never write an on-disk database in clear.
        qWarning("store: no key: refusing to create an unencrypted on-disk database");
        sqlite3_close(m_db);
        m_db = nullptr;
        return false;
    }
    exec("PRAGMA journal_mode=WAL");
    exec("PRAGMA synchronous=NORMAL");
    exec("PRAGMA cipher_memory_security=OFF"); // default; keeps the hot path fast
    static const char *schema[] = {
        "CREATE TABLE IF NOT EXISTS objects ("
        " id TEXT PRIMARY KEY, json TEXT NOT NULL, revision INTEGER, hlc TEXT,"
        " updated_at TEXT, trashed INTEGER, sync_status TEXT)",
        "CREATE TABLE IF NOT EXISTS types (id TEXT PRIMARY KEY, json TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)",
        "CREATE TABLE IF NOT EXISTS pending_ops ("
        " rowid INTEGER PRIMARY KEY AUTOINCREMENT, op_id TEXT UNIQUE, object_id TEXT, payload TEXT)",
        "CREATE TABLE IF NOT EXISTS pending_uploads ("
        " asset_id TEXT PRIMARY KEY, name TEXT, mime TEXT, object_id TEXT)",
        "CREATE TABLE IF NOT EXISTS assets ("
        " id TEXT PRIMARY KEY, object_id TEXT, name TEXT, mime TEXT,"
        " size INTEGER, created_at TEXT, duration_seconds INTEGER)",
        nullptr,
    };
    for (const char **sql = schema; *sql; ++sql)
        exec(*sql);
    search::ensureSchema(m_db, &m_fts5);
    if (!m_fts5) {
        qWarning().noquote() << "store: this SQLCipher build has no fts5 module — the built-in"
                                " inverted-index fallback drives local search";
    }
    return true;
}

void Store::close()
{
    if (!m_db)
        return;
    sqlite3_close(m_db);
    m_db = nullptr;
}

bool Store::ensureOpened() const
{
    return m_db != nullptr;
}

// ---------------------------------------------------------------------------
// Objects
// ---------------------------------------------------------------------------

bool Store::hasObject(const QString &id) const
{
    if (!ensureOpened())
        return false;
    Stmt q;
    if (!q.prepare(m_db, "SELECT 1 FROM objects WHERE id = ?"))
        return false;
    q.bind(1, id);
    return q.step();
}

std::optional<Object> Store::object(const QString &id) const
{
    if (!ensureOpened())
        return {};
    Stmt q;
    if (!q.prepare(m_db, "SELECT json FROM objects WHERE id = ?"))
        return {};
    q.bind(1, id);
    if (!q.step())
        return {};
    const QJsonDocument doc = QJsonDocument::fromJson(q.bytes(0));
    if (!doc.isObject())
        return {};
    return Object::fromJson(doc.object());
}

QList<Object> Store::allObjects() const
{
    QList<Object> out;
    if (!ensureOpened())
        return out;
    Stmt q;
    if (!q.prepare(m_db, "SELECT json FROM objects"))
        return out;
    while (q.step()) {
        const QJsonDocument doc = QJsonDocument::fromJson(q.bytes(0));
        if (doc.isObject())
            out.append(Object::fromJson(doc.object()));
    }
    return out;
}

QList<Object> Store::activeObjects() const
{
    QList<Object> out;
    for (const Object &o : allObjects())
        if (o.trashedAt.isEmpty())
            out.append(o);
    return out;
}

search::Doc Store::docFor(const Object &o)
{
    search::Doc d;
    d.objectId = o.id;
    d.title = o.title;
    for (const Block &b : o.blocks)
        d.body += b.plainText() + QLatin1Char('\n');
    d.props = o.summary();
    for (const QString &alias : o.aliases())
        d.props += QLatin1Char(' ') + alias;
    for (const Block &b : o.blocks) {
        if (!b.text.isEmpty() && b.type == BlockType::Code)
            d.props += QLatin1Char(' ') + b.text; // code content indexes too
    }
    d.trashed = !o.trashedAt.isEmpty();
    return d;
}

QVector<search::LinkRef> Store::linksFor(const Object &o)
{
    // Same rule as deriveLinks (spec/object.md) but keeping the source block
    // so the references view can show the snippet (spec §4.5).
    QVector<search::LinkRef> out;
    auto add = [&](const QString &dst, const QString &blockId) {
        if (!dst.isEmpty() && !out.contains({ dst, blockId }))
            out.append({ dst, blockId });
    };
    auto walkRuns = [&](const QVector<Run> &runs, const QString &blockId) {
        for (const Run &r : runs) {
            if (!r.objectId.isEmpty()) {
                add(r.objectId, blockId);
                continue;
            }
            if (!r.tagId.isEmpty()) {
                add(r.tagId, blockId);
                continue;
            }
            if (!r.tagName.isEmpty()) {
                add(tagKey(r.tagName), blockId);
                continue;
            }
            if (r.date.size() >= 10)
                add(QStringLiteral("daily-") + r.date.left(10), blockId);
        }
    };
    for (const Run &r : runsFromJson(o.titleRuns))
        walkRuns({ r }, QString());
    for (const Block &b : o.blocks) {
        walkRuns(b.runs, b.id);
        if (b.type == BlockType::Embed || b.type == BlockType::Bookmark)
            add(b.objectId, b.id);
        else if (!b.assetId.isEmpty()
                 && (b.type == BlockType::Image || b.type == BlockType::Audio
                     || b.type == BlockType::Pdf))
            add(objectIdForMedia(b.assetId), b.id);
    }
    // References born from properties (relations, tags, dates).
    for (const QString &id : o.propertyLinks)
        add(id, QString());
    for (const QString &tag : o.propertyStringList(QStringLiteral("tags")))
        add(tagKey(tag), QString());
    return out;
}

void Store::putObject(const Object &o)
{
    if (!ensureOpened())
        return;
    const QByteArray json = objectBytes(o);
    Tx tx;
    if (!tx.begin(m_db))
        return;
    {
        Stmt q;
        q.prepare(m_db,
                  "INSERT INTO objects (id, json, revision, hlc, updated_at, trashed, sync_status)"
                  " VALUES (?, ?, ?, ?, ?, ?, ?)"
                  " ON CONFLICT(id) DO UPDATE SET json=excluded.json, revision=excluded.revision,"
                  " hlc=excluded.hlc, updated_at=excluded.updated_at, trashed=excluded.trashed,"
                  " sync_status=excluded.sync_status");
        q.bind(1, o.id);
        q.bindBytes(2, json);
        q.bind(3, qlonglong(o.revision));
        q.bind(4, o.hlc);
        q.bind(5, o.updatedAt);
        q.bind(6, qlonglong(o.trashedAt.isEmpty() ? 0 : 1));
        q.bind(7, o.syncStatus);
        q.done();
    }
    if (!o.trashedAt.isEmpty()) {
        search::unindexObject(m_db, o.id, m_fts5);
    } else {
        search::indexObject(m_db, docFor(o), linksFor(o), m_fts5);
    }
    tx.commit();
}

void Store::removeObject(const QString &id)
{
    if (!ensureOpened())
        return;
    Tx tx;
    if (!tx.begin(m_db))
        return;
    {
        Stmt q;
        q.prepare(m_db, "DELETE FROM objects WHERE id = ?");
        q.bind(1, id);
        q.done();
    }
    search::unindexObject(m_db, id, m_fts5);
    tx.commit();
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

QList<ObjectType> Store::types() const
{
    QList<ObjectType> out;
    if (!ensureOpened())
        return out;
    Stmt q;
    if (!q.prepare(m_db, "SELECT json FROM types"))
        return out;
    while (q.step()) {
        const QJsonDocument doc = QJsonDocument::fromJson(q.bytes(0));
        if (doc.isObject())
            out.append(ObjectType::fromJson(doc.object()));
    }
    return out;
}

void Store::putType(const ObjectType &t)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db,
              "INSERT INTO types (id, json) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET"
              " json=excluded.json");
    q.bind(1, t.id);
    q.bindBytes(2, QJsonDocument(t.toJson()).toJson(QJsonDocument::Compact));
    q.done();
}

// ---------------------------------------------------------------------------
// kv
// ---------------------------------------------------------------------------

QString Store::kv(const QString &key, const QString &def) const
{
    if (!ensureOpened())
        return def;
    Stmt q;
    if (!q.prepare(m_db, "SELECT value FROM kv WHERE key = ?"))
        return def;
    q.bind(1, key);
    return q.step() ? q.text(0) : def;
}

void Store::setKv(const QString &key, const QString &value)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db, "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET"
                    " value=excluded.value");
    q.bind(1, key);
    q.bind(2, value);
    q.done();
}

qlonglong Store::kvLong(const QString &key, qlonglong def) const
{
    const QString v = kv(key);
    return v.isEmpty() ? def : v.toLongLong();
}

void Store::setKvLong(const QString &key, qlonglong value)
{
    setKv(key, QString::number(value));
}

// ---------------------------------------------------------------------------
// Pending queue / uploads / assets
// ---------------------------------------------------------------------------

QList<PendingOp> Store::pendingOps() const
{
    QList<PendingOp> out;
    if (!ensureOpened())
        return out;
    Stmt q;
    if (!q.prepare(m_db, "SELECT rowid, op_id, object_id, payload FROM pending_ops ORDER BY rowid"))
        return out;
    while (q.step()) {
        PendingOp op;
        op.rowid = q.number(0);
        op.opId = q.text(1);
        op.objectId = q.text(2);
        const QJsonDocument doc = QJsonDocument::fromJson(q.bytes(3));
        op.payload = doc.isObject() ? doc.object() : QJsonObject();
        out.append(op);
    }
    return out;
}

void Store::queueOp(const QString &opId, const QString &objectId, const QJsonObject &payload)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db, "INSERT OR IGNORE INTO pending_ops (op_id, object_id, payload) VALUES (?, ?, ?)");
    q.bind(1, opId);
    q.bind(2, objectId);
    q.bindBytes(3, QJsonDocument(payload).toJson(QJsonDocument::Compact));
    q.done();
}

void Store::dequeueOp(qlonglong rowid)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db, "DELETE FROM pending_ops WHERE rowid = ?");
    q.bind(1, rowid);
    q.done();
}

void Store::dequeueOpsFor(const QString &objectId)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db, "DELETE FROM pending_ops WHERE object_id = ?");
    q.bind(1, objectId);
    q.done();
}

QList<Store::PendingUpload> Store::pendingUploads() const
{
    QList<PendingUpload> out;
    if (!ensureOpened())
        return out;
    Stmt q;
    if (!q.prepare(m_db, "SELECT asset_id, name, mime, object_id FROM pending_uploads ORDER BY rowid"))
        return out;
    while (q.step()) {
        PendingUpload u;
        u.assetId = q.text(0);
        u.name = q.text(1);
        u.mime = q.text(2);
        u.objectId = q.text(3);
        out.append(u);
    }
    return out;
}

void Store::queueUpload(const PendingUpload &u)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db,
              "INSERT OR REPLACE INTO pending_uploads (asset_id, name, mime, object_id)"
              " VALUES (?, ?, ?, ?)");
    q.bind(1, u.assetId);
    q.bind(2, u.name);
    q.bind(3, u.mime);
    q.bind(4, u.objectId);
    q.done();
}

void Store::dequeueUpload(const QString &assetId)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db, "DELETE FROM pending_uploads WHERE asset_id = ?");
    q.bind(1, assetId);
    q.done();
}

bool Store::assetMeta(const QString &id, AssetMeta *out) const
{
    if (!ensureOpened())
        return false;
    Stmt q;
    if (!q.prepare(m_db,
                   "SELECT id, object_id, name, mime, size, created_at, duration_seconds"
                   " FROM assets WHERE id = ?"))
        return false;
    q.bind(1, id);
    if (!q.step())
        return false;
    if (out) {
        out->id = q.text(0);
        out->objectId = q.text(1);
        out->name = q.text(2);
        out->mime = q.text(3);
        out->size = q.number(4);
        out->createdAt = q.text(5);
        out->durationSeconds = int(q.number(6));
    }
    return true;
}

void Store::putAssetMeta(const AssetMeta &a)
{
    if (!ensureOpened())
        return;
    Stmt q;
    q.prepare(m_db,
              "INSERT INTO assets (id, object_id, name, mime, size, created_at, duration_seconds)"
              " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET"
              " name=excluded.name, mime=excluded.mime, size=excluded.size,"
              " duration_seconds=excluded.duration_seconds, object_id=excluded.object_id");
    q.bind(1, a.id);
    q.bind(2, a.objectId);
    q.bind(3, a.name);
    q.bind(4, a.mime);
    q.bind(5, a.size);
    q.bind(6, a.createdAt);
    q.bind(7, qlonglong(a.durationSeconds));
    q.done();
}

// ---------------------------------------------------------------------------
// Search + backlinks
// ---------------------------------------------------------------------------

QStringList Store::searchIds(const QString &query, int limit)
{
    QStringList out;
    if (!ensureOpened())
        return out;
    for (const search::Hit &h : search::query(m_db, query, limit, m_fts5))
        out.append(h.objectId);
    return out;
}

QList<search::SourceRef> Store::backlinkRows(const QString &dst) const
{
    if (!ensureOpened())
        return {};
    return search::backlinks(m_db, dst);
}

} // namespace pkm
