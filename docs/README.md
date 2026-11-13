# YAOP Documentation

Welcome to the technical documentation for **YAOP** (Yet Another Object PKM).

## 1. Where to Start

- **Architecture & cross-platform overview:** [`ARCHITECTURE.md`](../ARCHITECTURE.md)
- **Product decisions & domain rules:** [`docs/product.md`](product.md) and [`docs/habits.md`](habits.md)
- **Wire & protocol specifications:** [`spec/`](../spec) (and shared cross-platform vectors in [`spec/fixtures/`](../spec/fixtures))
- **Roadmap & open work:** [`ROADMAP.md`](../ROADMAP.md)
- **Inspirations & third-party credits:** [`CREDITS.md`](../CREDITS.md)

## 2. Codebase Guides

| Document | Scope | Audience |
|---|---|---|
| [`docs/code/server.md`](code/server.md) | Blind sync server (`yaop-server`), HTTP/SSE/WS endpoints, SQLite storage, rate limits, mDNS, `simclient`, deployment | Backend developers, self-hosters, sync implementers |
| [`docs/code/ui-customization.md`](code/ui-customization.md) | Practical map of colors, fonts, shapes, dock, cards, sheets, and QML/Compose UI components for theming and restyling | UI developers, contributors, AI agents |
| [`docs/code/pkmcore.md`](code/pkmcore.md) | Shared C++20 engine (`editor/` and `vault/`), invariants, tests, and platform bindings | Core, Desktop, and Android developers |
| [`docs/code/desktop.md`](code/desktop.md) | Qt 6 / QML desktop client, SQLCipher store, outbox sync, editor model, CLI flags, self-tests | Desktop developers |
| [`docs/code/mobile.md`](code/mobile.md) | Android Kotlin / Jetpack Compose client, Room + SQLCipher, Keystore vault, sync worker, tablet/phone UI | Android developers |

## 3. Protocol Specifications (`spec/`)

The files under [`spec/`](../spec) define the canonical wire contracts shared across Go (`server`), Kotlin (`mobile`), and C++ (`pkmcore` / `desktop`):

- [`spec/object.md`](../spec/object.md) — objects, types, properties, blocks, habits, whiteboards, derived data
- [`spec/text.md`](../spec/text.md) — rich text blocks and inline spans
- [`spec/merge.md`](../spec/merge.md) — HLC timestamps and convergent field/block merge rules
- [`spec/query.md`](../spec/query.md) — Set filtering, sorting, grouping, and views
- [`spec/crypto.md`](../spec/crypto.md) — HKDF, XChaCha20-Poly1305 envelopes, ID blinding, BIP39 recovery, X25519 QR pairing
- [`spec/sync.md`](../spec/sync.md) — `yaop-sync/1` push/pull oplog, compaction, bootstrap snapshots, resumable chunked assets, live streams

## 4. Platform Entry READMEs

- [`server/README.md`](../server/README.md) — quickstart and operational notes for `yaop-server`
- [`pkmcore/README.md`](../pkmcore/README.md) — C++20 core build & test quickstart
- [`desktop/README.md`](../desktop/README.md) — desktop build dependencies & keyboard shortcuts
- [`mobile/README.md`](../mobile/README.md) & [`mobile/GUIDELINES.md`](../mobile/GUIDELINES.md) — Android build commands and UI conventions
