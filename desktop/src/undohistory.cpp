#include "undohistory.h"

namespace pkm {

EditState makeEditState(const Blocks &blocks, const QString &title)
{
    EditState s;
    s.blocks = blocks;
    s.blocks.detach(); // own copy: the editor's rows point into its live vector, which must stay unshared
    s.title = title;
    s.wire.reserve(blocks.size());
    for (const Block &b : blocks)
        s.wire.append(blocksToWireJson(Blocks{ b }));
    return s;
}

QString editChange(const EditState &a, const EditState &b)
{
    const bool titleSame = a.title == b.title;
    if (a.wire == b.wire)
        return titleSame ? QString() : QStringLiteral("#title");
    if (!titleSame || a.blocks.size() != b.blocks.size())
        return QStringLiteral("*");
    int changed = -1;
    for (int i = 0; i < a.blocks.size(); ++i) {
        if (a.wire[i] == b.wire[i])
            continue;
        if (changed >= 0)
            return QStringLiteral("*");
        changed = i;
    }
    // Same block, only its text: compare with the runs taken out.
    Block x = a.blocks[changed], y = b.blocks[changed];
    if (x.id != y.id || !x.isTextual())
        return QStringLiteral("*");
    x.runs.clear(); x.text.clear();
    y.runs.clear(); y.text.clear();
    return blocksToWireJson(Blocks{ x }) == blocksToWireJson(Blocks{ y }) ? x.id : QStringLiteral("*");
}

QPair<QString, int> editFocus(const EditState &from, const EditState &to)
{
    const int n = int(qMin(from.blocks.size(), to.blocks.size()));
    int i = 0;
    while (i < n && from.blocks[i].id == to.blocks[i].id && from.wire[i] == to.wire[i])
        ++i;
    if (i >= to.blocks.size()) {
        if (to.blocks.isEmpty() || (i == n && from.blocks.size() == to.blocks.size()))
            return {};
        const Block &last = to.blocks.last(); // blocks were removed at the end
        return { last.id, last.plainText().length() };
    }
    const Block &t = to.blocks[i];
    const QString after = t.plainText();
    if (i >= from.blocks.size() || from.blocks[i].id != t.id)
        return { t.id, after.length() };
    const QString before = from.blocks[i].plainText();
    int p = 0;
    while (p < before.length() && p < after.length() && before[p] == after[p])
        ++p;
    // typing undone: caret where the removed text was; text restored: caret after it
    return { t.id, after.length() > before.length() ? p + int(after.length() - before.length()) : p };
}

void UndoHistory::reset(const EditState &current)
{
    m_undo.clear();
    m_redo.clear();
    m_current = current;
    m_lastChange.clear();
    m_lastMs = 0;
}

bool UndoHistory::record(const EditState &now, qint64 ms)
{
    const QString change = editChange(m_current, now);
    if (change.isEmpty())
        return false;
    const bool joins = change != QLatin1String("*") && change == m_lastChange
        && !m_undo.isEmpty() && ms - m_lastMs < kGroupMs;
    if (!joins) {
        m_undo.append(m_current);
        if (m_undo.size() > kLimit)
            m_undo.removeFirst();
    }
    m_redo.clear();
    m_current = now;
    m_lastChange = change;
    m_lastMs = ms;
    return true;
}

EditState UndoHistory::undo()
{
    if (m_undo.isEmpty())
        return m_current;
    m_redo.append(m_current);
    m_current = m_undo.takeLast();
    m_lastChange.clear();
    return m_current;
}

EditState UndoHistory::redo()
{
    if (m_redo.isEmpty())
        return m_current;
    m_undo.append(m_current);
    m_current = m_redo.takeLast();
    m_lastChange.clear();
    return m_current;
}

} // namespace pkm
