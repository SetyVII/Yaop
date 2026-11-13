#include "model.h"
#include "blockops.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QRegularExpression>
#include <QTextBlock>
#include <QTextDocument>
#include <QTextFragment>
#include <QTextList>

namespace pkm {

namespace {
inline QString optStr(const QJsonObject &o, const char *key, const QString &def = {})
{
    const QJsonValue &v = o.value(QLatin1String(key));
    return v.isString() ? v.toString() : def;
}
inline bool optBool(const QJsonObject &o, const char *key, bool def = false)
{
    const QJsonValue &v = o.value(QLatin1String(key));
    return v.isBool() ? v.toBool() : def;
}
inline int optInt(const QJsonObject &o, const char *key, int def = 0)
{
    const QJsonValue &v = o.value(QLatin1String(key));
    return v.isDouble() ? int(v.toDouble()) : def;
}
inline double optDouble(const QJsonObject &o, const char *key, double def = 0)
{
    const QJsonValue &v = o.value(QLatin1String(key));
    return v.isDouble() ? v.toDouble() : def;
}
} // namespace

QString romanFromInt(int n)
{
    if (n <= 0)
        return QString::number(n);
    static const int values[] = { 1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1 };
    static const char *symbols[] = { "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I" };
    QString out;
    for (int i = 0; i < 13; ++i) {
        while (n >= values[i]) {
            n -= values[i];
            out += QLatin1String(symbols[i]);
        }
    }
    return out;
}

QString alphabeticFromInt(int n)
{
    if (n <= 0)
        return QStringLiteral("a");
    QString out;
    while (n > 0) {
        --n;
        out.prepend(QChar(QLatin1Char('a').unicode() + char16_t(n % 26)));
        n /= 26;
    }
    return out;
}

bool Block::isTextual() const
{
    return type == BlockType::Paragraph || type == BlockType::Heading || type == BlockType::BulletList
           || type == BlockType::NumberedList || type == BlockType::AlphabeticList
           || type == BlockType::RomanList || type == BlockType::Todo || type == BlockType::Toggle
           || type == BlockType::Quote || type == BlockType::Callout;
}

bool Block::isSequencedList() const
{
    return type == BlockType::NumberedList || type == BlockType::AlphabeticList
           || type == BlockType::RomanList;
}

QString Block::listKey() const
{
    if (type == BlockType::NumberedList)
        return BlockKey::Numbered;
    if (type == BlockType::AlphabeticList)
        return BlockKey::Alphabetic;
    if (type == BlockType::RomanList)
        return BlockKey::Roman;
    return {};
}

QString Block::plainText() const
{
    if (isTextual() || type == BlockType::Image || type == BlockType::Audio)
        return runsToPlainText(runs);
    if (type == BlockType::Code)
        return text;
    if (type == BlockType::Math)
        return latex;
    if (type == BlockType::Table) {
        QStringList lines;
        for (const QStringList &row : rows)
            lines.append(row.join(QStringLiteral(" | ")));
        return lines.join(QLatin1Char('\n'));
    }
    if (type == BlockType::Embed)
        return objectId;
    if (type == BlockType::Bookmark)
        return title.isEmpty() ? url : title;
    return {};
}

QVector<Run> Block::spans() const
{
    return runs;
}

Block Block::makeParagraph(const QString &id, const QVector<Run> &spans, int indent,
                          const QString &gid, const QString &color)
{
    Block b;
    b.id = id;
    b.type = BlockType::Paragraph;
    b.runs = spans;
    b.indentLevel = indent;
    b.groupId = gid;
    b.blockColor = color;
    return b;
}

// ---------------------------------------------------------------------------
// Wire JSON (single block)
// ---------------------------------------------------------------------------
QJsonObject blockToWireJson(const Block &b)
{
    QJsonObject o;
    o.insert("id", b.id);
    if (b.indentLevel > 0)
        o.insert("indent", qBound(1, b.indentLevel + 1, 8));
    if (!b.blockColor.isEmpty())
        o.insert("block_color", b.blockColor);

    const bool captionRuns = (b.type == BlockType::Image || b.type == BlockType::Audio);

    if (b.type == BlockType::Paragraph) {
        o.insert("type", QLatin1String("paragraph"));
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Heading) {
        o.insert("type", QLatin1String("heading"));
        o.insert("level", qBound(1, b.level, 4));
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::BulletList) {
        o.insert("type", QLatin1String("bullet_list"));
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::NumberedList) {
        o.insert("type", QLatin1String("numbered_list"));
        o.insert("number", b.number);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::AlphabeticList) {
        o.insert("type", QLatin1String("alphabetic_list"));
        o.insert("number", b.number);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::RomanList) {
        o.insert("type", QLatin1String("roman_list"));
        o.insert("number", b.number);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Todo) {
        o.insert("type", QLatin1String("todo"));
        o.insert("checked", b.checked);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Toggle) {
        o.insert("type", QLatin1String("toggle"));
        o.insert("expanded", b.expanded);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Quote) {
        o.insert("type", QLatin1String("quote"));
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Callout) {
        o.insert("type", QLatin1String("callout"));
        o.insert("callout_type", b.calloutType);
        o.insert("emoji", b.emoji.isEmpty() ? QStringLiteral("\U0001F4A1") : b.emoji);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Code) {
        o.insert("type", QLatin1String("code"));
        o.insert("language", b.language.isEmpty() ? QStringLiteral("kotlin") : b.language);
        o.insert("text", QString(b.text).replace(QChar(0x2028), QLatin1Char('\n')));
        QJsonArray arr;
        arr.append(QJsonObject{ { "text", b.text } });
        o.insert("runs", arr);
    } else if (b.type == BlockType::Math) {
        o.insert("type", QLatin1String("math"));
        o.insert("text", QStringLiteral("\uFFFC"));
        QJsonArray arr;
        arr.append(QJsonObject{
            { "text", QStringLiteral("\uFFFC") },
            { "type", QStringLiteral("math") },
            { "latex", b.latex },
            { "display", true } });
        o.insert("runs", arr);
    } else if (b.type == BlockType::Divider) {
        o.insert("type", QLatin1String("divider"));
        o.insert("text", QStringLiteral("\uFFFC"));
        o.insert("runs", QJsonArray());
    } else if (b.type == BlockType::Table) {
        o.insert("type", QLatin1String("table"));
        o.insert("text", QString());
        o.insert("runs", QJsonArray());
        if (!b.align.isEmpty()) {
            QJsonArray arr;
            for (const QString &a : b.align)
                arr.append(a);
            o.insert("align", arr);
        }
        QJsonArray rowsArr;
        for (const QStringList &row : b.rows) {
            QJsonArray rowArr;
            for (const QString &cell : row)
                rowArr.append(QJsonObject{ { "text", cell } });
            rowsArr.append(rowArr);
        }
        o.insert("rows", rowsArr);
    } else if (b.type == BlockType::Embed) {
        o.insert("type", QLatin1String("embed"));
        o.insert("object_id", b.objectId);
        o.insert("view_mode", b.viewMode.isEmpty() ? QStringLiteral("card") : b.viewMode);
        o.insert("text", b.objectId);
        o.insert("runs", QJsonArray());
    } else if (b.type == BlockType::Image) {
        o.insert("type", QLatin1String("image"));
        o.insert("asset_id", b.assetId);
        o.insert("width_percent", b.widthPercent > 0 ? qRound(b.widthPercent * 10000.0) / 10000.0 : 1.0);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Audio) {
        o.insert("type", QLatin1String("audio"));
        o.insert("asset_id", b.assetId);
        o.insert("duration_seconds", b.durationSeconds);
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    } else if (b.type == BlockType::Pdf) {
        o.insert("type", QLatin1String("pdf"));
        o.insert("asset_id", b.assetId);
        o.insert("page_count", b.pageCount > 0 ? b.pageCount : 1);
        o.insert("last_read_page", b.lastReadPage > 0 ? b.lastReadPage : 1);
        o.insert("file_name", b.fileName.isEmpty() ? QStringLiteral("document.pdf") : b.fileName);
        o.insert("text", b.fileName);
        o.insert("runs", QJsonArray());
    } else if (b.type == BlockType::Bookmark) {
        o.insert("type", QLatin1String("bookmark"));
        o.insert("url", b.url);
        o.insert("title", b.title);
        o.insert("description", b.description);
        o.insert("domain", b.domain);
        if (!b.thumbnailUrl.isEmpty())
            o.insert("thumbnail_url", b.thumbnailUrl);
        if (!b.faviconUrl.isEmpty())
            o.insert("favicon_url", b.faviconUrl);
        if (!b.objectId.isEmpty())
            o.insert("object_id", b.objectId);
        o.insert("text", b.title.isEmpty() ? b.url : b.title);
        o.insert("runs", QJsonArray());
    } else if (b.type == BlockType::Canvas) {
        o.insert("type", QLatin1String("canvas"));
        o.insert("canvas_data", b.canvasData.isObject()
                                   ? b.canvasData.toObject()
                                   : QJsonObject{ { "id", b.id } });
        o.insert("text", QStringLiteral("Whiteboard Canvas"));
        o.insert("runs", QJsonArray());
    } else {
        o.insert("type", QLatin1String("paragraph"));
        o.insert("text", runsToPlainText(b.runs));
        o.insert("runs", runsToJson(b.runs));
    }
    return o;
}

Block blockFromWireJson(const QJsonObject &obj, const QString &forcedGroupId, const QString &forcedTypeIn)
{
    Block b;
    b.id = optStr(obj, "id");
    if (b.id.isEmpty())
        b.id = newUuid();
    const QString type = forcedTypeIn.isEmpty() ? optStr(obj, "type", BlockType::Paragraph) : forcedTypeIn;
    const int rawIndent = optInt(obj, "indent", 1);
    b.indentLevel = qBound(0, rawIndent - 1, 6);
    b.blockColor = optStr(obj, "block_color");
    b.groupId = forcedGroupId;

    const QJsonArray runsArr = obj.value(QLatin1String("runs")).isArray()
                                   ? obj.value(QLatin1String("runs")).toArray()
                                   : QJsonArray();
    QString rawText = optStr(obj, "text");

    QVector<Run> spans;
    if (!runsArr.isEmpty()) {
        spans = runsFromJson(runsArr);
    } else {
        static const QString markers[] = { QStringLiteral("\u25CB "), QStringLiteral("\u2714 "),
                                           QStringLiteral("\u25B6 "), QStringLiteral("\u25BC ") };
        for (const QString &marker : markers) {
            if (rawText.startsWith(marker)) {
                rawText = rawText.mid(marker.size());
                break;
            }
        }
        Run plain;
        plain.text = rawText;
        spans = { plain };
    }
    b.runs = spans;
    b.text = rawText;

    if (type == BlockType::Heading) {
        b.type = BlockType::Heading;
        b.level = optInt(obj, "level", 1);
    } else if (type == BlockType::BulletList) {
        b.type = BlockType::BulletList;
    } else if (type == BlockType::NumberedList || type == BlockType::AlphabeticList
               || type == BlockType::RomanList) {
        b.type = type;
        b.number = optInt(obj, "number", 1);
    } else if (type == BlockType::Todo) {
        b.type = BlockType::Todo;
        b.checked = optBool(obj, "checked", rawText.startsWith(QChar(0x2714)));
    } else if (type == BlockType::Toggle) {
        b.type = BlockType::Toggle;
        b.expanded = optBool(obj, "expanded", !rawText.startsWith(QChar(0x25B6)));
    } else if (type == BlockType::Quote) {
        b.type = BlockType::Quote;
    } else if (type == BlockType::Callout) {
        b.type = BlockType::Callout;
        const QString ct = optStr(obj, "callout_type", "NOTE");
        b.calloutType = (ct == "INFO" || ct == "IDEA" || ct == "WARNING" || ct == "NOTE") ? ct : QStringLiteral("NOTE");
        b.emoji = optStr(obj, "emoji");
        if (b.emoji.isEmpty())
            b.emoji = QStringLiteral("\U0001F4A1");
    } else if (type == BlockType::Code) {
        b.type = BlockType::Code;
        b.text = QString(optStr(obj, "text")).replace(QChar(0x2028), QLatin1Char('\n'));
        b.language = optStr(obj, "language", "kotlin");
        if (b.language.isEmpty())
            b.language = QStringLiteral("kotlin");
        b.indentLevel = 0;
    } else if (type == BlockType::Math) {
        b.type = BlockType::Math;
        const QJsonValue first = runsArr.size() > 0 ? runsArr.at(0) : QJsonValue();
        QString latex;
        if (first.isObject())
            latex = first.toObject().value(QLatin1String("latex")).toString();
        if (latex.isEmpty())
            latex = QString(optStr(obj, "text")).remove(QStringLiteral("\uFFFC"));
        b.latex = latex;
        b.indentLevel = 0;
    } else if (type == BlockType::Divider) {
        b.type = BlockType::Divider;
        b.indentLevel = 0;
    } else if (type == BlockType::Table) {
        b.type = BlockType::Table;
        const QJsonValue rowsVal = obj.value(QLatin1String("rows"));
        b.rows.clear();
        if (rowsVal.isArray() && rowsVal.toArray().size() > 0) {
            for (const QJsonValue &rv : rowsVal.toArray()) {
                QStringList row;
                if (rv.isArray()) {
                    for (const QJsonValue &cv : rv.toArray()) {
                        if (cv.isObject())
                            row.append(cv.toObject().value(QLatin1String("text")).toString());
                        else if (cv.isString())
                            row.append(cv.toString());
                        else
                            row.append(QString());
                    }
                } else {
                    row = { QString(), QString() };
                }
                b.rows.append(row);
            }
        } else {
            b.rows = { { QString(), QString() }, { QString(), QString() }, { QString(), QString() } };
        }
        b.align.clear();
        const QJsonValue alignVal = obj.value(QLatin1String("align"));
        if (alignVal.isArray())
            for (const QJsonValue &av : alignVal.toArray())
                b.align.append(av.toString());
        b.indentLevel = 0;
    } else if (type == BlockType::Embed) {
        b.type = BlockType::Embed;
        b.objectId = optStr(obj, "object_id", optStr(obj, "text"));
        b.viewMode = optStr(obj, "view_mode", "card");
        b.indentLevel = 0;
    } else if (type == BlockType::Image) {
        b.type = BlockType::Image;
        b.assetId = optStr(obj, "asset_id");
        b.widthPercent = qBound(0.25, optDouble(obj, "width_percent", 1.0), 1.0);
        b.indentLevel = 0;
    } else if (type == BlockType::Audio) {
        b.type = BlockType::Audio;
        b.assetId = optStr(obj, "asset_id");
        b.durationSeconds = optInt(obj, "duration_seconds", 0);
        b.indentLevel = 0;
    } else if (type == BlockType::Pdf) {
        b.type = BlockType::Pdf;
        b.assetId = optStr(obj, "asset_id");
        b.pageCount = optInt(obj, "page_count", 1);
        b.lastReadPage = optInt(obj, "last_read_page", 1);
        b.fileName = optStr(obj, "file_name", "document.pdf");
        b.indentLevel = 0;
    } else if (type == BlockType::Bookmark) {
        b.type = BlockType::Bookmark;
        b.url = optStr(obj, "url");
        b.title = optStr(obj, "title");
        b.description = optStr(obj, "description");
        b.domain = optStr(obj, "domain");
        b.thumbnailUrl = optStr(obj, "thumbnail_url");
        b.faviconUrl = optStr(obj, "favicon_url");
        b.objectId = optStr(obj, "object_id");
        b.indentLevel = 0;
    } else if (type == BlockType::Canvas) {
        b.type = BlockType::Canvas;
        const QJsonValue cd = obj.value(QLatin1String("canvas_data"));
        b.canvasData = cd.isObject() ? cd : QJsonValue(QJsonObject{ { "id", b.id } });
    } else if (type == BlockType::Group) {
        // A bare group without children renders as a paragraph (mobile parity).
        b.type = BlockType::Paragraph;
    } else {
        b.type = BlockType::Paragraph;
    }
    return b;
}

// ---------------------------------------------------------------------------
// Blocks <-> wire (group pack/unpack)
// ---------------------------------------------------------------------------
QString blocksToWireJson(const Blocks &blocks)
{
    // Flat wire blocks (spec/text.md doc/3): grouping is a `group_id` on each block.
    QJsonArray arr;
    for (const Block &b : blocks) {
        QJsonObject o = blockToWireJson(b);
        if (!b.groupId.isEmpty())
            o.insert("group_id", b.groupId);
        arr.append(o);
    }
    return QString::fromUtf8(QJsonDocument(arr).toJson(QJsonDocument::Compact));
}

QByteArray blocksToWireJsonBytes(const Blocks &blocks)
{
    return blocksToWireJson(blocks).toUtf8();
}

Blocks blocksFromWireJson(const QString &raw)
{
    const QJsonDocument doc = QJsonDocument::fromJson(raw.toUtf8());
    if (!doc.isArray())
        return { Block::makeParagraph(newUuid()) };
    return blocksFromWireJson(doc.array());
}

Blocks blocksFromWireJson(const QJsonArray &arr)
{
    Blocks out;
    for (const QJsonValue &e : arr) {
        if (!e.isObject())
            continue;
        const QJsonObject item = e.toObject();
        const QString type = optStr(item, "type", BlockType::Paragraph);
        if (type == BlockType::Group) {
            QString gid = optStr(item, "id");
            if (gid.isEmpty())
                gid = newUuid();
            const QJsonValue kids = item.value(QLatin1String("children"));
            if (kids.isArray() && kids.toArray().size() > 0) {
                for (const QJsonValue &kv : kids.toArray()) {
                    if (!kv.isObject())
                        continue;
                    out.append(blockFromWireJson(kv.toObject(), gid));
                }
            } else {
                Block child = blockFromWireJson(item, gid, BlockType::Paragraph);
                out.append(child);
            }
        } else {
            out.append(blockFromWireJson(item, optStr(item, "group_id")));
        }
    }
    if (out.isEmpty())
        out.append(Block::makeParagraph(newUuid()));
    return out;
}

// ---------------------------------------------------------------------------
// Markdown export / import (content shadow)
// ---------------------------------------------------------------------------
QString markdownSpanText(const QVector<Run> &spans)
{
    QString out;
    for (const Run &span : normalizeRuns(spans)) {
        if (!span.objectId.isEmpty()) {
            const QString label = span.text.isEmpty() ? span.objectId : span.text;
            out += QStringLiteral("[[%1]]").arg(label);
            continue;
        }
        if (!span.tagId.isEmpty() || !span.tagName.isEmpty()) {
            const QString clean = span.tagName.startsWith(QLatin1Char('#')) ? span.tagName.mid(1) : span.tagName;
            out += QLatin1Char('#') + clean;
            continue;
        }
        if (!span.date.isEmpty()) {
            out += span.date;
            continue;
        }
        if (span.isMath) {
            out += QLatin1Char('$') + span.latex + QLatin1Char('$');
            continue;
        }
        QString text = span.text;
        if (span.code)
            text = QLatin1Char('`') + text + QLatin1Char('`');
        if (span.bold)
            text = "**" + text + "**";
        if (span.italic)
            text = QLatin1Char('*') + text + QLatin1Char('*');
        if (span.underline)
            text = "<u>" + text + "</u>";
        if (span.strike)
            text = "~~" + text + "~~";
        if (span.highlight)
            text = "==" + text + "==";
        if (!span.href.isEmpty())
            text = QStringLiteral("[%1](%2)").arg(text, span.href);
        out += text;
    }
    return out;
}

QString blocksToMarkdown(const Blocks &blocks)
{
    QStringList parts;
    for (const Block &b : blocks) {
        const QString indentPrefix = QStringLiteral("  ").repeated(qMax(0, b.indentLevel));
        const QString groupPrefix = b.groupId.isEmpty() ? QString() : QStringLiteral(">> ");
        QString raw;
        if (b.type == BlockType::Paragraph) {
            raw = groupPrefix + markdownSpanText(b.runs);
        } else if (b.type == BlockType::Heading) {
            const QString hashes = QStringLiteral("#").repeated(qBound(1, b.level, 4));
            raw = QStringLiteral("%1 %2").arg(hashes, markdownSpanText(b.runs));
        } else if (b.type == BlockType::Todo) {
            raw = QStringLiteral("- [%1] %2").arg(b.checked ? QStringLiteral("x") : QStringLiteral(" "),
                                                  markdownSpanText(b.runs));
        } else if (b.type == BlockType::BulletList) {
            raw = "- " + markdownSpanText(b.runs);
        } else if (b.type == BlockType::NumberedList) {
            raw = QStringLiteral("%1. %2").arg(b.number).arg(markdownSpanText(b.runs));
        } else if (b.type == BlockType::AlphabeticList) {
            raw = QStringLiteral("%1. %2").arg(alphabeticFromInt(b.number), markdownSpanText(b.runs));
        } else if (b.type == BlockType::RomanList) {
            raw = QStringLiteral("%1. %2").arg(romanFromInt(b.number).toLower(), markdownSpanText(b.runs));
        } else if (b.type == BlockType::Quote) {
            raw = "> " + markdownSpanText(b.runs);
        } else if (b.type == BlockType::Callout) {
            raw = QStringLiteral("> [!%1] %2\n> %3").arg(b.calloutType, b.emoji, markdownSpanText(b.runs));
        } else if (b.type == BlockType::Toggle) {
            raw = QStringLiteral("%1 %2").arg(QString(b.expanded ? QChar(0x25BC) : QChar(0x25B6)),
                                               markdownSpanText(b.runs));
        } else if (b.type == BlockType::Code) {
            raw = QStringLiteral("```%1\n%2\n```").arg(b.language, b.text);
        } else if (b.type == BlockType::Math) {
            raw = QStringLiteral("$$\n%1\n$$").arg(b.latex);
        } else if (b.type == BlockType::Divider) {
            raw = "---";
        } else if (b.type == BlockType::Image) {
            raw = QStringLiteral("![%1](/v1/assets/%2/content)")
                      .arg(runsToPlainText(b.runs), b.assetId);
        } else if (b.type == BlockType::Audio) {
            raw = QStringLiteral("[Audio (%1s)](/v1/assets/%2/content)").arg(b.durationSeconds).arg(b.assetId);
        } else if (b.type == BlockType::Pdf) {
            raw = QStringLiteral("[%1](/v1/assets/%2/content)").arg(b.fileName.isEmpty() ? QStringLiteral("PDF") : b.fileName, b.assetId);
        } else if (b.type == BlockType::Embed) {
            raw = QStringLiteral("[[%1]]").arg(b.objectId);
        } else if (b.type == BlockType::Bookmark) {
            raw = QStringLiteral("[%1](%2)").arg(b.title.isEmpty() ? b.url : b.title, b.url);
        } else if (b.type == BlockType::Table) {
            QStringList rowsMd;
            for (const QStringList &row : b.rows)
                rowsMd.append(QStringLiteral("| ") + row.join(QStringLiteral(" | ")) + QStringLiteral(" |"));
            raw = rowsMd.join(QLatin1Char('\n'));
        } else if (b.type == BlockType::Canvas) {
            const QJsonObject cd = b.canvasData.toObject();
            raw = QStringLiteral("```whiteboard\n%1 nodes, %2 connections\n```")
                      .arg(cd.value(QLatin1String("nodes")).toArray().size())
                      .arg(cd.value(QLatin1String("connectors")).toArray().size());
        }
        parts.append(indentPrefix.isEmpty() ? raw
                                            : QString(raw).replace(QLatin1Char('\n'),
                                                                   QLatin1String("\n") + indentPrefix));
    }
    return parts.join(QStringLiteral("\n\n"));
}

Blocks markdownToBlocks(const QString &md)
{
    if (md.trimmed().isEmpty())
        return { Block::makeParagraph(newUuid()) };

    const QStringList lines = md.split(QLatin1Char('\n'));
    Blocks out;
    int i = 0;
    int numberedCounter = 1, alphaCounter = 1, romanCounter = 1;
    QString activeGroupId;

    auto leadingSpaces = [](const QString &line) {
        int n = 0;
        for (const QChar c : line) {
            if (c == QLatin1Char('\t'))
                n += 2;
            else if (c == QLatin1Char(' '))
                n += 1;
            else
                break;
        }
        return n;
    };
    auto plainSpan = [](const QString &t) {
        return markdownLineToSpans(t);
    };

    static const QRegularExpression romanRe(QStringLiteral("^[ivxlcdmM]+\\.\\s+.*"));
    static const QRegularExpression alphaRe(QStringLiteral("^[a-zA-Z][.)]\\s+.*"));
    static const QRegularExpression numRe(QStringLiteral("^\\d+[.)]\\s+.*"));

    while (i < lines.size()) {
        const QString line = lines[i];
        const int indentLevel = qBound(0, leadingSpaces(line) / 2, 6);
        const QString trimmed = line.trimmed();
        if (!trimmed.startsWith(QStringLiteral(">> ")))
            activeGroupId.clear();

        if (trimmed.isEmpty()) {
            numberedCounter = alphaCounter = romanCounter = 1;
            ++i;
            continue;
        }
        if (trimmed.startsWith(QLatin1String("```")) && trimmed != QLatin1String("```whiteboard")) {
            const QString lang = trimmed.mid(3).trimmed().isEmpty() ? QStringLiteral("kotlin")
                                                                   : trimmed.mid(3).trimmed();
            QStringList codeLines;
            ++i;
            while (i < lines.size() && !lines[i].trimmed().startsWith(QLatin1String("```"))) {
                codeLines.append(lines[i]);
                ++i;
            }
            ++i;
            Block b;
            b.id = newUuid();
            b.type = BlockType::Code;
            b.language = lang;
            b.text = codeLines.join(QLatin1Char('\n'));
            out.append(b);
            continue;
        }
        if (trimmed == QLatin1String("$$")) {
            QStringList mathLines;
            ++i;
            while (i < lines.size() && lines[i].trimmed() != QLatin1String("$$")) {
                mathLines.append(lines[i]);
                ++i;
            }
            ++i;
            Block b;
            b.id = newUuid();
            b.type = BlockType::Math;
            b.latex = mathLines.join(QLatin1Char('\n'));
            out.append(b);
            continue;
        }
        if (trimmed == QLatin1String("---") || trimmed == QLatin1String("***")
            || trimmed == QLatin1String("___")) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Divider;
            out.append(b);
            ++i;
            continue;
        }
        const auto heading = [&](const QString &prefix, int level) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Heading;
            b.level = level;
            b.runs = plainSpan(trimmed.mid(prefix.size()));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        };
        if (trimmed.startsWith(QStringLiteral("#### ")))
            heading(QStringLiteral("#### "), 4);
        else if (trimmed.startsWith(QStringLiteral("### ")))
            heading(QStringLiteral("### "), 3);
        else if (trimmed.startsWith(QStringLiteral("## ")))
            heading(QStringLiteral("## "), 2);
        else if (trimmed.startsWith(QStringLiteral("# ")))
            heading(QStringLiteral("# "), 1);
        else if (trimmed.startsWith(QStringLiteral("- [x] "), Qt::CaseInsensitive)
                 || trimmed.startsWith(QStringLiteral("- [X] "))) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Todo;
            b.checked = true;
            b.runs = plainSpan(trimmed.mid(6));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (trimmed.startsWith(QStringLiteral("- [ ] "))) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Todo;
            b.checked = false;
            b.runs = plainSpan(trimmed.mid(6));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (trimmed.startsWith(QStringLiteral("\u25B6 ")) || trimmed.startsWith(QStringLiteral("\u25BC "))) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Toggle;
            b.expanded = trimmed.startsWith(QChar(0x25BC));
            b.runs = plainSpan(trimmed.mid(2));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (trimmed.startsWith(QStringLiteral("- ")) || trimmed.startsWith(QStringLiteral("* "))) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::BulletList;
            b.runs = plainSpan(trimmed.mid(2));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (romanRe.match(trimmed).hasMatch()) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::RomanList;
            b.number = romanCounter++;
            b.runs = plainSpan(QString(trimmed).replace(QRegularExpression(QStringLiteral("^[ivxlcdmM]+\\.\\s+")), QString()));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (alphaRe.match(trimmed).hasMatch()) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::AlphabeticList;
            b.number = alphaCounter++;
            b.runs = plainSpan(QString(trimmed).replace(QRegularExpression(QStringLiteral("^[a-zA-Z][.)]\\s+")), QString()));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (numRe.match(trimmed).hasMatch()) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::NumberedList;
            b.number = numberedCounter++;
            b.runs = plainSpan(QString(trimmed).replace(QRegularExpression(QStringLiteral("^\\d+[.)]\\s+")), QString()));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (trimmed.startsWith(QStringLiteral(">> "))) {
            if (activeGroupId.isEmpty())
                activeGroupId = newUuid();
            Block b = Block::makeParagraph(newUuid(), plainSpan(trimmed.mid(3)), indentLevel, activeGroupId);
            out.append(b);
            ++i;
        } else if (trimmed.startsWith(QStringLiteral("> [!"))) {
            const QString header = trimmed.mid(4);
            const QString typeStr = header.section(QLatin1Char(']'), 0, 0).trimmed();
            QString emoji = header.section(QLatin1Char(']'), 1).trimmed();
            if (emoji.isEmpty())
                emoji = QStringLiteral("\U0001F4A1");
            QString body;
            if (i + 1 < lines.size() && lines[i + 1].trimmed().startsWith(QLatin1String("> "))) {
                ++i;
                body = lines[i].trimmed().mid(2);
            }
            Block b;
            b.id = newUuid();
            b.type = BlockType::Callout;
            b.calloutType = (typeStr == "INFO" || typeStr == "IDEA" || typeStr == "WARNING") ? typeStr : QStringLiteral("NOTE");
            b.emoji = emoji;
            b.runs = plainSpan(body);
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else if (trimmed.startsWith(QStringLiteral("> "))) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Quote;
            b.runs = plainSpan(trimmed.mid(2));
            b.indentLevel = indentLevel;
            out.append(b);
            ++i;
        } else {
            Block b = Block::makeParagraph(newUuid(), plainSpan(trimmed), indentLevel);
            out.append(b);
            ++i;
        }
    }
    if (out.isEmpty())
        out.append(Block::makeParagraph(newUuid()));
    return out;
}

