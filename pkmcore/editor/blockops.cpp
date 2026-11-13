#include "blockops.h"

namespace pkm::blockops {

bool isTextEditable(const Block &b)
{
    return canIndent(b);
}

bool canIndent(const Block &b)
{
    return b.isTextual();
}

QString newId()
{
    return newUuid();
}

SplitResult splitOnEnterAtOffset(const Block &currentB, int caretOffset)
{
    Block current = currentB;
    const auto [beforeSpans, afterSpans] = runSplitAt(current.runs, caretOffset);

    const bool isExitCandidate = current.type == BlockType::Todo || current.type == BlockType::BulletList
                                 || current.type == BlockType::NumberedList
                                 || current.type == BlockType::AlphabeticList
                                 || current.type == BlockType::RomanList || current.type == BlockType::Quote
                                 || current.type == BlockType::Toggle;

    if (isExitCandidate && runsToPlainText(beforeSpans).isEmpty() && runsToPlainText(afterSpans).isEmpty()) {
        Block converted = Block::makeParagraph(current.id, {}, current.indentLevel, current.groupId,
                                              current.blockColor);
        SplitResult r;
        r.kind = SplitResult::ExitList;
        r.updatedCurrent = converted;
        return r;
    }

    Block updated = current;
    updated.runs = beforeSpans;

    Block next;
    next.id = newId();
    next.indentLevel = current.indentLevel;
    next.groupId = current.groupId;

    if (current.type == BlockType::Todo) {
        next.type = BlockType::Todo;
        next.checked = false;
        next.runs = afterSpans;
    } else if (current.type == BlockType::BulletList) {
        next.type = BlockType::BulletList;
        next.runs = afterSpans;
    } else if (current.type == BlockType::NumberedList || current.type == BlockType::AlphabeticList
               || current.type == BlockType::RomanList) {
        next.type = current.type;
        next.number = current.number + 1;
        next.runs = afterSpans;
    } else if (current.type == BlockType::Toggle) {
        if (current.expanded) {
            // Open toggle: new paragraph inside (indent + 1)
            next.type = BlockType::Paragraph;
            next.runs = afterSpans;
            next.indentLevel = qMin(current.indentLevel + 1, 6);
        } else {
            // Closed toggle: sibling toggle below at the same indent
            next.type = BlockType::Toggle;
            next.runs = afterSpans;
            next.expanded = false;
        }
    } else {
        next.type = BlockType::Paragraph;
        next.runs = afterSpans;
    }

    SplitResult r;
    r.kind = SplitResult::Split;
    r.updatedCurrent = updated;
    r.newNext = next;
    return r;
}

BackspaceResult handleBackspaceAtStart(const Blocks &blocks, int currentIndex)
{
    BackspaceResult result;
    if (currentIndex < 0 || currentIndex >= blocks.size())
        return result;
    const Block &current = blocks[currentIndex];

    // 1. Outdent by one level first when indented (keeps the block type).
    if (current.indentLevel > 0) {
        Block outdented = current;
        outdented.indentLevel = qBound(0, current.indentLevel - 1, 6);
        result.kind = BackspaceResult::Outdented;
        result.demotedBlock = outdented;
        return result;
    }

    // 2. Demote special block to paragraph preserving spans/indent/groupId/color.
    if (current.type != BlockType::Paragraph) {
        Block demoted = Block::makeParagraph(current.id, current.runs, 0, current.groupId, current.blockColor);
        result.kind = BackspaceResult::DemotedToParagraph;
        result.demotedBlock = demoted;
        return result;
    }

    // 2b. First-in-group: leave the group before merging outside it.
    if (!current.groupId.isEmpty()) {
        const QString prevGroup = currentIndex > 0 ? blocks[currentIndex - 1].groupId : QString();
        if (prevGroup != current.groupId) {
            Block left = current;
            left.groupId.clear();
            result.kind = BackspaceResult::LeftGroup;
            result.demotedBlock = left;
            return result;
        }
    }

    // 3. Paragraph at indent 0 -> delete if empty, else merge with previous.
    if (currentIndex > 0) {
        const Block &prev = blocks[currentIndex - 1];
        const QString currentText = runsToPlainText(current.runs);
        const int prevLength = runsToPlainText(prev.runs).length();
        if (currentText.isEmpty()) {
            Blocks updated = blocks;
            updated.removeAt(currentIndex);
            result.kind = BackspaceResult::DeleteEmpty;
            result.previousBlockId = prev.id;
            result.cursorOffset = prevLength;
            result.updatedBlocks = updated;
            return result;
        }
        if (isTextEditable(prev)) {
            Blocks updated = blocks;
            QVector<Run> merged = prev.runs;
            merged += current.runs;
            updated[currentIndex - 1].runs = normalizeRuns(merged);
            updated.removeAt(currentIndex);
            result.kind = BackspaceResult::MergeWithPrevious;
            result.previousBlockId = prev.id;
            result.cursorOffset = prevLength;
            result.updatedBlocks = updated;
            return result;
        }
    }

    result.kind = BackspaceResult::NoOp;
    return result;
}

struct ListCounters {
    QString lastKey;
    int lastIndent = -1;
    QHash<QPair<QString, int>, int> counters;
};

Blocks renumberLists(const Blocks &blocks)
{
    ListCounters st;
    Blocks out = blocks;
    for (int i = 0; i < out.size(); ++i) {
        Block &b = out[i];
        if (b.isSequencedList()) {
            const QString key = b.listKey();
            const QPair<QString, int> k(key, b.indentLevel);
            const bool sameRun = st.lastKey == key && st.lastIndent == b.indentLevel;
            const bool resumeOuter = !st.lastKey.isEmpty() && st.lastIndent > b.indentLevel
                                     && st.counters.contains(k);
            const int n = (sameRun || resumeOuter) ? st.counters.value(k, 0) + 1 : 1;
            // Drop counters of deeper levels (they restart when returning).
            for (auto it = st.counters.begin(); it != st.counters.end();) {
                if (it.key().second > b.indentLevel)
                    it = st.counters.erase(it);
                else
                    ++it;
            }
            st.counters[k] = n;
            st.lastKey = key;
            st.lastIndent = b.indentLevel;
            b.number = n;
        } else {
            st.counters.clear();
            st.lastKey.clear();
            st.lastIndent = -1;
        }
    }
    return out;
}

int renumberOneAt(Blocks &blocks, int index)
{
    const Blocks out = renumberLists(blocks);
    blocks = out;
    return index;
}

QVector<VisibleEntry> visibleEntries(const Blocks &blocks, const QSet<QString> &collapsedIds,
                                     const QSet<QString> &selectedIds)
{
    QVector<VisibleEntry> out;
    out.reserve(blocks.size());
    for (int i = 0; i < blocks.size(); ++i) {
        const Block &b = blocks[i];
        // Skip blocks hidden under a collapsed toggle.
        bool hidden = false;
        for (int j = i - 1; j >= 0; --j) {
            const Block &p = blocks[j];
            if (p.type != BlockType::Toggle)
                continue;
            if (!collapsedIds.contains(p.id))
                continue; // open toggle does not hide
            if (blocks[j].indentLevel < blocks[i].indentLevel)
                hidden = true;
            break;
        }
        if (hidden)
            continue;

        VisibleEntry e;
        e.index = i;
        e.block = &blocks[i];
        e.selected = selectedIds.contains(b.id);
        e.firstInGroup = !b.groupId.isEmpty()
                         && (i == 0 || blocks[i - 1].groupId != b.groupId);
        e.lastInGroup = !b.groupId.isEmpty()
                        && (i + 1 >= blocks.size() || blocks[i + 1].groupId != b.groupId);
        if (b.type == BlockType::Toggle && !b.expanded) {
            int hiddenChildren = 0;
            for (int k = i + 1; k < blocks.size(); ++k) {
                if (blocks[k].indentLevel <= b.indentLevel)
                    break;
                ++hiddenChildren;
            }
            e.hiddenChildren = hiddenChildren;
        }
        out.append(e);
    }
    return out;
}

static QVector<Run> remainderSpans(const Block &current, const QString &text, int prefixLen)
{
    const QVector<Run> currentSpans = current.runs;
    if (text.length() <= prefixLen)
        return {};
    if (runsToPlainText(currentSpans) == text)
        return runSlice(currentSpans, prefixLen, text.length());
    Run r;
    r.text = text.mid(prefixLen);
    return { r };
}

MarkdownResult processMarkdownPrefix(const Block &currentB, const QString &text)
{
    MarkdownResult result;
    if (currentB.type != BlockType::Paragraph && currentB.type != BlockType::Heading)
        return result;
    const Block &current = currentB;
    const int indent = current.indentLevel;
    const QString groupId = current.groupId;
    const QString color = current.blockColor;

    auto transform = [&](Block b) {
        result.kind = MarkdownResult::Transform;
        result.block = b;
        return result;
    };

    if (text == QLatin1String("---") || text == QLatin1String("***")) {
        Block divider;
        divider.id = current.id;
        divider.type = BlockType::Divider;
        divider.groupId = groupId;
        divider.blockColor = color;
        Block next = Block::makeParagraph(newId(), {}, indent, groupId);
        result.kind = MarkdownResult::Divider;
        result.block = divider;
        result.next = next;
        return result;
    }
    if (text.startsWith(QStringLiteral("#### ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Heading;
            b.level = 4;
            b.runs = remainderSpans(current, text, 5);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("### ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Heading;
            b.level = 3;
            b.runs = remainderSpans(current, text, 4);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("## ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Heading;
            b.level = 2;
            b.runs = remainderSpans(current, text, 3);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("# ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Heading;
            b.level = 1;
            b.runs = remainderSpans(current, text, 2);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("[] ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Todo;
            b.runs = remainderSpans(current, text, 3);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("[ ] ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Todo;
            b.runs = remainderSpans(current, text, 4);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("[x] "), Qt::CaseInsensitive))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Todo;
            b.checked = true;
            b.runs = remainderSpans(current, text, 4);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("- ")) || text.startsWith(QStringLiteral("* "))
        || text.startsWith(QStringLiteral("+ ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::BulletList;
            b.runs = remainderSpans(current, text, 2);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("1. ")) || text.startsWith(QStringLiteral("1) ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::NumberedList;
            b.number = 1;
            b.runs = remainderSpans(current, text, 3);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("a. ")) || text.startsWith(QStringLiteral("a) "))
        || text.startsWith(QStringLiteral("A. ")) || text.startsWith(QStringLiteral("A) ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::AlphabeticList;
            b.number = 1;
            b.runs = remainderSpans(current, text, 3);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("I. ")) || text.startsWith(QStringLiteral("i. ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::RomanList;
            b.number = 1;
            b.runs = remainderSpans(current, text, 3);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral(">> "))) {
        const QString targetGroup = groupId.isEmpty() ? newId() : groupId;
        return transform(Block::makeParagraph(current.id, remainderSpans(current, text, 3), indent,
                                              targetGroup, color));
    }
    if (text.startsWith(QStringLiteral("\u25B6 ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Toggle;
            b.runs = remainderSpans(current, text, 2);
            b.expanded = true;
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral(">>> ")))
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Toggle;
            b.runs = remainderSpans(current, text, 4);
            b.expanded = true;
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    if (text.startsWith(QStringLiteral("> ")) || text.startsWith(QStringLiteral("\"\" "))) {
        const int prefixLen = text.startsWith(QStringLiteral("> ")) ? 2 : 3;
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Quote;
            b.runs = remainderSpans(current, text, prefixLen);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    }
    if (text.startsWith(QStringLiteral("! ")) || text.startsWith(QStringLiteral("\U0001F4A1 "))) {
        const int prefixLen = text.startsWith(QStringLiteral("! ")) ? 2 : 3;
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Callout;
            b.calloutType = "NOTE";
            b.emoji = QStringLiteral("\U0001F4A1");
            b.runs = remainderSpans(current, text, prefixLen);
            b.indentLevel = indent;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    }
    if (text == QLatin1String("$$") || text.startsWith(QStringLiteral("$$ "))) {
        QString formula = text;
        if (formula.startsWith(QStringLiteral("$$ ")))
            formula = formula.mid(3);
        else
            formula.clear();
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Math;
            b.latex = formula.trimmed();
            b.indentLevel = 0;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    }
    if (text == QLatin1String("```") || text.startsWith(QStringLiteral("```"))) {
        QString lang = text.mid(3).trimmed();
        if (lang.isEmpty())
            lang = QStringLiteral("kotlin");
        return transform([&] {
            Block b;
            b.id = current.id;
            b.type = BlockType::Code;
            b.language = lang;
            b.text.clear();
            b.indentLevel = 0;
            b.groupId = groupId;
            b.blockColor = color;
            return b;
        }());
    }
    return result;
}

Block convertBlockType(const Block &current, const QString &target)
{
    const QVector<Run> spans = current.isTextual() || current.type == BlockType::Image
                                   || current.type == BlockType::Audio
                               ? current.runs
                               : QVector<Run>();
    const QString text = current.plainText();
    const int indent = current.indentLevel;
    const QString groupId = current.groupId;
    const QString color = current.blockColor;

    Block b;
    b.id = current.id;
    b.indentLevel = indent;
    b.groupId = groupId;
    b.blockColor = color;

    if (target == BlockKey::H1 || target == BlockKey::H2 || target == BlockKey::H3
        || target == BlockKey::H4) {
        b.type = BlockType::Heading;
        b.level = target == BlockKey::H1 ? 1 : target == BlockKey::H2 ? 2 : target == BlockKey::H3 ? 3 : 4;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Todo) {
        b.type = BlockType::Todo;
        b.checked = current.type == BlockType::Todo ? current.checked : false;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Bullet) {
        b.type = BlockType::BulletList;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Numbered) {
        b.type = BlockType::NumberedList;
        b.number = 1;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Alphabetic) {
        b.type = BlockType::AlphabeticList;
        b.number = 1;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Roman) {
        b.type = BlockType::RomanList;
        b.number = 1;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Quote) {
        b.type = BlockType::Quote;
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Callout) {
        b.type = BlockType::Callout;
        b.calloutType = "NOTE";
        b.emoji = QStringLiteral("\U0001F4A1");
        b.runs = spans;
        return b;
    }
    if (target == BlockKey::Group) {
        const QString gid = groupId.isEmpty() ? newId() : groupId;
        Block base = current;
        if (base.type == BlockType::Divider || base.type == BlockType::Table)
            base = Block::makeParagraph(current.id, spans, indent, QString(), color);
        base.groupId = gid;
        return base;
    }
    if (target == BlockKey::Code) {
        b.type = BlockType::Code;
        b.text = text;
        b.runs.clear();
        b.indentLevel = 0;
        return b;
    }
    if (target == BlockKey::Math) {
        b.type = BlockType::Math;
        b.latex = text;
        b.runs.clear();
        b.indentLevel = 0;
        return b;
    }
    if (target == BlockKey::Table) {
        b.type = BlockType::Table;
        b.rows = { { QString(), QString() }, { QString(), QString() }, { QString(), QString() } };
        b.indentLevel = 0;
        return b;
    }
    if (target == BlockKey::Divider) {
        b.type = BlockType::Divider;
        b.indentLevel = 0;
        return b;
    }
    if (target == BlockKey::Toggle) {
        b.type = BlockType::Toggle;
        b.runs = spans;
        b.expanded = true;
        return b;
    }
    if (target == BlockKey::Bookmark) {
        QUrl url(text.trimmed());
        QString clean = text.trimmed();
        b.type = BlockType::Bookmark;
        b.url = (clean.startsWith(QStringLiteral("http://")) || clean.startsWith(QStringLiteral("https://"))) ? clean : QString();
        b.title = b.url;
        b.domain = QUrl(b.url).host();
        if (b.domain.startsWith(QStringLiteral("www.")))
            b.domain = b.domain.mid(4);
        b.indentLevel = 0;
        return b;
    }
    if (target == BlockKey::Canvas) {
        b.type = BlockType::Canvas;
        b.canvasData = current.type == BlockType::Canvas && current.canvasData.isObject()
                           ? current.canvasData
                           : QJsonObject{ { "id", newId() } };
        b.indentLevel = 0;
        return b;
    }
    b.type = BlockType::Paragraph;
    b.runs = spans;
    return b;
}

Blocks indentSelected(const Blocks &blocks, const QSet<QString> &ids, int delta)
{
    Blocks out = blocks;
    for (Block &b : out) {
        if (ids.contains(b.id) && canIndent(b))
            b.indentLevel = qBound(0, b.indentLevel + delta, 6);
    }
    return renumberLists(out);
}

Blocks moveSelected(const Blocks &blocks, const QSet<QString> &ids, int delta)
{
    if (ids.isEmpty() || delta == 0)
        return blocks;
    Blocks list = blocks;
    if (delta < 0) {
        for (int i = 1; i < list.size(); ++i) {
            if (ids.contains(list[i].id) && !ids.contains(list[i - 1].id))
                list.swapItemsAt(i, i - 1);
        }
    } else {
        for (int i = list.size() - 2; i >= 0; --i) {
            if (ids.contains(list[i].id) && !ids.contains(list[i + 1].id))
                list.swapItemsAt(i, i + 1);
        }
    }
    return renumberLists(list);
}

Blocks reorderSubtree(const Blocks &blocks, int from, int to)
{
    if (from < 0 || from >= blocks.size())
        return blocks;
    const int clampedTo = qBound(0, to, int(blocks.size()));
    // Range of the dragged block including its indent subtree (children).
    const int indent = blocks[from].indentLevel;
    int end = from + 1;
    while (end < blocks.size() && blocks[end].indentLevel > indent)
        ++end;
    if (clampedTo >= from && clampedTo <= end)
        return blocks; // dropped onto itself: no-op
    Blocks out;
    out.reserve(blocks.size());
    for (int i = 0; i < blocks.size(); ++i)
        if (i < from || i >= end)
            out.append(blocks[i]);
    int insertAt = clampedTo > end ? clampedTo - (end - from) : clampedTo;
    insertAt = qBound(0, insertAt, int(out.size()));
    for (int i = 0; i < end - from; ++i)
        out.insert(insertAt + i, blocks[from + i]);
    return renumberLists(out);
}

Blocks duplicateSelected(const Blocks &blocks, const QSet<QString> &ids)
{
    if (ids.isEmpty())
        return blocks;
    int lastIdx = -1;
    for (int i = 0; i < blocks.size(); ++i)
        if (ids.contains(blocks[i].id))
            lastIdx = i;
    if (lastIdx == -1)
        return blocks;
    Blocks selected;
    for (const Block &b : blocks)
        if (ids.contains(b.id)) {
            Block copy = b;
            copy.id = newId();
            selected.append(copy);
        }
    Blocks out = blocks;
    for (const Block &b : selected)
        out.insert(++lastIdx, b);
    return renumberLists(out);
}

Blocks toggleGroupSelected(const Blocks &blocks, const QSet<QString> &ids)
{
    if (ids.isEmpty())
        return blocks;
    QString existing;
    bool allSame = true;
    for (const Block &b : blocks) {
        if (!ids.contains(b.id))
            continue;
        if (existing.isEmpty())
            existing = b.groupId;
        else if (b.groupId != existing)
            allSame = false;
    }
    allSame = allSame && !existing.isEmpty();
    const QString target = allSame ? QString() : newId();
    Blocks out = blocks;
    for (Block &b : out) {
        if (ids.contains(b.id))
            b.groupId = target;
    }
    return out;
}

Blocks colorSelected(const Blocks &blocks, const QSet<QString> &ids, const QString &colorName)
{
    Blocks out = blocks;
    for (Block &b : out) {
        if (ids.contains(b.id))
            b.blockColor = colorName;
    }
    return out;
}

} // namespace pkm::blockops
