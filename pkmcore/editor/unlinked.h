#pragma once
// Unlinked mentions: plain text in other objects that names an object (title or alias) without
// linking it. Local text search, no AI. Pure functions, no store.

#include "model.h"

#include <QHash>

namespace pkm {

struct UnlinkedMention {
    QString objectId;  // where the text is
    QString blockId;
    int runIndex = 0;  // the plain run that holds the match
    int runOffset = 0; // match start inside that run's text
    int length = 0;
    QString snippet;   // block text around the match
};

// Whole-word, case-insensitive matches of `target`'s title or aliases (3+ characters) in plain
// text runs of other alive objects. Text inside mentions, tags, links, code and math is skipped.
// Ordered by object id, then block order.
QVector<UnlinkedMention> findUnlinkedMentions(const QHash<QString, Object> &objects, const QString &targetId);

// Turns the matched text into a mention of `targetId` (the run is split around it). Returns the
// block unchanged if the match no longer fits.
Block linkUnlinkedMention(const Block &block, const UnlinkedMention &m, const QString &targetId);

} // namespace pkm
