#pragma once

// YAOP canonical wire model (1:1 parity with server internal/model and
// mobile core/model + sync/SyncContractCodec). Structured blocks, runs, objects.

#include <QJsonArray>
#include <QJsonObject>
#include <QJsonValue>
#include <QString>
#include <QStringList>
#include <QVector>

#include "merge.h"

namespace pkm {

// ---------------------------------------------------------------------------
// Canonical identifiers
// ---------------------------------------------------------------------------
namespace BlockType {
constexpr auto Paragraph = "paragraph";
constexpr auto Heading = "heading";
constexpr auto BulletList = "bullet_list";
constexpr auto NumberedList = "numbered_list";
constexpr auto AlphabeticList = "alphabetic_list";
constexpr auto RomanList = "roman_list";
constexpr auto Todo = "todo";
constexpr auto Toggle = "toggle";
constexpr auto Quote = "quote";
constexpr auto Callout = "callout";
constexpr auto Group = "group";
constexpr auto Code = "code";
constexpr auto Math = "math";
constexpr auto Divider = "divider";
constexpr auto Table = "table";
constexpr auto Embed = "embed";
constexpr auto Image = "image";
constexpr auto Audio = "audio";
constexpr auto Pdf = "pdf";
constexpr auto Bookmark = "bookmark";
constexpr auto Canvas = "canvas";
} // namespace BlockType

// Editor-facing block type keys (mobile BlockTypeKeys).
namespace BlockKey {
constexpr auto H1 = "h1";
constexpr auto H2 = "h2";
constexpr auto H3 = "h3";
constexpr auto H4 = "h4";
constexpr auto Todo = "todo";
constexpr auto Bullet = "bullet";
constexpr auto Numbered = "numbered";
constexpr auto Alphabetic = "alphabetic";
constexpr auto Roman = "roman";
constexpr auto Quote = "quote";
constexpr auto Callout = "callout";
constexpr auto Group = "group";
constexpr auto Code = "code";
constexpr auto Math = "math";
constexpr auto Table = "table";
constexpr auto Divider = "divider";
constexpr auto Toggle = "toggle";
constexpr auto Bookmark = "bookmark";
constexpr auto Image = "image";
constexpr auto Audio = "audio";
constexpr auto Pdf = "pdf";
constexpr auto Embed = "embed";
constexpr auto Canvas = "canvas";
constexpr auto Paragraph = "paragraph";
} // namespace BlockKey

// Canonical object type ids.
namespace TypeId {
constexpr auto Note = "note";
constexpr auto Journal = "journal";
constexpr auto Book = "book";
constexpr auto Task = "task";
constexpr auto Person = "person";
constexpr auto Project = "project";
constexpr auto Web = "web";
constexpr auto Image = "image";
constexpr auto Pdf = "pdf";
constexpr auto Audio = "audio";
constexpr auto Collection = "collection";
constexpr auto Tag = "tag";
constexpr auto Whiteboard = "whiteboard";
} // namespace TypeId

QString normalizePropertyKey(const QString &raw);

// ---------------------------------------------------------------------------
// Named color palette (stored by name so themes adapt)
// ---------------------------------------------------------------------------
namespace Palette {
extern const char *const kAllNames[10]; // yellow orange red pink purple blue ice teal green gray
bool isNamedColor(const QString &name);
} // namespace Palette

// ---------------------------------------------------------------------------
// Run — inline span wire format
// ---------------------------------------------------------------------------
struct Run {
    QString text;
    bool bold = false;
    bool italic = false;
    bool underline = false;
    bool strike = false;
    bool code = false;
    bool highlight = false;
    QString href;
    QString textColor;
    QString bgColor;

    // Atomic inline nodes
    QString objectId;   // mention target (wire: object_id)
    QString tagId;
    QString tagName;    // without '#'
    QString date;       // ISO date
    bool hasTime = false;

    // Inline math run (spec/text.md)
    bool isMath = false;
    QString latex;
    bool display = false;

