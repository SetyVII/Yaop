# pkmcore

A C++20 core for **object-based, block-editing, local-first PKM apps**:
the data model, the editor semantics, rich text, a conflict-free merge and a query engine,
plus an optional end-to-end-encryption module. No UI, no database, no network — bring your own.

It is the core of [YAOP](../README.md), but it is meant to be lifted out: take the parts you
need, delete the rest.

## What is inside

| Module | Files | Depends on |
|---|---|---|
| **Object model** — objects, flat blocks with indent, rich-text runs, JSON codecs, markdown/HTML import-export, HLC clocks | `editor/model.h`, `model_*.cpp` | Qt Core |
| **Editor ops** — Enter/Backspace rules, markdown shortcuts (`# `, `- `, `[] `…), toggles, groups, indent/move/duplicate, list renumbering | `editor/blockops.*` | model |
| **Rich text** — runs ↔ `QTextDocument`/HTML, marks, colors, links, mentions | `editor/spancodec.*` | Qt Gui |
| **Merge** — per-field and per-block merge with hybrid logical clocks and block anchors; deterministic, symmetric, associative | `editor/merge.*` | model |
| **Queries** — filters, sorts, groups, limits over objects (sets, kanban, tables) | `editor/query.*` | Qt Core |
| **Vault** *(optional)* — 24-word phrase (BIP39), HKDF subkeys, XChaCha20-Poly1305 envelopes, blind ids, QR pairing, encrypted backup file, sync op handlers | `vault/*` | libsodium, zstd |

Every behavior that matters is pinned by the JSON fixtures in [`../spec/fixtures`](../spec/fixtures)
and described in [`../spec/*.md`](../spec). The same fixtures pass in Go and Kotlin, so a
port to another language has a ready-made test suite.

## Build

Needs Qt 6.5+ (Core, Gui), CMake 3.21+, a C++20 compiler; libsodium and zstd for the vault.

```bash
cmake -S . -B build -G Ninja
cmake --build build
QT_QPA_PLATFORM=offscreen ./build/pkmcore_tests   # runs against ../spec/fixtures
./build/pkmcore_tour                              # the example below
```

Without crypto: `-DPKMCORE_VAULT=OFF`. In your own CMake project:

```cmake
add_subdirectory(pkmcore)
target_link_libraries(my_app PRIVATE pkmcore)            # + pkmcore_vault if you want E2EE
```

## Tour

From [`examples/tour.cpp`](examples/tour.cpp):

```cpp
Object note;
note.blocks = markdownToBlocks("# Shopping\n- milk\n- bread");

// Enter in the middle of "bread" splits the block, like any block editor.
auto split = blockops::splitOnEnterAtOffset(note.blocks.last(), 3);

// Two devices edit offline. stamp() records what changed; mergeWire() converges.
note.stamp(nullptr, newHlc());
Object a = note, b = note;
a.title = "Groceries (A)";                 a.stamp(&note, newHlc());
b.blocks.append(markdownToBlocks("- eggs")); b.stamp(&note, newHlc());
QJsonObject merged = mergeWire(a.toJson(true), b.toJson(true));  // keeps both edits

// Queries run over the same wire JSON.
auto r = query::evaluate(QJsonDocument::fromJson(
    R"({"version":1,"filters":[{"prop":"@title","op":"contains","value":"groc"}]})").object(),
    { merged }, "2026-01-01");
```

## Design notes

- **Blocks are flat.** A list of blocks with an `indent` level, not a tree. Toggles hide the
  deeper blocks that follow them; groups are runs of blocks with the same `group_id`. Flat
  lists are easy to merge, diff and render in a list view.
- **Wire JSON is the source of truth for merge and queries.** `mergeWire` and
  `query::evaluate` take `QJsonObject`, not C++ structs, so they match the other platforms
  byte for byte.
- **Pure functions.** Nothing here touches disk or network; the host app does that. The only
  injected dependency is `vault::SecretStore` (where to keep the vault key).

## Tests

`tests/editor_tests.cpp` and `tests/vault_tests.cpp`, with a tiny harness in `tests/testing.h`
(`CHECK` counts failures and keeps going). Point `PKMCORE_SPEC_DIR` elsewhere to run them
against another fixture corpus.

## License

MIT, like the rest of the repository.
