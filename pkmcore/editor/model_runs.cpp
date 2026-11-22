#include "model.h"
#include <QCryptographicHash>

#include <QColor>
#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QUuid>

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
    if (v.isDouble())
        return int(v.toDouble());
    if (v.isString()) {
        bool ok = false;
        int n = v.toString().toInt(&ok);
        if (ok)
            return n;
    }
    return def;
}
inline double optDouble(const QJsonObject &o, const char *key, double def = 0)
{
    const QJsonValue &v = o.value(QLatin1String(key));
    return v.isDouble() ? v.toDouble() : def;
}
inline QJsonArray optArray(const QJsonObject &o, const char *key)
{
    const QJsonValue &v = o.value(QLatin1String(key));
    return v.isArray() ? v.toArray() : QJsonArray();
}
} // namespace

QString newUuid()
{
    return QUuid::createUuid().toString(QUuid::WithoutBraces);
}

// Normalizes incoming inline colors: palette names pass through; raw hex is
// mapped to the nearest palette name. Theme artifacts (pure black/white) are
// cleared so each client renders with its own base text color.
static QString normalizeColorName(const QString &raw)
{
    const QString c = raw.trimmed();
    if (c.isEmpty())
        return {};
    if (Palette::isNamedColor(c))
        return c;
    const QColor col(c);
    if (!col.isValid())
        return {};
    if (col == QColor("#000000") || col == QColor("#ffffff") || col == QColor("black")
        || col == QColor("white") || col == QColor("#fff") || col == QColor("#000"))
        return {};
    return col.name();
}

// ---------------------------------------------------------------------------
// HLC: <wallMillis>-<4hex>
// ---------------------------------------------------------------------------
// HLC (spec/merge.md): <13-digit wall>-<4 hex counter>-<8 hex node>, one shared clock.
namespace {
struct HlcClock {
    qint64 wall = 0;
    int counter = 0;
    QString node;
};
HlcClock &hlcClock()
{
    static HlcClock c;
    return c;
}
QString formatHlc(qint64 wall, int counter, const QString &node)
{
    QString s = QStringLiteral("%1-%2").arg(wall, 13, 10, QLatin1Char('0')).arg(counter, 4, 16, QLatin1Char('0'));
    if (!node.isEmpty())
        s += QLatin1Char('-') + node;
    return s;
}
}

void setHlcNode(const QString &deviceId)
{
    hlcClock().node = QString::fromLatin1(
        QCryptographicHash::hash(deviceId.toUtf8(), QCryptographicHash::Sha256).toHex().left(8));
}

QString newHlc()
{
    HlcClock &c = hlcClock();
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (now > c.wall) {
        c.wall = now;
        c.counter = 0;
    } else {
        c.counter = qMin(c.counter + 1, 0xFFFF);
    }
    return formatHlc(c.wall, c.counter, c.node);
}

quint64 hlcWall(const QString &hlc)
{
    const int dash = hlc.indexOf(QLatin1Char('-'));
    return (dash > 0 ? hlc.left(dash) : hlc).toULongLong();
}

int hlcCounter(const QString &hlc)
{
    const QStringList parts = hlc.split(QLatin1Char('-'));
    if (parts.size() < 2)
        return 0;
    bool ok = false;
    const int v = parts.at(1).toInt(&ok, 16);
    return ok ? v : 0;
}

static QString hlcNode(const QString &hlc)
{
    const QStringList parts = hlc.split(QLatin1Char('-'));
    return parts.size() >= 3 ? parts.at(2) : QString();
}

int compareHlc(const QString &a, const QString &b)
{
    if (a == b)
        return 0;
    if (a.isEmpty())
        return -1;
    if (b.isEmpty())
        return 1;
    const quint64 aw = hlcWall(a), bw = hlcWall(b);
    if (aw != bw)
        return aw < bw ? -1 : 1;
    const int ac = hlcCounter(a), bc = hlcCounter(b);
    if (ac != bc)
        return ac < bc ? -1 : 1;
    const int n = QString::compare(hlcNode(a), hlcNode(b));
    return n < 0 ? -1 : (n > 0 ? 1 : 0);
}

