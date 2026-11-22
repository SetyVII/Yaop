#include "textstats.h"

#include <QRegularExpression>

namespace pkm {

TextStats textStats(const QVector<Block> &blocks)
{
    static const QRegularExpression wordRe(QStringLiteral("[\\p{L}\\p{N}][\\p{L}\\p{N}'’_-]*"));
    static const QRegularExpression sentenceEnd(QStringLiteral("[.!?…]+(\\s|$)"));
    TextStats s;
    for (const Block &b : blocks) {
        // prose only: text blocks, code and tables; not math, media captions or embeds
        if (!b.isTextual() && b.type != QLatin1String(BlockType::Code) && b.type != QLatin1String(BlockType::Table))
            continue;
        const QString t = b.plainText().trimmed();
        if (t.isEmpty())
            continue;
        ++s.paragraphs;
        QString flat = t;
        flat.remove(QLatin1Char('\n'));
        s.characters += flat.size();
        for (auto it = wordRe.globalMatch(t); it.hasNext(); it.next())
            ++s.words;
        int ends = 0;
        for (auto it = sentenceEnd.globalMatch(t); it.hasNext(); it.next())
            ++ends;
        // a paragraph without final punctuation still counts as one sentence
        const bool closed = t.endsWith(QLatin1Char('.')) || t.endsWith(QLatin1Char('!'))
            || t.endsWith(QLatin1Char('?')) || t.endsWith(QChar(0x2026));
        s.sentences += ends + (closed ? 0 : 1);
    }
    return s;
}

QStringList containedObjectIds(const QVector<Block> &blocks, const QString &selfId)
{
    QStringList out;
    auto add = [&](const QString &id) {
        if (!id.isEmpty() && id != selfId && !out.contains(id))
            out << id;
    };
    for (const Block &b : blocks) {
        if (b.type == QLatin1String(BlockType::Embed))
            add(b.objectId);
        for (const Run &r : b.runs)
            add(r.objectId);
    }
    return out;
}

} // namespace pkm
