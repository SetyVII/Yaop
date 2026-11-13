# Query contract — `yaop-query/1`

A query describes **which objects** come out, **in which order** and **how they are grouped**.
It backs saved sets, the embedded query block, "save backlinks as a query" and every view:
table, list, gallery, kanban and calendar (product §7, §13, §19).

Source of truth for Go (`server/internal/simclient/query.go`, reference), Kotlin and C++. Test:
`spec/fixtures/query/`. With the same vault, the same query and the same `today`, every platform
must return **exactly** the same ids, in the same order, with the same groups.

A query is evaluated **over the objects' wire JSON** (`spec/object.md`), like the merge. It does
not depend on the type's property definitions: operators act on the **shape of the value**. So a
client that does not know a property filters it exactly like one that does.

## Shape

```json
{
  "version": 1,
  "types": ["book", "web"],
  "match": "all",
  "filters": [
    { "prop": "status", "op": "neq", "value": "done" },
    { "match": "any", "filters": [
      { "prop": "tags", "op": "has_any", "value": ["ai"] },
      { "prop": "@refs", "op": "has_any", "value": ["obj-mark"] }
    ]}
  ],
  "sort": [{ "prop": "due_date", "dir": "asc" }],
  "order": "sorted",
  "random_seed": "",
  "group": { "prop": "status", "order": ["not_started", "in_progress", "done"] },
  "limit": 50
}
```

Every field is optional except `version`.

| Field | Rule |
|---|---|
| `version` | `1`. A client **rejects** a query with a higher version than it knows (explicit error, never an approximate result). |
| `types` | canonical types (`spec/object.md`, e.g. `journal`, `web`…). Empty or missing = all. |
| `match` | `all` (AND) or `any` (OR) for `filters`. Default `all`. |
| `filters` | list of conditions or nested groups `{match, filters}`. Empty = no filter (`all` of nothing is true; `any` of nothing is true too). |
| `sort` | criteria in priority order: `{prop, dir}` with `dir` = `asc` (default) or `desc`. |
| `order` | `sorted` (default) or `random`. |
| `random_seed` | seed of the random order (see Order). |
| `group` | `{prop, order?}`: groups the result (kanban, sections). |
| `limit` | integer > 0. Missing or `0` = no limit. Applied to the sorted result **before** grouping. |

Unknown query fields are kept (passthrough) and do not change the result. An unknown `op`,
`match`, `dir` or `order` is an **error**: the whole query fails with `unknown_op`,
`unknown_match`, `unknown_dir` or `unknown_order`.

## Universe

Before filtering, these are always dropped:

- objects with `trashed_at` (the trash is its own view);
- templates (`properties.is_template == true`).

Then `types` is applied against the object's `type_id`.

## Fields (`prop`)

- **A key of `properties`** (`status`, `author`, `tags`…). `blocks` cannot be queried.
- **Pseudo-fields**, starting with `@`:

| Field | Value |
|---|---|
| `@id` | object id |
| `@type` | `type_id` |
| `@title` | `title` (plain text) |
| `@created_at`, `@updated_at` | ISO-8601 UTC wire timestamps. `updated_at` is presentation (`spec/merge.md` §6): the result is deterministic for a given vault, but two devices may differ until they converge. |
| `@pinned` | `properties.pinned == true` |
| `@refs` | sorted union of `links` and `property_links`: everything the object references |

**Save backlinks as a query** = `{ "prop": "@refs", "op": "has_any", "value": ["<target id>"] }`.

## Values

A field's value may be missing, `null`, a string, a number, a boolean, an array or an object.
Rules:

- **Empty** = missing, `null`, `""`, `[]` or `{}`.
- **Text**: strings are compared after lowercasing **code point by code point** with Unicode's
  simple mapping (`Simple_Lowercase_Mapping`; Go `unicode.ToLower` per rune, Kotlin
  `Character.toLowerCase(codePoint)`, Qt `QChar::toLower` per code point). Accents are not
  stripped in version 1.
- **Date**: a string starting with `YYYY-MM-DD`, or an object `{start, end?}` with such strings.
  Its **interval** is `[s, e]`, where `s` and `e` are the first 10 chars of `start` and `end`
  (or of the bare date, with `e = s`; without `end`, `e = s`). Timestamps (`@created_at`) use
  their UTC date.
- **List**: for list operators, a non-empty scalar counts as a one-element list and an empty
  value as `[]`. So `select` and `multi_select` filter the same way.

## Operators