QString hlcReceive(const QString &remote)
{
    HlcClock &c = hlcClock();
    if (remote.isEmpty())
        return newHlc();
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    const qint64 rWall = qint64(hlcWall(remote));
    const int rCnt = hlcCounter(remote);
    if (now > c.wall && now > rWall) {
        c.wall = now;
        c.counter = 0;
    } else if (c.wall == rWall) {
        c.counter = qMin(qMax(c.counter, rCnt) + 1, 0xFFFF);
    } else if (rWall > c.wall) {
        c.wall = rWall;
        c.counter = qMin(rCnt + 1, 0xFFFF);
    } else {
        c.counter = qMin(c.counter + 1, 0xFFFF);
    }
    return formatHlc(c.wall, c.counter, c.node);
}

// ---------------------------------------------------------------------------
// Type / property normalization (mirror of server model + mobile DefaultTypes)
// ---------------------------------------------------------------------------
QString normalizePropertyKey(const QString &raw)
{
    QString clean = raw.trimmed();
    if (clean.startsWith(QLatin1String("prop_")))
        clean = clean.mid(5);
    return clean;
}

namespace Palette {
const char *const kAllNames[10] = { "yellow", "orange", "red",    "pink",  "purple",
                                    "blue",   "ice",   "teal", "green", "gray" };
bool isNamedColor(const QString &name)
{
    for (const char *n : kAllNames)
        if (name == QLatin1String(n))
            return true;
    return false;
}
} // namespace Palette

// ---------------------------------------------------------------------------
// Runs
// ---------------------------------------------------------------------------
QString runVisibleText(const Run &r)
{
    if (!r.date.isEmpty())
        return r.date;
    if (!r.tagId.isEmpty() || !r.tagName.isEmpty()) {
        const QString clean = r.tagName.startsWith(QLatin1Char('#')) ? r.tagName.mid(1) : r.tagName;
        return clean.isEmpty() ? QStringLiteral("#tag") : QLatin1Char('#') + clean;
    }
    if (!r.objectId.isEmpty()) {
        const QString raw = r.text.startsWith(QLatin1Char('@')) ? r.text.mid(1) : r.text;
        const QString clean = raw.trimmed().isEmpty() ? QStringLiteral("mention") : raw.trimmed();
        return QLatin1Char('@') + clean;
    }
    return r.text;
}

QVector<Run> normalizeRuns(const QVector<Run> &in)
{
    QVector<Run> out;
    out.reserve(in.size());
    for (const Run &r : in) {
        if (r.isMath || !r.objectId.isEmpty() || !r.date.isEmpty() || !r.tagId.isEmpty()
            || !r.imageName.isEmpty() || r.referenceIcon) {
            out.append(r);
            continue;
        }
        if (r.text.isEmpty())
            continue;
        if (!out.isEmpty()) {
            const Run &prev = out.last();
            if (prev.sameStyleAs(r) && prev.objectId.isEmpty() && prev.tagId.isEmpty()
                && prev.date.isEmpty() && !prev.isMath) {
                out.last().text += r.text;
                continue;
            }
        }
        out.append(r);
    }
    return out;
}

QString runsToPlainText(const QVector<Run> &in)
{
    QString out;
    for (const Run &r : in)
        out += runVisibleText(r);
    return out;
}

