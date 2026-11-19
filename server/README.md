# yaop-server — the blind sync server (`yaop-sync/1`)

Go server for YAOP's blind protocol (`spec/sync.md`). It has exactly one job: **store and
forward bytes it cannot read**. It orders operations (`seq`), guarantees idempotency by `op_id`,
fans out live (SSE/WebSocket) and serves encrypted blobs by chunks. **It sees no content**: no
titles, types, blocks, file names or MIME types, journal dates, reference graph or meaningful ids
(product §15.1: only blind `wire_id`s). There is no search, backlinks, journal or merge on the
server: each client does all of that, checked by the fixtures in `spec/`.

E2EE is the client's business (`spec/crypto.md`): XChaCha20-Poly1305 envelopes with per-domain
subkeys, `wire_id = HMAC(ids, real_id)[0:32]` and zstd before encrypting. The reference
implementation used by the tests lives in `internal/simclient` (not part of the binary). The
server's AES-256-GCM `master.key` is only an **extra** at-rest layer for its own admin records
(devices, identity), never what protects content.

## Build and test

```bash
cd server
go vet ./...
go test ./... -count=1          # api, store, sync (e2e), simclient, cmd
```

A throwaway local server to try things by hand:

```bash
D=$(mktemp -d)
go run ./cmd/yaop-server -data-dir "$D" -init-secrets
go run ./cmd/yaop-server -listen 127.0.0.1:8766 -data-dir "$D" -mdns=false &
cat "$D/bearer.token"                 # master token of THAT test server
curl -s 127.0.0.1:8766/health
```

`server/dev.sh [port]` does the same with data in `~/.yaop-dev`. Open `yaop.db` with `sqlite3`
and you will only see tables of opaque blobs and service metadata.

## The op log

Table `oplog(seq INTEGER PK, op_id TEXT UNIQUE, device_id, kind, wire_id, key_epoch, blob BLOB,
received_at)`. The `blob` is the client's envelope (base64 on the wire, raw bytes on disk): the
server never opens it.

- **Seq**: assigned by the server in arrival order. `head_seq` marks the end of the log.
- **Idempotency**: `op_id` is unique per vault; a retry (in the same push or a later one) returns
  the original `seq` and does not duplicate the log.
- **Full pull**: `pull(since_seq)` returns ALL later ops in ascending order, never deduplicated —
  if an object was saved 3 times, 3 ops arrive and the client merges each one (the merge is
  deterministic and idempotent, `spec/merge.md`).
- **Compaction**: only below the active bootstrap snapshot's `covered_seq`, never above.
  Promoting a bootstrap compacts automatically. A pull below the floor answers
  `410 oplog_compacted` pointing at the bootstrap — never a silent gap.
- **Purge**: `kind=snapshot` ops older than 30 days (by `received_at`) disappear; the active
  bootstrap never does. Orthogonal to the operational log.
- **Valid kinds** (all the server understands about an op): `obj`, `type`, `asset_meta`,
  `derived`, `snapshot`, `node_request`. Anything else → `400 bad_kind`.

## Endpoints

Authentication: `Authorization: Bearer <token>` on every sync endpoint; tokens are per device
(`/v1/devices`), the master token only enrolls and revokes. Errors always use the same schema:

```json
{ "error": { "code": "unauthorized", "message": "...", … } }
```

