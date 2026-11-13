# Sync contract — `yaop-sync/1` (blind server)

The protocol devices use to sync **without the server seeing content**. The server is an
append-only log of encrypted operations + blobs + fan-out: it does not validate, merge or
search. Everything it could read arrives inside the envelope of `spec/crypto.md`.

Clients are identified by a token (one per device, `Authorization: Bearer`); responses always
use the same error schema (below). The exact fixtures are in `spec/fixtures/sync/`; the
reference implementation of the abstract log model is `server/internal/simclient/protocol.go`,
and the real server must answer exactly like it.

## The operation (op)

Everything that syncs is an operation:

```json
{
  "op_id": "e59e21af7cb8423ca4a3740d00b0e19c",
  "device_id": "dev_6a9f…",
  "kind": "obj",
  "wire_id": "6f85020f85506181e573ce22c245d578",
  "key_epoch": 1,
  "blob": "<base64 of the spec/crypto.md envelope>"
}
```

- `op_id`: 32 hex chars (random per push). **Idempotent**: unique per vault; retrying it returns
  the original `seq` and does not duplicate the log. Also unique within one push.
- `device_id`: must match the token's device (the server checks; otherwise
  `403 forbidden_device`).
- `kind` and its crypto domain (the subkey that encrypts `blob`, `spec/crypto.md`):

  | kind | meaning | domain | `wire_id` | payload inside the envelope |
  |---|---|---|---|---|
  | `obj` | **full state** of an object | `objects` | blind(real id) | the object's wire JSON: full `yaop-doc/1` (`properties.blocks`), `schema_version`, titles…, and the merge metadata `field_hlc` + `block_hlc` + `deleted_blocks` + `block_anchor` |
  | `type` | type definition (custom property definitions included) | `objects` | blind(type id) | the type's wire JSON |
  | `asset_meta` | the `media_<asset>` object: MIME type and file name travel HERE, encrypted | `objects` | blind(`media_<asset>`) | wire JSON of the media_* object |
  | `derived` | derived artifact (product §23.3) | `derived` | blind(`derived/<objectId>/<kind>`) | artifact manifest (below) |
  | `snapshot` | snapshot of an object, or the bootstrap one | `snapshots` | blind(objectId); blind(`"bootstrap"`) for bootstrap | object state or the bootstrap container (below) |
  | `node_request` | request to the trusted node | `ops` | blind(`derived/<objectId>/<kind>`) or blind(objectId), depending on the request | `{"v":1,"object_id":"<real id>","kind":"<kind>"}` — the real id travels ENCRYPTED |

- `key_epoch`: epoch of the VK used to encrypt (spec/crypto.md §Rotation). The server does NOT
  close epochs: it is an opaque integer for ordering and so a client knows what to decrypt.
- `blob`: the full envelope, base64.

**`obj` carries the full state, not diffs**: a journal with 1k blocks uploads whole on every save
(accepted trade-off: the outbox collapses saves of the same object, zstd evens out the size and
compaction keeps the log short).

### `derived/` artifacts (product §23.3)

```
wire_id        = blind("derived/<objectId>/<kind>")
initial kinds  = thumb.grid | thumb.hero | pdf.text | pdf.pages | video.poster | ocr.text
payload (JSON inside the envelope, domain derived):
  { "v": 1,
    "kind": "pdf.text",
    "source_hash": "<sha256 hex of the original it was derived from>",
    "producer": "yaop-node/<version>/<engine>",
    "data": <artifact JSON: text, pages, base64 byte bins depending on kind> }
```

- `source_hash` tells whether the artifact is still valid for the current original; `producer`
  says who generated it (a better engine = a new version → old artifacts become stale).
- Clients: if a valid artifact exists they use it; otherwise they do the cheap thing and show
  "processing…".

## Endpoints

Authentication: `Authorization: Bearer <device token>` on all of them.

### POST /v1/sync/push

```json
// request
{ "ops": [ <op>, … ] }          // at most 500 ops; at most 64 MiB per request
// response 200
{ "results": [ { "op_id": "…", "seq": 8 }… ],   // in arrival order
  "head_seq": 9 }                              // highest seq of the vault after the push
```

