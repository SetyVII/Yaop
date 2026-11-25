#pragma once

// UndoHistory: undo/redo for one open object (product.md §16.3: per editor session, never synced).
// Every step is a whole snapshot (blocks + title); notes are small and this keeps undo exact.
// Typing in one block (or the title) within kGroupMs of the previous step joins that step, so
// Ctrl+Z takes back a burst of typing at once, not a letter at a time.

#include "model.h"

#include <QStringList>
#include <QVector>

namespace pkm {

struct EditState {
    Blocks blocks;
    QString title;
    QStringList wire; // per-block wire JSON, to tell what changed between two states
};

EditState makeEditState(const Blocks &blocks, const QString &title);

// What changed from `a` to `b`: "" nothing, a block id when only that block's text changed,
// "#title" when only the title changed, "*" otherwise (structure, several blocks, attributes).
QString editChange(const EditState &a, const EditState &b);

// Where the caret goes after jumping from `from` to `to`: the first block that differs, at the
// first character that differs. Returns {blockId, position}; blockId empty when nothing differs.
QPair<QString, int> editFocus(const EditState &from, const EditState &to);

class UndoHistory {
public:
    static constexpr int kGroupMs = 1000;
    static constexpr int kLimit = 300;

    void reset(const EditState &current);
    // An edit happened and `now` is the result. Returns false when nothing changed.
    bool record(const EditState &now, qint64 ms);
    bool canUndo() const { return !m_undo.isEmpty(); }
    bool canRedo() const { return !m_redo.isEmpty(); }
    // Step back / forward; the returned state is the new current one (caller shows it).
    EditState undo();
    EditState redo();
    const EditState &current() const { return m_current; }

private:
    QVector<EditState> m_undo;
    QVector<EditState> m_redo;
    EditState m_current;
    QString m_lastChange; // what the newest step changed, for grouping typing
    qint64 m_lastMs = 0;
};

} // namespace pkm
