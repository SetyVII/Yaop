#include "datequery.h"

#include <QRegularExpression>
#include <QStringList>

namespace pkm::mention {

namespace {

QString fold(const QString &s)
{
    const QString d = s.normalized(QString::NormalizationForm_D);
    QString out;
    out.reserve(d.size());
    for (const QChar c : d) {
        if (c.category() != QChar::Mark_NonSpacing)
            out.append(c.toLower());
    }
    return out.simplified();
}

struct Word {
    const char *key;
    int offset;          // days from today; weekdays use 0 and `weekday`
    int weekday;         // 1 = Monday … 7 = Sunday, 0 = none
    bool withTime;       // "now": today at the current minute
    QStringList names;   // folded
};

const QList<Word> &words()
{
    static const QList<Word> table = {
        { "now", 0, 0, true, { "now", "ahora", "hour", "hora", "time" } },
        { "today", 0, 0, false, { "today", "hoy" } },
        { "tomorrow", 1, 0, false, { "tomorrow", "manana" } },
        { "yesterday", -1, 0, false, { "yesterday", "ayer" } },
        { "weekday", 0, 1, false, { "monday", "lunes" } },
        { "weekday", 0, 2, false, { "tuesday", "martes" } },
        { "weekday", 0, 3, false, { "wednesday", "miercoles" } },
        { "weekday", 0, 4, false, { "thursday", "jueves" } },
        { "weekday", 0, 5, false, { "friday", "viernes" } },
        { "weekday", 0, 6, false, { "saturday", "sabado" } },
        { "weekday", 0, 7, false, { "sunday", "domingo" } },
        { "next-week", 7, 0, false, { "next week", "proxima semana", "semana que viene" } },
    };
    return table;
}

// A cut-short word counts from 2 letters ("ho" → hoy, hora); a full word always.
bool matches(const QString &typed, const QString &name)
{
    return typed == name || (typed.size() >= 2 && name.startsWith(typed));
}

int weekdayOffset(const QDate &today, int weekday)
{
    const int d = (weekday - today.dayOfWeek() + 7) % 7;
    return d == 0 ? 7 : d;   // "friday" on a Friday is the next one
}

QString isoOf(const QDate &d) { return d.toString(Qt::ISODate); }

// "15:30", "9:05", "9am", "3 pm", "21h", "9" (only after a day word) → minutes since 00:00.
int parseTime(const QString &t, bool bareNumberOk)
{
    static const QRegularExpression re(QStringLiteral("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|h)?$"));
    const auto m = re.match(t);
    if (!m.hasMatch())
        return -1;
    const bool hasColon = m.capturedLength(2) > 0;
    const QString suffix = m.captured(3);
    if (!hasColon && suffix.isEmpty() && !bareNumberOk)
        return -1;
    int h = m.captured(1).toInt();
    const int min = hasColon ? m.captured(2).toInt() : 0;
    if (suffix == QLatin1String("pm") && h < 12)
        h += 12;
    else if (suffix == QLatin1String("am") && h == 12)
        h = 0;
    if (h > 23 || min > 59 || ((suffix == QLatin1String("am") || suffix == QLatin1String("pm")) && m.captured(1).toInt() > 12))
        return -1;
    return h * 60 + min;
}

// "2026-12-01", "1/12", "1/12/2027", "1-12" → a date, or invalid.
QDate parseDate(const QString &t, const QDate &today, bool dayFirst)
{
    const QDate iso = QDate::fromString(t, Qt::ISODate);
    if (iso.isValid() && t.size() == 10)
        return iso;
    static const QRegularExpression re(QStringLiteral("^(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?$"));
    const auto m = re.match(t);
    if (!m.hasMatch())
        return {};
    const int a = m.captured(1).toInt();
    const int b = m.captured(2).toInt();
    const int day = dayFirst ? a : b;
    const int month = dayFirst ? b : a;
    int year = today.year();
    if (m.capturedLength(3) > 0) {
        year = m.captured(3).toInt();
        if (year < 100)
            year += 2000;
    }
    QDate d(year, month, day);
    // no year: the next time that day comes (today counts)
    if (d.isValid() && m.capturedLength(3) == 0 && d < today)
        d = d.addYears(1);
    return d;
}

// "+3", "-2", "+2w", "in 3 days", "en 2 semanas", "3 days ago", "hace 3 dias" → days.
bool parseOffset(const QString &t, int *days)
{
    static const QRegularExpression sign(QStringLiteral("^([+-])\\s*(\\d{1,3})\\s*([dws]?)$"));
    static const QRegularExpression in(QStringLiteral("^(?:in|en|dentro de)\\s+(\\d{1,3})\\s*(d|days?|dias?|w|weeks?|semanas?)?$"));
    static const QRegularExpression ago(QStringLiteral("^(?:hace\\s+(\\d{1,3})\\s*(d|dias?|semanas?)?|(\\d{1,3})\\s*(d|days?|w|weeks?)\\s+ago)$"));
    auto unit = [](const QString &u) {
        return (u.startsWith(QLatin1Char('w')) || u.startsWith(QLatin1Char('s'))) ? 7 : 1;
    };
    if (const auto m = sign.match(t); m.hasMatch()) {
        *days = m.captured(2).toInt() * unit(m.captured(3)) * (m.captured(1) == QLatin1String("-") ? -1 : 1);
        return true;
    }
    if (const auto m = in.match(t); m.hasMatch()) {
        *days = m.captured(1).toInt() * unit(m.captured(2));
        return true;
    }
    if (const auto m = ago.match(t); m.hasMatch()) {
        const bool es = m.capturedLength(1) > 0;
        *days = -(es ? m.captured(1).toInt() * unit(m.captured(2)) : m.captured(3).toInt() * unit(m.captured(4)));
        return true;
    }
    return false;
}

} // namespace

QList<DateSuggestion> dateSuggestions(const QString &query, const QDateTime &now, bool dayFirst, int max)
{
    QList<DateSuggestion> out;
    const QString q = fold(query);
    if (q.isEmpty())
        return out;
    const QDate today = now.date();
    const int nowMinutes = now.time().hour() * 60 + now.time().minute();
    bool exact = true;
    auto add = [&](const QString &key, const QDate &day, int minutes, int offset) {
        if (!day.isValid() || out.size() >= max)
            return;
        DateSuggestion s;
        s.key = key;
        s.dayOffset = offset;
        s.exact = exact;
        s.iso = isoOf(day);
        if (minutes >= 0) {
            s.hasTime = true;
            s.iso += QStringLiteral("T%1:%2").arg(minutes / 60, 2, 10, QLatin1Char('0')).arg(minutes % 60, 2, 10, QLatin1Char('0'));
        }
        for (const DateSuggestion &o : out)
            if (o.iso == s.iso)
                return;
        out.append(s);
    };
    auto dayOfWord = [&](const Word &w) {
        return w.weekday > 0 ? weekdayOffset(today, w.weekday) : w.offset;
    };

    // 1) a time alone: today at that time
    if (const int t = parseTime(q, false); t >= 0) {
        add(QStringLiteral("time"), today, t, 0);
        return out;
    }
    // 2) a number of days
    if (int days = 0; parseOffset(q, &days)) {
        add(QStringLiteral("offset"), today.addDays(days), -1, days);
        return out;
    }
    // 3) a date, maybe with a time after it
    {
        const int space = q.indexOf(QLatin1Char(' '));
        const QDate d = parseDate(space < 0 ? q : q.left(space), today, dayFirst);
        if (d.isValid()) {
            const int t = space < 0 ? -1 : parseTime(q.mid(space + 1), true);
            if (space < 0 || t >= 0)
                add(QStringLiteral("date"), d, t, int(today.daysTo(d)));
            return out;
        }
    }
    // 4) words, maybe followed by a time ("mañana 9", "friday 15:30")
    for (const Word &w : words()) {
        for (const QString &name : w.names) {
            if (matches(q, name)) {
                exact = q == name;
                const int offset = dayOfWord(w);
                add(QString::fromLatin1(w.key), today.addDays(offset), w.withTime ? nowMinutes : -1, offset);
                break;
            }
            if (q.startsWith(name + QLatin1Char(' ')) && !w.withTime) {
                const int t = parseTime(q.mid(name.size() + 1), true);
                if (t >= 0) {
                    const int offset = dayOfWord(w);
                    add(QString::fromLatin1(w.key), today.addDays(offset), t, offset);
                    break;
                }
            }
        }
    }
    return out;
}

bool mayBecomeDate(const QString &query, const QDateTime &now)
{
    const QString q = fold(query);
    if (q.isEmpty())
        return false;
    if (!dateSuggestions(q, now, true, 1).isEmpty())
        return true;
    // the start of a phrase: "in 3 d", "en 2", "dentro de", "next w", "proxima", "semana que",
    // "hace 3", "3 days a", a date or a day word then the start of a time ("mañana 9:", "1/12 1")
    static const QRegularExpression phrase(QStringLiteral(
        "^(?:(?:in|en|dentro|dentro de|hace)(?: \\d{1,3}(?: [a-z]*)?)?|next(?: [a-z]*)?|proxima(?: [a-z]*)?"
        "|semana(?: q[a-z]*)?(?: v[a-z]*)?|\\d{1,3} [a-z]*(?: a[a-z]*)?)$"));
    if (phrase.match(q).hasMatch())
        return true;
    const int space = q.lastIndexOf(QLatin1Char(' '));
    return space > 0 && QRegularExpression(QStringLiteral("^\\d{1,2}:?\\d{0,2}$")).match(q.mid(space + 1)).hasMatch()
           && !dateSuggestions(q.left(space), now, true, 1).isEmpty();
}

} // namespace pkm::mention