| Endpoint | What it does |
|---|---|
| `GET /health` | Binary status: protocol, head_seq. No content. |
| `GET /v1/sync/status` | `{protocol_version:"1", vault, head_seq, compact_floor, bootstrap_seq, device_id}` |
| `POST /v1/sync/push` | `{ops:[op…]}` → `{results:[{op_id,seq}…], head_seq}` in arrival order |
| `POST /v1/sync/pull` | `{since_seq, limit}` → `{ops:[…], head_seq, has_more}`; continue with `since_seq = seq of the last one` |
| `GET /v1/sync/stream` | Canonical SSE (`?device_id=…`, `Last-Event-ID` resumes from a seq, `: keep-alive` every 20 s); HTTP upgrade → WebSocket with the same JSON frame |
| `GET /v1/sync/bootstrap` | `{covered_seq, seq, blob}` of the active bootstrap snapshot (404 if none) |
| `POST /v1/sync/bootstrap` | `{op_id, covered_seq}`: promotes an already pushed snapshot; moves the floor and compacts |
| `POST /v1/devices/enroll`, `GET /v1/devices`, `DELETE /v1/devices/{id}` | Devices and per-device tokens |
| `GET /v1/assets/{wire_id}/upload` | Resumable session state: stored chunks and where to continue |
| `PUT\|PATCH /v1/assets/{wire_id}/upload` | Uploads the chunk at `Upload-Offset:<n>` of `Upload-Length:<N>` (encrypted bytes) → `204` + next `Upload-Offset` · `201` when complete |
| `GET /v1/assets/{wire_id}/chunks` | Size and hash of each chunk: the map clients use to request ranges |
| `GET /v1/assets/{wire_id}/chunk/{n}` | Ciphertext of chunk n (ETag = sha256) |
| `GET /v1/assets/{wire_id}/content` | Chunks concatenated; cross-chunk `Range` → `206` |
| `POST /v1/pairing` and family | Pairing transport (below) |

There are no content endpoints (search, objects, journal): the server cannot read content.

### Live stream

```
data: {"v":1,"event":"hello","head_seq":9142}
id: 9143
data: {"v":1,"event":"op","op":{"seq":9143,"op_id":"…","device_id":"…","kind":"obj","wire_id":"…","key_epoch":1,"blob":"…","received_at":"…"}}
: keep-alive                    ← comment every 20 s
```

A stream op has EXACTLY the shape of a pull op. Reconnecting: `Last-Event-ID: <seq>` (SSE) or
`?since_seq=` (WS): the server replays everything after it and stays live. A device keeps ONE
stream open; a new one replaces the old (a zombie connection never blocks the device).

## Assets: encrypted, resumable, deduplicated chunks

The client encrypts EACH chunk in its own envelope (domain `assets`, AAD
`assets/<wire_id>/chunk/<n>`), so an upload resumes chunk by chunk after a network cut:
`GET .../upload` says where to continue. The server deduplicates by **ciphertext** hash (product
§15.4): the same chunk uploaded under another `wire_id` takes no new space
(`X-Chunk-Deduplicated: 1`).

**MIME type and file name never travel in clear**: they are encrypted inside the
`media_<asset>` object that the client pushes as an `asset_meta` op. Content is always served as
`application/octet-stream` — only whoever holds the vault key knows the real type.

## Connecting the first device (setup QR)

With no device enrolled yet, show a QR on the server's terminal and scan it from the phone's
onboarding ("Connect a server" → scan). It carries the URL, the pinned TLS fingerprint and the
master token, which the phone swaps for its own token right away:

```bash
yaop-server -data-dir "$D" -print-setup -public-url https://yaop.example.net | qrencode -t ansiutf8
```

`qrencode` is the standard package of the same name. Anyone who sees that QR holds the master
token: show it only to yourself. Every later device pairs from an enrolled one and gets its own
token in the pairing (below), so nothing is typed.

## Pairing (blind transport)

`spec/crypto.md` defines the cryptography; this server only relays opaque bytes with a short TTL
(10 min) and deletes what expires:

```
POST /v1/pairing {pairing_id, eph_pub}    — A registers the QR key
POST /v1/pairing/{id}/join {b_pub}        — B answers
GET  /v1/pairing/{id}/answer → {b_pub}    — A waits for the answer
POST /v1/pairing/{id}/transfer <bytes>    — A leaves the wrapped VK
GET  /v1/pairing/{id}/transfer → <bytes>  — B picks it up
POST /v1/pairing/{id}/enroll {device_id, name, enroll_key} → {device_id, token}  — B's token
```