    // Legacy inline image runs (older notes may contain them)
    QString imageName;
    double imageWidth = 0;
    double imageHeight = 0;
    bool referenceIcon = false;
    double pointSize = 0;

    bool isPlain() const {
        return !bold && !italic && !underline && !strike && !code && !highlight &&
               href.isEmpty() && textColor.isEmpty() && bgColor.isEmpty() &&
               objectId.isEmpty() && tagId.isEmpty() && tagName.isEmpty() &&
               date.isEmpty() && !isMath && imageName.isEmpty() && !referenceIcon && pointSize <= 0;
    }
    // Style identity used to coalesce adjacent plain text runs.
    bool sameStyleAs(const Run &o) const {
        return bold == o.bold && italic == o.italic && underline == o.underline &&
               strike == o.strike && code == o.code && highlight == o.highlight &&
               href == o.href && textColor == o.textColor && bgColor == o.bgColor;
    }
};

QVector<Run> normalizeRuns(const QVector<Run> &in);
QString runsToPlainText(const QVector<Run> &in);
// Visible per-span string identical to the mobile toVisibleString semantics.
QString runVisibleText(const Run &r);
QVector<Run> runsFromJson(const QJsonArray &arr);
QJsonArray runsToJson(const QVector<Run> &runs);
QVector<Run> runSlice(const QVector<Run> &in, int start, int end);
QPair<QVector<Run>, QVector<Run>> runSplitAt(const QVector<Run> &in, int offset);
// Segment coordinates + atomic semantics (mirror of RichSpanEngine).
struct RunSegment {
    int start = 0;
    int length = 0;
    const Run *span = nullptr;
    bool atomic = false;
    int end() const { return start + length; }
};
QVector<RunSegment> runSegments(const QVector<Run> &in);
QVector<Run> parseMarkdownToSpans(const QString &raw);

// ---------------------------------------------------------------------------
// Block — canonical wire block
// ---------------------------------------------------------------------------
struct Block {
    QString id;
    QString type = BlockType::Paragraph;
    int indentLevel = 0;      // 0..6 internal (wire indent is 1..8 = level+1)
    QString blockColor;
    QString groupId;

    QString text;             // plain text; code content for code blocks
    QVector<Run> runs;        // inline spans (caption for image/audio)

    int level = 0;           // heading level 1..4
    int number = 1;          // ordered lists
    bool checked = false;     // todo
    bool expanded = true;     // toggle

    QString calloutType = "NOTE";
    QString emoji;

    QString language = "kotlin";
    QString latex;

    QStringList align;
    QVector<QStringList> rows;

    QString objectId;         // embed
    QString viewMode = "card";

    QString assetId;          // image / audio / pdf
    double widthPercent = 1.0;
    int durationSeconds = 0;
    int pageCount = 1;
    int lastReadPage = 1;
    QString fileName;

    QString url;              // bookmark
    QString title;
    QString description;
    QString domain;
    QString thumbnailUrl;
    QString faviconUrl;

    QJsonValue canvasData;    // whiteboard canvas payload (kept opaque, JSON in/out)

    bool isTextual() const;   // spans-bearing: paragraph, heading, lists, todo, toggle, quote, callout
    bool isSequencedList() const;
    QString listKey() const;  // numbered/alphabetic/roman, else empty
    QString plainText() const;
    QVector<Run> spans() const;