- A repeated `op_id` (in the same push or an earlier one) **does not create a new op**: it
  returns the `seq` it already had.
- `device_id` ≠ token → `403`; unknown kind → `400 bad_kind`; blob > 32 MiB (or > 256 MiB if
  `kind=snapshot`) → `413 too_large`.
- Default quota: 60 pushes/min and 20 000 ops/day per device. Exceeding it → `429 rate_limited`
  with `retry_after`.

### POST /v1/sync/pull

```json
// request
{ "since_seq": 40, "limit": 200 }     // limit: 1..1000, default 200
// response 200
{ "ops": [ { "seq": 41, "op_id": "…", "device_id": "…", "kind": "obj",
             "wire_id": "…", "key_epoch": 1, "blob": "…", "received_at": "…" } … ],
  "head_seq": 9142,
  "has_more": false }
```

- Returns **ALL** ops with `seq > since_seq` in ascending order, never deduplicated by id: if the
  same object was saved 3 times, 3 ops arrive (the client merges each one; the merge is
  idempotent, `spec/merge.md`).
- `has_more=true` means more ops remain: continue with `since_seq = seq of the last one`.
- `since_seq` below the compaction floor → error `oplog_compacted` (below): the client then
  starts from the bootstrap snapshot.

### GET /v1/sync/status

Response:

```json
{ "protocol_version": "1",
  "vault": "9f…",
  "head_seq": 9142,
  "compact_floor": 40,
  "bootstrap_seq": 4911,
  "device_id": "dev_6a9f…" }
```

- **`vault` identifies the server.** The client stores the `vault` of the last handshake; if it
  changes (new, empty or reset server) or `head_seq` is below its cursor, it resets the cursor to
  0 and **re-sends everything it has** (the merge makes it idempotent). Otherwise a new server
  would only receive what is edited afterwards.
- `protocol_version` is `"1"` (string). `compact_floor`: ops with `seq ≤ compact_floor` may no
  longer exist. `bootstrap_seq`: seq of the current bootstrap snapshot (or `null` if none).

### GET /v1/sync/stream (canonical SSE; WebSocket accepted)

- `text/event-stream`, one connection per device (`?device_id=…`), `Last-Event-ID` resumes from
  a `seq`.
- Events (JSON, one per `data:` line):

  ```
  data: {"v":1,"event":"hello","head_seq":9142}
  data: {"v":1,"event":"op","op":{"seq":9143,"op_id":"…","device_id":"…","kind":"obj","wire_id":"…","key_epoch":1,"blob":"…","received_at":"…"}}
  : keep-alive every 20 s (SSE comment)
  ```

- A stream op has EXACTLY the same shape as a pull op; WebSocket uses the same JSON frame by
  frame (`{"event":…}`) over the HTTP upgrade.

### GET /v1/sync/bootstrap

```json
{ "covered_seq": 4900, "seq": 4911, "blob": "<base64 of the kind=snapshot envelope, domain snapshots>" }
```

The latest uploaded bootstrap snapshot (404 if none). A new device: status → bootstrap →
decrypt and apply (below) → `pull(since_seq=covered_seq)`.

## Errors (exact schema, all endpoints)

```json
{ "error": { "code": "not_found", "message": "no bootstrap snapshot yet" } }
```

- Responses: `400` (`bad_request`, `bad_kind`), `401 unauthorized`, `403 forbidden_device`,
  `404 not_found`, `413 too_large`, `429 rate_limited` (+ `"retry_after": 30`),
  `410 oplog_compacted`.
- `oplog_compacted`: `{ "error": { "code": "oplog_compacted", "message": "sync from seq 5 not
  available; bootstrap first", "compact_floor": 40, "bootstrap": { "covered_seq": 4900, "seq":
  4911 } } }` → the client goes to `/v1/sync/bootstrap` and continues from `covered_seq`.
- Version: if the status `protocol_version` is not the one the client speaks, the client does not
  sync and shows `protocol_mismatch` ("update the server or the app"); it never syncs blindly
  (product §16.4).

## Assets (encrypted chunks over a resumable upload)

