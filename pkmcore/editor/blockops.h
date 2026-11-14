#pragma once

// Block document operations — 1:1 port of mobile ui/editor/BlockOperations.kt
// and DefaultMarkdownShortcutHandler, over the pkm wire block model.

#include "model.h"

#include <QSet>
#include <functional>
#include <QStringList>

namespace pkm::blockops {

bool isTextEditable(const Block &b);
bool canIndent(const Block &b);

struct SplitResult {
    enum Kind { ExitList, Split, NoOp } kind = NoOp;
    Block updatedCurrent;  // ExitList: converted; Split: first half
    Block newNext;         // Split: second half
};
SplitResult splitOnEnterAtOffset(const Block &current, int caretOffset);

struct BackspaceResult {
    enum Kind { Outdented, DemotedToParagraph, LeftGroup, DeleteEmpty, MergeWithPrevious, NoOp } kind = NoOp;
    QString previousBlockId;
    int cursorOffset = 0;
    Blocks updatedBlocks;          // for DeleteEmpty / MergeWithPrevious
    Block demotedBlock;            // for Outdented / DemotedToParagraph / LeftGroup
};
BackspaceResult handleBackspaceAtStart(const Blocks &blocks, int currentIndex);

Blocks renumberLists(const Blocks &blocks);

// A mention run keeps its target's title as `text` from the moment it was inserted, so after a
// rename it would still read "@hydroge". Sets every mention label to titleOf(objectId); an empty
// result (unknown or untitled target) keeps the label. Returns true when a label changed.
bool refreshMentionLabels(Blocks &blocks, const std::function<QString(const QString &)> &titleOf);

int renumberOneAt(Blocks &blocks, int index);

struct VisibleEntry {
    int index = 0;          // index into the flat doc
    const Block *block = nullptr;
    int hiddenChildren = 0; // for toggles
    bool firstInGroup = false;
    bool lastInGroup = false;
    bool selected = false;
};
QVector<VisibleEntry> visibleEntries(const Blocks &blocks, const QSet<QString> &collapsedIds = {},
                                      const QSet<QString> &selectedIds = {});

struct MarkdownResult {
    enum Kind { None, Transform, Divider } kind = None;
    Block block;      // Transform: transformed block; Divider: divider block
    Block next;       // Divider: following paragraph
};
MarkdownResult processMarkdownPrefix(const Block &current, const QString &text);

Block convertBlockType(const Block &current, const QString &targetType);
Blocks indentSelected(const Blocks &blocks, const QSet<QString> &ids, int delta);
Blocks moveSelected(const Blocks &blocks, const QSet<QString> &ids, int delta);
// Drag & drop: move block `from` (with its whole indent subtree) so it lands
// directly before block `to` (or at the end when to == blocks.size()).
Blocks reorderSubtree(const Blocks &blocks, int from, int to);
Blocks duplicateSelected(const Blocks &blocks, const QSet<QString> &ids);
Blocks toggleGroupSelected(const Blocks &blocks, const QSet<QString> &ids);
Blocks colorSelected(const Blocks &blocks, const QSet<QString> &ids, const QString &colorName);

} // namespace pkm::blockops
