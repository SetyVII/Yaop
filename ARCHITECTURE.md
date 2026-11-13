# Architecture

YAOP is three programs that never share code, only **contracts**:

```
 Android (Kotlin/Compose)          yaop-server (Go)            Desktop (C++20/Qt Quick)
 ───────────────────────          ────────────────            ──────────────────────
 local DB (Room+SQLCipher)  ──►   blind op log + blobs   ◄──   local DB (SQLCipher)
 merge, search, queries     ◄──   (cannot read anything) ──►   merge, search, queries
                                         ▲
                       spec/*.md + spec/fixtures/*.json  ← the law for all three
```

- **Local-first.** Each app works fully offline with its own encrypted database. The server is
  optional and only relays encrypted operations between your devices.
- **End-to-end encrypted.** The server stores sealed envelopes and opaque ids. Merge, search,
  backlinks and queries all run on the clients.
- **Contracts, not shared code.** Anything that crosses platforms is written in `spec/` with
  JSON fixtures. Go, Kotlin and C++ each run the same fixtures in their tests. If one platform
  drifts, its tests fail.

## Repository

| Path | What |
|---|---|
| `spec/` | Wire contracts (object, text, merge, query, crypto, sync) + fixtures. Start here. |
| `pkmcore/` | Reusable C++ core: object model, block editor ops, rich text, merge, queries, vault crypto. See [pkmcore/README.md](pkmcore/README.md). |
| `desktop/` | Qt Quick app on top of `pkmcore`: storage, sync loop, UI. |
| `mobile/` | Android app. Same layers, written in Kotlin. |
| `server/` | Blind sync relay in Go, plus `internal/simclient`: the reference implementation of every contract, used by the tests. |
| `docs/` | Product decisions (`product.md`), design system (`design/`), habits. |
| `tools/check.sh` | Runs every test suite. |

## Data model

- **Object**: `id`, `type_id`, title (rich runs), properties, blocks, icon, cover, pinned,
  `trashed_at`, plus merge metadata (`hlc`, `field_hlc`, `block_hlc`, `block_anchor`).
  Everything is an object: notes, journal days, tags, images, web links, people, tasks, sets.
- **Block**: flat list with an indent level (no tree). Types: paragraph, headings, lists, todo,
  toggle, quote, callout, code, math, divider, table, embed, image/audio/pdf, bookmark, canvas.
  Consecutive blocks with the same `group_id` form a group. A toggle hides the deeper blocks
  that follow it. Contract: `spec/text.md`.
- **Run** (inline span): text with marks and colors, or an atomic mention / tag / date / math.
- **Type**: built-in types plus user types with property definitions and templates.

## How an edit travels

1. The editor changes blocks through pure functions (`blockops` on desktop,
   `BlockOperations.kt` on mobile).
2. Save: the object is **stamped** (`stampWire` / `MergeContract.stamp`): every changed field
   and block gets a hybrid logical clock (HLC). It goes to the local DB and an outbox.
3. Push: each outbox entry is sealed into an envelope (XChaCha20-Poly1305 with a per-domain
   subkey, zstd first) under a blind `wire_id`, and sent to `POST /v1/sync/push`.
4. The server appends it to the op log with a `seq` and fans it out on `GET /v1/sync/stream`.
5. Other devices pull (`POST /v1/sync/pull`), open the envelope, and **merge** the remote state
   with the local one (`mergeWire` / `MergeContract.merge`). The merge is deterministic,
   symmetric and associative, so every device converges to the same bytes.

New devices start from an encrypted **bootstrap snapshot** instead of replaying the whole log.
Pairing: a QR with an ephemeral X25519 key, a 6-digit code to compare, and the vault key sent
wrapped. Losing every device is covered by the 24-word phrase and by `.yaop` backup files.

## Desktop

```
desktop/src/
  main.cpp, driver.*          entry point, CLI flags (--selftest, --screenshot, --e2e …)
  workspace*.cpp              the app's brain, split by concern:
    workspace.cpp               startup, vault keys, config, open editors
    workspace_objects.cpp       objects, types, properties, sets, tasks, habits, journal
    workspace_search.cpp        local search, @-mentions, tags, backlinks
    workspace_weblinks.cpp      media objects, web link metadata and covers
    workspace_media.cpp         media files sealed at rest, upload/download
    workspace_sync.cpp          push, pull, live stream, bootstrap, op registry
    workspace_backup.cpp        .yaop export / restore
  store.*, searchindex.*      SQLCipher local DB and its search index
  outbox.*, syncclient.*      push batches and the HTTP/SSE client
  cachecrypto.*               keychain access + media sealing
  editormodel.*               QAbstractListModel the QML editor binds to
  onboarding.*, applock.*     vault creation / pairing, optional lock
  texrender.*, syntaxhighlight.*, providers.*, audioplayback.*   rendering helpers
desktop/qml/                  UI (pages, editor/ block views, Theme.qml tokens)
```