QVector<Run> markdownLineToSpans(const QString &line)
{
    if (line.isEmpty())
        return {};

    // Single-pass inline markdown scanner: [label](url), `code`, **bold**, *italic*,
    // ~~strike~~, ==highlight==, <u>underline</u>. Unclosed markers stay literal text.
    static const QRegularExpression re(
        QStringLiteral("\\[([^\\]\\n]+)\\]\\(([^\\)\\s]+)\\)"
                       "|`([^`\\n]+)`"
                       "|\\*\\*([^\\*\\n]+)\\*\\*"
                       "|~~([^~\\n]+)~~"
                       "|==([^=\\n]+)=="
                       "|<u>([^<\\n]+)</u>"
                       "|\\*([^\\*\\n]+)\\*"));

    QVector<Run> out;
    int pos = 0;
    auto it = re.globalMatch(line);
    while (it.hasNext()) {
        const QRegularExpressionMatch m = it.next();
        if (m.capturedStart() > pos) {
            Run plain;
            plain.text = line.mid(pos, m.capturedStart() - pos);
            out.append(plain);
        }
        Run r;
        if (!m.captured(1).isNull()) {
            r.text = m.captured(1);
            r.href = m.captured(2);
        } else if (!m.captured(3).isNull()) {
            r.text = m.captured(3);
            r.code = true;
        } else if (!m.captured(4).isNull()) {
            r.text = m.captured(4);
            r.bold = true;
        } else if (!m.captured(5).isNull()) {
            r.text = m.captured(5);
            r.strike = true;
        } else if (!m.captured(6).isNull()) {
            r.text = m.captured(6);
            r.highlight = true;
        } else if (!m.captured(7).isNull()) {
            r.text = m.captured(7);
            r.underline = true;
        } else if (!m.captured(8).isNull()) {
            r.text = m.captured(8);
            r.italic = true;
        }
        out.append(r);
        pos = m.capturedEnd();
    }
    if (pos < line.size()) {
        Run tail;
        tail.text = line.mid(pos);
        out.append(tail);
    }
    return normalizeRuns(out);
}