QVector<Run> runsFromJson(const QJsonArray &arr)
{
    QVector<Run> out;
    out.reserve(arr.size());
    for (const QJsonValue &e : arr) {
        if (!e.isObject())
            continue;
        const QJsonObject run = e.toObject();
        Run r;
        r.text = optStr(run, "text");
        r.bold = optBool(run, "bold");
        r.italic = optBool(run, "italic");
        r.underline = optBool(run, "underline");
        r.strike = optBool(run, "strike");
        r.code = optBool(run, "code");
        r.highlight = optBool(run, "highlight");
        r.href = optStr(run, "href");
        r.textColor = normalizeColorName(optStr(run, "text_color"));
        r.bgColor = normalizeColorName(optStr(run, "bg_color"));
        r.objectId = optStr(run, "object_id");
        r.blockId = optStr(run, "block_id");
        r.tagId = optStr(run, "tag_id");
        r.tagName = optStr(run, "tag_name");
        r.date = optStr(run, "date");
        r.hasTime = optBool(run, "has_time");
        const QString type = optStr(run, "type");
        if (type == QLatin1String("math")) {
            r.isMath = true;
            r.latex = optStr(run, "latex");
            r.display = optBool(run, "display");
        } else if (type == QLatin1String("image") || run.contains("image_name")) {
            r.imageName = optStr(run, "image_name");
            r.imageWidth = optDouble(run, "width");
            r.imageHeight = optDouble(run, "height");
            r.referenceIcon = optBool(run, "reference_icon");
            if (r.referenceIcon && r.imageName.isEmpty()) {
                // reference icon fragment: degrade to mention text
            }
            out.append(r);
            continue;
        }
        r.referenceIcon = optBool(run, "reference_icon");
        r.pointSize = optDouble(run, "point_size");

        // Atomic resolution order mirrors the mobile parser.
        if (!r.objectId.isEmpty() || r.href.startsWith(QLatin1String("lumen://object/"))) {
            QString resolved = r.objectId;
            if (resolved.isEmpty())
                resolved = r.href.mid(int(strlen("lumen://object/")));
            if (resolved.isEmpty())
                resolved = r.text;
            if (optBool(run, "reference_icon")) {
                // Purely visual fragment from legacy notes: the anchor
                // title run follows with the real label. Skip it (mobile parity).
                continue;
            }
            Run m;
            m.objectId = resolved;
            m.blockId = r.blockId;
            m.text = r.text.isEmpty() ? resolved : r.text;
            out.append(m);
            continue;
        }
        if (!r.tagId.isEmpty() || !r.tagName.isEmpty()) {
            QString cleanName = r.tagName.isEmpty() ? QString(r.text).remove(QLatin1Char('#')) : r.tagName;
            Run t;
            t.tagId = r.tagId.isEmpty() ? tagKey(cleanName) : r.tagId;
            t.tagName = cleanName;
            t.text = r.text;
            out.append(t);
            continue;
        }
        if (!r.date.isEmpty()) {
            Run d;
            d.date = r.date;
            d.hasTime = r.hasTime;
            d.text = r.text;
            out.append(d);
            continue;
        }
        if (r.isMath) {
            r.text = r.text.isEmpty() ? QStringLiteral("\uFFFC") : r.text;
            out.append(r);
            continue;
        }
        if (r.href == QLatin1String("lumen://object/"))
            r.href.clear();
        if (optBool(run, "reference_icon"))
            r.referenceIcon = true;
        out.append(r);
    }
    return normalizeRuns(out);
}

QJsonArray runsToJson(const QVector<Run> &runs)
{
    QJsonArray arr;
    for (const Run &span : normalizeRuns(runs)) {
        QJsonObject run;
        if (span.isMath) {
            run.insert("text", span.text.isEmpty() ? QStringLiteral("\uFFFC") : span.text);
            run.insert("type", QStringLiteral("math"));
            if (!span.latex.isEmpty())
                run.insert("latex", span.latex);
            if (span.display)
                run.insert("display", true);
            arr.append(run);
            continue;
        }
        if (!span.objectId.isEmpty()) {
            const QString label = span.text.isEmpty() ? span.objectId : span.text;
            run.insert("text", label);
            run.insert("object_id", span.objectId);
            if (!span.blockId.isEmpty())
                run.insert("block_id", span.blockId);
            arr.append(run);
            continue;
        }
        if (!span.tagId.isEmpty() || !span.tagName.isEmpty()) {
            const QString clean = span.tagName.startsWith(QLatin1Char('#')) ? span.tagName.mid(1) : span.tagName;
            run.insert("text", QLatin1Char('#') + clean);
            if (!span.tagId.isEmpty())
                run.insert("tag_id", span.tagId);
            run.insert("tag_name", clean);
            arr.append(run);
            continue;
        }
        if (!span.date.isEmpty()) {
            run.insert("text", span.date);
            run.insert("date", span.date);
            if (span.hasTime)
                run.insert("has_time", true);
            arr.append(run);
            continue;
        }
        run.insert("text", span.text);
        if (span.bold)
            run.insert("bold", true);
        if (span.italic)
            run.insert("italic", true);
        if (span.underline)
            run.insert("underline", true);
        if (span.strike)
            run.insert("strike", true);
        if (span.code)
            run.insert("code", true);
        if (span.highlight)
            run.insert("highlight", true);
        if (!span.href.isEmpty())
            run.insert("href", span.href);
        if (!span.textColor.isEmpty())
            run.insert("text_color", span.textColor);
        if (!span.bgColor.isEmpty())
            run.insert("bg_color", span.bgColor);
        if (span.pointSize > 0)
            run.insert("point_size", span.pointSize);
        if (!span.imageName.isEmpty()) {
            run.insert("type", QStringLiteral("image"));
            run.insert("image_name", span.imageName);
            if (span.imageWidth > 0)
                run.insert("width", span.imageWidth);
            if (span.imageHeight > 0)
                run.insert("height", span.imageHeight);
        }
        if (span.referenceIcon)
            run.insert("reference_icon", true);
        arr.append(run);
    }
    return arr;
}

