#pragma once

// Local search + backlinks of the E2EE desktop (spec §4.5, §17.4): indexes
// updated incrementally IN THE SAME TRANSACTION as the save (never rebuilt on
// startup), stored inside the encrypted SQLite database. The wanted store is
// SQLite FTS5 with the unicode61 tokenizer (remove_diacritics 2); when the
// SQLCipher build in use does not carry the FTS5 module (Fedora's does not),
// an inverted-index fallback with the same tokenizer semantics drives the same
// API. Backlinks rows (src, dst, block_id) are maintained here too, and the
// reference view resolves origin title + the snippet of the block that holds
// the reference — all local, never from the server.

#include <QString>
#include <QStringList>
#include <QVector>

struct sqlite3;

namespace pkm::search {

// What gets indexed for an object.
struct Doc {
    QString objectId;
    QString title;   // plain title
    QString body;    // block texts (concatenated)
    QString props;   // textual properties (summary, aliases, …)
    bool trashed = false;
};

// One outgoing reference of an object (spec §4.5): target id + block that holds
// it ("" for title/property references).
struct LinkRef {
    QString dst;
    QString blockId;
    bool operator==(const LinkRef &o) const = default;
};

// True when the SQLite at hand carries the fts5 module.
bool fts5Available(sqlite3 *db);

// Creates the index/backlinks schema (idempotent). Called once on open,
// before any indexer/query. `usingFts5` tells which store is live.
bool ensureSchema(sqlite3 *db, bool *usingFts5 = nullptr);

// Up/replace the doc + its outgoing links; call inside the save transaction.
bool indexObject(sqlite3 *db, const Doc &doc, const QVector<LinkRef> &links, bool usingFts5);
bool unindexObject(sqlite3 *db, const QString &objectId, bool usingFts5);

struct Hit {
    QString objectId;
    double rank = 0.0; // bm25 (lower = better); fallback: matched tokens
};
// Tokenizes the query with the same unicode61/remove_diacritics(2) fold: AND
// over the terms, the last term matches as a prefix (incremental typing).
QList<Hit> query(sqlite3 *db, const QString &query_, int limit, bool usingFts5);

// Backlinks: references pointing at `objectIds`. Row = (src, block_id, doc).
struct SourceRef {
    QString src;
    QString blockId;
    bool operator==(const SourceRef &o) const = default;
};
QList<SourceRef> backlinks(sqlite3 *db, const QString &dst);

// unicode61 + remove_diacritics(2) tokens of a text (NFKD fold, drop the
// combining marks, lowercase, split on non letter/number).
QStringList tokens(const QString &text);

} // namespace pkm::search