namespace {

QString semanticRunHtml(const Run &r)
{
    if (!r.objectId.isEmpty()) {
        const QString label = r.text.isEmpty() ? r.objectId : r.text;
        return QStringLiteral("<span>[[%1]]</span>").arg(label.toHtmlEscaped());
    }
    if (!r.tagId.isEmpty() || !r.tagName.isEmpty()) {
        const QString clean = r.tagName.startsWith(QLatin1Char('#')) ? r.tagName.mid(1) : r.tagName;
        return QStringLiteral("<span>#%1</span>").arg(clean.toHtmlEscaped());
    }
    if (!r.date.isEmpty())
        return r.date.toHtmlEscaped();
    if (r.isMath)
        return QStringLiteral("<code>$%1$</code>").arg(r.latex.toHtmlEscaped());

    QString t = r.text.toHtmlEscaped();
    t.replace(QLatin1Char('\n'), QStringLiteral("<br>"));
    t.replace(QChar(0x2028), QStringLiteral("<br>"));
    if (r.code)
        t = QStringLiteral("<code>%1</code>").arg(t);
    if (r.bold)
        t = QStringLiteral("<strong>%1</strong>").arg(t);
    if (r.italic)
        t = QStringLiteral("<em>%1</em>").arg(t);
    if (r.underline)
        t = QStringLiteral("<u>%1</u>").arg(t);
    if (r.strike)
        t = QStringLiteral("<s>%1</s>").arg(t);
    if (r.highlight)
        t = QStringLiteral("<mark>%1</mark>").arg(t);
    if (!r.href.isEmpty())
        t = QStringLiteral("<a href=\"%1\">%2</a>").arg(r.href.toHtmlEscaped(), t);
    return t;
}

QString semanticRunsHtml(const QVector<Run> &runs)
{
    QString out;
    for (const Run &r : normalizeRuns(runs))
        out += semanticRunHtml(r);
    return out;
}

QString listTagFor(const Block &b)
{
    if (b.type == BlockType::BulletList || b.type == BlockType::Todo)
        return QStringLiteral("ul");
    if (b.type == BlockType::NumberedList)
        return QStringLiteral("ol");
    if (b.type == BlockType::AlphabeticList)
        return QStringLiteral("ol type=\"a\"");
    if (b.type == BlockType::RomanList)
        return QStringLiteral("ol type=\"i\"");
    return {};
}

} // namespace

