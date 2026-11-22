# Text contract — `yaop-doc/1`

How an object's content (`properties.blocks`) travels between platforms. Guarantee: **a
document written on one platform looks identical on the others**, with indentation, lists,
mentions, marks, code and media.

The test is `spec/fixtures/doc/`: each platform reads the blocks and exports them again, and
the result must be **exactly** the canonical JSON. If a platform loses or changes anything, its
suite fails (`tools/check.sh`).

## Block

| Field | Rule |
|---|---|
| `id` | stable; never changes on edit |
| `type` | `paragraph`, `heading`, `bullet_list`, `numbered_list`, `alphabetic_list`, `roman_list`, `todo`, `toggle`, `quote`, `callout`, `code`, `math`, `divider`, `table`, `embed`, `image`, `audio`, `pdf`, `bookmark`, `canvas`, `group` |
| `indent` | 1..8 on the wire = `indentLevel + 1`. **Omitted when the level is 0.** Indentation means ownership: a more indented block is a child of the previous less indented one, and going back to a lower level "leaves" it. |
| `block_color` | palette name (`blue`, `red`, …), never a hex |
| `text` | plain-text shadow, **derived** from `runs` (see below). Never the source of truth. |
| `runs` | the real content (see Spans) |

Per-type fields: `heading.level` (1–4); `*_list.number`; `todo.checked`; `toggle.expanded`;
`callout.callout_type` + `emoji`; `code.language` (text in `text` and in a single run); `math`
as a run `{type:"math", display:true}`; `table.align` + `rows` (`[[{text}]]`);
`embed.object_id` + `view_mode`; `image.asset_id` + `width_percent`; `audio.asset_id` +
`duration_seconds`; `pdf.asset_id` + `page_count` + `last_read_page` + `file_name`;
`bookmark.url/title/description/domain`; `canvas.canvas_data`.

**Groups**: blocks are **flat**; each group member carries `group_id`, and consecutive members
with the same `group_id` form the group. The merge works block by block inside a group too.
Import also accepts the nested shape (`{type:"group", children:[…]}`) and flattens it.

**Unknown fields (passthrough)**: known = `id type indent block_color group_id text runs children
level number checked expanded callout_type emoji language latex display align rows object_id
view_mode asset_id width_percent duration_seconds page_count last_read_page file_name url title
description domain thumbnail_url favicon_url canvas_data`. Any other block field is kept as-is on
export (looked up by `id` in the received blocks), even if the block is edited.

## Spans (`runs`)

| Span | Shape |
|---|---|
| text | `{text, bold?, italic?, underline?, strike?, code?, highlight?, href?, text_color?, bg_color?}`; only active marks (`true`) are emitted |
| mention | `{text: label, object_id, block_id?}`. No `href`; on import a mention `href` is accepted and dropped. With `block_id` it is a **block reference** (product §14): it points at that block inside `object_id`, the UI jumps there, and the block shows how many references point at it. It still counts as a mention of `object_id` in `links`. There is no transclusion: the referenced block is never rendered inline. |
| tag | `{text: "#name", tag_id, tag_name}` |
| date | `{text: iso, date: iso, has_time?}` |
| inline math | `{text: "￼", type: "math", latex, display?}` |

## Normalization rules (same on every platform)

1. **Derived `text`**: the runs concatenated, with a mention as `@label`, a tag as `#name`, a
   date as `iso` (no `@`) and a formula as `￼`. `todo` prepends `○ ` or `✔ `, and `toggle`
   prepends `▶ ` or `▼ `.
2. **Artifact colors**: `#000000`, `#000`, `black`, `#ffffff`, `#fff` and `white` are dropped on
   import (editor leftovers that make text invisible in one of the two themes).
3. **Fractions** (`width_percent`): rounded to 4 decimals, so storing them as `Float` does not
   look like an edit.
4. Adjacent runs with the same marks are merged.

## Changing the contract

A new field or type is added **at the same time** to this document, to a fixture in
`spec/fixtures/doc/` and to both codecs (`SyncContractCodec.kt` and
`model_blocks.cpp`/`model_runs.cpp`). If a codec does not know it, the round trip fails and the
change does not go in. That is the protection against losing data a platform does not
understand.