QVector<RunSegment> runSegments(const QVector<Run> &in)
{
    const QVector<Run> spans = normalizeRuns(in);
    QVector<RunSegment> segs;
    segs.reserve(spans.size());
    int start = 0;
    for (qsizetype i = 0; i < spans.size(); ++i) {
        RunSegment s;
        s.span = &spans[i];
        s.start = start;
        s.length = qMax(1, runVisibleText(spans[i]).length());
        s.atomic = !spans[i].objectId.isEmpty() || !spans[i].date.isEmpty()
                    || !spans[i].tagId.isEmpty() || spans[i].isMath;
        segs.append(s);
        start += s.length;
    }
    return segs;
}

QVector<Run> runSlice(const QVector<Run> &in, int start, int end)
{
    if (end <= start)
        return {};
    const QVector<RunSegment> segs = runSegments(in);
    QVector<Run> out;
    for (const RunSegment &seg : segs) {
        if (seg.end() <= start || seg.start >= end) {
            // unused
        }
    }
    for (const RunSegment &seg : segs) {
        const int segEnd = seg.start + seg.length;
        if (segEnd <= start || seg.start >= end)
            continue;
        const int localStart = qMax(start - seg.start, 0);
        const int localEnd = qMin(end - seg.start, seg.length);
        if (localEnd <= localStart)
            continue;
        if (seg.atomic) {
            if (localStart == 0 && localEnd == seg.length) {
                out.append(*seg.span);
            } else {
                Run plain;
                plain.text = runVisibleText(*seg.span).mid(localStart, localEnd - localStart);
                out.append(plain);
            }
            continue;
        }
        Run cut = *seg.span;
        cut.text = cut.text.mid(localStart, localEnd - localStart);
        out.append(cut);
    }
    return normalizeRuns(out);
}

QPair<QVector<Run>, QVector<Run>> runSplitAt(const QVector<Run> &in, int offset)
{
    const int total = runsToPlainText(normalizeRuns(in)).length();
    const int clamp = qBound(0, offset, total);
    return qMakePair(runSlice(in, 0, clamp), runSlice(in, clamp, total));
}

