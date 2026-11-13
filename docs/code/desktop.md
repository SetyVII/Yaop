# Desktop Client Documentation (`desktop/`)

The desktop client (CMake target `yaop-desktop`, binary `yaop`) is a C++20 + Qt 6 (Qt Quick / QML) application linked directly against [`pkmcore`](pkmcore.md) (`pkmcore` and `pkmcore_vault`), SQLCipher, Qt6Keychain, and libsodium.

---

## 1. Architecture Overview

```text
QML UI (desktop/qml/, qrc:/qml/)
   ▲  Q_PROPERTY / Q_INVOKABLE / Signals
   ▼
Workspace (desktop/src/workspace*.cpp) & EditorModel (desktop/src/editormodel.cpp)
   ├── pkmcore (object model, blockops, spancodec, merge, query)
   ├── pkmcore_vault (XChaCha20-Poly1305, HKDF, blind IDs, BIP39, pairing, backup)
   ├── Store + SearchIndex (SQLCipher + FTS5 + CacheCrypto)
   └── Outbox + SyncClient (yaop-sync/1 push/pull/SSE/chunked assets)
```

---

## 2. C++ Source Map (`desktop/src/`)

| File(s) | Responsibility |
|---|---|
| [`main.cpp`](../../desktop/src/main.cpp) | App initialization (`--profile` isolation, `Basic` QQuickStyle), CLI flags, onboarding gate, lock gate, window creation, headless/QA modes (`--selftest`, `--pushtest`, `--uploadtest`, `--pullcheck`, `--drive`, `--e2e`, `--screenshot`) |
| `workspace.h`, `workspace.cpp`, `workspace_internal.h` | Core QML-facing controller (`ws` context property): lifecycle, vault unlock, navigation helpers, property bindings |
| `workspace_objects.cpp` | Object & type CRUD, pinned objects, daily note lookup, habits, tasks, trash/restore |
| `workspace_search.cpp` | Search queries, Set evaluation (`pkm::query`), backlinks, tag creation |
| `workspace_weblinks.cpp` | URL metadata fetching and bookmark preview hydration |
| `workspace_media.cpp` | Encrypted local asset import/cache, thumbnail generation, PDF/audio/image support |
| `workspace_sync.cpp` & `workspace_backup.cpp` | Sync orchestration with `SyncClient`/`Outbox` and encrypted `.yaop-backup` export/import |
| `store.h`, `store.cpp` | SQLCipher-backed local persistence for objects, types, assets, and vault metadata |
| `searchindex.h`, `searchindex.cpp` | SQLite FTS5 full-text index inside the encrypted local database |
| `cachecrypto.h`, `cachecrypto.cpp` | Encrypted local media/thumbnail cache using keys derived from the Vault Key |
| `outbox.h`, `outbox.cpp` | Persistent sealed operation queue waiting to be pushed to the server |
| `syncclient.h`, `syncclient.cpp` | `yaop-sync/1` HTTP + SSE + resumable chunked asset client |
| `onboarding.h`, `onboarding.cpp` | `VaultOnboarding` & `PairingFlow` QML types (create vault, BIP39 restore, X25519 QR join/host via `qrcodegen`) |
| `applock.h`, `applock.cpp` | Desktop idle/manual passphrase lock (`Ctrl+Shift+L`) and QtKeychain integration |
| `editormodel.h`, `editormodel.cpp` | QML block-list model bridging `QQuickTextDocument` editing, slash commands, mentions, tags, and `pkm::blockops`/`spancodec` |
| `texrender.cpp`, `syntaxhighlight.cpp` | LaTeX math block renderer and fenced code block syntax highlighter |
| `audioplayback.h`, `audioplayback.cpp`, `providers.h`, `providers.cpp` | Audio note playback and Qt Quick image/asset providers |
| `selftest.h`, `selftest.cpp`, `driver.h`, `driver.cpp` | In-binary unit checks (`--selftest`) and scripted UI/sync verification (`--drive`) |

---

## 3. CLI Flags & Testing Utilities

From [`main.cpp`](../../desktop/src/main.cpp#L68-L142):

- `--profile <name>`: isolates `XDG_DATA_HOME`, `XDG_CONFIG_HOME`, and keychain service (`YAOP-<name>`) so test runs never touch your personal vault.
- `--page <name>` / `--open <id>` / `--overlay <name>`: jump straight to a route or object on launch.
- `--server <url>` / `--token <token>`: override saved sync server credentials.
- `--onboarding [step]`: force first-run onboarding UI.
- `--selftest`: run codec, merge, and core self-tests headless and exit `0`/`1`.
- `--pushtest`, `--uploadtest <path>`, `--pullcheck`, `--e2e <step>`: headless sync/e2e diagnostics.
- `--drive <scenario> [--drive-out <dir>]` & `--screenshot <path> [--size WxH]`: automated GUI inspection.

---

## 4. Build & Verification

Prerequisites listed in [`desktop/README.md`](../../desktop/README.md): Qt 6 (`>= 6.5` with `Core Gui Qml Quick QuickControls2 Svg Network Pdf`), `Qt6Keychain`, `libsodium`, `sqlcipher`, `libzstd`.

```bash
cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build -j
./desktop/build/yaop --selftest

# Or via the repository check script
./tools/check.sh desktop
```

For customizing QML styling, fonts, colors, or controls, see [`ui-customization.md`](ui-customization.md) §2.
