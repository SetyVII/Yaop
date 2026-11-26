#pragma once

// Dates typed after `@` (product §4.5, spec/text.md date span): `@today`, `@mañana 9`, `@now`,
// `@friday`, `@+3`, `@in 2 weeks`, `@15:30`, `@1/12`, `@2026-12-01`. English and Spanish words,
// accents and case ignored, any word may be cut short (`@tom`, `@vie`). Pure: the clock is a
// parameter, so the same query always gives the same answer in a test.

#include <QDateTime>
#include <QList>
#include <QString>

namespace pkm::mention {

struct DateSuggestion {
    QString key;     // what it is, for the label: now today tomorrow yesterday weekday next-week
                     // offset date time (a time on a day named by `key` of the day part)
    int dayOffset = 0;   // days from today (weekday, offset: the number of days)
    QString iso;     // the span's `date`: "2026-11-27" or, with a time, "2026-11-27T09:00"
    bool hasTime = false;
    bool exact = false;  // the whole word or a number/date/time, not a word cut short (`@ma`):
                         // the `@` list shows exact dates before objects, others after them
};

// Up to [max] suggestions for [query] (the text after `@`), best first; empty when it does not
// read as a date. [dayFirst]: "1/12" is 1 December (es) or January 12 (en).
QList<DateSuggestion> dateSuggestions(const QString &query, const QDateTime &now,
                                      bool dayFirst = true, int max = 4);

// True while [query] may still become a date with more words ("mañana ", "in 3", "hace 2 d"):
// the `@` list stays open across a space only then.
bool mayBecomeDate(const QString &query, const QDateTime &now);

} // namespace pkm::mention