// ---------------------------------------------------------------------------
// Markdown inline parsing (legacy import parity — mobile RichSpanEngine subset)
// ---------------------------------------------------------------------------
QVector<Run> parseMarkdownToSpans(const QString &raw)
{
    if (raw.isEmpty())
        return {};
    static const QRegularExpression mentionBracket(QStringLiteral("\\[@([^\\]]+)\\]"));
    static const QRegularExpression dateToken(QStringLiteral("@(\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2})?)"));
    static const QRegularExpression tagToken(QStringLiteral("#([\\p{L}\\p{N}_-]+)"));
    static const QRegularExpression linkToken(QStringLiteral("\\[([^\\]]+)\\]\\(([^)]+)\\)"));
    static const QRegularExpression codeToken(QStringLiteral("`([^`]+)`"));
    static const QRegularExpression boldToken(QStringLiteral("\\*\\*([^*]+)\\*\\*"));
    static const QRegularExpression highlightToken(QStringLiteral("==([^=]+)=="));
    static const QRegularExpression strikeToken(QStringLiteral("~~([^~]+)~~"));

    QVector<Run> out;
    QString buffer;
    int i = 0;
    const QString &text = raw;

    auto flush = [&]() {
        if (!buffer.isEmpty()) {
            Run r;
            r.text = buffer;
            out.append(r);
            buffer.clear();
        }
    };

    while (i < text.size()) {
        const QChar ch = text.at(i);
        if (ch == QLatin1Char('[')) {
            QRegularExpressionMatch lm = linkToken.match(text, i);
            if (lm.hasMatch() && lm.capturedStart() == i) {
                flush();
                Run r;
                r.text = lm.captured(1);
                r.href = lm.captured(2);
                out.append(r);
                i = lm.capturedEnd();
                continue;
            }
            QRegularExpressionMatch mm = mentionBracket.match(text, i);
            if (mm.hasMatch() && mm.capturedStart() == i) {
                flush();
                const QString content = mm.captured(1).trimmed();
                const QString label = content.mid(content.lastIndexOf(QLatin1Char('/')) + 1).trimmed();
                Run r;
                r.objectId = content.isEmpty() ? label : content;
                r.text = label;
                out.append(r);
                i = mm.capturedEnd();
                continue;
            }
        } else if (ch == QLatin1Char('@')) {
            QRegularExpressionMatch dm = dateToken.match(text, i);
            if (dm.hasMatch() && dm.capturedStart() == i) {
                flush();
                Run r;
                r.date = dm.captured(1);
                r.text = dm.captured(1);
                r.hasTime = dm.captured(1).contains(QLatin1Char('T')) || dm.captured(1).contains(QLatin1Char(' '));
                out.append(r);
                i = dm.capturedEnd();
                continue;
            }
        } else if (ch == QLatin1Char('#')) {
            QRegularExpressionMatch tm = tagToken.match(text, i);
            if (tm.hasMatch() && tm.capturedStart() == i) {
                flush();
                Run r;
                r.tagName = tm.captured(1);
                r.tagId = tagKey(tm.captured(1));
                r.text = tm.captured(1);
                out.append(r);
                i = tm.capturedEnd();
                continue;
            }
        } else if (ch == QLatin1Char('`')) {
            QRegularExpressionMatch cm = codeToken.match(text, i);
            if (cm.hasMatch() && cm.capturedStart() == i) {
                flush();
                Run r;
                r.text = cm.captured(1);
                r.code = true;
                out.append(r);
                i = cm.capturedEnd();
                continue;
            }
        } else if (ch == QLatin1Char('*') && text.mid(i, 2) == QLatin1String("**")) {
            QRegularExpressionMatch bm = boldToken.match(text, i);
            if (bm.hasMatch() && bm.capturedStart() == i) {
                flush();
                Run r;
                r.text = bm.captured(1);
                r.bold = true;
                out.append(r);
                i = bm.capturedEnd();
                continue;
            }
        } else if (ch == QLatin1Char('=') && text.mid(i, 2) == QLatin1String("==")) {
            QRegularExpressionMatch hm = highlightToken.match(text, i);
            if (hm.hasMatch() && hm.capturedStart() == i) {
                flush();
                Run r;
                r.text = hm.captured(1);
                r.highlight = true;
                out.append(r);
                i = hm.capturedEnd();
                continue;
            }
        } else if (ch == QLatin1Char('~') && text.mid(i, 2) == QLatin1String("~~")) {
            QRegularExpressionMatch sm = strikeToken.match(text, i);
            if (sm.hasMatch() && sm.capturedStart() == i) {
                flush();
                Run r;
                r.text = sm.captured(1);
                r.strike = true;
                out.append(r);
                i = sm.capturedEnd();
                continue;
            }
        }
        buffer += ch;
        ++i;
    }
    flush();
    return normalizeRuns(out);
}

} // namespace pkm
