# Merge contract — `yaop-sync/1`

Source of truth for Go (`server/internal/simclient`), Kotlin (`mobile/.../core/sync`) and C++
(`pkmcore/editor/merge.*`). The fixtures in `spec/fixtures/` are mandatory for all three; if a
platform diverges, its suite fails.

Everything operates on the object's **wire JSON** (what travels in sync), not on each client's
internal model.

**`block_anchor`** (block anchors) makes ordering **associative**:
`merge(merge(a,b),c) == merge(a,merge(b,c))` with three devices. If some block arrives without
an anchor, ordering uses the fallback rule (see *Order without anchors*); it is the only
non-associative case and clients never produce it: they always write anchors.

## HLC

- Format: `<13-digit wall>-<4 hex counter>-<8 hex node>`, e.g. `1794404334793-0000-a1b2c3d4`.
- `node` = the first 8 lowercase hex chars of `SHA-256(device_id)` in UTF-8. E.g.
  `device_id = "pc"` → `d8463bd3`. The server uses `00000000`, only for ops that arrive
  without a stamp.
- Total order: wall, then counter, then node. Stamps without a node (`<wall>-<counter>`) sort
  before one with a node and the same wall and counter. `""` is the smallest of all.
- Every client calls `receive(remote)` with every HLC it receives (pull, SSE), so its next
  stamp is greater than any it has seen.

## Per-object metadata

```json
"field_hlc":      { "title": "<hlc>", "type_id": "<hlc>", "order": "<hlc>", "trashed_at": "<hlc>", "p:<propKey>": "<hlc>" },
"block_hlc":      { "<blockId>": "<hlc>" },
"deleted_blocks": { "<blockId>": "<hlc>" },
"block_anchor":   { "<blockId>": { "after": "<blockId or \"\" for the start>", "hlc": "<hlc>" } }
```

- Blocks are identified by `id` in `properties.blocks` (top level only; a `group` counts as a
  single block, including its `children`).
- `p:<propKey>` covers every key of `properties` except `blocks`.
- `order` is the sequence of top-level block ids.
- A missing stamp counts as that side's object `hlc`.
- `block_anchor[key]` records **where each block sat relative to its predecessor** in the last
  layout its author saw: `after` = id of the preceding block (`""` if it was first) when its
  predecessor last changed; `hlc` = stamp of that anchoring event (creation or any save that
  changed its predecessor). **Tombstones keep their entry**: a deleted block still holds the gap
  that orders its followers.

## Stamp (done by the client on save)

`stamp(prevWire | null, nextWire, prevMeta, now) → meta`. Starting from `prevMeta`:

1. `title`, `type_id`: if the value changed → `field_hlc[k] = now`.
2. `trashed_at`: if its presence or value changed (trash or restore) →
   `field_hlc.trashed_at = now`. With `prev = null` it is stamped only if `next` has it.
3. For each property key (except `blocks`) present in `prev` or `next`: if its JSON value
   changed (including appearing or disappearing) → `field_hlc["p:"+k] = now`.
4. For each block of `next`: if new or its JSON changed → `block_hlc[id] = now` (and it is
   removed from `deleted_blocks` if there).