## Mobile

```
mobile/app/src/main/java/org/yaop/
  core/model        PkmObject, Block, InlineSpan, property definitions
  core/database     Room + SQLCipher, FTS
  core/repository   PkmRepository: the single source of data for the UI (Flows)
  core/sync         SyncManager (the loop), SyncContractCodec (wire JSON), MergeContract,
                    SyncPolicy (pure decisions), ops/ (handlers per op kind)
  core/crypto       vault crypto (BIP39, HKDF, AEAD, envelopes, pairing), Keystore wrapping
  core/query        QueryEngine + saved sets
  ui/               Compose screens: home, daily, editor, spaces, tasks, habits, whiteboard…
```

UI rules for mobile (no literal strings, i18n, editor behavior, performance budget) are in
[mobile/GUIDELINES.md](mobile/GUIDELINES.md).

## Server

`server/cmd/yaop-server` → `internal/api` (HTTP routes) → `internal/sync` (`Engine.Push`,
`Engine.Pull`, `Hub` for the live stream) → `internal/store` (SQLite WAL op log and media
chunks). `internal/discovery` announces `_yaop-sync._tcp` on the LAN. API and flags:
[server/README.md](server/README.md).

## Key functions

| Function | Where | What it guarantees |
|---|---|---|
| `stampWire(prev, next, prevMeta, now, metaOut)` | `pkmcore/editor/merge.h` | Marks what changed with an HLC so the merge can decide per field and per block. |
| `mergeWire(a, b)` | `pkmcore/editor/merge.h` | Merges two versions of one object; same result on every device, in any order. |
| `blockops::splitOnEnterAtOffset`, `handleBackspaceAtStart` | `pkmcore/editor/blockops.h` | Enter / Backspace semantics of the block editor (lists, toggles, groups). |
| `blockops::processMarkdownPrefix` | `pkmcore/editor/blockops.h` | `# `, `- `, `[] `, `> `… typed at the start of a block convert it. |
| `blockops::visibleEntries` | `pkmcore/editor/blockops.h` | Which blocks are shown (collapsed toggles hide their children). |
| `span::runsToHtml`, `span::documentToRuns` | `pkmcore/editor/spancodec.h` | Rich text ↔ `QTextDocument`, the live editing loop on desktop. |
| `query::evaluate(query, objects, today)` | `pkmcore/editor/query.h` | Filters, sorts and groups objects for sets and views (`spec/query.md`). |
| `vault::sealEnvelope`, `openEnvelope` | `pkmcore/vault/vaultcrypto.h` | Encrypts one operation; the AAD binds it to its domain and id. |
| `vault::wireId` | `pkmcore/vault/vaultcrypto.h` | Blind id the server sees instead of the real one. |
| `Workspace::saveObject`, `syncNow` | `desktop/src/workspace.h` | Local save + outbox, and one full sync round. |
| `SyncManager.syncNow` | `mobile/.../core/sync/SyncManager.kt` | Same round on Android. |
| `Engine.Push`, `Engine.Pull` | `server/internal/sync/engine.go` | Ordered, idempotent (`op_id`) op log. |

## Extending

| I want to… | Touch |
|---|---|
| a new block or object field | `spec/text.md` / `spec/object.md` + a fixture, then the codecs (`pkmcore/editor/model_*.cpp`, `SyncContractCodec.kt`) |
| a new sync op kind | a handler + one line in `Workspace::buildOpRegistry` / `core/sync/ops/` |
| a new query operator | `spec/query.md` + fixture, then `query.cpp`, `QueryEngine.kt`, `simclient/query.go` |
| colors and fonts | tokens only: `desktop/qml/Theme.qml`, `mobile/.../ui/theme/` |

Rules that keep it simple:

1. **Contract first.** Cross-platform behavior starts as a spec + fixture.
2. **Pure decisions, effects outside.** Ordering, merge, policies are pure functions with tests;
   HTTP, DB and UI call them.
3. **One place per rule.** One kind→key table, one merge, one codec per platform.
4. **Small files.** Split a file when a feature touches it and it mixes concerns.
5. **Failures are values.** An unreadable op is skipped and the cursor moves on; only a real
   pause stops the pull.

## Tests

`tools/check.sh` runs all of them:

- `server`: `go test ./...` (API, op log, e2e of the blind protocol, all contract fixtures).
- `core`: `pkmcore_tests` (model, editor ops, merge, queries, crypto, backup — against `spec/fixtures`).
- `desktop`: `yaop --selftest` (local DB at rest, search, outbox, onboarding, lock, clipboard).
- `mobile`: `./gradlew testStressUnitTest` (same fixtures in Kotlin + editor and policy tests).
