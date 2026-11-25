# Capacities study (2026-11-25)

What Capacities does, seen in a real (free) account, next to what yaop does today. This is input
for decisions, not a decision: anything adopted goes into `docs/product.md` first.

Legend: **seen** = tried in the account · **not seen** = Pro-only or the menu could not be opened
from the test browser (described from Capacities' public behaviour, to verify).

## 1. Daily note (Calendar → Day) — seen

| Capacities | yaop today |
|---|---|
| Coloured weekday label above the title ("Miércoles" green, "Martes" pink), red **Hoy** badge on today | Title "Journal 2026-11-25", no weekday, no badge |
| Title is the long localized date ("25 De Noviembre De 2026") + **Semana 48** | ISO date in the title |
| **‹ Hoy ›** to move by day; **Mes / Semana / Tres días / Día** switch | Calendar page + mini month; no ‹ › on the opened note |
| Tabs inside the day: **Todo · Nota diaria · Creado hoy** (on past days: "Creado en este día") | `JournalPage`: daily, habits, created on this day, referenced — stacked, no tabs |
| The note is created on demand: **+ Nota diaria** button, not before | `journalFor` creates it when the day is opened |
| Section header carries **Revisado** checkbox, voice note and quick-capture buttons inline | Reviewed is a property row |
| Date never shown as a property | Was shown as "loose" — fixed in 8a52e4a |
| Header done in yaop | 7cb8c9d: coloured weekday, Today badge, long local date, week number, ‹ Today › |
| "Creado hoy" list has a count and its own view switch (gallery/list) | Count in the heading, one layout |

## 1b. Around the daily note — seen

| Capacities | yaop today |
|---|---|
| **Record audio** button in the daily-note header (mic icon) | Audio blocks with waveform exist; no one-click record from the journal header |
| **`…` object menu** on the note | Object menu (Ctrl+.) — exists |
| Right side pane with tabs **Enlaces entrantes** (backlinks) and **Objetos contenidos** (embedded objects) | Backlinks / Contains panel — exists |
| Calendar Mes / Semana / Tres días / Día | Calendar page — exists, compare views |

## 2. Type pages (Quotes, Notas diarias) — seen

| Capacities | yaop today |
|---|---|
| Tabs **Vista general** (recently opened + collections) and **Todo** (all objects), **+** adds a saved view | Named views per type as tabs (a63db2b) |
| Built-in **Sin revisar** tab on daily notes (filters reviewed = no) | No built-in unreviewed view |
| Toolbar: count · filter · sort · group · gallery/list/table · settings | Similar in Library |
| **Ir a hoy** button on the daily-notes type | — |
| Gallery cards: type chip, title, tag chips, divider, first lines of the body | `ObjectCard` |

## 3. Editor — partly seen

- Writing in the daily note works inline; the page goes into a focus mode while typing (sidebar
  header and top bar fade out). **seen**
- `@` mention menu and `/` command menu: **not seen** (the test browser types text without the key
  events that open them). yaop already has `@`, `#` and `/` triggers (`BlocksView.qml`) — compare
  by hand.

## 4. Tasks — not seen (Pro)

Settings → Gestión de tareas says tasks are Pro ("Tareas donde las necesitas"). What Capacities is
known to do, to verify with a Pro account before copying:

- A checkbox written inside any note can be turned into a **Task object**: it stays in the note
  where it was written and also appears in the Tasks page and on its due day.
- Task fields: status (not started / in progress / done), due date, priority, and the object it
  belongs to (project, person…).
- Tasks page with fixed views (inbox, today, upcoming, done) and custom ones.
- Tasks can be hidden entirely (Settings → "Ocultar gestión de tareas").

yaop today: tasks are objects with due date and status (§6); Tasks page with quick add and
Today / Upcoming / Done; `todo` blocks exist in text but are plain checkboxes, not task objects.

## 5. Reminders and quick capture — not seen yet

- The rocket button on the daily-note header is quick capture into that day. yaop: Ctrl+N quick
  note (§8.3).
- Reminders: not explored. yaop: ROADMAP (§6, §habits).

## Proposed order (smallest change first)

1. ~~Daily note header~~ — done (7cb8c9d).
1b. ~~Record audio~~ — done (affc170, 0edaead).
2. ~~Unreviewed view~~ — done (c60b224).
3. **Day tabs** (All · Daily note · Created this day) in `JournalPage`, reusing current sections.
4. **Todo block → Task object** (the Capacities task model). Needs a decision in `docs/product.md`
   §6 and a spec + fixture (`spec/text.md` link from block to task id), then Go, Kotlin, C++.
5. Reminders: decision first (§6 lists them as ROADMAP "if asked for").

## Review 2026-11-25: bugs found and what is still missing

Tested with every suite (`tools/check.sh`: server, core, desktop, mobile, extension — all pass)
and the desktop drive scenarios (`--drive <name>`): editor-keys, keys, caret, mention, rename,
rename-ui, dnd, writing, journal-keys, journal-record, journal-unreviewed — all pass.

Bugs fixed:
- Daily note: ↑ from the first line moved the caret to the hidden title and the next keys renamed
  the note (25635f1).
- ‹ › created an empty daily note for every day browsed; now a day without a note opens in the
  calendar with "Start the daily note" (42104ed).
- "Unreviewed only" filter never matched (compared the text "false", a checkbox reads "✓"/"")
  (c60b224).
- Discarding a recording could show "No microphone input" (0edaead).
- The date of a daily note showed as a loose property (8a52e4a).
- Drive scenarios rename, rename-ui, dnd failed because they drove a hidden editor (25635f1).

Writing checked by `--drive writing`: Enter splits, Backspace joins, `- ` bullet, Enter continues
the list, Enter on an empty item leaves it, `[] ` todo, accents/ñ/¿?.

Still missing:
- **Undo/redo inside the text** (Ctrl+Z does nothing in blocks) — ROADMAP.md "Document-level
  undo/redo". The biggest gap when writing.
- **Todo block → Task object** (Capacities tasks) — needs the §6 decision and a spec first.
- **"The day starts at…"** (product §20, DECIDED) is not in the desktop: "today" is the calendar
  date everywhere (journal header, calendar, home).
- **Desktop translations**: desktop strings are English only ("Today", "Week 48"); dates already
  follow the system language.
- Day tabs (All · Daily note · Created this day) in the daily note — not done.
- Reminders — ROADMAP.
- The header, voice note and Unreviewed tab are desktop only; Android has none of them yet.
- The Unreviewed count scans every object of the type on each save while the page is open; fine
  for daily notes, to watch for types with thousands of objects.
- Capacities' `@` and `/` menus were not compared (the test browser cannot open them).
