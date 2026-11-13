#include "testing.h"
#include "blockops.h"
#include "merge.h"
#include "model.h"
#include "query.h"
#include "spancodec.h"
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

    const QString html = span::runsToHtml(runs);
    CHECK(html.contains("<b>"), "bold html");
    CHECK(html.contains("yaop://object/obj-9"), "mention anchor");

    QTextDocument doc;
    doc.setHtml(html);
    const QVector<Run> parsed = span::documentToRuns(&doc, QColor("#F2F2EC"));
    CHECK(runsToPlainText(parsed) == runsToPlainText(runs),
          "visible text identical after doc round trip");
    bool foundBold = false, foundMention = false, foundTag = false, foundDate = false,
         foundCode = false, foundHighlight = false, foundRed = false;
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
    }
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
    testConformanceMergeAssoc();
    testObjectStampRoundTrip();
    if (failures == before)
        std::printf("ok editor (model, blocks, merge, query)\n");
    return failures - before;
}

} // namespace pkm::testing
