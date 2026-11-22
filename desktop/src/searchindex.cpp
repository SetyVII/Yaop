#include "searchindex.h"

#include <sqlite3.h>

#include <QDebug>
#include <QHash>
#include <QSet>

#include <algorithm>

namespace pkm::search {

namespace {

QString columnText(sqlite3_stmt *st, int col)
{
    const char *raw = reinterpret_cast<const char *>(sqlite3_column_text(st, col));
    if (!raw)
        return {};
    return QString::fromUtf8(raw, int(sqlite3_column_bytes(st, col)));
}

} // namespace

bool fts5Available(sqlite3 *db)
{
    sqlite3_stmt *stmt = nullptr;
    if (sqlite3_prepare_v2(db, "CREATE VIRTUAL TABLE fts5_probe USING fts5(x)", -1, &stmt,
                           nullptr)
        != SQLITE_OK)
        return false;
    const int rc = sqlite3_step(stmt);
    sqlite3_finalize(stmt);
    sqlite3_exec(db, "DROP TABLE IF EXISTS fts5_probe", nullptr, nullptr, nullptr);
    return rc == SQLITE_DONE;
}

QStringList tokens(const QString &text)
{
    // unicode61 + remove_diacritics(2): NFKD fold, drop combining marks,
    // lowercase, split on non letter/number codepoints ('_' included).
    QStringList out;
    QString cur;
    const QString norm = text.normalized(QString::NormalizationForm_KD);
    for (const QChar &ch : norm) {
        const QChar::Category c = ch.category();
        if (c == QChar::Mark_NonSpacing || c == QChar::Mark_Enclosing)
            continue; // diacritics out (remove_diacritics 2)
        if (ch.isLetterOrNumber() || ch == QLatin1Char('_')) {
            cur += ch.toLower();
        } else if (!cur.isEmpty()) {
            out.append(cur);
            cur.clear();
        }
    }
    if (!cur.isEmpty())
        out.append(cur);
    return out;
}

bool ensureSchema(sqlite3 *db, bool *usingFts5)
{
    char *err = nullptr;
    const bool fts5 = fts5Available(db);
    if (fts5) {
        sqlite3_exec(db,
                     "CREATE VIRTUAL TABLE IF NOT EXISTS objects_fts USING fts5("
                     " object_id UNINDEXED, title, body, props,"
                     " tokenize = 'unicode61 remove_diacritics 2')",
                     nullptr, nullptr, &err);
    } else {
        // Fallback inverted index (the bundled SQLCipher has no fts5 module):
        // same tokenizer semantics as the FTS5 store, same transactional
        // updates, encrypted at rest like everything else in this database.
        sqlite3_exec(db,
                     "CREATE TABLE IF NOT EXISTS search_tokens ("
                     " token TEXT NOT NULL, object_id TEXT NOT NULL, field INTEGER NOT NULL,"
                     " PRIMARY KEY (token, object_id, field)) WITHOUT ROWID",
                     nullptr, nullptr, &err);
    }
    if (err) {
        qWarning("search: create index schema failed: %s", err);
        sqlite3_free(err);
        err = nullptr;
    }
    sqlite3_exec(db,
                 "CREATE TABLE IF NOT EXISTS backlinks ("
                 " src TEXT NOT NULL, dst TEXT NOT NULL, block_id TEXT NOT NULL,"
                 " PRIMARY KEY (dst, src, block_id)) WITHOUT ROWID",
                 nullptr, nullptr, &err);
    // Every save deletes this object's old rows first: without these indexes each delete scanned
    // the whole table, so saving got slower as the vault grew (O(n) per save).
    sqlite3_exec(db,
                 "CREATE INDEX IF NOT EXISTS backlinks_src ON backlinks(src);"
                 "CREATE TABLE IF NOT EXISTS objects_fts_rowid (object_id TEXT PRIMARY KEY, rid INTEGER NOT NULL)",
                 nullptr, nullptr, nullptr);
    if (!fts5)
        sqlite3_exec(db, "CREATE INDEX IF NOT EXISTS search_tokens_object ON search_tokens(object_id)",
                     nullptr, nullptr, nullptr);
    if (err) {
        qWarning("search: create backlinks schema failed: %s", err);
        sqlite3_free(err);
    }
    if (usingFts5)
        *usingFts5 = fts5;
    return true;
}

// FTS5 cannot index its UNINDEXED object_id, so deleting by it scans the whole table. We keep
// object_id -> rowid next to it and delete by rowid; rows written before that map existed are
// found by the old scan, once.
static void deleteFtsRow(sqlite3 *db, const QByteArray &id)
{
    sqlite3_stmt *st = nullptr;
    sqlite3_int64 rid = -1;
    if (sqlite3_prepare_v2(db, "SELECT rid FROM objects_fts_rowid WHERE object_id = ?", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(st, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        if (sqlite3_step(st) == SQLITE_ROW)
            rid = sqlite3_column_int64(st, 0);
    }
    sqlite3_finalize(st);
    st = nullptr;
    const char *sql = rid >= 0 ? "DELETE FROM objects_fts WHERE rowid = ?" : "DELETE FROM objects_fts WHERE object_id = ?";
    if (sqlite3_prepare_v2(db, sql, -1, &st, nullptr) == SQLITE_OK) {
        if (rid >= 0)
            sqlite3_bind_int64(st, 1, rid);
        else
            sqlite3_bind_text(st, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_step(st);
    }
    sqlite3_finalize(st);
    st = nullptr;
    if (sqlite3_prepare_v2(db, "DELETE FROM objects_fts_rowid WHERE object_id = ?", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(st, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_step(st);
    }
    sqlite3_finalize(st);
}

static void rememberFtsRow(sqlite3 *db, const QByteArray &id, sqlite3_int64 rid)
{
    sqlite3_stmt *st = nullptr;
    if (sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO objects_fts_rowid (object_id, rid) VALUES (?, ?)", -1, &st, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(st, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_bind_int64(st, 2, rid);
        sqlite3_step(st);
    }
    sqlite3_finalize(st);
}

bool indexObject(sqlite3 *db, const Doc &doc, const QVector<LinkRef> &links, bool usingFts5)
{
    struct Scoped {
        sqlite3_stmt *s = nullptr;
        ~Scoped()
        {
            if (s)
                sqlite3_finalize(s);
        }
    } del, ins, delBl, insBl;

    const QByteArray id = doc.objectId.toUtf8();
    const QByteArray title = doc.title.toUtf8();
    const QByteArray body = doc.body.toUtf8();
    const QByteArray props = doc.props.toUtf8();

    if (usingFts5) {
        deleteFtsRow(db, id);
        if (sqlite3_prepare_v2(db,
                                "INSERT INTO objects_fts (object_id, title, body, props) "
                                "VALUES (?, ?, ?, ?)",
                                -1, &ins.s, nullptr)
            != SQLITE_OK)
            return false;
        sqlite3_bind_text(ins.s, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_bind_text(ins.s, 2, title.constData(), int(title.size()), SQLITE_TRANSIENT);
        sqlite3_bind_text(ins.s, 3, body.constData(), int(body.size()), SQLITE_TRANSIENT);
        sqlite3_bind_text(ins.s, 4, props.constData(), int(props.size()), SQLITE_TRANSIENT);
        sqlite3_step(ins.s);
        rememberFtsRow(db, id, sqlite3_last_insert_rowid(db));
    } else {
        if (sqlite3_prepare_v2(db, "DELETE FROM search_tokens WHERE object_id = ?", -1, &del.s,
                               nullptr)
                != SQLITE_OK)
            return false;
        sqlite3_bind_text(del.s, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_step(del.s);
        if (sqlite3_prepare_v2(db,
                                "INSERT OR IGNORE INTO search_tokens (token, object_id, field) "
                                "VALUES (?, ?, ?)",
                                -1, &ins.s, nullptr)
            != SQLITE_OK)
            return false;
        // field: 0 title (best), 1 body, 2 props
        const QPair<QString, int> fields[] = { { doc.title, 0 }, { doc.body, 1 },
                                               { doc.props, 2 } };
        for (const auto &f : fields) {
            for (const QString &tok : tokens(f.first)) {
                const QByteArray tk = tok.toUtf8();
                sqlite3_reset(ins.s);
                sqlite3_clear_bindings(ins.s);
                sqlite3_bind_text(ins.s, 1, tk.constData(), int(tk.size()), SQLITE_TRANSIENT);
                sqlite3_bind_text(ins.s, 2, id.constData(), int(id.size()), SQLITE_TRANSIENT);
                sqlite3_bind_int(ins.s, 3, f.second);
                sqlite3_step(ins.s);
            }
        }
    }

    // Outgoing references of this saved object (spec §4.5).
    if (sqlite3_prepare_v2(db, "DELETE FROM backlinks WHERE src = ?", -1, &delBl.s, nullptr)
            != SQLITE_OK)
        return false;
    sqlite3_bind_text(delBl.s, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
    sqlite3_step(delBl.s);
    if (sqlite3_prepare_v2(db, "INSERT OR IGNORE INTO backlinks (src, dst, block_id) VALUES (?, ?, ?)",
                           -1, &insBl.s, nullptr)
        != SQLITE_OK)
        return false;
    for (const LinkRef &r : links) {
        if (r.dst.isEmpty())
            continue;
        sqlite3_reset(insBl.s);
        sqlite3_clear_bindings(insBl.s);
        const QByteArray dst = r.dst.toUtf8();
        const QByteArray blk = r.blockId.toUtf8();
        sqlite3_bind_text(insBl.s, 1, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_bind_text(insBl.s, 2, dst.constData(), int(dst.size()), SQLITE_TRANSIENT);
        sqlite3_bind_text(insBl.s, 3, blk.constData(), int(blk.size()), SQLITE_TRANSIENT);
        sqlite3_step(insBl.s);
    }
    return true;
}

bool unindexObject(sqlite3 *db, const QString &objectId, bool usingFts5)
{
    const QByteArray id = objectId.toUtf8();
    auto run = [&id](sqlite3 *db2, const char *sql, int binds) {
        sqlite3_stmt *st = nullptr;
        if (sqlite3_prepare_v2(db2, sql, -1, &st, nullptr) != SQLITE_OK)
            return;
        for (int i = 1; i <= binds; ++i)
            sqlite3_bind_text(st, i, id.constData(), int(id.size()), SQLITE_TRANSIENT);
        sqlite3_step(st);
        sqlite3_finalize(st);
    };
    if (usingFts5)
        deleteFtsRow(db, id);
    else
        run(db, "DELETE FROM search_tokens WHERE object_id = ?", 1);
    // only the outgoing references die with the object; rows pointing AT it
    // belong to their own sources (removed when those are re-saved or dropped)
    run(db, "DELETE FROM backlinks WHERE src = ?", 1);
    return true;
}

QList<Hit> query(sqlite3 *db, const QString &query_, int limit, bool usingFts5)
{
    QList<Hit> out;
    const QStringList terms = tokens(query_);
    if (terms.isEmpty() || limit <= 0)
        return out;

    if (usingFts5) {
        // Terms are quoted against FTS5 syntax; the last one matches as a
        // prefix (incremental search while typing).
        QString match;
        for (int i = 0; i < terms.size(); ++i) {
            if (i > 0)
                match += QLatin1Char(' ');
            QString t = terms.at(i);
            t.replace(QLatin1Char('"'), QStringLiteral("\"\""));
            match += QLatin1Char('"') + t + QLatin1Char('"');
            if (i == terms.size() - 1)
                match += QLatin1Char('*');
        }
        sqlite3_stmt *st = nullptr;
        if (sqlite3_prepare_v2(db,
                                "SELECT object_id, bm25(objects_fts, 10.0, 3.0, 1.0) "
                                "FROM objects_fts WHERE objects_fts MATCH ? "
                                "ORDER BY bm25(objects_fts, 10.0, 3.0, 1.0), object_id LIMIT ?",
                                -1, &st, nullptr)
            != SQLITE_OK)
            return out;
        const QByteArray m = match.toUtf8();
        sqlite3_bind_text(st, 1, m.constData(), int(m.size()), SQLITE_TRANSIENT);
        sqlite3_bind_int(st, 2, limit);
        while (sqlite3_step(st) == SQLITE_ROW) {
            Hit h;
            h.objectId = columnText(st, 0);
            h.rank = sqlite3_column_double(st, 1);
            if (!h.objectId.isEmpty())
                out.append(h);
        }
        sqlite3_finalize(st);
        return out;
    }

    // Fallback: AND over terms, prefix on the last; ranking by best field.
    QHash<QString, int> bestField;
    QSet<QString> present;
    for (int i = 0; i < terms.size(); ++i) {
        const QByteArray term = terms.at(i).toUtf8();
        sqlite3_stmt *st = nullptr;
        if (i == terms.size() - 1) {
            // Prefix: UTF-8 tokens are never 0xFF, so [term, term+0xFF) is
            // exactly the set of tokens that start with `term`.
            if (sqlite3_prepare_v2(db,
                                    "SELECT object_id, MIN(field) FROM search_tokens "
                                    "WHERE token >= ? AND token < ? GROUP BY object_id",
                                    -1, &st, nullptr)
                != SQLITE_OK)
                return out;
            QByteArray hi = term;
            hi.append('\xFF');
            sqlite3_bind_text(st, 1, term.constData(), int(term.size()), SQLITE_TRANSIENT);
            sqlite3_bind_text(st, 2, hi.constData(), int(hi.size()), SQLITE_TRANSIENT);
        } else {
            if (sqlite3_prepare_v2(db,
                                    "SELECT object_id, MIN(field) FROM search_tokens "
                                    "WHERE token = ? GROUP BY object_id",
                                    -1, &st, nullptr)
                != SQLITE_OK)
                return out;
            sqlite3_bind_text(st, 1, term.constData(), int(term.size()), SQLITE_TRANSIENT);
        }
        QSet<QString> hits;
        while (sqlite3_step(st) == SQLITE_ROW) {
            const QString id = columnText(st, 0);
            if (id.isEmpty())
                continue;
            hits.insert(id);
            const int field = sqlite3_column_int(st, 1);
            const auto it = bestField.constFind(id);
            if (it == bestField.constEnd() || field < it.value())
                bestField.insert(id, field);
        }
        sqlite3_finalize(st);
        if (i == 0) {
            present = hits;
        } else {
            QSet<QString> keep;
            for (const QString &id : std::as_const(present))
                if (hits.contains(id))
                    keep.insert(id);
            present = keep;
        }
        if (present.isEmpty())
            return out;
    }
    QList<Hit> hits;
    for (const QString &id : std::as_const(present)) {
        Hit h;
        h.objectId = id;
        h.rank = double(bestField.value(id, 99));
        hits.append(h);
    }
    std::sort(hits.begin(), hits.end(), [](const Hit &x, const Hit &y) {
        if (x.rank != y.rank)
            return x.rank < y.rank;
        return x.objectId < y.objectId;
    });
    for (int i = 0; i < hits.size() && i < limit; ++i)
        out.append(hits.at(i));
    return out;
}

QList<SourceRef> backlinks(sqlite3 *db, const QString &dst)
{
    QList<SourceRef> out;
    sqlite3_stmt *st = nullptr;
    if (sqlite3_prepare_v2(db, "SELECT DISTINCT src, block_id FROM backlinks WHERE dst = ? "
                               "ORDER BY src, block_id",
                           -1, &st, nullptr)
        != SQLITE_OK)
        return out;
    const QByteArray d = dst.toUtf8();
    sqlite3_bind_text(st, 1, d.constData(), int(d.size()), SQLITE_TRANSIENT);
    while (sqlite3_step(st) == SQLITE_ROW) {
        SourceRef r;
        r.src = columnText(st, 0);
        r.blockId = columnText(st, 1);
        if (!r.src.isEmpty())
            out.append(r);
    }
    sqlite3_finalize(st);
    return out;
}

} // namespace pkm::search