5. **Anchoring**: for each top-level block of `next` whose predecessor changed relative to
   `prev` (new ones included; a first block's predecessor is `""`),
   `block_anchor[id] = { after: <predecessor in next>, hlc: now }`. Deleted blocks keep their
   previous entry (ghost tombstone).
6. For each block of `prev` missing in `next` → `deleted_blocks[id] = now`, removed from
   `block_hlc`.
7. If the id sequence changed → `field_hlc.order = now`.
8. The object `hlc` becomes `now` if anything changed; otherwise `meta` is returned unchanged.

With `prevWire = null` (new object) every present field and block is stamped, and each block is
anchored to its real predecessor in `next` (the first one to `""`).

JSON equality is structural: key order inside a JSON object does not matter, and `1` equals
`1.0`.

Invariants every client must keep (the fixtures check them):

- `block_anchor` covers every live top-level block of the saved sequence and rendering it
  reproduces exactly that sequence.
- The anchor is causal: its `hlc` is never lower than the `hlc` of the object that wrote it.

## Merge (deterministic and symmetric: `merge(a,b) == merge(b,a)`)

For each side `s`, `h(s, key)` is the stamp of `key` in `s`, or else `s.hlc`.

1. **title, type_id**: the side with the greater `h` wins. On a tie, the value with the
   lexicographically greater canonical JSON wins.
2. **Properties**: for each key `k` present on either side (or in its stamps), the side with
   the greater stamp wins. If that side no longer has the key, the key disappears. A side that
   never had the key nor its stamp does not take part.
   2b. **`trashed_at`** (trash): same rule as a property, with stamp `field_hlc.trashed_at`
   (or the side's `hlc` if missing). A side without the field and without its stamp does not
   take part (it has seen neither the trash nor a restore), so editing on another device
   without knowing about the trash does not resurrect the object. The greater stamp wins; if
   that side lacks the field, the object stays alive. Tie → greater canonical value.
3. **Blocks**: for each id in the union, the candidate is the one held by the side with the
   greater `block_hlc` (tie → greater canonical JSON). `tomb = max(deleted_blocks[id])` over
   both sides; if `tomb >= hlc(candidate)` the block dies, otherwise the candidate stays. An
   edit after a delete revives it.
4. **Order (with anchors)**: build the merged `block_anchor`: for each id present on either
   side, the entry with the greater `hlc` wins (tie → the one with the canonically greater
   `after`). The resulting sequence is **rendered as an RGA forest**:
   - a block's parent is the block it follows according to its winning anchor; an unknown or
     `""` `after` sends it to the root;
   - siblings under the same anchor are ordered by anchoring event, **most recent first** (the
     newest insertion sits closest to the anchor); tie by id with the greater canonical JSON;
   - roots are ordered the same way;
   - tombstones are not shown but keep their gap: the children of a dead anchor appear where
     the dead one was;
   - if anchors form a cycle (two concurrent moves pointing at each other), the cycle breaks by
     re-rooting its member with the oldest anchoring event (tie: canonically smaller id).

   **Order without anchors**: if some surviving block has no entry in the merged
   `block_anchor`, the order uses the whole-sequence rule: `primary` is the side with the
   greater `h(s,"order")` (tie → the id list with the greater canonical JSON); the sequence
   starts from `primary` filtered to surviving blocks, and each block only the other side knows
   is inserted after its nearest already placed predecessor; after the same anchor, the most
   recent sits closest. This rule is **not associative** with three devices when an anchor is
   inferred after a concurrent delete (covered by `spec/fixtures/merge_assoc/`); clients
   always write anchors, so it only applies to hand-made data.
5. **Resulting meta**: `field_hlc` = max per key; `block_hlc` = the chosen candidate's stamp;
   `deleted_blocks` = max per id over both sides, minus the ids that survived; `block_anchor` =
   the merged one from step 4.
6. `hlc` = the max of both sides. `schema_version` = **the max of both** (associative,
   commutative and idempotent like the rest; a side without a version loses to any known
   version). `created_at` = the smallest non-empty one. `content` and `updated_at` come from the
   `primary` side (local presentation metadata, **not part of the convergence guarantee**).
   `links` = the union, sorted.

Tasks have no special case: their `status` is just a property (`p:status`).

## Merge algebra: convergence with a blind server

The server is blind: each client runs `merge(local, remote)` per operation, in any arrival
order. For everyone to converge, the merge must be a commutative, idempotent semigroup over the
synced data:

- **Idempotent**: `merge(a, a) == a`. The whole merge is LWW by stamp; re-applying changes
  nothing.
- **Commutative**: `merge(a, b) == merge(b, a)`. Each field's winner is chosen by (stamp,
  canonical value), a total order, not by input side.
- **Associative**: `merge(merge(a,b),c) == merge(a,merge(b,c))` in any permutation. It holds
  because **the synced state is a union of closed data and the result is a pure function of
  that union**, never of the grouping:
  - every field carries its own stamp and the result takes the maximum — "max" is
    associative;
  - `block_anchor` merges per id taking the max by (event hlc, `after`), and the sequence is
    rendered by a deterministic function of the merged map. Inferring anchors *at merge time*
    is what breaks associativity; with anchors recorded at write time, a deleted block's gap is
    always the same, whatever the merge order.

Clients emit `block_anchor` (stamp step 5) and therefore satisfy all three laws;
`spec/fixtures/merge_assoc/` checks them over the 6 permutations of three devices, and the
deterministic Go fuzz test (`TestMergeAlgebra_Fuzz`, 400 seeds) checks them on random states
with concurrent inserts, deletes, moves and edits.

## Fixtures

- `spec/fixtures/hlc/*.json` — `{ "cases": [{ "a", "b", "cmp" }] }`
- `spec/fixtures/stamp/*.json` — `{ "prev", "next", "prev_meta", "now", "expect_meta",
  "expect_hlc" }`; `expect_meta.block_anchor` when the save re-anchors.
- `spec/fixtures/merge/*.json` — `{ "a", "b", "expect" }` (pairwise); the runner checks
  `merge(a,b)` and `merge(b,a)` against `expect`, comparing `title`, `type_id`, `properties`,
  `hlc`, `field_hlc`, `block_hlc`, `block_anchor` and `deleted_blocks`.
- `spec/fixtures/merge_assoc/*.json` — three devices: `{ "ancestor", "a", "b", "c", "expect" }`;
  the runner checks that **every** grouping and permutation of `merge` over `a`,`b`,`c` gives
  exactly `expect` (idempotency, commutativity and associativity).
