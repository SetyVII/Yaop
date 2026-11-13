# Habits

Summary in [product.md](product.md) §24; the wire contract is in `spec/object.md` (Habits).

## 1. What a habit is

Something you want to do **regularly**, whose value is in the **series**, not in one time: read
30 min, meditate, drink 2 L, go to the workshop. Unlike a task:

| | Task (product §6) | Habit |
|---|---|---|
| Ends | yes, once (or once per recurrence) | never: logged every period |
| What matters | that it is done | the streak and the rate |
| Missing a day | it becomes overdue | that day stays empty; the series goes on |
| History | not looked at | the centre (heat calendar) |

A recurring task is still a task. They are never converted into each other automatically.

## 2. Principles

1. **Objects are the unit**: a habit is an **object** of the base type **Habit**, with the same
   block editor (for the why, notes, plan). It can be mentioned (`@Read`), tagged, put in
   collections, and it appears in the graph.
2. **The journal is the door**: each record shows on the day it was done (the day's Habits section
   and a timeline line). No blocks are created in the journal.
3. **No computed fields**: streak, rate and calendar are **computed when shown**; never stored.
4. **Local-first and E2EE**: records live inside the encrypted object; the server does not know
   habits exist.
5. **Performance**: logging a habit writes one key; Home reads only today's habits.

## 3. Model

### 3.1 Base type `Habit`

Template properties:

| Key | Kind | Values | Default |
|---|---|---|---|
| `habit_kind` | select | `check` (yes/no) · `count` (amount) · `time` (minutes) | `check` |
| `habit_goal` | number | goal per period for `count`/`time` (e.g. 8 glasses, 30 min) | 1 |
| `habit_unit` | text | "glasses", "pages", "min" | — |
| `habit_freq` | select | `daily` · `weekdays` (given days) · `per_week` (N per week) · `per_month` (N per month) | `daily` |
| `habit_days` | multi-select | `mon`…`sun` (only with `weekdays`) | — |
| `habit_times` | number | N (with `per_week` / `per_month`) | 3 |
| `habit_slot` | select | `morning` · `afternoon` · `evening` · `any` — only sorts the day's list | `any` |
| `habit_start` | date | first day that counts | creation day |
| `habit_state` | select | `active` · `paused` · `archived` | `active` |
| `tags`, icon, color | (common) | | type color |

Type color: **habit green `#86E8A8`** (the same green as "done").

### 3.2 Records: one key per day

Each record is **a property of the habit itself**, with the day's local date (time zone and start
of day of product §20) in the key:

```json
"properties": {
  "habit_kind": "count", "habit_goal": 8, "habit_unit": "glasses", "habit_freq": "daily",
  "d:2026-11-12": 8,
  "d:2026-11-13": 3,
  "d:2026-11-10": "skip"
}
```

| Value | Meaning |
|---|---|
| `true` | done (`check`) |
| number ≥ 0 | amount or minutes logged that day (`count` / `time`) |
| `"skip"` | justified rest day: **does not break the streak** and does not count in the rate |
| (missing key) | not done |

Why this shape, and not a list or a map in a single property:

- **The merge already handles it.** `spec/merge.md` merges per key (`field_hlc["p:<key>"]`).
  Logging the 12th on the phone and the 13th on the desktop offline are **two different keys**: no
  conflict, nothing lost. With a list, one of the two records would disappear (LWW of the whole
  property).
- **Unchecking** = removing the key; the stamp rule already covers a key disappearing.
- **No change to the merge contract** was needed: only `spec/object.md` and new fixtures.
- Cost: ~365 keys per habit per year, about 10–15 KB encrypted. Fine at product §17.1 scale.
  After ~3 years a habit could be **compacted** to one property per year (`y:2024` with a bit
  string) — ROADMAP.

Rejected alternative: storing the record in the day's journal object. It ties the habit to the
journal (clearing a day erases its history), forces creating days just to log, and drawing a
calendar would read N objects.

### 3.3 Computations (only when shown)

- **Period** from `habit_freq`: day (`daily`, `weekdays`), ISO week (`per_week`), month
  (`per_month`).
- **Met** in a period: `check` → a `true` exists; `count`/`time` → sum ≥ `habit_goal` (for
  `per_week`/`per_month`: days with a record ≥ `habit_times`).
- **Due**: with `weekdays`, only the chosen days; when `paused`, none.
- **Streak**: consecutive due periods met, counting back from yesterday (today adds if already
  met, but **does not break it** until the day ends). `skip` neither adds nor breaks.
- **30-day rate**: met / due (without `skip`) over the last 30 days.
- **Best streak** and **total**: over all history; cached in memory, never in the object.

### 3.4 Queries and sets

Habits are objects: they filter by type, tag, `habit_state`, `habit_slot`. Streak and rate are
**not** filterable (they are not properties), but a set view of type Habit can **show** them as
read-only columns.

## 4. Where it lives in the UI

### 4.1 Mobile

| Place | What you see | Interaction |
|---|---|---|
| **Home · Today** | Strip of habits due today, under today's journal and before Tasks: 44 dp rings with icon and progress | Tap = check/uncheck (`check`) or +1 (`count`); long press = quick sheet (amount, minutes, rest, note); "See all" → Habits |
| **Habits** (Search → Types → Habit, or "See all") | List grouped by time of day (Morning · Afternoon · Evening · Any time); each row: icon, name, 7-dot mini week, streak | Tapping the ring logs; tapping the row opens the habit |
| **Habit** (object) | Object header, today's big ring, streak / best streak / 30-day rate, 26-week **heat calendar**, properties and the block editor | Tapping a calendar day logs or corrects that day |
| **Journal** | The day's "Habits" section (those due that day with their state) and timeline lines ("21:40 · Read · 30 min") | Logging from a past day corrects that day |
| **Capture `+`** | A "Habit" card | Creates a new habit (does not log) |

The Home strip hides when there are no active habits. At most 8 rings in the strip; the rest under
"See all" (limits in `mobile/GUIDELINES.md` §6).

### 4.2 Desktop

- Sidebar → **Habits** (under Tasks).
- Left pane: **weekly grid**: rows = habits, columns = the 7 days of this week + streak + rate;
  each cell logs with a click. Arrows change the week.
- Right pane: the open habit (one-year heat calendar, stats, properties and blocks).
- Desktop Home: today's ring strip inside the "Today" column.

### 4.3 Visual notes

- Rings and heatmap track daily completion cleanly without clutter.
- Today counts toward the streak even when today is open (`not yet`), per §2.2.

- **Ring**: white 14% track, progress in habit green with a round cap; done = 100% green fill
  with the icon in `accent-ink`; rest day = dashed track; not due today = 40% opacity.
- **Heat calendar**: 11 dp cells, radius 3, gap 3. Levels: no record = white 7%; partial = green
  35%; met = green 100%; rest = white 7% with a diagonal stripe; future or before `habit_start` =
  no fill, white 10% border. Months in Plex Mono 10 above the grid.
- **Streak**: big number in Inter Tight 34/500 with the word "days" in Doto `ice` (the only Doto
  word on the screen).
- Done on tap: the ring fills with the `bouncy()` spring and a 300 ms green light pulse; respect
  "animations off".

## 5. Not now (ROADMAP)

Push reminders (like tasks), home-screen widgets, "negative" habits (stop doing…), goals that
change over time, importing from other apps, compacting records per year.

## 6. Open

- `skip` unlimited or with a weekly maximum. Until decided: **unlimited**.