QString blocksToHtml(const Blocks &blocks)
{
    QString out;
    QString openListTag;
    auto closeList = [&]() {
        if (!openListTag.isEmpty()) {
            out += openListTag.startsWith(QLatin1String("ol")) ? QStringLiteral("</ol>\n")
                                                               : QStringLiteral("</ul>\n");
            openListTag.clear();
        }
    };

    for (const Block &b : blocks) {
        const QString listTag = listTagFor(b);
        if (!listTag.isEmpty()) {
            if (openListTag != listTag) {
                closeList();
                openListTag = listTag;
                out += QStringLiteral("<%1>\n").arg(listTag);
            }
            const QString body = semanticRunsHtml(b.runs);
            if (b.type == BlockType::Todo) {
                out += QStringLiteral("  <li><input type=\"checkbox\"%1 disabled> %2</li>\n")
                           .arg(b.checked ? QStringLiteral(" checked") : QString(), body);
            } else {
                out += QStringLiteral("  <li>%1</li>\n").arg(body);
            }
            continue;
        }
        closeList();

        if (b.type == BlockType::Heading) {
            const int lvl = qBound(1, b.level, 4);
            out += QStringLiteral("<h%1>%2</h%1>\n").arg(lvl).arg(semanticRunsHtml(b.runs));
        } else if (b.type == BlockType::Quote) {
            out += QStringLiteral("<blockquote><p>%1</p></blockquote>\n").arg(semanticRunsHtml(b.runs));
        } else if (b.type == BlockType::Callout) {
            out += QStringLiteral("<blockquote><p><strong>[%1] %2</strong> %3</p></blockquote>\n")
                       .arg(b.calloutType.toHtmlEscaped(), b.emoji.toHtmlEscaped(), semanticRunsHtml(b.runs));
        } else if (b.type == BlockType::Toggle) {
            out += QStringLiteral("<details%1><summary>%2</summary></details>\n")
                       .arg(b.expanded ? QStringLiteral(" open") : QString(), semanticRunsHtml(b.runs));
        } else if (b.type == BlockType::Code) {
            const QString langAttr = b.language.isEmpty()
                ? QString()
                : QStringLiteral(" class=\"language-%1\"").arg(b.language.toHtmlEscaped());
            out += QStringLiteral("<pre><code%1>%2</code></pre>\n").arg(langAttr, b.text.toHtmlEscaped());
        } else if (b.type == BlockType::Math) {
            out += QStringLiteral("<pre>$$%1$$</pre>\n").arg(b.latex.toHtmlEscaped());
        } else if (b.type == BlockType::Divider) {
            out += QStringLiteral("<hr>\n");
        } else if (b.type == BlockType::Table) {
            out += QStringLiteral("<table>\n");
            for (int r = 0; r < b.rows.size(); ++r) {
                out += QStringLiteral("  <tr>");
                const char *cellTag = r == 0 ? "th" : "td";
                for (const QString &cell : b.rows[r])
                    out += QStringLiteral("<%1>%2</%1>").arg(QLatin1String(cellTag), cell.toHtmlEscaped());
                out += QStringLiteral("</tr>\n");
            }
            out += QStringLiteral("</table>\n");
        } else if (b.type == BlockType::Bookmark) {
            const QString label = b.title.isEmpty() ? b.url : b.title;
            out += QStringLiteral("<p><a href=\"%1\">%2</a></p>\n").arg(b.url.toHtmlEscaped(), label.toHtmlEscaped());
        } else if (b.type == BlockType::Embed) {
            out += QStringLiteral("<p>[[%1]]</p>\n").arg(b.objectId.toHtmlEscaped());
        } else if (b.type == BlockType::Image) {
            const QString cap = runsToPlainText(b.runs);
            out += QStringLiteral("<p>%1</p>\n").arg((cap.isEmpty() ? QStringLiteral("[image]") : cap).toHtmlEscaped());
        } else if (b.type == BlockType::Audio) {
            out += QStringLiteral("<p>[audio %1s]</p>\n").arg(b.durationSeconds);
        } else if (b.type == BlockType::Pdf) {
            out += QStringLiteral("<p>[PDF: %1]</p>\n").arg((b.fileName.isEmpty() ? QStringLiteral("document") : b.fileName).toHtmlEscaped());
        } else if (b.type == BlockType::Canvas) {
            out += QStringLiteral("<p>[whiteboard]</p>\n");
        } else {
            out += QStringLiteral("<p>%1</p>\n").arg(semanticRunsHtml(b.runs));
        }
    }
    closeList();
    return out.trimmed();
}

