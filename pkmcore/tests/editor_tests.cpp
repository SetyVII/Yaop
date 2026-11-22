#include "testing.h"
#include "blockops.h"
#include "mentionquery.h"
#include "merge.h"
#include "model.h"
#include "query.h"
#include "spancodec.h"
#include "standardtypes.h"
#include "imagecolors.h"
#include "objectops.h"
#include "graph.h"
#include "unlinked.h"
#include "optionview.h"
#include "textstats.h"
#include "collection.h"
#include "history.h"
#include <QImage>
#include <QPainter>
#include <QElapsedTimer>
#include <QClipboard>
#include <QGuiApplication>
#include <QMimeData>
#include <QBuffer>
#include <QtEndian>
#include <algorithm>
#include <QCryptographicHash>
#include <QJsonArray>
#include <QDir>
#include <QRegularExpression>
#include <QTemporaryDir>
#include <QFile>
#include <QJsonDocument>
#include <QTextCharFormat>
#include <QTextCursor>
#include <QTextDocument>
#include <QtGlobal>
#include <cstdio>

namespace pkm::testing {

static int visibleLength(const QVector<Run> &runs)
{
    return runsToPlainText(runs).length();
}

static void testRunRoundTrip()
{
    QJsonArray wire;
    {
        QJsonObject plain{ { "text", "Hello " } };
        QJsonObject bold{ { "text", "world" }, { "bold", true } };
        QJsonObject mention{ { "text", "Mi nota" },
                              { "href", "lumen://object/obj-123" },
                              { "object_id", "obj-123" } };
        QJsonObject tag{ { "text", "#work" }, { "tag_id", "tag_work" }, { "tag_name", "work" } };
        QJsonObject date{ { "text", "2025-11-10" }, { "date", "2025-11-10" } };
        QJsonObject color{ { "text", "rojo" }, { "text_color", "red" }, { "bg_color", "yellow" } };
        QJsonObject math{ { "text", "\uFFFC" },
                          { "type", "math" },
                          { "latex", "\\frac{a}{b}" },
                          { "display", true } };
        wire.append(plain);
        wire.append(bold);
        wire.append(mention);
        wire.append(tag);
        wire.append(date);
        wire.append(color);
        wire.append(math);
    }
    const QVector<Run> parsed = runsFromJson(wire);
    CHECK(parsed.size() == 7, "runs parsed count");
    CHECK(parsed.at(1).bold, "bold preserved");
    CHECK(parsed.at(2).objectId == "obj-123", "mention preserved");
    CHECK(visibleLength(parsed) > 0, "visible text");
    CHECK(parsed.at(3).tagName == "work", "tag name");
    CHECK(parsed.at(4).date == "2025-11-10", "date preserved");
    CHECK(parsed.at(5).textColor == "red" && parsed.at(5).bgColor == "yellow", "colors by name");
    CHECK(parsed.at(6).isMath && parsed.at(6).latex == "\\frac{a}{b}", "inline math preserved");

    const QJsonArray out = runsToJson(parsed);
    const QVector<Run> again = runsFromJson(out);
    CHECK(runsToPlainText(again) == runsToPlainText(parsed), "round trip visible text stable");
    CHECK(again.at(1).bold && again.at(6).isMath, "round trip marks stable");
    const QJsonArray out2 = runsToJson(again);
    CHECK(QJsonDocument(out).toJson() == QJsonDocument(out2).toJson(), "wire stability second pass");
}

static void testBlockRoundTrip()
{
    Blocks blocks;
    Block p = Block::makeParagraph("p1");
    Run s;
    s.text = "párrafo ";
    Run s2;
    s2.text = "importante";
    s2.bold = true;
    p.runs = { s, s2 };
    p.indentLevel = 1;
    blocks.append(p);

    Block h;
    h.id = "h1";
    h.type = BlockType::Heading;
    h.level = 2;
    Run hr;
    hr.text = "Título";
    h.runs = { hr };
    blocks.append(h);

    Block todo;
    todo.id = "t1";
    todo.type = BlockType::Todo;
    todo.checked = true;
    Run tr;
    tr.text = "hecho";
    todo.runs = { tr };
    blocks.append(todo);

    Block numbered;
    numbered.id = "n1";
    numbered.type = BlockType::NumberedList;
    numbered.number = 3;
    Run nr;
    nr.text = "tres";
    numbered.runs = { nr };
    numbered.indentLevel = 2;
    blocks.append(numbered);

    Block code;
    code.id = "c1";
    code.type = BlockType::Code;
    code.language = "kotlin";
    code.text = "fun main() {\n    println(1)\n}";
    blocks.append(code);

    Block math;
    math.id = "m1";
    math.type = BlockType::Math;
    math.latex = "\\sqrt{2}";
    blocks.append(math);

    Block img;
    img.id = "i1";
    img.type = BlockType::Image;
    img.assetId = "asset-9";
    img.widthPercent = 0.9;
    Run cap;
    cap.text = "captión";
    img.runs = { cap };
    blocks.append(img);

    Block table;
    table.id = "tb1";
    table.type = BlockType::Table;
    table.rows = { { "a", "b" }, { "c", "d" } };
    table.align = { "left", "right" };
    blocks.append(table);

    const QString wire = blocksToWireJson(blocks);
    const Blocks back = blocksFromWireJson(wire);
    CHECK(back.size() == blocks.size(), "block count preserved");
    CHECK(back.at(0).runs.at(1).bold, "para bold preserved");
    CHECK(back.at(0).indentLevel == 1, "indent mapped");
    CHECK(back.at(3).number == 3 && back.at(3).indentLevel == 2, "numbered preserved");
    CHECK(back.at(4).type == BlockType::Code && back.at(4).text.contains("println"), "code preserved");
    CHECK(back.at(4).language == "kotlin", "code language preserved");
    CHECK(back.at(5).type == BlockType::Math && back.at(5).latex == "\\sqrt{2}", "math preserved");
    CHECK(back.at(6).type == BlockType::Image && back.at(6).assetId == "asset-9", "image asset id");
    CHECK(back.at(6).runs.first().text == "captión", "image caption preserved");
    CHECK(back.at(7).type == BlockType::Table && back.at(7).rows.size() == 2, "table rows");
    CHECK(back.at(7).rows.at(1).at(1) == "d", "table cell");
    const QString wire2 = blocksToWireJson(back);
    // Text-level stability: second pass should produce identical wire bytes.
    CHECK(wire == wire2, "wire form stable");
}

static void testGroupWire()
{
    Blocks blocks;
    Block a = Block::makeParagraph("g1", {}, 0, "group-x");
    Block b = Block::makeParagraph("g2", {}, 0, "group-x");
    Block c = Block::makeParagraph("g3");
    Run t;
    t.text = "x";
    a.runs = { t };
    blocks.append(a);
    blocks.append(b);
    blocks.append(c);
    const QString wire = blocksToWireJson(blocks);
    const QJsonDocument doc = QJsonDocument::fromJson(wire.toUtf8());
    const QJsonArray arr = doc.array();
    // doc/3: flat blocks, grouping as group_id on each member.
    CHECK(arr.size() == 3, "groups are flat on the wire");
    CHECK(arr.at(0).toObject().value("group_id").toString() == "group-x"
              && arr.at(1).toObject().value("group_id").toString() == "group-x", "group_id on members");
    CHECK(!arr.at(2).toObject().contains("group_id"), "ungrouped block has no group_id");
    const Blocks back = blocksFromWireJson(wire);
    CHECK(back.size() == 3, "group unpacked flat");
    CHECK(back.at(0).groupId == "group-x" && back.at(1).groupId == "group-x", "group ids shared");
    CHECK(back.at(2).groupId.isEmpty(), "third block ungrouped");
}

static void testMentionTypeQuery()
{
    auto type = [](const QString &id, const QString &name) {
        ObjectType t;
        t.id = id;
        t.name = name;
        return t;
    };
    const QList<ObjectType> types = { type("web", "Web Links"), type("custom_ai", "Modelos de IA"),
                                      type("custom_ref", "Modelos de referencia"), type("note", "Note") };
    const QHash<QString, QString> aliases = { { "nota", "note" }, { "enlace", "web" } };
    CHECK(mention::typeSlug("Web Links") == "web-links", "mention: slug hyphenates spaces");
    CHECK(mention::typeSlug("Canciones  rápidas") == "canciones-rapidas", "mention: slug folds accents");
    for (const char *q : { "web-links", "weblinks", "web links", "web_links", "Web-Links" })
        CHECK(mention::resolveTypeToken(q, types, aliases) == "web", "mention: any separator resolves the type");
    CHECK(mention::resolveTypeToken("modelos-de-ia", types, aliases) == "custom_ai", "mention: user type by slug");
    CHECK(mention::resolveTypeToken("nota", types, aliases) == "note", "mention: alias resolves");
    CHECK(mention::resolveTypeToken("zz", types, aliases).isEmpty(), "mention: unknown token");
    const auto mode = mention::matchingTypes("mode", types, aliases, {});
    CHECK(mode.size() == 2 && mode[0].id == "custom_ai" && mode[1].id == "custom_ref",
          "mention: prefix suggests every matching type");
    const auto refe = mention::matchingTypes("refe", types, aliases, {});
    CHECK(refe.size() == 1 && refe[0].id == "custom_ref", "mention: a word of the name matches");
    const auto recent = mention::matchingTypes("", types, aliases, { "note", "custom_ref" });
    CHECK(recent.size() == 4 && recent[0].id == "note" && recent[1].id == "custom_ref",
          "mention: recently used types first");
}

static void testMentionLabelRefresh()
{
    Run text;
    text.text = "ver ";
    Run mention;
    mention.text = "hydroge";
    mention.objectId = "obj-1";
    Run gone;
    gone.text = "old";
    gone.objectId = "missing";
    Blocks blocks = { Block::makeParagraph("b1", { text, mention, gone }) };
    auto titleOf = [](const QString &id) { return id == "obj-1" ? QString("hydrogen") : QString(); };
    CHECK(blockops::refreshMentionLabels(blocks, titleOf), "mention label: rename detected");
    CHECK(blocks[0].runs[1].text == "hydrogen" && blocks[0].runs[1].objectId == "obj-1",
          "mention label: follows the current title");
    CHECK(blocks[0].runs[2].text == "old", "mention label: unknown target keeps its label");
    CHECK(blocks[0].plainText() == "ver @hydrogen@old", "mention label: derived text updated");
    CHECK(!blockops::refreshMentionLabels(blocks, titleOf), "mention label: no change the second time");
}

static void testSplitMerge()
{
    Block todo;
    todo.id = "t1";
    todo.type = BlockType::Todo;
    Run r1;
    r1.text = "primero ";
    r1.bold = true;
    Run r2;
    r2.text = "segundo";
    todo.runs = { r1, r2 };

    const auto split = blockops::splitOnEnterAtOffset(todo, 8);
    CHECK(split.kind == blockops::SplitResult::Split, "split result");
    CHECK(runsToPlainText(split.updatedCurrent.runs) == "primero ", "first half");
    CHECK(split.updatedCurrent.runs.first().bold, "bold in first half");
    CHECK(runsToPlainText(split.newNext.runs) == "segundo", "second half");
    CHECK(split.newNext.type == BlockType::Todo && !split.newNext.checked, "next inherits todo unchecked");

    Block emptyTodo;
    emptyTodo.id = "t2";
    emptyTodo.type = BlockType::Todo;
    const auto exitList = blockops::splitOnEnterAtOffset(emptyTodo, 0);
    CHECK(exitList.kind == blockops::SplitResult::ExitList, "empty todo exits list");
    CHECK(exitList.updatedCurrent.type == BlockType::Paragraph, "converted to paragraph");

    Block toggle;
    toggle.id = "tk1";
    toggle.type = BlockType::Toggle;
    toggle.expanded = true;
    Run t;
    t.text = "toggle";
    toggle.runs = { t };
    const auto inside = blockops::splitOnEnterAtOffset(toggle, 6);
    CHECK(inside.kind == blockops::SplitResult::Split, "toggle split");
    CHECK(inside.newNext.type == BlockType::Paragraph && inside.newNext.indentLevel == 1,
          "open toggle child paragraph indent+1");
}

static void testBackspaceRules()
{
    Blocks blocks;
    Block p1 = Block::makeParagraph("a");
    Run r;
    r.text = "uno";
    p1.runs = { r };
    Block li;
    li.id = "b";
    li.type = BlockType::BulletList;
    Run r2;
    r2.text = "dos";
    li.runs = { r2 };
    li.indentLevel = 1;
    Block p2 = Block::makeParagraph("c");
    Run r3;
    r3.text = "tres";
    p2.runs = { r3 };
    blocks.append(p1);
    blocks.append(li);
    blocks.append(p2);

    const auto outdent = blockops::handleBackspaceAtStart(blocks, 1);
    CHECK(outdent.kind == blockops::BackspaceResult::Outdented, "backspace outdents indented");
    CHECK(outdent.demotedBlock.type == BlockType::BulletList, "type kept on outdent");

    const auto demoted = blockops::handleBackspaceAtStart(blocks, 1);
    // after outdent (indent 0): next backspace demotes type
    Blocks outdented = blocks;
    outdented[1].indentLevel = 0;
    const auto typeRule = blockops::handleBackspaceAtStart(outdented, 1);
    CHECK(typeRule.kind == blockops::BackspaceResult::DemotedToParagraph, "backspace demotes");
    CHECK(typeRule.demotedBlock.type == BlockType::Paragraph, "demoted to paragraph");

    Blocks merged = outdented;
    merged[1].type = BlockType::Paragraph;
    const auto merge = blockops::handleBackspaceAtStart(merged, 1);
    CHECK(merge.kind == blockops::BackspaceResult::MergeWithPrevious, "merges with previous");
    CHECK(runsToPlainText(merge.updatedBlocks.at(0).runs) == "unodos", "merged spans");
    CHECK(blocksToWireJson(blocks).size() > 0, "wire still serializes");
}

static void testMarkdownPrefixes()
{
    Block p = Block::makeParagraph("p1");
    Run r;
    r.text = "Hola";
    p.runs = { r };

    auto m1 = blockops::processMarkdownPrefix(p, "# Hola");
    CHECK(m1.kind == blockops::MarkdownResult::Transform, "heading transform");
    CHECK(m1.block.type == BlockType::Heading && m1.block.level == 1, "h1 by prefix");

    auto m2 = blockops::processMarkdownPrefix(p, "- Hola");
    CHECK(m2.kind == blockops::MarkdownResult::Transform && m2.block.type == BlockType::BulletList,
          "bullet prefix");

    auto m3 = blockops::processMarkdownPrefix(p, "[x] Hola");
    CHECK(m3.block.type == BlockType::Todo && m3.block.checked, "todo checked prefix");

    auto m4 = blockops::processMarkdownPrefix(p, "---");
    CHECK(m4.kind == blockops::MarkdownResult::Divider, "divider insertion");
    CHECK(m4.block.type == BlockType::Divider && m4.next.type == BlockType::Paragraph, "divider + next");

    auto m5 = blockops::processMarkdownPrefix(p, ">> Hola");
    CHECK(m5.block.type == BlockType::Paragraph && !m5.block.groupId.isEmpty(), "group prefix");
    CHECK(runsToPlainText(m5.block.runs) == "Hola", "group remainder");

    auto m6 = blockops::processMarkdownPrefix(p, "```\nkotlin");
    CHECK(m6.block.type == BlockType::Code && m6.block.language == "kotlin", "code prefix parse");
    auto m7 = blockops::processMarkdownPrefix(p, "$$");
    CHECK(m7.block.type == BlockType::Math, "math prefix");
}

static void testCodecLiveEditRoundTrip()
{
    // Runs -> HTML -> QTextDocument -> runs. Fidelity of the live edit loop.
    QVector<Run> runs;
    Run a;
    a.text = "Texto ";
    runs.append(a);
    Run b;
    b.text = "negrita";
    b.bold = true;
    b.textColor = "red";
    runs.append(b);
    Run c;
    c.text = " cód";
    c.code = true;
    runs.append(c);
    Run m;
    m.objectId = "obj-9";
    m.text = "Nota";
    runs.append(m);
    Run t;
    t.tagName = "work";
    t.tagId = "tag_work";
    runs.append(t);
    Run d;
    d.date = "2025-11-10";
    runs.append(d);
    Run h;
    h.text = "resaltado";
    h.highlight = true;
    runs.append(h);
    Run br;
    br.objectId = "obj-retro";
    br.blockId = "blk-42";
    br.text = "Retro";
    runs.append(br);

    const QString html = span::runsToHtml(runs);
    CHECK(html.contains("<b>"), "bold html");
    CHECK(html.contains("yaop://object/obj-9"), "mention anchor");

    QTextDocument doc;
    doc.setHtml(html);
    const QVector<Run> parsed = span::documentToRuns(&doc, QColor("#F2F2EC"));
    CHECK(runsToPlainText(parsed) == runsToPlainText(runs),
          "visible text identical after doc round trip");
    bool foundBold = false, foundMention = false, foundTag = false, foundDate = false,
         foundCode = false, foundHighlight = false, foundRed = false, foundBlockRef = false;
    for (const Run &r : parsed) {
        if (r.bold && r.text == "negrita")
            foundBold = true;
        if (r.bold && r.textColor == "red")
            foundRed = true;
        if (r.code)
            foundCode = true;
        if (r.objectId == "obj-9")
            foundMention = true;
        if (r.tagName == "work")
            foundTag = true;
        if (r.date == "2025-11-10")
            foundDate = true;
        if (r.highlight)
            foundHighlight = true;
        if (r.objectId == "obj-retro")
            foundBlockRef = r.blockId == "blk-42";
    }
    CHECK(foundBlockRef, "block reference keeps its block id through the live editor");
    CHECK(foundBold, "bold recovered");
    CHECK(foundRed, "named text color recovered");
    CHECK(foundCode, "code mark recovered");
    CHECK(foundMention, "mention recovered");
    CHECK(foundTag, "tag recovered");
    CHECK(foundDate, "date recovered");
    CHECK(foundHighlight, "highlight recovered");
}

static void testMarkdownImport()
{
    const Blocks imported = markdownToBlocks(QStringLiteral(
        "# Título\n\n- uno\n- dos\n\n1. primero\n2. segundo\n\n> cita\n\n```kotlin\nfun a() {}\n```\n\n$$\n\\alpha\n$$\n\n---\n\npara normal"));
    bool sawHeading = false, sawBullet = false, sawNumbered = false, sawQuote = false,
         sawCode = false, sawMath = false, sawDivider = false;
    for (const Block &b : imported) {
        if (b.type == BlockType::Heading) sawHeading = true;
        if (b.type == BlockType::BulletList) sawBullet = true;
        if (b.type == BlockType::NumberedList) sawNumbered = true;
        if (b.type == BlockType::Quote) sawQuote = true;
        if (b.type == BlockType::Code) sawCode = true;
        if (b.type == BlockType::Math) sawMath = true;
        if (b.type == BlockType::Divider) sawDivider = true;
    }
    CHECK(sawHeading && sawBullet && sawNumbered, "md heading/bullets/numbered");
    CHECK(sawQuote && sawCode && sawMath && sawDivider, "md quote/code/math/divider");
    CHECK(blocksToWireJson(imported).size() > 10, "imported blocks serialize");
}

static void testReorderSubtree()
{
    auto para = [](const QString &id, const QString &text, int indent = 0) {
        Block b = Block::makeParagraph(id);
        Run r;
        r.text = text;
        b.runs = { r };
        b.indentLevel = indent;
        return b;
    };
    Blocks blocks;
    blocks.append(para("a", "alpha"));
    blocks.append(para("b", "beta"));
    blocks.append(para("c", "gamma"));
    blocks.append(para("c1", "child", 1)); // child of gamma

    // move alpha (0) below gamma's subtree -> before index 4 (end)
    Blocks r1 = blockops::reorderSubtree(blocks, 0, 4);
    CHECK(runsToPlainText(r1[0].runs) == "beta" && runsToPlainText(r1[1].runs) == "gamma"
              && runsToPlainText(r1[2].runs) == "child" && runsToPlainText(r1[3].runs) == "alpha",
          "reorder alpha to end");

    // move gamma's subtree (index 2..3) to top
    Blocks r2 = blockops::reorderSubtree(blocks, 2, 0);
    CHECK(runsToPlainText(r2[0].runs) == "gamma" && runsToPlainText(r2[1].runs) == "child"
              && runsToPlainText(r2[2].runs) == "alpha" && runsToPlainText(r2[3].runs) == "beta",
          "reorder subtree to top keeps children");

    // no-op cases
    Blocks r3 = blockops::reorderSubtree(blocks, 2, 3);
    CHECK(r3.size() == blocks.size() && r3[2].id == "c", "drop inside itself is no-op");
    Blocks r4 = blockops::reorderSubtree(blocks, 1, 1);
    CHECK(r4[1].id == "b", "drop on itself is no-op");
}

static void testColorArtifactNormalization()
{
    // Wire runs carrying theme artifacts (pure black/white) must not serialize
    // as explicit colors into the live document HTML (invisible on dark bg).
    QJsonArray arr;
    QJsonObject r;
    r.insert("text", "hola");
    r.insert("text_color", "#000000");
    arr.append(r);
    const QVector<Run> runs = runsFromJson(arr);
    CHECK(runs.size() == 1 && runs.first().textColor.isEmpty(), "#000000 text_color dropped at decode");
    const QString html = span::runsToHtml(runs);
    CHECK(!html.contains("#000000"), "no black color in html");

    // documentToRuns must ignore the implicit QBrush() default (opaque black):
    // a plain fragment without ForegroundBrush keeps the base color.
    QTextDocument doc;
    doc.setPlainText(QStringLiteral("plain"));
    const QVector<Run> plain = span::documentToRuns(&doc, QColor("#F2F2EC"));
    CHECK(plain.size() == 1 && plain.first().textColor.isEmpty(),
          "plain fragment keeps base color (no black artifact)");

    // explicit non-base color survives
    QTextCursor c(&doc);
    c.select(QTextCursor::Document);
    QTextCharFormat f;
    f.setForeground(QColor("#DDEA4D"));
    c.mergeCharFormat(f);
    const QVector<Run> colored = span::documentToRuns(&doc, QColor("#F2F2EC"));
    CHECK(colored.size() == 1 && !colored.first().textColor.isEmpty()
              && colored.first().textColor != QLatin1String("#000000"),
          "explicit color preserved");

    // Soft line breaks (Shift+Enter) round-trip as '\n' <-> '<br>' inside the same block.
    Run multi;
    multi.text = QStringLiteral("line one\nline two");
    const QString multiHtml = span::runsToHtml({ multi });
    CHECK(multiHtml.contains(QLatin1String("<br>")), "soft newline encodes to <br>");
    QTextDocument multiDoc;
    multiDoc.setHtml(multiHtml);
    const QVector<Run> multiBack = span::documentToRuns(&multiDoc, QColor("#F2F2EC"));
    CHECK(runsToPlainText(multiBack) == QLatin1String("line one\nline two"),
          "soft newline round-trips through QTextDocument as '\\n'");
}

static void testConformanceHlc()
{
    for (const auto &fx : loadFixtures(QStringLiteral("hlc"))) {
        for (const QJsonValue &v : fx.second.value(QLatin1String("cases")).toArray()) {
            const QJsonObject c = v.toObject();
            const QString a = c.value(QLatin1String("a")).toString();
            const QString b = c.value(QLatin1String("b")).toString();
            const int want = c.value(QLatin1String("cmp")).toInt();
            const QByteArray label = (fx.first + QLatin1Char('/') + c.value(QLatin1String("name")).toString()).toUtf8();
            CHECK(compareHlc(a, b) == want, label.constData());
            CHECK(compareHlc(b, a) == -want, label.constData());
        }
        for (const QJsonValue &v : fx.second.value(QLatin1String("nodes")).toArray()) {
            const QJsonObject n = v.toObject();
            setHlcNode(n.value(QLatin1String("device_id")).toString());
            const QString node = newHlc().section(QLatin1Char('-'), 2);
            CHECK(node == n.value(QLatin1String("node")).toString(), "hlc node derivation");
        }
    }
    setHlcNode(QStringLiteral("selftest"));
    const QString a = newHlc();
    const QString far = QStringLiteral("9999999999999-0005-ffffffff");
    const QString b = hlcReceive(far);
    CHECK(compareHlc(b, far) > 0, "receive moves the clock past a remote stamp ");
    CHECK(compareHlc(newHlc(), b) > 0, "clock stays monotonic after receive");
    CHECK(compareHlc(b, a) > 0, "receive never goes backwards");
}

static void testConformanceDocRoundTrip()
{
    // Cross-platform fidelity: parse → export must reproduce the canonical wire blocks exactly.
    for (const auto &fx : loadFixtures(QStringLiteral("doc"))) {
        // "input" (optional, non-canonical) must normalize to "blocks"; otherwise blocks → blocks.
        const QJsonArray in = fx.second.value(QLatin1String("blocks")).toArray();
        const QJsonArray src = fx.second.contains(QLatin1String("input"))
                                   ? fx.second.value(QLatin1String("input")).toArray() : in;
        const QJsonArray out = QJsonDocument::fromJson(blocksToWireJson(blocksFromWireJson(src)).toUtf8()).array();
        const QByteArray dump = qgetenv("YAOP_ROUNDTRIP_DUMP");
        if (!dump.isEmpty()) {
            // Differential mode over a private corpus: write outputs, never print content.
            QDir().mkpath(QString::fromLocal8Bit(dump));
            QFile f(QString::fromLocal8Bit(dump) + QLatin1Char('/') + fx.first);
            if (f.open(QIODevice::WriteOnly))
                f.write(QJsonDocument(out).toJson(QJsonDocument::Compact));
            continue;
        }
        const bool ok = in == out;
        if (!ok) {
            for (int i = 0; i < qMax(in.size(), out.size()); ++i) {
                const QJsonValue a = in.at(i), b = out.at(i);
                if (a != b)
                    std::printf("  %s[%d]\n    want %s\n    got  %s\n", qPrintable(fx.first), i,
                                QJsonDocument(a.toObject()).toJson(QJsonDocument::Compact).constData(),
                                QJsonDocument(b.toObject()).toJson(QJsonDocument::Compact).constData());
            }
        }
        CHECK(ok, qPrintable(QStringLiteral("doc roundtrip ") + fx.first));
    }
}

static void testConformanceLinks()
{
    for (const auto &fx : loadFixtures(QStringLiteral("links"))) {
        if (fx.second.contains(QLatin1String("cases"))) {
            for (const QJsonValue &v : fx.second.value(QLatin1String("cases")).toArray()) {
                const QJsonObject c = v.toObject();
                CHECK(tagKey(c.value(QLatin1String("name")).toString()) == c.value(QLatin1String("key")).toString(),
                      qPrintable(QStringLiteral("tagKey ") + c.value(QLatin1String("name")).toString()));
            }
            continue;
        }
        const QJsonObject obj = fx.second.value(QLatin1String("object")).toObject();
        QStringList want;
        for (const QJsonValue &v : fx.second.value(QLatin1String("expect_links")).toArray())
            want.append(v.toString());
        const QStringList got = deriveLinks(obj);
        if (got != want)
            std::printf("  links got %s\n  want %s\n", qPrintable(got.join(',')), qPrintable(want.join(',')));
        CHECK(got == want, qPrintable(QStringLiteral("links ") + fx.first));
    }
}

static void testConformanceObjectRoundTrip()
{
    // Whole-object fidelity (spec/object.md): title, rich title, properties, references.
    static const char *keys[] = { "id", "type_id", "title", "title_runs", "properties", "links", "property_links" };
    const QByteArray dump = qgetenv("YAOP_ROUNDTRIP_DUMP");
    for (const auto &fx : loadFixtures(QStringLiteral("object"))) {
        const QJsonObject in = fx.second.value(QLatin1String("object")).toObject();
        const QJsonObject out = Object::fromJson(in).toJson(true);
        if (!dump.isEmpty()) {
            QDir().mkpath(QString::fromLocal8Bit(dump));
            QFile f(QString::fromLocal8Bit(dump) + QLatin1Char('/') + fx.first);
            if (f.open(QIODevice::WriteOnly))
                f.write(QJsonDocument(out).toJson(QJsonDocument::Compact));
            continue;
        }
        for (const char *k : keys) {
            const QString key = QString::fromLatin1(k);
            QJsonValue a = in.value(key), b = out.value(key);
            if (key == QLatin1String("links") || key == QLatin1String("property_links")) {
                if (a.isUndefined()) a = QJsonArray();
                if (b.isUndefined()) b = QJsonArray();
            }
            if (a != b) {
                std::printf("  %s %s\n    want %s\n    got  %s\n", qPrintable(fx.first), k,
                            QJsonDocument(QJsonArray{ a }).toJson(QJsonDocument::Compact).constData(),
                            QJsonDocument(QJsonArray{ b }).toJson(QJsonDocument::Compact).constData());
                CHECK(false, qPrintable(QStringLiteral("object roundtrip ") + fx.first + QLatin1Char(' ') + key));
            }
        }
    }
}

static void testConformanceStamp()
{
    for (const auto &fx : loadFixtures(QStringLiteral("stamp"))) {
        const QJsonObject &o = fx.second;
        const bool hasPrev = o.value(QLatin1String("prev")).isObject();
        const QJsonObject prev = o.value(QLatin1String("prev")).toObject();
        QJsonObject prevMeta;
        for (const char *k : { "field_hlc", "block_hlc", "deleted_blocks", "block_anchor" })
            prevMeta.insert(QString::fromLatin1(k), prev.value(QString::fromLatin1(k)));
        QJsonObject meta;
        const QString now = o.value(QLatin1String("now")).toString();
        const bool changed =
            stampWire(hasPrev ? &prev : nullptr, o.value(QLatin1String("next")).toObject(),
                      prevMeta, now, meta);
        const QJsonObject want = o.value(QLatin1String("expect_meta")).toObject();
        for (const char *k : { "field_hlc", "block_hlc", "deleted_blocks" }) {
            const QString key = QString::fromLatin1(k);
            if (!jsonEq(meta.value(key).toObject(QJsonObject()),
                        want.value(key).toObject(QJsonObject())))
                CHECK(false, qPrintable(fx.first + QLatin1Char(' ') + key));
        }
        // block_anchor is compared only when the fixture spells the expectation,
        // exactly as the Go conformance suite does.
        if (want.contains(QLatin1String("block_anchor"))) {
            if (!jsonEq(meta.value(QLatin1String("block_anchor")).toObject(),
                        want.value(QLatin1String("block_anchor")).toObject()))
                CHECK(false, qPrintable(fx.first + QLatin1String(" block_anchor")));
        }
        const QString gotHlc = changed ? now : prev.value(QLatin1String("hlc")).toString();
        CHECK(gotHlc == o.value(QLatin1String("expect_hlc")).toString(),
              qPrintable(fx.first + QStringLiteral(" hlc")));
    }
}

static void checkExpect(const QString &label, const QJsonObject &got, const QJsonObject &expect)
{
    for (auto it = expect.begin(); it != expect.end(); ++it) {
        QJsonValue g = got.value(it.key());
        if (g.isUndefined())
            g = QJsonValue(QJsonObject{});
        if (!jsonEq(g, it.value()))
            CHECK(false, qPrintable(label + QLatin1Char(' ') + it.key()));
    }
}

static void testConformanceMerge()
{
    for (const auto &fx : loadFixtures(QStringLiteral("merge"))) {
        const QJsonObject &o = fx.second;
        const QJsonObject a = o.value(QLatin1String("a")).toObject();
        const QJsonObject b = o.value(QLatin1String("b")).toObject();
        const QJsonObject expect = o.value(QLatin1String("expect")).toObject();
        checkExpect(fx.first + QStringLiteral(" merge(a,b)"), mergeWire(a, b), expect);
        checkExpect(fx.first + QStringLiteral(" merge(b,a)"), mergeWire(b, a), expect);
    }
}

// Go fuzzObjCanon: project onto the convergent fields, normalize nil -> {}/[],
// canonical Compact JSON. Used for idempotency/associativity/convergence.
static QByteArray mergCanon(const QJsonObject &o)
{
    QJsonObject n;
    n.insert(QStringLiteral("created_at"), o.value(QLatin1String("created_at")));
    n.insert(QStringLiteral("deleted_blocks"), o.value(QLatin1String("deleted_blocks")).toObject());
    n.insert(QStringLiteral("field_hlc"), o.value(QLatin1String("field_hlc")).toObject());
    n.insert(QStringLiteral("hlc"), o.value(QLatin1String("hlc")));
    n.insert(QStringLiteral("id"), o.value(QLatin1String("id")));
    n.insert(QStringLiteral("block_anchor"),
             o.value(QLatin1String("block_anchor")).toObject());
    n.insert(QStringLiteral("block_hlc"), o.value(QLatin1String("block_hlc")).toObject());
    n.insert(QStringLiteral("links"), o.value(QLatin1String("links")).toArray());
    n.insert(QStringLiteral("properties"), o.value(QLatin1String("properties")));
    n.insert(QStringLiteral("property_links"), o.value(QLatin1String("property_links")).toArray());
    n.insert(QStringLiteral("title"), o.value(QLatin1String("title")));
    n.insert(QStringLiteral("title_runs"), o.value(QLatin1String("title_runs")).toArray());
    n.insert(QStringLiteral("type_id"), o.value(QLatin1String("type_id")));
    return QJsonDocument(n).toJson(QJsonDocument::Compact);
}


// spec/types.md: the baked-in standard types are the fixture, valid, and resolve labels per language.
static void testStandardTypes()
{
    const auto fx = loadFixtures(QStringLiteral("types"));
    CHECK(fx.size() == 1, "types fixture present");
    if (fx.isEmpty())
        return;
    const QJsonObject file = fx.first().second;
    CHECK(pkm::standardTypesDoc() == file, "baked-in :/types/standard.json equals spec/fixtures/types/standard.json");
    const QStringList problems = pkm::validateStandardTypes(file);
    CHECK(problems.isEmpty(), qPrintable(QStringLiteral("standard types valid: ") + problems.join(QStringLiteral("; "))));

    // Wire values shared with Kotlin (DefaultTypes) must not drift.
    auto optionIds = [](const QString &type, const QString &key) {
        QStringList ids;
        for (const QJsonValue &v : pkm::standardPropertyDefs(type, QStringLiteral("en"))) {
            const QJsonObject d = v.toObject();
            if (d.value(QLatin1String("key")).toString() == key)
                for (const QJsonValue &o : d.value(QLatin1String("options")).toArray())
                    ids << o.toObject().value(QLatin1String("id")).toString();
        }
        return ids;
    };
    CHECK(optionIds("task", "status") == QStringList({ "todo", "in_progress", "done" }), "task status ids");
    CHECK(optionIds("book", "medium") == QStringList({ "paperback", "hardcover", "ebook", "audiobook" }), "book medium ids");
    CHECK(optionIds("book", "status") == QStringList({ "to_read", "reading", "finished", "abandoned" }), "book status ids");
    CHECK(optionIds("web", "category").size() == 30 && optionIds("web", "topics").size() == 29, "web category/topic counts");
    CHECK(optionIds("image", "colors").size() == 21, "image colors count");

    // Labels: Spanish when asked, English fallback for an unknown language.
    const QJsonArray es = pkm::standardPropertyDefs(QStringLiteral("web"), QStringLiteral("es"));
    const QJsonArray xx = pkm::standardPropertyDefs(QStringLiteral("web"), QStringLiteral("xx"));
    QString esCat, xxCat;
    for (const QJsonValue &v : es) if (v.toObject().value("key").toString() == "category") esCat = v.toObject().value("name").toString();
    for (const QJsonValue &v : xx) if (v.toObject().value("key").toString() == "category") xxCat = v.toObject().value("name").toString();
    CHECK(esCat == QStringLiteral("Categoría") && xxCat == QStringLiteral("Category"), "property labels per language");
    CHECK(pkm::standardTypeLabel(QStringLiteral("web"), QStringLiteral("es")) == QStringLiteral("Enlace web"), "type label es");

    // Rule 5: a synced copy extends the standard type, never replaces a standard key.
    const QJsonArray extra{ QJsonObject{ { "key", "category" }, { "name", "Mine" }, { "kind", "text" } },
                            QJsonObject{ { "key", "my_prop" }, { "name", "My prop" }, { "kind", "text" } } };
    const QJsonArray merged = pkm::mergeStandardPropertyDefs(es, extra);
    CHECK(merged.size() == es.size() + 1 && merged.last().toObject().value("key").toString() == "my_prop",
          "synced type extends the standard definitions");
}


// Dominant colors use the ids of image.colors in spec/types.md and pick the largest areas.
static void testImageColors()
{
    QStringList fixtureIds;
    for (const QJsonValue &v : pkm::standardPropertyDefs(QStringLiteral("image"), QStringLiteral("en")))
        if (v.toObject().value("key").toString() == QLatin1String("colors"))
            for (const QJsonValue &o : v.toObject().value("options").toArray())
                fixtureIds << o.toObject().value("id").toString();
    CHECK(pkm::referenceColorIds() == fixtureIds, "reference colors match image.colors options, same order");

    QImage img(100, 100, QImage::Format_ARGB32);
    img.fill(Qt::white);
    QPainter p(&img);
    p.fillRect(0, 0, 60, 100, QColor(220, 30, 35));   // 60 % red
    p.fillRect(60, 0, 40, 40, QColor(20, 20, 20));    // 16 % black
    p.end();
    CHECK(pkm::dominantColorIds(img) == QStringList({ "red", "white", "black" }), "largest shares first");
    CHECK(pkm::dominantColorIds(img, 1) == QStringList({ "red" }), "max limits the result");

    QImage clear(10, 10, QImage::Format_ARGB32);
    clear.fill(Qt::transparent);
    CHECK(pkm::dominantColorIds(clear).isEmpty() && pkm::dominantColorIds(QImage()).isEmpty(),
          "transparent or null image has no colors");
}


// Duplicate: a new local object with fresh ids and no merge stamps; single-instance types refuse.
static void testDuplicateObject()
{
    Object src;
    src.id = QStringLiteral("orig");
    src.typeId = QStringLiteral("book");
    src.title = QStringLiteral("Notas inteligentes");
    src.hlc = QStringLiteral("0001");
    src.fieldHlc.insert(QStringLiteral("title"), QStringLiteral("0001"));
    src.trashedAt = QStringLiteral("2026-11-01T00:00:00Z");
    src.properties.insert(QStringLiteral("pinned"), true);
    src.properties.insert(QStringLiteral("genre"), QJsonArray{ QStringLiteral("essay") });
    Block a = Block::makeParagraph(QStringLiteral("b1"));
    a.groupId = QStringLiteral("g1");
    Block b = Block::makeParagraph(QStringLiteral("b2"));
    b.groupId = QStringLiteral("g1");
    src.blocks = { a, b, Block::makeParagraph(QStringLiteral("b3")) };

    int n = 0;
    const Object d = pkm::duplicateObject(src, QStringLiteral("copy"), QStringLiteral("2026-11-22T10:00:00Z"),
                                          [&n] { return QStringLiteral("n%1").arg(++n); });
    CHECK(d.id == QLatin1String("copy") && d.title == src.title && d.typeId == src.typeId, "duplicate keeps title and type");
    CHECK(d.hlc.isEmpty() && d.fieldHlc.isEmpty() && d.trashedAt.isEmpty() && d.syncStatus == QLatin1String("local"),
          "duplicate has no stamps, is alive and local");
    CHECK(!d.properties.contains(QStringLiteral("pinned")) && d.properties.value("genre") == src.properties.value("genre"),
          "duplicate is not pinned and keeps properties");
    CHECK(d.blocks.size() == 3 && d.blocks[0].id != QLatin1String("b1") && d.blocks[0].groupId == d.blocks[1].groupId
              && d.blocks[0].groupId != QLatin1String("g1") && d.blocks[2].groupId.isEmpty(),
          "duplicate gets new block ids and one new id per group");
    Object img = src;
    img.typeId = QStringLiteral("image");
    CHECK(pkm::duplicateObject(img, QStringLiteral("x"), QString(), [] { return QStringLiteral("y"); }).id.isEmpty(),
          "media, journal and tag objects are not duplicated");
}


// spec/fixtures/schema/change_type.json: same loose keys and errors as Go and Kotlin.
static void testConformanceChangeType()
{
    int ran = 0;
    for (const auto &fx : loadFixtures(QStringLiteral("schema"))) {
        for (const QJsonValue &cv : fx.second.value(QLatin1String("cases")).toArray()) {
            const QJsonObject c = cv.toObject();
            const QString label = fx.first + QLatin1Char('/') + c.value(QLatin1String("name")).toString();
            const Object src = Object::fromJson(c.value(QLatin1String("object")).toObject());
            QStringList toKeys;
            for (const QJsonValue &k : c.value(QLatin1String("to_keys")).toArray())
                toKeys << k.toString();
            const pkm::TypeChange r = pkm::changeObjectType(src, c.value(QLatin1String("to")).toString(), toKeys);
            const QJsonObject expect = c.value(QLatin1String("expect")).toObject();
            ++ran;
            if (expect.contains(QLatin1String("error"))) {
                CHECK(r.error == expect.value(QLatin1String("error")).toString(), qPrintable(label));
                continue;
            }
            QStringList loose;
            for (const QJsonValue &k : expect.value(QLatin1String("loose")).toArray())
                loose << k.toString();
            CHECK(r.error.isEmpty() && r.object.typeId == expect.value(QLatin1String("type_id")).toString(), qPrintable(label + QStringLiteral(": type")));
            CHECK(r.loose == loose, qPrintable(label + QStringLiteral(": loose ") + r.loose.join(QLatin1Char(','))));
            if (expect.contains(QLatin1String("properties"))) {
                const QJsonObject want = expect.value(QLatin1String("properties")).toObject();
                CHECK(r.object.properties == want && r.object.blocks.size() == src.blocks.size(),
                      qPrintable(label + QStringLiteral(": properties and blocks kept")));
            }
        }
    }
    CHECK(ran >= 6, "change_type fixture ran");
}


// Move blocks to another object: subtree travels, indents shift to 0, ids are kept.
static void testMoveBlocksToObject()
{
    auto para = [](const QString &id, int indent) { Block b = Block::makeParagraph(id); b.indentLevel = indent; return b; };
    Object a; a.id = QStringLiteral("A");
    a.blocks = { para("a1", 0), para("a2", 1), para("a3", 2), para("a4", 1), para("a5", 0) };
    Object b; b.id = QStringLiteral("B");
    b.blocks = { para("b1", 0) };
    const pkm::BlockMove r = pkm::moveBlocksToObject(a, b, { QStringLiteral("a2") }, [] { return QStringLiteral("new"); });
    QStringList fromIds, toIds; QList<int> toIndents;
    for (const Block &x : r.from.blocks) fromIds << x.id;
    for (const Block &x : r.to.blocks) { toIds << x.id; toIndents << x.indentLevel; }
    CHECK(r.moved == 2 && fromIds == QStringList({ "a1", "a4", "a5" }), "moved block takes its children out of the source");
    CHECK(toIds == QStringList({ "b1", "a2", "a3" }) && toIndents == QList<int>({ 0, 0, 1 }),
          "moved blocks land at the end of the target, shallowest at level 0");
    const pkm::BlockMove all = pkm::moveBlocksToObject(a, b, { QStringLiteral("a1"), QStringLiteral("a5") }, [] { return QStringLiteral("new"); });
    CHECK(all.moved == 5 && all.from.blocks.size() == 1 && all.from.blocks[0].text.isEmpty() && all.from.blocks[0].id == QLatin1String("new"),
          "emptying the source leaves one empty paragraph");
    CHECK(pkm::moveBlocksToObject(a, a, { QStringLiteral("a1") }, [] { return QStringLiteral("new"); }).moved == 0, "moving into the same object does nothing");
}


// Graph: undirected edges from links and property links; trashed, templates and filtered types out.
static void testGraph()
{
    auto obj = [](const QString &id, const QString &type, QStringList links, QStringList props = {}) {
        Object o; o.id = id; o.typeId = type; o.title = id; o.links = links; o.propertyLinks = props; return o;
    };
    QHash<QString, Object> all;
    all.insert("day", obj("day", "journal", { "ana", "book", "day" }));
    all.insert("book", obj("book", "book", { "ana" }, { "tag_pkm" }));
    all.insert("ana", obj("ana", "person", { "day" }));
    all.insert("tag_pkm", obj("tag_pkm", "tag", {}));
    all.insert("far", obj("far", "note", { "tag_pkm" }));
    Object gone = obj("gone", "note", { "ana" }); gone.trashedAt = "2026-11-01T00:00:00Z"; all.insert("gone", gone);
    all.insert("ghost", obj("ghost", "note", { "missing" }));

    const pkm::Graph g = pkm::buildGraph(all);
    QStringList nodes; for (const auto &n : g.nodes) nodes << n.id;
    QStringList edges; for (const auto &e : g.edges) edges << e.from + "-" + e.to;
    CHECK(nodes == QStringList({ "ana", "book", "day", "far", "ghost", "tag_pkm" }), "graph nodes: alive objects, sorted");
    CHECK(edges == QStringList({ "ana-book", "ana-day", "book-day", "book-tag_pkm", "far-tag_pkm" }),
          "graph edges: undirected, once, no self or dangling links");
    int bookDegree = 0; for (const auto &n : g.nodes) if (n.id == "book") bookDegree = n.degree;
    CHECK(bookDegree == 3, "degree counts distinct neighbours");

    const pkm::Graph local = pkm::localGraph(g, "day", 1);
    QStringList ln; for (const auto &n : local.nodes) ln << n.id;
    CHECK(ln == QStringList({ "ana", "book", "day" }) && local.edges.size() == 3, "local graph depth 1 with edges among them");
    QStringList l2; for (const auto &n : pkm::localGraph(g, "day", 2).nodes) l2 << n.id;
    CHECK(l2.contains("tag_pkm") && !l2.contains("far"), "local graph depth 2");
    CHECK(pkm::localGraph(g, "nope").nodes.isEmpty(), "local graph of a missing node is empty");

    const pkm::Graph people = pkm::buildGraph(all, { "person", "journal" });
    CHECK(people.nodes.size() == 2 && people.edges.size() == 1, "type filter keeps only those types");

    // the direct local graph gives exactly what building the whole graph does
    auto same = [](const pkm::Graph &a, const pkm::Graph &b) {
        if (a.nodes.size() != b.nodes.size() || a.edges.size() != b.edges.size()) return false;
        for (int i = 0; i < a.nodes.size(); ++i)
            if (a.nodes[i].id != b.nodes[i].id || a.nodes[i].degree != b.nodes[i].degree) return false;
        for (int i = 0; i < a.edges.size(); ++i)
            if (a.edges[i].from != b.edges[i].from || a.edges[i].to != b.edges[i].to) return false;
        return true;
    };
    for (const QString &focus : { "day", "book", "far", "ghost", "nope", "gone" })
        for (int depth : { 1, 2, 3 })
            CHECK(same(pkm::localGraphOf(all, focus, depth), pkm::localGraph(g, focus, depth)),
                  qPrintable(QStringLiteral("localGraphOf matches localGraph for %1 depth %2").arg(focus).arg(depth)));
    const QHash<QString, QStringList> ov{ { "far", { "ana" } } };
    CHECK(same(pkm::localGraphOf(all, "ana", 1, {}, ov), pkm::localGraph(pkm::buildGraph(all, {}, ov), "ana", 1)),
          "localGraphOf honours link overrides");
    CHECK(same(pkm::localGraphOf(all, "day", 1, { "person", "journal" }), pkm::localGraph(people, "day", 1)),
          "localGraphOf honours the type filter");
}


// Unlinked mentions: whole-word title/alias matches in plain runs of other objects; linking splits the run.
static void testCollectionsAndTemplates()
{
    namespace col = pkm::collection;
    const QStringList a = col::add(col::add({}, "x"), "y");
    CHECK(a == QStringList({ "x", "y" }) && col::add(a, "x") == a && col::add(a, "") == a, "collection add keeps order, no repeats");
    CHECK(col::remove(a, "x") == QStringList({ "y" }), "collection remove");
    CHECK(col::move({ "a", "b", "c" }, 0, 2) == QStringList({ "b", "c", "a" })
              && col::move({ "a", "b", "c" }, 2, 0) == QStringList({ "c", "a", "b" })
              && col::move({ "a", "b" }, 5, 0) == QStringList({ "a", "b" })
              && col::move({ "a", "b", "c" }, 0, 99) == QStringList({ "b", "c", "a" }),
          "collection move clamps and ignores bad indexes");
    CHECK(col::clean({ "a", "a", "gone", "", "b" }, [](const QString &id) { return id != "gone"; }) == QStringList({ "a", "b" }),
          "collection clean drops repeats, blanks and dead ids");

    Object tpl; tpl.id = "t"; tpl.typeId = TypeId::Book; tpl.title = "Book template";
    tpl.properties.insert("is_template", true);
    tpl.properties.insert("status", "to_read");
    tpl.properties.insert("rating", 3);
    tpl.properties.insert("tags", QJsonArray{ "reading" });
    Block h = Block::makeParagraph("h"); h.type = BlockType::Heading; h.level = 2; h.text = "Key ideas";
    tpl.blocks = { h, Block::makeParagraph("p") };
    Object target; target.id = "n"; target.typeId = TypeId::Book; target.title = "Dune";
    target.properties.insert("rating", 5);
    target.properties.insert("tags", QJsonArray{ "scifi", "Reading" });
    target.blocks = { Block::makeParagraph("empty") };
    int n = 0;
    const Object out = pkm::applyTemplate(target, tpl, [&n] { return QStringLiteral("nb%1").arg(++n); });
    CHECK(out.title == "Dune" && out.id == "n", "template keeps the target's id and title");
    CHECK(out.properties.value("status").toString() == "to_read" && out.properties.value("rating").toInt() == 5,
          "template fills missing properties only");
    CHECK(!out.properties.contains("is_template"), "the copy is not a template");
    CHECK(out.propertyStringList("tags") == QStringList({ "scifi", "Reading" }), "tags merge case-insensitively");
    CHECK(out.blocks.size() == 2 && out.blocks[0].id == "nb1" && out.blocks[0].type == QLatin1String(BlockType::Heading),
          "an empty page takes the template's blocks with new ids");
    Object busy = target; busy.blocks[0].text = "my notes";
    busy.blocks[0].runs = {}; Run r; r.text = "my notes"; busy.blocks[0].runs << r;
    CHECK(pkm::applyTemplate(busy, tpl, [&n] { return QStringLiteral("nb%1").arg(++n); }).blocks.size() == 3,
          "a page with text keeps it and gets the template after");
}

static void testTextStats()
{
    auto para = [](const QString &id, QVector<Run> runs) { Block b = Block::makeParagraph(id); b.runs = runs; b.text = runsToPlainText(runs); return b; };
    Run a; a.text = QStringLiteral("Hola mundo. ¿Qué tal estás?");
    Run m; m.text = QStringLiteral("Ana"); m.objectId = QStringLiteral("ana");
    Run b; b.text = QStringLiteral(" sin punto");
    Block math = Block::makeParagraph("m"); math.type = BlockType::Math; math.latex = QStringLiteral("x^2 + y");
    Block embed = Block::makeParagraph("e"); embed.type = BlockType::Embed; embed.objectId = QStringLiteral("book");
    const QVector<Block> blocks = { para("1", { a }), para("2", { m, b }), para("3", {}), math, embed,
                                    para("4", { m }) };
    const pkm::TextStats s = pkm::textStats(blocks);
    CHECK(s.words == 9, "words count letters and digits, skip math");
    CHECK(s.sentences == 4, "sentences end in . ! ? or at the end of a paragraph");
    CHECK(s.paragraphs == 3, "empty blocks and math are not paragraphs");
    CHECK(s.characters == blocks[0].plainText().size() + blocks[1].plainText().size() + blocks[5].plainText().size(),
          "characters of the text, mentions as they read");
    CHECK(pkm::containedObjectIds(blocks, "self") == QStringList({ "ana", "book" }), "mentions and embeds, first seen first, no repeats");
    CHECK(pkm::containedObjectIds(blocks, "ana") == QStringList({ "book" }), "an object never contains itself");
}

static void testOptionNeighborhood()
{
    QHash<QString, Object> all;
    auto web = [&](const QString &id, const QString &cat, QJsonArray topics, QJsonArray tags) {
        Object o; o.id = id; o.typeId = TypeId::Web;
        o.properties.insert("category", cat);
        o.properties.insert("topics", topics);
        o.properties.insert("tags", tags);
        all.insert(id, o);
    };
    web("a", "video", { "programming", "design" }, { "pkm" });
    web("b", "Video", { "programming" }, {});
    web("c", "tutorial", { "programming" }, { "pkm" });
    Object gone = all["a"]; gone.id = "z"; gone.trashedAt = "2026-11-01T00:00:00Z"; all.insert("z", gone);
    Object book; book.id = "k"; book.typeId = TypeId::Book; book.properties.insert("category", "video"); all.insert("k", book);

    const auto n = pkm::optionNeighborhood(all, TypeId::Web, "category", "video", { "category", "topics", "tags" });
    CHECK(n.objectIds == QStringList({ "a", "b" }), "select value matches case-insensitively; trash and other types skipped");
    CHECK(n.related.size() == 3 && n.related[0].prop == "topics" && n.related[0].value == "programming"
              && n.related[0].objectIds == QStringList({ "a", "b" }),
          "most shared related value first");
    bool hasSelf = false;
    for (const auto &r : n.related) hasSelf = hasSelf || (r.prop == "category");
    CHECK(!hasSelf, "the centre value is not its own neighbour");

    const auto t = pkm::optionNeighborhood(all, TypeId::Web, "topics", "programming", { "category", "topics" });
    CHECK(t.objectIds == QStringList({ "a", "b", "c" }), "multi_select value matches inside the list");
    CHECK(!t.related.isEmpty() && t.related[0].prop == "category" && t.related[0].value.toLower() == "video"
              && t.related[0].objectIds.size() == 2,
          "related values merge case variants");
    CHECK(pkm::optionNeighborhood(all, TypeId::Web, "topics", "  ", { "topics" }).objectIds.isEmpty(), "empty value matches nothing");
}

static void testUnlinkedMentions()
{
    auto para = [](const QString &id, QVector<Run> runs) { Block b = Block::makeParagraph(id); b.runs = runs; b.text = runsToPlainText(runs); return b; };
    Run plain; plain.text = QStringLiteral("Hablé con ana lópez y con Anabel.");
    Run linked; linked.text = QStringLiteral("Ana López"); linked.objectId = QStringLiteral("ana");
    Run code; code.text = QStringLiteral("ana lópez"); code.code = true;
    QHash<QString, Object> all;
    Object ana; ana.id = "ana"; ana.title = QStringLiteral("Ana López"); all.insert(ana.id, ana);
    Object day; day.id = "day"; day.blocks = { para("b1", { plain }), para("b2", { linked }), para("b3", { code }) }; all.insert(day.id, day);
    Object trashed = day; trashed.id = "old"; trashed.trashedAt = "2026-11-01T00:00:00Z"; all.insert(trashed.id, trashed);

    const QVector<pkm::UnlinkedMention> found = pkm::findUnlinkedMentions(all, QStringLiteral("ana"));
    CHECK(found.size() == 1 && found[0].objectId == QLatin1String("day") && found[0].blockId == QLatin1String("b1")
              && found[0].runOffset == 10 && found[0].length == 9,
          "finds the whole-word, case-insensitive match only; skips mentions, code, trash and partial words");
    CHECK(!found.isEmpty() && found[0].snippet.contains(QStringLiteral("ana lópez")), "snippet shows the match");

    if (!found.isEmpty()) {
        const Block linkedBlock = pkm::linkUnlinkedMention(day.blocks[0], found[0], QStringLiteral("ana"));
        CHECK(linkedBlock.runs.size() == 3 && linkedBlock.runs[1].objectId == QLatin1String("ana")
                  && linkedBlock.runs[0].text == QStringLiteral("Hablé con ")
                  && linkedBlock.runs[2].text == QStringLiteral(" y con Anabel."),
              "linking splits the run around a mention");
    }
    Object shortName; shortName.id = "x"; shortName.title = QStringLiteral("AB"); all.insert("x", shortName);
    CHECK(pkm::findUnlinkedMentions(all, QStringLiteral("x")).isEmpty(), "names under 3 characters are ignored");
}


// Block reference counter: runs with object_id + block_id in alive objects.
static void testBlockReferenceCounts()
{
    auto ref = [](const QString &obj, const QString &blk) { Run r; r.objectId = obj; r.blockId = blk; r.text = "x"; return r; };
    Run plainMention; plainMention.objectId = "retro"; plainMention.text = "Retro";
    QHash<QString, Object> all;
    Object a; a.id = "a"; Block b1 = Block::makeParagraph("p1"); b1.runs = { ref("retro", "blk-1"), ref("retro", "blk-2"), plainMention }; a.blocks = { b1 };
    Object b; b.id = "b"; Block b2 = Block::makeParagraph("p2"); b2.runs = { ref("retro", "blk-1"), ref("other", "blk-1") }; b.blocks = { b2 };
    Object t = b; t.id = "t"; t.trashedAt = "2026-11-01T00:00:00Z";
    all.insert(a.id, a); all.insert(b.id, b); all.insert(t.id, t);
    const QHash<QString, int> c = pkm::blockReferenceCounts(all, "retro");
    CHECK(c.value("blk-1") == 2 && c.value("blk-2") == 1 && c.size() == 2,
          "counts block references per block; plain mentions, other objects and trash do not count");
}


// Version history rules (product §16.3).
static void testHistory()
{
    auto para = [](const QString &id, const QString &text) { Block b = Block::makeParagraph(id); Run r; r.text = text; b.runs = { r }; b.text = text; return b; };
    Object v1; v1.id = "o"; v1.typeId = "note"; v1.title = "Plan"; v1.blocks = { para("a", "uno"), para("b", "dos"), para("c", "tres") };
    v1.properties.insert("status", "draft");
    const QByteArray s1 = pkm::history::snapshotContent(v1);
    Object stamped = v1; stamped.hlc = "999"; stamped.updatedAt = "2026-11-22T10:00:00Z"; stamped.revision = 7;
    CHECK(!pkm::history::shouldSnapshot(s1, pkm::history::snapshotContent(stamped)), "stamps and sync state do not make a new snapshot");
    Object v2 = v1; v2.title = "Plan final"; v2.blocks = { para("a", "uno!"), para("c", "tres") }; v2.properties.insert("status", "done");
    CHECK(pkm::history::shouldSnapshot(s1, pkm::history::snapshotContent(v2)) && pkm::history::shouldSnapshot({}, s1),
          "content changes (or no snapshot yet) make a new snapshot");

    Object cur = v2; cur.hlc = "h2";
    const Object back = pkm::history::restoreObject(cur, s1);
    CHECK(back.id == "o" && back.hlc == "h2" && back.title == "Plan" && back.blocks.size() == 3
              && back.properties.value("status").toString() == QLatin1String("draft"),
          "restoring the object takes its content back and keeps id and stamps");

    const Object oneBlock = pkm::history::restoreBlock(cur, s1, "b");
    QStringList ids; for (const Block &b : oneBlock.blocks) ids << b.id;
    CHECK(ids == QStringList({ "a", "b", "c" }) && oneBlock.title == QLatin1String("Plan final")
              && oneBlock.blocks[0].text == QLatin1String("uno!"),
          "restoring a deleted block puts it back after its earlier neighbour, nothing else changes");
    const Object replaced = pkm::history::restoreBlock(cur, s1, "a");
    CHECK(replaced.blocks[0].text == QLatin1String("uno") && replaced.blocks.size() == 2, "restoring an existing block replaces its content");
    CHECK(pkm::history::restoreBlock(cur, s1, "zz").blocks.size() == 2, "unknown block leaves the object as it is");

    const QVector<pkm::history::SnapshotMeta> metas = { { 1, "2026-10-01T00:00:00.000Z" }, { 2, "2026-11-20T00:00:00.000Z" }, { 3, "garbage" } };
    CHECK(pkm::history::expiredSnapshots(metas, "2026-11-22T00:00:00.000Z") == QVector<qint64>({ 1 }),
          "snapshots older than 30 days expire; unreadable dates never do");
}

// spec/fixtures/query: same vault + query + today -> same ids, order and groups as Go and Kotlin.
static void testConformanceQuery()
{
    int ran = 0;
    for (const auto &fx : loadFixtures(QStringLiteral("query"))) {
        QVector<QJsonObject> objects;
        for (const QJsonValue &v : fx.second.value(QLatin1String("objects")).toArray())
            objects << v.toObject();
        const QString today = fx.second.value(QLatin1String("today")).toString();
        for (const QJsonValue &cv : fx.second.value(QLatin1String("cases")).toArray()) {
            const QJsonObject c = cv.toObject();
            const QString label = fx.first + QLatin1Char('/') + c.value(QLatin1String("name")).toString();
            const QJsonObject expect = c.value(QLatin1String("expect")).toObject();
            const pkm::query::Result r = pkm::query::evaluate(c.value(QLatin1String("query")).toObject(), objects, today);
            ++ran;
            if (expect.contains(QLatin1String("error"))) {
                CHECK(r.error == expect.value(QLatin1String("error")).toString(), qPrintable(label + QStringLiteral(": error ") + r.error));
                continue;
            }
            CHECK(r.error.isEmpty(), qPrintable(label + QStringLiteral(": unexpected error ") + r.error));
            QJsonObject got;
            if (expect.contains(QLatin1String("groups"))) {
                QJsonArray groups;
                for (const pkm::query::Group &g : r.groups)
                    groups.append(QJsonObject{ { "key", g.key ? QJsonValue(*g.key) : QJsonValue(QJsonValue::Null) },
                                               { "ids", QJsonArray::fromStringList(g.ids) } });
                got.insert(QStringLiteral("groups"), groups);
            } else {
                got.insert(QStringLiteral("ids"), QJsonArray::fromStringList(r.ids));
            }
            if (got != expect)
                std::printf("  got  %s\n  want %s\n", QJsonDocument(got).toJson(QJsonDocument::Compact).constData(),
                            QJsonDocument(expect).toJson(QJsonDocument::Compact).constData());
            CHECK(got == expect, qPrintable(label));
        }
    }
    CHECK(ran >= 56, "query: all fixture cases ran");

    // Scale probe (budget: first page of 10k results < 200 ms). Printed, not gated.
    QVector<QJsonObject> big;
    big.reserve(10000);
    static const char *const kStatus[] = { "not_started", "in_progress", "done" };
    for (int i = 0; i < 10000; ++i) {
        big << QJsonObject{ { "id", QStringLiteral("o%1").arg(i, 5, 10, QLatin1Char('0')) },
                            { "type_id", i % 4 ? QStringLiteral("task") : QStringLiteral("note") },
                            { "title", QStringLiteral("Object %1 title").arg(i) },
                            { "created_at", QStringLiteral("2026-11-%1T10:00:00Z").arg(1 + i % 28, 2, 10, QLatin1Char('0')) },
                            { "updated_at", QStringLiteral("2026-11-%1T%2:00:00Z").arg(1 + i % 28, 2, 10, QLatin1Char('0')).arg(i % 24, 2, 10, QLatin1Char('0')) },
                            { "links", QJsonArray{ QStringLiteral("o%1").arg((i * 7) % 10000, 5, 10, QLatin1Char('0')) } },
                            { "properties", QJsonObject{ { "status", QLatin1String(kStatus[i % 3]) },
                                                          { "due_date", QStringLiteral("2026-12-%1").arg(1 + i % 28, 2, 10, QLatin1Char('0')) },
                                                          { "tags", QJsonArray{ QStringLiteral("t%1").arg(i % 9) } },
                                                          { "rating", i % 5 } } } };
    }
    const QJsonObject heavy{ { "version", 1 },
                             { "filters", QJsonArray{ QJsonObject{ { "prop", "@title" }, { "op", "contains" }, { "value", "object" } } } },
                             { "sort", QJsonArray{ QJsonObject{ { "prop", "due_date" } }, QJsonObject{ { "prop", "@title" }, { "dir", "desc" } } } },
                             { "limit", 50 } };
    const QJsonObject kanban{ { "version", 1 }, { "types", QJsonArray{ "task" } }, { "group", QJsonObject{ { "prop", "status" } } } };
    QElapsedTimer t;
    t.start();
    const pkm::query::Result rh = pkm::query::evaluate(heavy, big, QStringLiteral("2026-11-12"));
    const qint64 msHeavy = t.restart();
    const pkm::query::Result rk = pkm::query::evaluate(kanban, big, QStringLiteral("2026-11-12"));
    const qint64 msKanban = t.elapsed();
    CHECK(rh.ids.size() == 50 && rk.groups.size() == 3, "query: scale probe returns results");
    std::printf("QUERY-BENCH 10k objects: contains+2 sorts+limit %lld ms, kanban group %lld ms\n",
                qlonglong(msHeavy), qlonglong(msKanban));
}

static void testConformanceMergeAssoc()
{
    for (const auto &fx : loadFixtures(QStringLiteral("merge_assoc"))) {
        const QJsonObject &o = fx.second;
        const QJsonObject a = o.value(QLatin1String("a")).toObject();
        const QJsonObject b = o.value(QLatin1String("b")).toObject();
        const QJsonObject c = o.value(QLatin1String("c")).toObject();
        const QJsonObject expect = o.value(QLatin1String("expect")).toObject();

        // idempotency: merge(x,x) == x for every device state
        CHECK(mergCanon(mergeWire(a, a)) == mergCanon(a), qPrintable(fx.first + " idempotencia a"));
        CHECK(mergCanon(mergeWire(b, b)) == mergCanon(b), qPrintable(fx.first + " idempotencia b"));
        CHECK(mergCanon(mergeWire(c, c)) == mergCanon(c), qPrintable(fx.first + " idempotencia c"));

        // every permutation, both groupings, plus the swapped fold: one result
        const QJsonObject perms[6][3] = {
            { a, b, c }, { a, c, b }, { b, a, c }, { b, c, a }, { c, a, b }, { c, b, a },
        };
        for (int i = 0; i < 6; ++i) {
            const QJsonObject &x = perms[i][0];
            const QJsonObject &y = perms[i][1];
            const QJsonObject &z = perms[i][2];
            const QByteArray g1 = mergCanon(mergeWire(mergeWire(x, y), z));
            const QByteArray g2 = mergCanon(mergeWire(x, mergeWire(y, z)));
            const QByteArray g3 = mergCanon(mergeWire(z, mergeWire(y, x)));
            const QString lbl = fx.first + QLatin1Char('/') + QChar(QLatin1Char('0' + i));
            if (g1 != g2) {
                std::printf("  %s not associative\n    left  %s\n    right %s\n", qPrintable(lbl),
                            g1.constData(), g2.constData());
                CHECK(false, qPrintable(lbl + " asociatividad"));
            }
            if (g1 != g3) {
                std::printf("  %s not convergent\n    g1 %s\n    g3 %s\n", qPrintable(lbl),
                            g1.constData(), g3.constData());
                CHECK(false, qPrintable(lbl + " convergencia"));
            }
            checkExpect(lbl, mergeWire(mergeWire(x, y), z), expect);
        }
    }
}

static void testObjectStampRoundTrip()
{
    // Object path used by Workspace::saveObject: an edit to one block stamps only that block.
    Object a;
    a.id = QStringLiteral("o1");
    a.title = QStringLiteral("T");
    Block b1 = Block::makeParagraph(QStringLiteral("b1"));
    Block b2 = Block::makeParagraph(QStringLiteral("b2"));
    a.blocks = { b1, b2 };
    CHECK(a.stamp(nullptr, QStringLiteral("0000000000001-0000-aaaaaaaa")), "new object stamps");
    CHECK(a.schemaVersion == 1, "schema_version 1 written on save (spec/object.md)");
    CHECK(a.blockAnchor.size() == 2, "new object anchors every block");
    CHECK(a.blockAnchor.value(QLatin1String("b1")).toObject().value(QLatin1String("after")).toString().isEmpty(),
          "first block anchored at the document start");
    CHECK(a.blockAnchor.value(QLatin1String("b2")).toObject().value(QLatin1String("after")).toString()
              == QLatin1String("b1"), "second block anchored after b1");
    Object b = Object::fromJson(a.toJson(false));
    CHECK(b.blockHlc == a.blockHlc && b.fieldHlc == a.fieldHlc, "stamps persist through local json");
    CHECK(b.blockAnchor == a.blockAnchor, "anchors persist through local json");
    CHECK(b.schemaVersion == 1, "schema_version persists through local json");
    Object c = b;
    c.blocks[1].text = QStringLiteral("edit");
    c.blocks[1].runs = { Run{} };
    c.blocks[1].runs[0].text = QStringLiteral("edit");
    CHECK(c.stamp(&b, QStringLiteral("0000000000002-0000-aaaaaaaa")), "edit detected");
    CHECK(c.blockHlc.value(QLatin1String("b1")).toString() == QLatin1String("0000000000001-0000-aaaaaaaa"), "untouched block keeps stamp");
    CHECK(c.blockHlc.value(QLatin1String("b2")).toString() == QLatin1String("0000000000002-0000-aaaaaaaa"), "edited block restamped");
    CHECK(c.blockAnchor == b.blockAnchor, "no re-anchor when the sequence is unchanged");
    Object d = c;
    CHECK(!d.stamp(&c, QStringLiteral("0000000000003-0000-aaaaaaaa")), "no-op save is not a change");
    Object e = c;
    std::swap(e.blocks[0], e.blocks[1]);
    CHECK(e.stamp(&c, QStringLiteral("0000000000004-0000-aaaaaaaa")), "reorder detected");
    CHECK(e.blockAnchor.value(QLatin1String("b2")).toObject().value(QLatin1String("after")).toString().isEmpty()
              && e.blockAnchor.value(QLatin1String("b2")).toObject().value(QLatin1String("hlc")).toString()
                  == QLatin1String("0000000000004-0000-aaaaaaaa"),
          "moved block re-anchored at the start");
    CHECK(e.blockAnchor.value(QLatin1String("b1")).toObject().value(QLatin1String("after")).toString()
              == QLatin1String("b2"), "follower re-anchored behind the moved block");
}

int runEditorTests()
{
    std::printf("== editor (model, blocks, merge, query) ==\n");
    const int before = failures;
    testRunRoundTrip();
    testBlockRoundTrip();
    testGroupWire();
    testSplitMerge();
    testBackspaceRules();
    testMentionLabelRefresh();
    testMentionTypeQuery();
    testMarkdownPrefixes();
    testCodecLiveEditRoundTrip();
    testMarkdownImport();
    testReorderSubtree();
    testColorArtifactNormalization();
    testConformanceHlc();
    testConformanceDocRoundTrip();
    testConformanceLinks();
    testConformanceObjectRoundTrip();
    testConformanceStamp();
    testConformanceMerge();
    testConformanceQuery();
    testStandardTypes();
    testImageColors();
    testDuplicateObject();
    testConformanceChangeType();
    testMoveBlocksToObject();
    testGraph();
    testUnlinkedMentions();
    testOptionNeighborhood();
    testTextStats();
    testCollectionsAndTemplates();
    testBlockReferenceCounts();
    testHistory();
    testConformanceMergeAssoc();
    testObjectStampRoundTrip();
    if (failures == before)
        std::printf("ok editor (model, blocks, merge, query)\n");
    return failures - before;
}

} // namespace pkm::testing
