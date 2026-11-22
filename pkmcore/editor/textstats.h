#pragma once
// What an object holds: text statistics (words, sentences, paragraphs, characters) and the
// objects it contains (mentions and embeds, in reading order). Pure functions, no store.

#include "model.h"

namespace pkm {

struct TextStats {
    int words = 0;
    int sentences = 0;
    int paragraphs = 0; // text, code and table blocks with text (not math, media or embeds)
    int characters = 0; // without line breaks
};

TextStats textStats(const QVector<Block> &blocks);

// Objects mentioned in runs or embedded as blocks, first appearance first, without repeats and
// without `selfId`. Tags and dates are not objects you put in a page, so they are left out.
QStringList containedObjectIds(const QVector<Block> &blocks, const QString &selfId);

} // namespace pkm
