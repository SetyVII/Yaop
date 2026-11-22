#include "spancodec.h"

#include <QGuiApplication>
#include <QTextBlock>
#include <QTextCharFormat>
#include <QTextCursor>
#include <QTextFragment>
#include <QTextImageFormat>
#include <QUrl>

namespace pkm::span {

namespace {
struct PaletteEntry {
    const char *name;
    const char *hex;
};
const PaletteEntry kPalette[] = {
    // Palette colors (the names are the wire contract).
    { "yellow", "#ffd666" }, { "orange", "#ffb07a" }, { "red", "#ff786e" },
    { "pink", "#ff8fb3" },   { "purple", "#c2b2ff" }, { "blue", "#6eaaff" },
    { "ice", "#bfe3ff" },    { "teal", "#7af0d0" },   { "green", "#7fe0a6" },
    { "gray", "#ffffff" },
};
constexpr int kPaletteSize = int(sizeof(kPalette) / sizeof(PaletteEntry));

QString escapeHtml(const QString &s)
{
    QString out = s.toHtmlEscaped();
    out.replace(QLatin1Char('\n'), QStringLiteral("<br>"));
    out.replace(QChar(0x2028), QStringLiteral("<br>"));
    return out;
}

bool isMonoFamily(const QStringList &families)
{
    for (const QString &f : families) {
        if (f.contains(QStringLiteral("mono"), Qt::CaseInsensitive))
            return true;
    }
    return false;
}

QString monoFamily()
{
    return QStringLiteral("IBM Plex Mono");
}

QString anchorFor(const Run &r)
{
    if (!r.objectId.isEmpty())
        return QStringLiteral("yaop://object/") + r.objectId
               + (r.blockId.isEmpty() ? QString() : QLatin1Char('#') + r.blockId);
    if (!r.tagId.isEmpty() || !r.tagName.isEmpty())
        return QStringLiteral("yaop-tag://") + (r.tagId.isEmpty()
                                                        ? tagKey(r.tagName)
                                                        : r.tagId)
               + QLatin1Char('/') + QString(QUrl::toPercentEncoding(r.tagName));
    if (!r.date.isEmpty())
        return QStringLiteral("yaop-date://") + r.date;
    if (!r.href.isEmpty())
        return r.href;
    return {};
}

QString chipForeground(const Run &r)
{
    if (!r.objectId.isEmpty())
        return QStringLiteral("#BFE3FF");
    if (!r.tagId.isEmpty() || !r.tagName.isEmpty())
        return QStringLiteral("#C9F2A6");
    if (!r.date.isEmpty())
        return QStringLiteral("#E8EEFF");
    return {};
}

QString chipBackground(const Run &r)
{
    // Mentions and #tags carry no background (spec §5); dates are a surface-2 chip.
    if (!r.date.isEmpty())
        return QStringLiteral("#2B3D7C");
    return {};
}

QString visibleLabel(const Run &r)
{
    return runVisibleText(r);
}

} // namespace

QColor paletteHex(const QString &name)
{
    for (int i = 0; i < kPaletteSize; ++i) {
        if (name == QLatin1String(kPalette[i].name))
            return QColor(QLatin1String(kPalette[i].hex));
    }
    return QColor(name); // direct CSS color fallback
}

QString paletteName(const QColor &c)
{
    // Exact palette matching only: fuzzy hue comparison remapped near-miss
    // colors (e.g. mention chips #A78BFA) onto palette entries, drifting the
    // rendered color on every round-trip.
    for (int i = 0; i < kPaletteSize; ++i) {
        if (QColor(QLatin1String(kPalette[i].hex)) == c)
            return QLatin1String(kPalette[i].name);
    }
    return c.name();
}

QString runsToHtml(const QVector<Run> &runs, qreal basePointSize)
{
    QString html;
    for (const Run &span : runs) {
        const QString anchor = anchorFor(span);
        const QString visible = visibleLabel(span);
        if (span.isMath) {
            const QString name = QStringLiteral("yaop-math://")
                                 + QString(QUrl::toPercentEncoding(span.latex))
                                 + (span.display ? QStringLiteral("?1") : QString());
            html += QStringLiteral("<img src=\"%1\" width=\"12\" height=\"12\" align=\"middle\">").arg(name);
            continue;
        }
        if (!span.imageName.isEmpty()) {
            const QString name = QStringLiteral("yaop-img://%1").arg(QString(QUrl::toPercentEncoding(span.imageName)));
            const int w = span.imageWidth > 0 ? int(span.imageWidth) : 0;
            const int h = span.imageHeight > 0 ? int(span.imageHeight) : 12;
            html += QStringLiteral("<img src=\"%1\"%2>")
                        .arg(name,
                             w > 0 ? QStringLiteral(" width=\"%1\" height=\"%2\" align=\"middle\"").arg(w).arg(h)
                                   : QStringLiteral(" height=\"12\" align=\"middle\""));
            continue;
        }
        QString open, close;
        if (!anchor.isEmpty()) {
            const QString fg = chipForeground(span);
            const QString bg = chipBackground(span);
            open += QStringLiteral("<a href=\"%1\" style=\"color:%2; background-color:%3; text-decoration:none\">")
                        .arg(anchor, fg.isEmpty() ? QStringLiteral("#BFE3FF") : fg,
                             bg.isEmpty() ? QStringLiteral("transparent") : bg);
            close.prepend(QStringLiteral("</a>"));
        }
        const QString fg = span.textColor.isEmpty() ? QString() : paletteHex(span.textColor).name();
        const bool patchedBg = span.highlight
                               || (!span.bgColor.isEmpty() && !span.highlight);
        QString bg;
        if (span.highlight)
            bg = QStringLiteral("#46568f");
        else if (!span.bgColor.isEmpty())
            bg = paletteHex(span.bgColor).name();
        QString inner;
        if (span.bold)
            inner += QLatin1String("<b>");
        if (span.italic)
            inner += QLatin1String("<i>");
        if (span.underline)
            inner += QLatin1String("<u>");
        if (span.strike)
            inner += QLatin1String("<s>");
        QString style;
        if (span.code)
            style += QStringLiteral("font-family:'%1';").arg(monoFamily());
        // Never serialize pure black/white text colors: they are theme
        // artifacts (mobile light-theme defaults) and become invisible on the
        // near-black surface when re-parsed as explicit HTML.
        const bool fgIsArtifact = fg.compare(QLatin1String("#000000"), Qt::CaseInsensitive) == 0
                                  || fg.compare(QLatin1String("#ffffff"), Qt::CaseInsensitive) == 0;
        if (!fg.isEmpty() && !fgIsArtifact)
            style += QStringLiteral("color:%1;").arg(fg);
        const bool bgIsArtifact = bg.compare(QLatin1String("#000000"), Qt::CaseInsensitive) == 0;
        if (!bg.isEmpty() && !bgIsArtifact)
            style += QStringLiteral("background-color:%1;").arg(bg);
        if (span.pointSize > 0)
            style += QStringLiteral("font-size:%1pt;").arg(span.pointSize);
        if (!style.isEmpty()) {
            inner += QStringLiteral("<span style=\"%1\">").arg(style);
            inner += escapeHtml(visible);
            inner += QLatin1String("</span>");
        } else {
            inner += escapeHtml(visible);
        }
        if (span.strike)
            inner += QLatin1String("</s>");
        if (span.underline)
            inner += QLatin1String("</u>");
        if (span.italic)
            inner += QLatin1String("</i>");
        if (span.bold)
            inner += QLatin1String("</b>");
        html += open + inner + close;
    }
    return html;
}

QVector<Run> documentToRuns(const QTextDocument *doc, const QColor &baseForeground)
{
    QVector<Run> out;
    if (!doc)
        return out;
    bool wroteAnything = false;
    for (QTextBlock block = doc->begin(); block.isValid(); block = block.next()) {
        if (wroteAnything) {
            Run r;
            r.text = QStringLiteral("\n");
            out.append(r);
        }
        for (auto it = block.begin(); !it.atEnd(); ++it) {
            const QTextFragment fragment = it.fragment();
            if (!fragment.isValid())
                continue;
            const QTextCharFormat f = fragment.charFormat();
            const QString text = fragment.text();
            if (f.isImageFormat()) {
                const QTextImageFormat img = f.toImageFormat();
                const QString name = img.name();
                if (name.startsWith(QStringLiteral("yaop-math://"))) {
                    QString src = name.mid(int(strlen("yaop-math://")));
                    bool display = src.endsWith(QLatin1Char('?')) ? false
                                 : src.section(QLatin1Char('?'), 1).toInt() == 1;
                    src = src.section(QLatin1Char('?'), 0, 0);
                    Run r;
                    r.isMath = true;
                    r.latex = QUrl::fromPercentEncoding(src.toUtf8());
                    r.display = display;
                    r.text = QStringLiteral("\uFFFC");
                    out.append(r);
                    wroteAnything = true;
                    continue;
                }
                if (name.startsWith(QStringLiteral("yaop-img://"))) {
                    Run r;
                    r.imageName = QUrl::fromPercentEncoding(
                        name.mid(int(strlen("yaop-img://"))).toUtf8());
                    r.imageWidth = img.width();
                    r.imageHeight = img.height();
                    r.text = QStringLiteral("\uFFFC");
                    out.append(r);
                    wroteAnything = true;
                    continue;
                }
                // Unknown image: keep name for lossless round-trip.
                Run r;
                r.imageName = name;
                r.imageWidth = img.width();
                r.imageHeight = img.height();
                r.text = QStringLiteral("\uFFFC");
                out.append(r);
                wroteAnything = true;
                continue;
            }

            Run r;
            r.text = QString(text).replace(QChar(0x2028), QLatin1Char('\n'));
            const QString href = f.isAnchor() ? f.anchorHref() : QString();
            if (href.startsWith(QStringLiteral("yaop://object/"))) {
                r.objectId = href.mid(int(strlen("yaop://object/")));
                if (const int hash = r.objectId.indexOf(QLatin1Char('#')); hash >= 0) {
                    r.blockId = r.objectId.mid(hash + 1);
                    r.objectId.truncate(hash);
                }
                if (r.objectId.isEmpty())
                    r.objectId = text;
                QString label = text;
                while (label.startsWith(QLatin1Char('@')))
                    label.remove(0, 1);
                if (!label.trimmed().isEmpty())
                    r.text = label.trimmed();
                else
                    r.text = r.objectId;
                out.append(r);
                wroteAnything = true;
                continue;
            }
            if (href.startsWith(QStringLiteral("yaop-tag://"))) {
                const QString rest = href.mid(int(strlen("yaop-tag://")));
                const QString id = rest.section(QLatin1Char('/'), 0, 0);
                QString label = rest.section(QLatin1Char('/'), 1);
                if (!label.isEmpty())
                    label = QUrl::fromPercentEncoding(label.toUtf8());
                while (label.startsWith(QLatin1Char('#')))
                    label.remove(0, 1);
                if (label.isEmpty())
                    label = QString(text).remove(QLatin1Char('#'));
                r.tagId = id;
                r.tagName = label;
                out.append(r);
                wroteAnything = true;
                continue;
            }
            if (href.startsWith(QStringLiteral("yaop-date://"))) {
                r.date = href.mid(int(strlen("yaop-date://")));
                if (r.date.isEmpty())
                    r.date = QString(text).remove(QLatin1Char('@')).trimmed();
                r.hasTime = r.date.contains(QLatin1Char(':'));
                out.append(r);
                wroteAnything = true;
                continue;
            }
            if (!href.isEmpty())
                r.href = href;

            r.bold = f.fontWeight() >= QFont::DemiBold;
            r.italic = f.fontItalic();
            r.underline = f.fontUnderline();
            r.strike = f.fontStrikeOut();
            const QStringList families = f.fontFamilies().toStringList();
            if (!families.isEmpty() && isMonoFamily(families))
                r.code = true;
            if (f.hasProperty(QTextFormat::FontPointSize) && f.fontPointSize() > 0)
                r.pointSize = f.fontPointSize();
            {
                // QBrush() default-constructs as an opaque BLACK brush, so
                // foreground().color() always yields a valid #000000 even when
                // the fragment has no explicit color. Only honor the brush when
                // the format actually declares the ForegroundBrush property;
                // otherwise the run keeps the surface's base color.
                if (f.hasProperty(QTextFormat::ForegroundBrush)) {
                    const QColor fg = f.foreground().color();
                    if (fg.isValid() && fg.alpha() > 0 && fg != baseForeground)
                        r.textColor = paletteName(fg);
                }
            }
            if (f.hasProperty(QTextFormat::BackgroundBrush)) {
                const QColor bg = f.background().color();
                if (bg.isValid() && bg.alpha() > 0) {
                    if (bg.name().toLower() == QLatin1String("#46568f"))
                        r.highlight = true;
                    else
                        r.bgColor = paletteName(bg);
                }
            }
            out.append(r);
            wroteAnything = true;
        }
    }
    if (!wroteAnything)
        return {};
    return normalizeRuns(out);
}

bool plainTextEquals(const QVector<Run> &a, const QString &plain)
{
    return runsToPlainText(a) == plain;
}

QString htmlBody(const QString &html)
{
    QString out = html;
    static const QRegularExpression anyTag(QStringLiteral("<[^>]*>"),
                                           QRegularExpression::CaseInsensitiveOption);
    out.replace(anyTag, QString());
    return out;
}

// ---------------------------------------------------------------------------
// Formatting mutations on the live document (range-based)
// ---------------------------------------------------------------------------
namespace {

QTextCharFormat makeMark(Mark mark, bool on)
{
    QTextCharFormat f;
    switch (mark) {
    case Mark::Bold:
        f.setFontWeight(on ? QFont::Bold : QFont::Normal);
        break;
    case Mark::Italic:
        f.setFontItalic(on);
        break;
    case Mark::Underline:
        f.setFontUnderline(on);
        break;
    case Mark::Strike:
        f.setFontStrikeOut(on);
        break;
    case Mark::Code:
        f.setFontFamilies({ on ? QStringLiteral("IBM Plex Mono") : QStringLiteral("Inter Tight") });
        break;
    case Mark::Highlight:
        if (on)
            f.setBackground(QColor("#46568f"));
        else
            f.setBackground(Qt::transparent);
        break;
    }
    return f;
}

bool hasMark(const QTextCharFormat &f, Mark mark)
{
    switch (mark) {
    case Mark::Bold:
        return f.fontWeight() >= QFont::DemiBold;
    case Mark::Italic:
        return f.fontItalic();
    case Mark::Underline:
        return f.fontUnderline();
    case Mark::Strike:
        return f.fontStrikeOut();
    case Mark::Code: {
        const QStringList fam = f.fontFamilies().toStringList();
        return !fam.isEmpty() && isMonoFamily(fam);
    }
    case Mark::Highlight:
        return f.background().color() == QColor("#46568f");
    }
    return false;
}

void mergeRange(QTextDocument *doc, int from, int to, const QTextCharFormat &fmt)
{
    if (!doc || to <= from)
        return;
    for (QTextBlock block = doc->findBlock(from); block.isValid() && block.position() < to;
         block = block.next()) {
        QTextCursor c(block);
        c.setPosition(qMax(from, block.position()));
        c.setPosition(qMin(to - 1, block.position() + block.length() - 1) + 1,
                       QTextCursor::KeepAnchor);
        if (c.hasSelection())
            c.mergeCharFormat(fmt);
    }
}

} // namespace

void applyMark(QTextDocument *doc, Mark mark, bool on, int from, int to)
{
    if (!doc)
        return;
    const int last = qMax(0, int(doc->characterCount()) - 1);
    const int f = qBound(0, from, last);
    int t = qBound(0, to, int(doc->characterCount()));
    if (t <= f)
        t = f + 1;
    mergeRange(doc, f, t, makeMark(mark, on));
    // Keep insertion format sticky for subsequent typing at the block end.
    QTextCursor c(doc);
    c.setPosition(qBound(0, qMin(t, last), last));
    c.setCharFormat(makeMark(mark, on));
}

bool markActiveInRange(QTextDocument *doc, Mark mark, int from, int to)
{
    if (!doc)
        return false;
    const int last = qMax(0, int(doc->characterCount()) - 1);
    int f = qBound(0, from, last);
    int t = qBound(0, to, int(doc->characterCount()));
    if (t <= f) {
        f = qBound(0, qMin(from, last), last);
        t = f + 1;
    }
    for (QTextBlock block = doc->findBlock(f); block.isValid() && block.position() < t;
         block = block.next()) {
        for (auto it = block.begin(); !it.atEnd(); ++it) {
            const QTextFragment frag = it.fragment();
            if (!frag.isValid())
                continue;
            const int overlapsFrom = qMax(frag.position(), f);
            const int overlapsTo = qMin(frag.position() + frag.length(), t);
            if (overlapsTo - overlapsFrom <= 0)
                continue;
            if (!hasMark(frag.charFormat(), mark))
                return false;
        }
    }
    return true;
}

void applyColor(QTextDocument *doc, const QString &role, const QString &colorName, int from, int to)
{
    if (!doc)
        return;
    QTextCharFormat fmt;
    if (role == QLatin1String("text")) {
        if (colorName.isEmpty())
            fmt.setForeground(QColor("#FFFFFF"));
        else
            fmt.setForeground(paletteHex(colorName));
    } else if (role == QLatin1String("bg")) {
        if (colorName.isEmpty())
            fmt.setBackground(Qt::transparent);
        else
            fmt.setBackground(paletteHex(colorName));
    }
    const int last = qMax(0, int(doc->characterCount()) - 1);
    const int f = qBound(0, from, last);
    int t = qBound(0, to, int(doc->characterCount()));
    if (t <= f)
        t = f + 1;
    mergeRange(doc, f, t, fmt);
}

void applyLink(QTextDocument *doc, const QString &href, int from, int to)
{
    if (!doc)
        return;
    QTextCharFormat fmt;
    if (href.isEmpty()) {
        fmt.setAnchor(false);
        fmt.setAnchorHref(QString());
    } else {
        fmt.setAnchor(true);
        fmt.setAnchorHref(href);
        fmt.setFontUnderline(false);
        fmt.setForeground(QColor("#BFE3FF"));
    }
    const int last = qMax(0, int(doc->characterCount()) - 1);
    const int f = qBound(0, from, last);
    int t = qBound(0, to, int(doc->characterCount()));
    if (t <= f)
        t = f + 1;
    mergeRange(doc, f, t, fmt);
}

} // namespace pkm::span
