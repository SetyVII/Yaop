# Backend Documentation (`server/`)

The backend binary (`yaop-server`, Go) implements the server side of **`yaop-sync/1`** ([`spec/sync.md`](../../spec/sync.md)).

It is a **blind sync server**:

- It never decrypts user notes, types, asset metadata, search indexes, or snapshots.
- It stores opaque encrypted operation blobs, assigns monotonic sequence numbers (`seq`), enforces per-op idempotency (`op_id`), relays opaque ciphertext asset chunks with ciphertext SHA-256 deduplication, relays ephemeral X25519 pairing envelopes, and fans out live notifications over SSE or WebSocket.
- Only server-level administrative bookkeeping (device enrollment registry and server identity) is encrypted at rest by the server's own AES-256-GCM vault ([`internal/crypto/vault.go`](../../server/internal/crypto/vault.go)).

---

## 1. Directory & Package Map

| Path | Responsibility |
|---|---|
| [`server/cmd/yaop-server/main.go`](../../server/cmd/yaop-server/main.go) | CLI entrypoint, flag/env parsing, secret loading/generation, SQLite store init, `-fsck`, `-healthcheck`, mDNS registration, HTTP server & graceful shutdown |
| [`server/internal/api/server.go`](../../server/internal/api/server.go) | HTTP router (`net/http.ServeMux`), CORS, security headers, bearer auth dispatch, gzip response wrapper, health & sync error formatting |
| [`server/internal/api/devices.go`](../../server/internal/api/devices.go) | Master vs per-device token authentication, device enrollment/rotation/revocation, IP failed-auth rate limiter |
| [`server/internal/api/limits.go`](../../server/internal/api/limits.go) | Rolling-window per-device rate limiter (`PushPerMin`, `PullPerMin`, `OpsPerDay`) returning HTTP `429` with `retry_after` |
| [`server/internal/api/handlers_sync.go`](../../server/internal/api/handlers_sync.go) | `/v1/sync/*` handlers and maintenance cleanup (`PurgeExpired`) |
| [`server/internal/api/handlers_assets.go`](../../server/internal/api/handlers_assets.go) | `/v1/assets/{wire_id}/*` resumable ciphertext chunk upload/download and `Range` serving |
| [`server/internal/api/handlers_pairing.go`](../../server/internal/api/handlers_pairing.go) | `/v1/pairing/*` ephemeral public-key & wrapped-VK relay |
| [`server/internal/sync/engine.go`](../../server/internal/sync/engine.go) | `Engine` implementing push validation/serialization, pull pagination, compaction floor checks (`410 oplog_compacted`), bootstrap promotion, snapshot expiration |
| [`server/internal/sync/hub.go`](../../server/internal/sync/hub.go) | `Hub` fanning `LoggedOp` frames out over SSE (`GET /v1/sync/stream`) and WebSocket (single active stream per device) |
| [`server/internal/store/sqlite.go`](../../server/internal/store/sqlite.go) | Dual SQLite WAL pool (1 write connection with `_txlock=immediate` + read-only pool), schema creation, `system/key-check` verification |
| [`server/internal/store/oplog.go`](../../server/internal/store/oplog.go) | Append-only `oplog` persistence, `op_id` idempotency, cursor queries, bootstrap compaction |
| [`server/internal/store/chunks.go`](../../server/internal/store/chunks.go) | `op_chunks`, `opaque_blobs`, `asset_uploads` tables and SHA-256 ciphertext deduplication |
| [`server/internal/store/pairings.go`](../../server/internal/store/pairings.go) | Ephemeral `pairings` storage and TTL purge |
| [`server/internal/store/batch.go`](../../server/internal/store/batch.go) & [`meta.go`](../../server/internal/store/meta.go) | AES-256-GCM sealed `records` table + `meta` key/value helpers + `Fsck()` |
| [`server/internal/crypto/vault.go`](../../server/internal/crypto/vault.go) | AES-256-GCM (`Vault`) for server administrative records at rest |
| [`server/internal/discovery/mdns.go`](../../server/internal/discovery/mdns.go) | mDNS announcement for `_yaop-sync._tcp` (`yaop-sync/1`) |
| [`server/internal/simclient/`](../../server/internal/simclient) | Go reference blind client + abstract `OpLog` executable model used by e2e tests and cross-platform fixture verification (`merge`, `query`, `pairing`, `aead`, `hkdf`, `envelope`, `blindid`, `bip39`, `backup`) |

