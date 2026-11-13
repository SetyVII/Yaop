# Object contract — `yaop-doc/1`

Everything essential about an object travels between platforms without loss: the title (with
formatting and mentions), the type, all properties, its appearance and its references. Editor
content is defined in `spec/text.md`, and merging between devices in `spec/merge.md`.

Tests: `spec/fixtures/object/` (full object round trip) and `spec/fixtures/links/`. They pass in
Go, Kotlin and C++ (`tools/check.sh`).

**`schema_version`**: optional integer at the object root. `1` = this contract (`yaop-doc/1` +
`block_anchor`, `spec/merge.md`). Rules:

- Every client writes it on every object it saves.
- A client must **explicitly reject** (not "best effort") an object whose `schema_version` is
  higher than the one it knows: silent corruption is forbidden (product §16.4).
- The merge takes the **maximum** (associative and commutative, `spec/merge.md` §Merge 6).
- Bump it only for a real wire break; compatible additions do not touch it.

## Fields

| Field | Rule |
|---|---|
| `title` | plain text, derived from `title_runs` with the same rule as a block's `text` |
| `title_runs` | runs of the title (bold, mentions, …). **Only emitted when the title has more than plain text.** If a client that does not edit rich titles changes the plain title, it drops the old runs. The merge treats `title` and `title_runs` as one field (`field_hlc.title`). |
| `type_id` | canonical type |
| `schema_version` | see above |
| `properties` | see below |
| `trashed_at` | ISO-8601 UTC time the object went to the trash; **absent** = alive. It is the object's tombstone (there is no delete action). Restoring = removing the field. Merged by stamp (`field_hlc.trashed_at`, `spec/merge.md`), so a later restore beats an earlier trash and vice versa. Retention (product §3.4): each client deletes the object **locally** when `trashed_at + 30 days <= now` and its tombstone has already travelled (`sync_status = synced`, no queued op); it emits no op. When a remote state merges into an expired result, it is not stored (and the local copy is deleted if it has no pending changes). Empty or unreadable dates never expire. |
| `links` | **derived** from content; never edited by hand nor unioned between devices |
| `property_links` | targets of properties (relations, tags, dates) still present in `properties` |

`content` (markdown per object) **is not part of the contract**: search is local and built from
the blocks. If it arrives, it is kept as passthrough and nothing else.

## Properties

- **Special keys** with their own mapping in each client:
  - `icon`: `{emoji?, icon_name?, color_hex?}`;
  - `cover`: `{asset_id, crop_offset_y?, fit?}`;
  - `aliases`: `[string]`;
  - `summary`: string;
  - `pinned` and `is_template`: `true` or absent;
  - `blocks`: see `spec/text.md`.
- **Passthrough**: any value a client does not model, or has not changed, is sent back
  **exactly** as it arrived. Only what the user edited on that device is re-encoded. So a date
  with an end (`{start,end}`), a nested custom field or a new property from another platform
  is never lost.
- **Object tags**: `properties.tags` = `[name]`. The tag object's key is `tagKey(name)`.

## Habits (`type_id: "habit"`, product §24)

Plain properties; **there are no computed fields** (streak, rate and calendar are computed when
displayed).

| Key | Wire value |
|---|---|
| `habit_kind` | `"check"` \| `"count"` \| `"time"` (default `check`) |
| `habit_goal` | number, goal per period (default 1) |
| `habit_unit` | free text |
| `habit_freq` | `"daily"` \| `"weekdays"` \| `"per_week"` \| `"per_month"` (default `daily`) |
| `habit_days` | `["mon",…,"sun"]` (only with `weekdays`) |
| `habit_times` | number N (with `per_week` / `per_month`, default 3) |
| `habit_slot` | `"morning"` \| `"afternoon"` \| `"evening"` \| `"any"` |
| `habit_start` | date `YYYY-MM-DD` of the first day that counts |
| `habit_state` | `"active"` \| `"paused"` \| `"archived"` |
| `d:YYYY-MM-DD` | **one day's record** (local date, product §20): `true` = done, number ≥ 0 = amount or minutes, `"skip"` = rest day (does not break the streak nor count in the rate). Missing key = not done; unchecking = removing the key |

Each record is a separate key, so the per-key merge (`p:<key>`, `spec/merge.md`) never clashes
between different days, and the same day is resolved by HLC. Records are not stored in the
journal. Fixtures: `fixtures/object/habit.json`, `fixtures/merge/props_habit_days.json`.

## `tagKey(name)`

`"tag_"` + the name without a leading `#`, trimmed, lowercase, spaces turned into `_`, keeping
only letters, digits and `_` (Unicode). E.g. `"My Topic"` → `tag_my_topic`,
`"C++ / Rust!"` → `tag_c__rust`.

## `links` (references coming from content)

Ordered set without duplicates, without the object's own id, of:
- each run of `title_runs` and of the blocks (including group `children`):
  - a mention adds its `object_id`;
  - a tag adds its `tag_id`, or `tagKey(tag_name)` if missing;
  - a date adds `daily-<yyyy-mm-dd>`;
- `embed.object_id` and `bookmark.object_id`;
- `image`, `audio` and `pdf` add `media_<asset_id>`.

## `property_links`

Clients emit the targets of their properties:
- a relation adds its ids;
- `tags` adds `tagKey(name)`;
- a date adds `daily-<date>`.

The merge and the server keep only the ids still present in `properties`, comparing by value,
by `tagKey(value)` and by `daily-<value>`.

Known limitation: without the type's schema, mobile treats any list of strings as a relation.
