// Dates typed after `@` (editor/datequery.h).
#include "testing.h"
#include "datequery.h"

namespace pkm::testing {

namespace {

QString first(const QString &q, bool dayFirst = true)
{
    // Thursday 26 November 2026, 14:07
    const QDateTime now(QDate(2026, 11, 26), QTime(14, 7));
    const auto s = mention::dateSuggestions(q, now, dayFirst);
    return s.isEmpty() ? QString() : s.first().iso;
}

} // namespace

int runDateQueryTests()
{
    const QDateTime now(QDate(2026, 11, 26), QTime(14, 7));
    CHECK(first("today") == "2026-11-26", "@today");
    CHECK(first("Hoy") == "2026-11-26", "@hoy, any case");
    CHECK(first("mañana") == "2026-11-27" && first("manana") == "2026-11-27", "@mañana with or without the accent");
    CHECK(first("tom") == "2026-11-27", "@tom: a word cut short");
    CHECK(first("ayer") == "2026-11-25" && first("yesterday") == "2026-11-25", "@ayer / @yesterday");
    CHECK(first("now") == "2026-11-26T14:07" && first("ahora") == "2026-11-26T14:07", "@now: today at this minute");
    CHECK(first("hora") == "2026-11-26T14:07" && first("hour") == "2026-11-26T14:07", "@hora / @hour: the current time");
    CHECK(first("friday") == "2026-11-27" && first("vie") == "2026-11-27", "@friday: the next one");
    CHECK(first("jueves") == "2026-12-03", "@jueves on a Thursday: next week's");
    CHECK(first("miercoles") == "2026-12-02", "@miércoles");
    CHECK(first("next week") == "2026-12-03" && first("proxima semana") == "2026-12-03", "@next week = in 7 days");
    CHECK(first("+3") == "2026-11-29" && first("-2") == "2026-11-24" && first("+2w") == "2026-12-10", "@+3 @-2 @+2w");
    CHECK(first("in 3 days") == "2026-11-29" && first("en 2 semanas") == "2026-12-10", "@in 3 days / @en 2 semanas");
    CHECK(first("hace 3 dias") == "2026-11-23" && first("3 days ago") == "2026-11-23", "@hace 3 días / @3 days ago");
    CHECK(first("15:30") == "2026-11-26T15:30" && first("9am") == "2026-11-26T09:00" && first("3 pm") == "2026-11-26T15:00",
          "@15:30 @9am @3 pm: today at that time");
    CHECK(first("21h") == "2026-11-26T21:00", "@21h");
    CHECK(first("mañana 9") == "2026-11-27T09:00" && first("friday 15:30") == "2026-11-27T15:30", "a day word then a time");
    CHECK(first("2026-12-01") == "2026-12-01", "@2026-12-01");
    CHECK(first("1/12") == "2026-12-01", "@1/12 is 1 December when the day comes first");
    CHECK(first("1/12", false) == "2027-01-12", "@1/12 is January 12 otherwise (next one)");
    CHECK(first("25/12/27 18:00") == "2027-12-25T18:00", "a full date and a time");
    CHECK(first("1/11") == "2027-11-01", "a day already gone this year is next year's");
    CHECK(first("31/2").isEmpty() && first("25:00").isEmpty() && first("13pm").isEmpty(), "impossible dates and times");
    CHECK(first("ana").isEmpty() && first("book").isEmpty() && first("h").isEmpty() && first("").isEmpty(),
          "names that are not dates suggest none");
    CHECK(first("9").isEmpty(), "a bare number is not a time");
    const auto ma = mention::dateSuggestions("ma", now);
    CHECK(ma.size() == 2 && ma[0].key == "tomorrow" && ma[1].iso == "2026-12-01", "@ma: mañana and martes");
    const auto ho = mention::dateSuggestions("ho", now);
    CHECK(ho.size() == 2 && ho[0].hasTime && !ho[1].hasTime, "@ho: hora (with time) and hoy");
    CHECK(mention::dateSuggestions("now", now).first().key == "now", "the key names the word for the label");
    CHECK(mention::dateSuggestions("hoy", now).first().exact && !ma[0].exact && mention::dateSuggestions("+3", now).first().exact,
          "whole words and numbers are exact, a cut-short word is not");
    CHECK(mention::mayBecomeDate("mañana ", now) && mention::mayBecomeDate("in 3", now) && mention::mayBecomeDate("en 2 sem", now)
          && mention::mayBecomeDate("hace 3", now) && mention::mayBecomeDate("3 days a", now) && mention::mayBecomeDate("next w", now)
          && mention::mayBecomeDate("friday 15:", now) && mention::mayBecomeDate("semana que v", now),
          "phrases still being typed keep the list open");
    CHECK(!mention::mayBecomeDate("ana ", now) && !mention::mayBecomeDate("book club", now) && !mention::mayBecomeDate("", now),
          "names with spaces close it");
    return failures;
}

} // namespace pkm::testing
