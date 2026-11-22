#include "unlinked.h"

#include <algorithm>

namespace pkm {

namespace {

bool isWordChar(QChar c)
{
    return c.isLetterOrNumber() || c == QLatin1Char('_');
}

bool plainRun(const Run &r)
{
    return r.objectId.isEmpty() && r.tagId.isEmpty() && r.tagName.isEmpty() && r.date.isEmpty()
        && r.href.isEmpty() && !r.code && !r.isMath && r.imageName.isEmpty();
}

QString snippetAround(const QString &text, int at, int len)
{
    const int from = std::max(0, at - 40);
    const int to = std::min(int(text.size()), at + len + 40);
    QString s = text.mid(from, to - from).simplified();
    if (from > 0)
        s.prepend(QStringLiteral("…"));
    if (to < text.size())
        s.append(QStringLiteral("…"));
    return s;
}

} // namespace

QVector<UnlinkedMention> findUnlinkedMentions(const QHash<QString, Object> &objects, const QString &targetId)
{
    QVector<UnlinkedMention> out;
    const auto tit = objects.constFind(targetId);
    if (tit == objects.constEnd())
        return out;
    QStringList names;
    for (const QString &n : QStringList{ tit.value().title } + tit.value().aliases())
        if (n.trimmed().size() >= 3 && !names.contains(n.trimmed(), Qt::CaseInsensitive))
            names.append(n.trimmed());
    if (names.isEmpty())
        return out;
    // Longest names first, so "Ana López" wins over "Ana" at the same place.
    std::sort(names.begin(), names.end(), [](const QString &a, const QString &b) { return a.size() > b.size(); });

    QStringList ids = objects.keys();
    std::sort(ids.begin(), ids.end());
    for (const QString &oid : std::as_const(ids)) {
        const Object &o = objects.value(oid);
        if (oid == targetId || !o.trashedAt.isEmpty() || o.isTemplate())
            continue;
        for (const Block &b : o.blocks) {
            int prefix = 0; // visible length of the runs before this one
            for (int ri = 0; ri < b.runs.size(); ++ri) {
                const Run &r = b.runs[ri];
                if (plainRun(r)) {
                    const QString lower = r.text.toLower();
                    QVector<QPair<int, int>> taken;
                    for (const QString &name : std::as_const(names)) {
                        const QString needle = name.toLower();
                        for (int at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, at + 1)) {
                            const int end = at + needle.size();
                            const bool leftOk = at == 0 || !isWordChar(lower.at(at - 1));
                            const bool rightOk = end == lower.size() || !isWordChar(lower.at(end));
                            const bool overlaps = std::any_of(taken.begin(), taken.end(), [&](const QPair<int, int> &t) {
                                return at < t.second && end > t.first;
                            });
                            if (!leftOk || !rightOk || overlaps)
                                continue;
                            taken.append({ at, end });
                        }
                    }
                    std::sort(taken.begin(), taken.end());
                    const QString plain = runsToPlainText(b.runs);
                    for (const QPair<int, int> &t : std::as_const(taken))
                        out.append({ oid, b.id, ri, t.first, t.second - t.first,
                                     snippetAround(plain, prefix + t.first, t.second - t.first) });
                }
                prefix += runsToPlainText({ r }).size();
            }
        }
    }
    return out;
}

Block linkUnlinkedMention(const Block &block, const UnlinkedMention &m, const QString &targetId)
{
    if (m.runIndex < 0 || m.runIndex >= block.runs.size())
        return block;
    const Run &r = block.runs[m.runIndex];
    if (!plainRun(r) || m.runOffset < 0 || m.runOffset + m.length > r.text.size())
        return block;
    Block out = block;
    Run before = r, mention, after = r;
    before.text = r.text.left(m.runOffset);
    mention.text = r.text.mid(m.runOffset, m.length);
    mention.objectId = targetId;
    after.text = r.text.mid(m.runOffset + m.length);
    QVector<Run> runs;
    for (int i = 0; i < block.runs.size(); ++i) {
        if (i != m.runIndex) {
            runs.append(block.runs[i]);
            continue;
        }
        if (!before.text.isEmpty())
            runs.append(before);
        runs.append(mention);
        if (!after.text.isEmpty())
            runs.append(after);
    }
    out.runs = runs;
    out.text = runsToPlainText(runs);
    return out;
}

} // namespace pkm
