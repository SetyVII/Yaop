// A five-minute tour of pkmcore. Build: cmake --build build && ./build/pkmcore_tour
#include "blockops.h"
#include "merge.h"
#include "model.h"
#include "query.h"

#include <QGuiApplication>
#include <QJsonArray>
#include <QJsonDocument>
#include <cstdio>

using namespace pkm;

int main(int argc, char **argv)
{
    QGuiApplication app(argc, argv);
    setHlcNode(QStringLiteral("device-a"));

    // 1. Blocks from markdown. Blocks are a flat list; nesting is an indent level.
    Object note;
    note.id = QStringLiteral("note-1");
    note.title = QStringLiteral("Groceries");
    note.blocks = markdownToBlocks(QStringLiteral("# Shopping\n- milk\n- bread"));

    // 2. Editor semantics: Enter in the middle of "bread" splits the block in two.
    const blockops::SplitResult split = blockops::splitOnEnterAtOffset(note.blocks.last(), 3);
    if (split.kind == blockops::SplitResult::Split) {
        note.blocks.last() = split.updatedCurrent;
        note.blocks.append(split.newNext);
    }
    std::printf("after Enter:\n%s\n", qPrintable(blocksToMarkdown(note.blocks)));

    // 3. Two devices edit the same object offline; the merge converges either way.
    note.stamp(nullptr, newHlc());
    Object a = note, b = note;
    a.title = QStringLiteral("Groceries (A)");
    a.stamp(&note, newHlc());
    b.blocks.append(markdownToBlocks(QStringLiteral("- eggs")));
    b.stamp(&note, newHlc());
    const QJsonObject ab = mergeWire(a.toJson(true), b.toJson(true));
    const QJsonObject ba = mergeWire(b.toJson(true), a.toJson(true));
    const int blocks = ab.value(QStringLiteral("properties")).toObject()
                           .value(QStringLiteral("blocks")).toArray().size();
    std::printf("merge is symmetric: %s; title from A = \"%s\"; blocks incl. B's \"eggs\" = %d\n",
                ab == ba ? "yes" : "no", qPrintable(ab.value(QStringLiteral("title")).toString()), blocks);

    // 4. Queries over wire objects (spec/query.md).
    const QJsonObject q = QJsonDocument::fromJson(R"({"version":1,"filters":[{"prop":"@title","op":"contains","value":"groc"}]})").object();
    const query::Result r = query::evaluate(q, { ab }, QStringLiteral("2026-01-01"));
    std::printf("query matched: %s %s\n", qPrintable(r.ids.join(',')), qPrintable(r.error));
    return 0;
}