`/v1/assets` is a resumable upload (sessions with ranges and retries). What is uploaded is
**encrypted** chunks:

- The client splits the original file into 4 MiB chunks (the last one smaller) and **encrypts
  each chunk in its own envelope** (domain `assets`, version `0x01`): AAD
  `assets/<asset_wire_id>/chunk/<n>`, random nonce, current `key_epoch`. No single encrypted
  stream: every chunk is independent so uploads can resume by ranges.
- The `media_<asset>` object (kind `asset_meta`) declares, encrypted:
  `{ "asset_wire_id": …, "chunks": N, "chunk_size": 4194304, "total_plain_size": … }` — plus
  the MIME type and file name, which **never** go in the upload session (only here, encrypted).
- On-demand download chunk by chunk (the server serves chunk n with its size metadata; clients
  ask for the chunk map first); server dedup by hash of the chunk **ciphertext** (product §15.4).
- Thumbnails: same mechanism, domain `thumbs`, always downloaded (15–30 KB, product §17.3).

## Bootstrap snapshot (starting a new device)

An envelope `kind=snapshot` with `wire_id = blind("bootstrap")`, domain `snapshots`, payload
(JSON, zstd level 3 before encrypting):

```json
{ "v": 1,
  "key_epoch": 1,
  "covered_seq": 4900,
  "objects": [ <full wire of each object at its latest known state> ],
  "assets": [ { "wire_id": "…", "chunks": N, "total_plain_size": 3145728 } ] }
```

- Only a client with the full vault uploads it (any can; in practice the trusted node, product
  §23.7). `covered_seq` = the head seq the included state represents.
- A new device consumes it like this: decrypt → insert the objects as-is (no merge: they are full
  states and the old log no longer matters) → register the asset metadata →
  `pull(since_seq=covered_seq)` → apply new ops with `merge` as always.
- Uploading a bootstrap with `covered_seq = head_seq` **moves the compaction floor**: from then
  on the server may delete ops with `seq ≤ covered_seq`.

## Compaction

- The server may only compact/delete **below** the active bootstrap's `covered_seq`
  (`compact_floor = covered_seq`); never above.
- Pull never lies: a client asking for `seq < compact_floor` gets `oplog_compacted` with the
  pointer to the bootstrap, never a silent gap.
- Purging snapshots and tombstones older than 30 days (by `received_at`, product §3.4) is
  orthogonal and does not touch the operational log.

## `node.request`

Encrypted request to the trusted node ("this PDF first", product §23.3): kind `node_request`,
domain `ops`, payload `{"v":1,"object_id":"<real id>","kind":"pdf.text"}`. The node serves it
before its normal scan; its answer is a normal `derived`. The server only sees an opaque blob and
its blind `wire_id`.

## Default limits (configurable on the server)

| Limit | Default |
|---|---|
| ops per push | 500 |
| max request | 64 MiB |
| blob per op | 32 MiB (256 MiB if `kind=snapshot`) |
| push rate | 60/min per device; 20 000 ops/day |
| pull rate | 120/min per device |
| pull `limit` | 200 default, 1000 max |
| open streams | 1 per device |
| asset chunk | 4 MiB |
| snapshot/tombstone retention | 30 days (by `received_at`) |

## Fixtures

`spec/fixtures/sync/*.json` — cases with `initial` (abstract server state) and `steps`
(request → expected response, line by line):

| file | case |
|---|---|
| `push_pull_sequence.json` | push of 3 ops → seq 1..3; another device pulling from 0 sees ALL; a new push → seq 4; pull from 3 sees only the new one |
| `op_id_idempotency.json` | retrying the same `op_id` (e.g. after the Wi-Fi drops) returns the SAME seq and does not duplicate the log (pull proves it) |
| `compaction_bootstrap.json` | uploading a bootstrap with `covered_seq=40` → status shows `compact_floor` and `bootstrap_seq`; pull from 40 OK; pull from 5 → exact `oplog_compacted` |

The abstract model that runs them (`simclient/protocol.go`) is not the server: it is the
executable specification of the semantics (`seq`, `op_id`, compaction, errors); the real
server must produce exactly these responses.