Blocks htmlToBlocks(const QString &html)
{
    if (html.trimmed().isEmpty())
        return { Block::makeParagraph(newUuid()) };

    QTextDocument doc;
    doc.setHtml(html);

    Blocks out;
    int numCounter = 1, alphaCounter = 1, romanCounter = 1;
    QTextList *lastList = nullptr;

    for (QTextBlock tb = doc.begin(); tb.isValid(); tb = tb.next()) {
        const QTextBlockFormat bf = tb.blockFormat();
        if (bf.hasProperty(QTextFormat::BlockTrailingHorizontalRulerWidth)) {
            Block div;
            div.id = newUuid();
            div.type = BlockType::Divider;
            out.append(div);
            lastList = nullptr;
            continue;
        }

        // Extract runs from QTextFragments inside this QTextBlock
        QVector<Run> runs;
        bool allMono = true;
        bool hasChars = false;
        for (QTextBlock::iterator it = tb.begin(); !it.atEnd(); ++it) {
            const QTextFragment frag = it.fragment();
            if (!frag.isValid())
                continue;
            const QString txt = frag.text();
            if (txt.isEmpty())
                continue;
            hasChars = true;
            const QTextCharFormat cf = frag.charFormat();
            const bool mono = cf.fontFixedPitch()
                || cf.fontFamilies().toStringList().join(QLatin1Char(' ')).contains(QLatin1String("mono"), Qt::CaseInsensitive);
            if (!mono)
                allMono = false;
            Run r;
            r.text = QString(txt).replace(QChar(0x2028), QLatin1Char('\n'));
            r.bold = cf.fontWeight() >= QFont::Bold;
            r.italic = cf.fontItalic();
            r.underline = cf.fontUnderline();
            r.strike = cf.fontStrikeOut();
            r.code = mono;
            if (cf.isAnchor() && !cf.anchorHref().isEmpty())
                r.href = cf.anchorHref();
            if (cf.background().style() != Qt::NoBrush && cf.background().color().alpha() > 0)
                r.highlight = true;
            runs.append(r);
        }

        if (!hasChars && !tb.next().isValid() && !out.isEmpty())
            continue; // skip trailing empty paragraph Qt adds after lists/tables

        const int headingLvl = bf.headingLevel();
        QTextList *tl = tb.textList();
        if (tl != lastList) {
            numCounter = alphaCounter = romanCounter = 1;
            lastList = tl;
        }

        if (headingLvl > 0) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Heading;
            b.level = qBound(1, headingLvl, 4);
            b.runs = normalizeRuns(runs);
            out.append(b);
        } else if (tl) {
            const QTextListFormat::Style st = tl->format().style();
            Block b;
            b.id = newUuid();
            b.indentLevel = qBound(0, tl->format().indent() - 1, 6);
            if (st == QTextListFormat::ListDecimal) {
                b.type = BlockType::NumberedList;
                b.number = numCounter++;
            } else if (st == QTextListFormat::ListLowerAlpha || st == QTextListFormat::ListUpperAlpha) {
                b.type = BlockType::AlphabeticList;
                b.number = alphaCounter++;
            } else if (st == QTextListFormat::ListLowerRoman || st == QTextListFormat::ListUpperRoman) {
                b.type = BlockType::RomanList;
                b.number = romanCounter++;
            } else {
                b.type = BlockType::BulletList;
            }
            b.runs = normalizeRuns(runs);
            out.append(b);
        } else if (bf.hasProperty(QTextFormat::BlockCodeLanguage)
                   || (bf.nonBreakableLines() && allMono && hasChars)) {
            const QString lang = bf.stringProperty(QTextFormat::BlockCodeLanguage);
            const QString codeText = tb.text();
            if (!out.isEmpty() && out.last().type == BlockType::Code && out.last().language == lang) {
                out.last().text += QLatin1Char('\n') + codeText;
            } else {
                Block b;
                b.id = newUuid();
                b.type = BlockType::Code;
                b.language = lang;
                b.text = codeText;
                out.append(b);
            }
        } else if (bf.leftMargin() >= 20.0 && hasChars) {
            Block b;
            b.id = newUuid();
            b.type = BlockType::Quote;
            b.runs = normalizeRuns(runs);
            out.append(b);
        } else if (hasChars || out.isEmpty()) {
            out.append(Block::makeParagraph(newUuid(), normalizeRuns(runs)));
        }
    }

    if (out.isEmpty())
        out.append(Block::makeParagraph(newUuid()));
    return blockops::renumberLists(out);
}

} // namespace pkm
