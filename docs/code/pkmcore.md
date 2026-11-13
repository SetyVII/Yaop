# C++ Core Documentation (`pkmcore/`)

`pkmcore` is the standalone C++20 domain, editor, merge, query, and cryptography library embedded by the desktop application (and usable independently via CMake `add_subdirectory(pkmcore)`).

---

## 1. CMake Targets & Build Options

Defined in [`pkmcore/CMakeLists.txt`](../../pkmcore/CMakeLists.txt):

| Target | Dependencies | Purpose |
|---|---|---|
| `pkmcore` (STATIC) | `Qt6::Core`, `Qt6::Gui` | Object/type/block model, block editing operations, rich-text span codec, convergent HLC merge, Set query engine |
| `pkmcore_vault` (STATIC) | `Qt6::Core`, `libsodium`, `libzstd` | E2EE vault crypto (XChaCha20-Poly1305, HKDF-SHA256, X25519, ID blinding), BIP39 recovery phrase + embedded English wordlist (`:/crypto/bip39_english.txt`), `.yaop-backup` archives, remote op/sync policy helpers |
| `pkmcore_tests` | `pkmcore`, optional `pkmcore_vault` | CTest test runner executing unit tests and cross-platform fixture verification against `spec/fixtures/` |
| `pkmcore_tour` | `pkmcore` | Minimal standalone usage example ([`examples/tour.cpp`](../../pkmcore/examples/tour.cpp)) |

Options:
- `-DPKMCORE_VAULT=ON|OFF` (default `ON`)
- `-DPKMCORE_TESTS=ON|OFF` (default `ON`)
- `-DPKMCORE_EXAMPLES=ON|OFF` (default `ON`)
- `-DPKMCORE_SPEC_DIR=<path>` (defaults to `../spec`)

---

## 2. `editor/` Module (`pkmcore`)

Implements the canonical document, merge, and query contracts from [`spec/object.md`](../../spec/object.md), [`spec/text.md`](../../spec/text.md), [`spec/merge.md`](../../spec/merge.md), and [`spec/query.md`](../../spec/query.md).

| Files | Responsibility |
|---|---|
| [`model.h`](../../pkmcore/editor/model.h), `model_runs.cpp`, `model_blocks.cpp`, `model_object.cpp` | Canonical C++ structs (`Object`, `ObjectType`, `Block`, `InlineRun`, `Hlc`, whiteboard/habit data) and JSON/wire serialization (`yaop-doc/1`) |
| [`blockops.h`](../../pkmcore/editor/blockops.h), `blockops.cpp` | Pure block editing operations: split, merge, indent/outdent, move, toggle todo/callout/toggle, slash command transforms, span toggles |
| [`spancodec.h`](../../pkmcore/editor/spancodec.h), `spancodec.cpp` | Bidirectional conversion between `InlineRun` spans (bold, italic, code, strike, highlight, link, mention `@`, tag `#`, named palette colors) and Qt rich-text/HTML fragments for editing |
| [`merge.h`](../../pkmcore/editor/merge.h), `merge.cpp` | Convergent per-field LWW / block-list merge driven by Hybrid Logical Clocks (HLC) |
| [`query.h`](../../pkmcore/editor/query.h), `query.cpp` | Set query evaluator: property filters, full-text/substring filters, sorting, and grouping across active objects |

---

## 3. `vault/` Module (`pkmcore_vault`)

Implements the end-to-end encryption, backup, and remote-operation contracts from [`spec/crypto.md`](../../spec/crypto.md) and [`spec/sync.md`](../../spec/sync.md).

| Files | Responsibility |
|---|---|
| [`vaultcrypto.h`](../../pkmcore/vault/vaultcrypto.h), `vaultcrypto.cpp` | HKDF-SHA256 subkey derivation from the Vault Key (VK), XChaCha20-Poly1305 AEAD, compressed wire envelopes (zstd level 3), HMAC/HKDF ID blinding (`wire_id`), X25519 QR pairing & 6-digit verification code derivation |
| [`bip39.h`](../../pkmcore/vault/bip39.h), `bip39.cpp` | BIP39 24-word mnemonic encoding/decoding & validation against the bundled wordlist |
| [`backup.h`](../../pkmcore/vault/backup.h), `backup.cpp` | Portable encrypted `.yaop-backup` archive read/write |
| [`remoteops.h`](../../pkmcore/vault/remoteops.h), `remoteops.cpp` | Decryption/dispatch helpers for incoming `yaop-sync/1` operations (`obj`, `type`, `asset_meta`, `derived`, `snapshot`, `node_request`) |
| [`nodeops.h`](../../pkmcore/vault/nodeops.h), `nodeops.cpp` | Client handling for `node_request` operations and vault-level coordination |
| [`syncpolicy.h`](../../pkmcore/vault/syncpolicy.h) | Shared retry/backoff/batching policy constants matching the sync contract |
| [`bootstrapsnapshot.h`](../../pkmcore/vault/bootstrapsnapshot.h) | Bootstrap snapshot payload packing and unpacking for compacted oplog catch-up |

---

## 4. Building & Running Tests

```bash
cmake -S pkmcore -B pkmcore/build
cmake --build pkmcore/build -j
ctest --test-dir pkmcore/build --output-on-failure

# Or via the repository check script
./tools/check.sh core
```

Tests in [`pkmcore/tests/`](../../pkmcore/tests):
- [`editor_tests.cpp`](../../pkmcore/tests/editor_tests.cpp) — block ops, span codec roundtrips, HLC merge fixtures, query evaluation.
- [`vault_tests.cpp`](../../pkmcore/tests/vault_tests.cpp) — HKDF, XChaCha20-Poly1305 envelopes, ID blinding, BIP39, pairing, and backup fixtures.