The joining device has no token yet: the capability is the QR's random 16-byte `pairing_id`, and
the human check is the 6-digit code both screens show. The server never sees the shared secret,
the code or the VK (only an opaque blob ≤ 4 KiB).

**B's token (step 7 of `spec/crypto.md`).** When A sends the transfer with its own
`Authorization: Bearer` token (device or master) and `X-YAOP-Enroll-Key: <64 hex>`, the pairing
may mint one device token. `enroll` answers `200 {device_id, token}` once and only with that
`enroll_key`: `403` wrong key, `404` no authorized transfer, `409` already redeemed or a
`device_id` that already exists (it never rotates another device's token). Without the header
the transfer still relays and B pastes a token by hand.

## Limits

Defaults are those of `spec/sync.md` (engine limits in `internal/sync.Limits`, per-device pacing
in `internal/api.RateLimits`):

| Limit | Default |
|---|---|
| ops per push | 500 (`400 bad_request` with a clear message above) |
| max request | 64 MiB (`413 too_large`) |
| blob per op | 32 MiB · 256 MiB if `kind=snapshot` |
| push rate | 60/min and 20 000 ops/day per device (`429 rate_limited` + `retry_after`) |
| pull rate | 120/min per device |
| pull `limit` | 200 default, 1000 max |
| open streams | 1 per device (the new one replaces the old) |
| asset chunk | 4 MiB + envelope |
| snapshot / pairing retention | 30 days / 10 minutes |

## Flags

| Flag | Default | Description |
|---|---|---|
| `-listen` | `0.0.0.0:8765` | Address and port |
| `-data-dir` | `./data` | Database and secrets directory |
| `-allowed-origins` | `""` | Allowed CORS origins, comma-separated |
| `-mdns` | `true` | mDNS announcement `_yaop-sync._tcp` with `protocol=yaop-sync/1` |
| `-init-secrets` | `false` | Generates `master.key` and `bearer.token` and exits |
| `-print-setup` | `false` | Prints the first-device setup QR payload (`spec/crypto.md`) and exits |
| `-public-url` | `$YAOP_PUBLIC_URL` | URL devices use to reach the server (for `-print-setup`) |
| `-fsck` | `false` | Checks every admin record decrypts with the master key |
| `-healthcheck` | `false` | Local GET to `/health`, exits 0/1 |

Environment variables: `YAOP_LISTEN`, `YAOP_DATA_DIR`, `YAOP_ALLOWED_ORIGINS`, `YAOP_MASTER_KEY`,
`YAOP_BEARER_TOKEN`. Maintenance (purging snapshots and pairings) runs every 15 minutes and also
when a bootstrap is promoted.

## Deploying (systemd + Caddy)

Examples in `deploy/`:

- `yaop-server.service`: user `yaop`, listens only on `127.0.0.1:8765`, data in
  `/var/lib/yaop-server`, systemd hardening.
- `Caddyfile`: automatic HTTPS + `flush_interval -1` for the SSE stream.

Or `docker compose up -d --build` with `compose.yaml`.

## Internal packages

| Path | What |
|---|---|
| `internal/api` | HTTP handlers: sync, chunked assets, pairing, devices, limits |
| `internal/sync` | `engine.go` (op log: seq, idempotency, bootstrap/compaction) and `hub.go` (SSE/WS) |
| `internal/store` | SQLite WAL, 1 writer + reader pool: `oplog`, `op_chunks` + `opaque_blobs`, `pairings`, `meta`, AES-GCM records |
| `internal/crypto` | AES-256-GCM at-rest layer (extra protection, not E2EE) |
| `internal/discovery` | mDNS announcement |
| `internal/simclient` | **Reference client for the tests** (merge, stamp, crypto, query, protocol, e2e). Not part of the binary |
