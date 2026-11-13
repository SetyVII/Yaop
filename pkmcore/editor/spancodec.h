#pragma once

// SpanCodec: bidirectional bridge between pkm::Run vectors and the QTextDocument
// each block's QML TextEdit owns. The document is the live editing surface; the
// runs are the canonical wire state.
//
// Anchors:  yaop://object/<id>          -> mention
//           yaop-tag://<tagId>/<tag> -> tag
//           yaop-date://<iso>       -> date
// Images:   yaop-math://<enc>?<d>    -> inline math (resource added by C++)

#include "model.h"

#include <QColor>
#include <QTextDocument>

namespace pkm::span {

// Named palette <-> dark-theme hex (block/inline colors adapt by name).
QColor paletteHex(const QString &name);
QString paletteName(const QColor &c);

// Renders runs to the HTML subset consumed by QML TextEdit (RichText).
QString runsToHtml(const QVector<Run> &runs, qreal basePointSize = 0);

// Extracts runs from a QTextDocument (the block's live editor content).
// baseForeground/baseFontFootprint filter out theme defaults.
QVector<Run> documentToRuns(const QTextDocument *doc, const QColor &baseForeground);

// True when the plain-text content matches (cheap dirty check).
bool plainTextEquals(const QVector<Run> &a, const QString &plain);

// Raw plain text of html for comparisons (no format).
QString htmlBody(const QString &html);

// Applies a formatting toggle to a range of the live document.
enum class Mark { Bold, Italic, Underline, Strike, Code, Highlight };
void applyMark(QTextDocument *doc, Mark mark, bool on, int from, int to);
// "active" = every character in the range has the mark (toggle semantics).
bool markActiveInRange(QTextDocument *doc, Mark mark, int from, int to);
void applyColor(QTextDocument *doc, const QString &role, const QString &colorName, int from, int to);
void applyLink(QTextDocument *doc, const QString &href, int from, int to);

} // namespace pkm::span