---

## 2. Configuration, Flags & Startup Files

Defined in [`registerFlags`](../../server/cmd/yaop-server/main.go#L42-L52):

| CLI Flag | Environment Variable | Default | Purpose |
|---|---|---|---|
| `-listen` | `YAOP_LISTEN` | `0.0.0.0:8765` | Bind host and port |
| `-data-dir` | `YAOP_DATA_DIR` | `./data` | Directory holding `yaop.db`, `master.key`, `bearer.token` |
| `-allowed-origins` | `YAOP_ALLOWED_ORIGINS` | `""` | Comma-separated CORS allowed origins (empty = reflect request `Origin`) |
| `-mdns` | — | `true` | Announce `_yaop-sync._tcp` on the local network |
| `-init-secrets` | — | `false` | Generate `master.key` and `bearer.token` in `-data-dir` and exit |
| `-fsck` | — | `false` | Decrypt and GCM-verify 100% of AES-sealed admin `records`, then exit |
| `-healthcheck` | — | `false` | Probe local `GET /health` and exit non-zero on failure |

Generated secrets in `-data-dir` (permissions `0600`, dir `0700`):
- `master.key`: 32-byte key for `crypto.Vault` (AES-256-GCM) protecting `records`.
- `bearer.token`: 32-byte hex-encoded **master bearer token** used only to enroll/revoke devices.

Every `15 minutes` ([`main.go`](../../server/cmd/yaop-server/main.go#L139)), background housekeeping purges snapshot ops older than `SnapshotRetention` (30 days) and expired pairing rows older than `PairingTTL` (`10 minutes`).

---

## 3. Authentication & Security Headers

### 3.1 Roles & Tokens ([`devices.go`](../../server/internal/api/devices.go))
1. **Master token (`bearer.token`)** — compared via SHA-256 + `subtle.ConstantTimeCompare`. Grants `roleMaster`, allowed to call `POST /v1/devices/enroll` and `DELETE /v1/devices/{id}` (cannot push ops, since pushes verify `device_id` against a device token).
2. **Per-device token** — generated randomly (32 random bytes hex-encoded) by `POST /v1/devices/enroll`. Only `sha256(token)` is persisted in the encrypted `records` table (`kind='system'`, `id='devices'`). Re-enrolling a `device_id` rotates its token and invalidates the old hash immediately. `LastSeen` updates are throttled to at most once every 10 minutes.
3. **Failed-auth lockout (`authLimiter`)** — 10 invalid attempts from the same IP within a `10 minute` window return HTTP `429 rate_limited` with `Retry-After: 600`. Valid tokens bypass the IP block.

### 3.2 Transport & Response Headers ([`server.go`](../../server/internal/api/server.go#L130-L161))
Every response includes:
- `X-YAOP-Api-Version: 0.1.0`
- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`

Public routes (no `Authorization: Bearer` required):
- `GET /health`
- `/v1/pairing*` (protected instead by the unguessable 128-bit `pairing_id` capability and short TTL)

JSON `GET` responses support gzip when `Accept-Encoding: gzip` is sent (disabled for `/stream`, `/content`, and `/chunk`).

---

## 4. HTTP & Streaming API Surface

All error responses use the schema `{"error": {"code": "...", "message": "...", ...extra}}` ([`SyncError`](../../server/internal/sync/engine.go#L131-L160)).

### 4.1 Health & Devices

| Method & Path | Auth | Behavior |
|---|---|---|
| `GET /health` | Public | Blind health check: returns `{status, protocol_version, head_seq, api_version}` |
| `POST /v1/devices/enroll` | Master | Body `{device_id, name}` -> issues/rotates token `{device_id, token}` |
| `GET /v1/devices` | Master or Device | Lists registered devices (without `token_hash`) and caller's `you` `device_id` |
| `DELETE /v1/devices/{id}` | Master | Marks device `revoked=true` and drops active `token_hash` |

### 4.2 Blind Sync (`yaop-sync/1`)

| Method & Path | Auth | Request / Response Summary |
|---|---|---|
| `GET /v1/sync/status` | Master or Device | Returns `{protocol_version: "1", vault, head_seq, compact_floor, bootstrap_seq, device_id}` |
| `POST /v1/sync/push` | **Device only** | Body `{ops: [{op_id, device_id, kind, wire_id, key_epoch, blob}]}` (max 64 MiB body). Validates `op_id` (32 hex chars), `kind` in `{"obj","type","asset_meta","derived","snapshot","node_request"}`, `op.device_id == token.device_id`, and base64 blob size. Appends atomically, assigns monotonic `seq`, returns `{results: [{op_id, seq}], head_seq}` and fans out fresh ops to `Hub`. |
| `POST /v1/sync/pull` | Master or Device | Body `{since_seq, limit}` (max 1 MiB body). If `since_seq < compact_floor`, returns HTTP `410 oplog_compacted` with `compact_floor` and `{covered_seq, seq}`. Otherwise returns `{ops: [LoggedOp...], head_seq, has_more}` in ascending `seq`. |
| `GET /v1/sync/stream` | Master or Device (`?token=` fallback allowed for WS) | Opens live fan-out for a device (replaces any prior stream for that `device_id`). Speaks **SSE** by default (`Last-Event-ID` or `?since_seq=` supported for catch-up replay) or **WebSocket** when `Upgrade: websocket` is present. |
| `GET /v1/sync/bootstrap` | Master or Device | Returns `{covered_seq, seq, blob}` for the active promoted bootstrap snapshot |
| `POST /v1/sync/bootstrap` | Master or Device | Body `{op_id, covered_seq}`. Validates the snapshot op exists and advances `compact_floor`, compacting prior oplog entries below `covered_seq`, and returns the full bootstrap snapshot object. |

### 4.3 Resumable Ciphertext Asset Chunks (`/v1/assets/{wire_id}/*`)

Offsets and lengths count **chunks** (4 MiB plaintext chunk + client envelope overhead, bounded by `MaxAssetChunkBytes = 4 MiB + 4 KiB`), never plaintext bytes:

| Method & Path | Headers / Body | Behavior |
|---|---|---|
| `GET /v1/assets/{wire_id}/upload` | — | Returns `{stored_chunks, next_chunk, total_chunks, complete, total_size}` |
| `PUT` or `PATCH /v1/assets/{wire_id}/upload` | `Upload-Offset: <n>`, `Upload-Length: <N>`, raw ciphertext body | Deduplicates blob bytes by SHA-256 (`X-Chunk-Deduplicated: 1` when reused). Returns `204` + `Upload-Offset: <next>` while incomplete, or `201` JSON `{complete: true, ...}` once all `0..N-1` chunks exist. (`PATCH` is accepted alongside `PUT` for HTTP clients like Android `HttpURLConnection`.) |
| `GET /v1/assets/{wire_id}/chunks` | — | Lists stored chunk indices, ciphertext sizes, and SHA-256 hashes |
| `GET /v1/assets/{wire_id}/chunk/{n}` | Optional `If-None-Match`, `Range` | Serves ciphertext chunk `n` as `application/octet-stream` with `ETag` and `206 Partial Content` support |
| `GET /v1/assets/{wire_id}/content` | Optional `Range` | Concatenates chunks across chunk boundaries with `206 Partial Content` support |

### 4.4 Ephemeral Pairing Relay (`/v1/pairing*`)

Relays X25519 ephemeral public keys (32 raw bytes, unpadded `base64url`) and the wrapped-VK envelope (`MaxPairingTransferBytes = 4 KiB`, `PairingTTL = 10 min`) keyed by 32 lowercase hex chars (`pairing_id`):

- `POST /v1/pairing` `{pairing_id, eph_pub}` — QR holder announces pairing (`201`).
- `GET /v1/pairing/{id}` — polls transport status (`eph_pub`, `has_join`, `has_transfer`).
- `POST /v1/pairing/{id}/join` `{b_pub}` — joining device posts its ephemeral public key.
- `GET /v1/pairing/{id}/answer` — QR holder polls `{b_pub}`.
- `POST /v1/pairing/{id}/transfer` — QR holder uploads raw opaque wrapped-VK bytes.
- `GET /v1/pairing/{id}/transfer` — joining device downloads raw wrapped-VK bytes.

---

## 5. Limits Summary

| Limit | Default | Location |
|---|---|---|
| Max pushes / min / device | `60` | [`limits.go`](../../server/internal/api/limits.go#L18-L20) |
| Max pulls / min / device | `120` | [`limits.go`](../../server/internal/api/limits.go#L18-L20) |
| Max ops / day / device | `20,000` | [`limits.go`](../../server/internal/api/limits.go#L18-L20) |
| Max ops per push | `500` | [`engine.go`](../../server/internal/sync/engine.go#L47-L56) |
| Max push request body | `64 MiB` | [`handlers_sync.go`](../../server/internal/api/handlers_sync.go#L10) |
| Max op blob size (normal / `snapshot`) | `32 MiB` / `256 MiB` | [`engine.go`](../../server/internal/sync/engine.go#L50-L51) |
| Pull page limit (default / max) | `200` / `1000` | [`engine.go`](../../server/internal/sync/engine.go#L52-L53) |
| Max asset chunk payload | `4 MiB + 4 KiB` | [`server.go`](../../server/internal/api/server.go#L82) |
| Pairing TTL / max transfer payload | `10 min` / `4 KiB` | [`server.go`](../../server/internal/api/server.go#L86-L90) |
| Snapshot oplog retention | `30 days` | [`engine.go`](../../server/internal/sync/engine.go#L54) |

---

## 6. SQLite Schema & Concurrency

[`store.Open`](../../server/internal/store/sqlite.go#L41-L83) opens two SQLite handles in `WAL` mode:
- `writeDB`: `SetMaxOpenConns(1)`, `_txlock=immediate`, serialized by `Store.mu`.
- `readDB`: `_query_only=1`, pool size `max(4, NumCPU*2)`, so concurrent pulls and streams never block behind a write transaction.

Tables initialized in [`initSchema`](../../server/internal/store/sqlite.go#L99-L149):
- `records(kind, id, payload)` — AES-256-GCM sealed server config (`system/key-check`, `system/server-id`, `system/devices`).
- `meta(key, value)` — integer/string sync cursors (`compact_floor`, `bootstrap_seq`, `bootstrap_op_id`).
- `oplog(seq, op_id, device_id, kind, wire_id, key_epoch, blob, received_at)` — append-only op log (`seq` is `INTEGER PRIMARY KEY AUTOINCREMENT`, `op_id` is `UNIQUE`).
- `op_chunks(wire_id, chunk_seq, hash, size)` & `opaque_blobs(hash, size, payload)` — content-addressed ciphertext chunk storage.
- `asset_uploads(wire_id, total_chunks, updated_at)` — declared chunk totals for validation/resume.
- `pairings(pairing_id, eph_pub, b_pub, transfer, created_at, updated_at)` — ephemeral pairing relay state.

---

## 7. mDNS Service Discovery

When `-mdns=true` (default), [`discovery.StartAnnounce`](../../server/internal/discovery/mdns.go#L28-L35) registers:
- Service: `YAOP` (`_yaop-sync._tcp`, domain `local.`) on the listening port.
- TXT records: `id=<serverID>`, `protocol=yaop-sync/1`, and (when non-empty) `tls=1`, `fingerprint=<sha256(serverID:bearerToken)>`.

---

## 8. Running, Building & Deployment

### Local Development
```bash
# Start a throwaway dev server on 127.0.0.1:8765 (state in ~/.yaop-dev)
./server/dev.sh 8765

# For an Android device connected via USB:
adb reverse tcp:8765 tcp:8765
```

### Tests & Verification
```bash
# Run all Go unit + end-to-end + cross-platform fixture tests
cd server && go test ./...

# Or via the repository check script
./tools/check.sh server
```

### Containers & Production Reverse Proxy
- **Docker / Compose:** [`server/Dockerfile`](../../server/Dockerfile) builds a multi-stage CGO-enabled binary inside Debian Slim running as UID/GID `1000` with `-healthcheck`; [`server/compose.yaml`](../../server/compose.yaml) drops all Linux capabilities (`cap_drop: ALL`), sets `no-new-privileges:true`, and mounts `/data/yaop`.
- **systemd + Caddy TLS:** [`server/deploy/yaop-server.service`](../../server/deploy/yaop-server.service) binds `127.0.0.1:8765` with strict systemd sandboxing (`ProtectSystem=strict`, `IPAddressAllow=localhost`), paired with [`server/deploy/Caddyfile`](../../server/deploy/Caddyfile) (`flush_interval -1` so SSE streams are not buffered, `max_size 60MB` request body).