    static Block makeParagraph(const QString &id, const QVector<Run> &spans = {},
                               int indent = 0, const QString &groupId = QString(),
                               const QString &color = QString());
    static Block newBlockIdFor(Block b);
};

using Blocks = QVector<Block>;

QString blocksToWireJson(const Blocks &blocks);          // canonical structured wire (groups packed)
QByteArray blocksToWireJsonBytes(const Blocks &blocks);
Blocks blocksFromWireJson(const QString &raw);
Blocks blocksFromWireJson(const QJsonArray &arr);
QJsonObject blockToWireJson(const Block &b);
Block blockFromWireJson(const QJsonObject &o, const QString &forcedGroupId = {},
                        const QString &forcedType = {});

// Markdown & HTML helpers (content field / clipboard / import+export).
QString blocksToMarkdown(const Blocks &blocks);
QString markdownSpanText(const QVector<Run> &spans);
Blocks markdownToBlocks(const QString &md);
QVector<Run> markdownLineToSpans(const QString &line);
QString blocksToHtml(const Blocks &blocks);
Blocks htmlToBlocks(const QString &html);

QString romanFromInt(int n);
QString alphabeticFromInt(int n);
QString newUuid();
QString newHlc();
quint64 hlcWall(const QString &hlc);
int hlcCounter(const QString &hlc);
QString hlcReceive(const QString &remote);
int compareHlc(const QString &a, const QString &b);
void setHlcNode(const QString &deviceId);
// `links` rule of spec/object.md, computed on the wire object (same algorithm as Go/Kotlin).
QStringList deriveLinks(const QJsonObject &wire);
// Type ids are compared trimmed and lowercase.
inline QString normalizeTypeId(const QString &raw) { return raw.trimmed().toLower(); }
// Canonical tag object id for a tag name (spec/object.md): tag_ + lowercase, spaces→_, letters/digits/_.
QString tagKey(const QString &name);

// ---------------------------------------------------------------------------
// Object + ObjectType
// ---------------------------------------------------------------------------
struct ObjectIcon {
    QString emoji;
    QString iconName;
    QString colorHex;
};

struct Object {
    QString id;
    QString typeId = TypeId::Note;
    QString title;
    QString content;              // markdown shadow (searchable)
    QJsonObject properties;       // raw server properties; "blocks" is extracted into blocks
    Blocks blocks;
    QStringList links;            // derived on export (spec/object.md); never edited by hand
    QStringList propertyLinks;    // object-ref property targets (passthrough + filtered by server)
    QJsonArray titleRuns;         // rich title (spec/object.md); `title` is its plain shadow
    QString createdAt;
    QString updatedAt;
    QString hlc;
    QString incarnation;
    QString trashedAt;
    quint64 revision = 0;
    QString syncStatus = "local";
    QString syncError;
    int schemaVersion = 0;      // spec/object.md: 1 = doc/3 + block_anchor era; refuse higher
    // Per-field / per-block stamps (spec/merge.md). Values are HLC strings.
    QJsonObject fieldHlc;
    QJsonObject blockHlc;
    QJsonObject deletedBlocks;
    QJsonObject blockAnchor;   // yaop-sync/1 anchors (spec/merge.md): id -> {after, hlc}

    // convenience accessors over raw properties
    bool isPinned() const;
    bool isTemplate() const;
    QString summary() const;
    QStringList aliases() const;
    QString propertyString(const QString &key) const;
    QStringList propertyStringList(const QString &key) const;
    ObjectIcon icon() const;
    QString coverAssetId() const;
    QJsonObject toJson(bool forWire) const;      // forWire injects properties.blocks + content
    static Object fromJson(const QJsonObject &o);
    // Client-side stamp of spec/merge.md: diff prev vs this on the wire JSON and update the
    // stamps with `now`. Returns false when nothing changed (hlc and stamps untouched).
    bool stamp(const Object *prev, const QString &now);
    // Asset ids referenced by this object (cover, property asset_id, media blocks).
    QStringList referencedAssets(QVector<QPair<QString, QString>> *withMime = nullptr) const;
};

struct ObjectType {
    QString id;
    QString name;
    QString icon;
    QString color;
    bool standard = true;
    QJsonArray propertyDefs;
    QString defaultTemplateId;
    static ObjectType fromJson(const QJsonObject &o);
    QJsonObject toJson() const;
};

QString plainTitleFor(const Object &o); // title or "Untitled"

QString objectIdForMedia(const QString &assetId); // media_<assetId>
QString detectMimeForFilename(const QString &filename);
QString canonicalTypeIdForFilename(const QString &filename);

} // namespace pkm