| `op` | `value` | True when |
|---|---|---|
| `eq` | scalar | the value is JSON-equal (numbers by value: `1 == 1.0`; exact strings, no lowercasing). If the object's value is a list: the list contains `value`. |
| `neq` | scalar | not `eq` (a missing value counts as different) |
| `contains` | string | the value is a string containing `value` (both lowercased) |
| `not_contains` | string | not `contains` |
| `starts_with` | string | the value is a string starting with `value` (lowercased) |
| `gt`, `gte`, `lt`, `lte` | number | the value is a number satisfying the comparison; otherwise false |
| `empty` | — | the value is empty |
| `not_empty` | — | not empty |
| `is_true` | — | the value is `true` |
| `is_false` | — | the value is not `true` (missing counts as false, like an unchecked box) |
| `has_any` | list | the object's list shares an element with `value` (exact string equality) |
| `has_all` | list | contains all of `value` |
| `has_none` | list | contains none |
| `before` | date | the value is a date and `e < d` |
| `after` | date | `s > d` |
| `on` | date | `s <= d <= e` |
| `between` | `[a, b]` | overlaps: `s <= b` and `e >= a` |

If the object's value does not have the shape the operator needs (a number with `contains`, a
non-date text with `before`), the condition is **false**, and its negation (`neq`,
`not_contains`) true.

**Relative dates**: in `before`, `after`, `on` and `between`, a date may be `today`, `today+N`
or `today-N` (days). It resolves against the evaluation's `today`: the device's local day
(product §20: time zone and start of day). The fixtures pin it.

## Order

With `order: "sorted"` each `sort` criterion is applied and, as the final tie-break, `@id`
ascending (string comparison by code point). With an empty `sort`, the order is `@updated_at`
descending, then `@id`.

Sort key of a value:

1. **Empty values always go last**, in `asc` and in `desc`.
2. A non-empty list uses its first element; a date object uses its `start`.
3. Between different kinds: boolean < number < string (independent of `dir`, except that
   `desc` reverses the whole order of non-empty values).
4. Booleans `false < true`; numbers by value; strings lowercased (see Text) and compared **by
   code point** (in UTF-16, compare by code point, not by unit: supplementary characters sort
   after U+FFFF).

With `order: "random"`, `sort` is ignored and each object's key is the lowercase hex of
`SHA-256(random_seed + ":" + id)` (UTF-8), ascending. It looks random, is the same on every
device and is stable while the seed does not change.

## Groups

With `group`, the (already sorted and limited) result is split into groups:

- a scalar value's group key is its canonical text: the string as-is, the number in canonical
  JSON (`3`, `2.5`), `true`/`false`;
- a **list** puts the object in one group per distinct element (in a kanban by tags an object
  may appear in several columns);
- a date object groups by its `start` (first 10 chars);
- an empty value goes to the `null` group, always last;
- different values giving the same key (the number `5` and the string `"5"`) share a group,
  ordered by the smallest of those values.

Group order: first the keys of `group.order` that have objects, in that order; then the rest,
ordered by **the value that produced the key** with the Order rules (`asc`: number 9 before 10);
`null` last. Inside each group objects keep the result order.

## Result

- Without `group`: `{ "ids": [...] }`.
- With `group`: `{ "groups": [ { "key": "<key or null>", "ids": [...] }, ... ] }`.

## Where a query is stored

- **Saved set**: an object of type `set` with `properties.query` (the query),
  `properties.views` (list of views, below) and `properties.active_view` (id of the open
  view). Without `views`, clients show `gallery`, `table` and `list`. A set never lists itself.
  Clients that do not model these keys keep them as-is (passthrough, `spec/object.md`).
- **Query block** (planned): block type `query` with fields `query` and `view`
  (`spec/text.md`, in both codecs at once).
- The query travels as **a single value**: the merge treats it like any property (`p:query`,
  last writer wins; `spec/merge.md`).

## Views (presentation; they do not change the result)

```json
{ "id": "v1", "name": "By status", "kind": "kanban", "group_prop": "status",
  "columns": ["@title", "due_date"], "date_prop": "due_date", "cover": true }
```

- `kind`: `table`, `list`, `gallery`, `kanban`, `calendar`.
- `kanban` needs `group_prop` (evaluated as `group: {prop: group_prop}` if the query has no
  `group`).
- `calendar` needs `date_prop`: results are placed on each value's day (intervals span their
  days).
- `table` uses `columns` (fields, `@` ones included). `gallery` may show `cover`.
- An unknown `kind` is shown as `list` and kept when sent back (passthrough).

## Future versions

- Full-text filter (`@text`) over blocks: today search is the local FTS index (product §17.4).
- Accent-insensitive comparison.
- "Collection" source (manual order, product §19), with its per-key merge contract.
