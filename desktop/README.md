# YAOP Desktop

Native desktop client: C++20 + Qt 6 Quick/QML. No Electron, no web view. The data model,
editor rules, merge and crypto come from [`../pkmcore`](../pkmcore); this folder is storage,
sync and UI.

## Build

Needs Qt 6.5+ (Quick, QuickControls2, Qml, Svg, Network, Pdf), CMake 3.21+, Ninja, a C++20
compiler, and `libsodium`, `sqlcipher`, `libzstd`, `qtkeychain-qt6` (dev packages).
Optional: `ffplay`/`ffprobe` for audio blocks.

```bash
./run.sh                                   # configure + build Release once, then run
# or by hand:
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Debug && cmake --build build
./build/yaop
```

## Useful flags

```bash
./build/yaop --profile test                # separate settings, data and keychain entry
./build/yaop --page library | --open <object-id> | --overlay palette
./build/yaop --selftest                    # app tests (storage, onboarding, lock…)
QT_QPA_PLATFORM=offscreen ./build/yaop --profile qa --showcase --size 1280x800 --screenshot out.png
./build/yaop --server https://sync.example.com --token <device-token>
```

`--keylog` prints every key press, the items it was delivered to and the Shortcut that fired
(why a key "misses"); shortcuts live in one table, `src/keymap.cpp` ([docs/keyboard.md](../docs/keyboard.md)).
`tools/qa-desktop.sh <dir>` takes screenshots of every page at several window sizes.
`--drive <scenario>` drives the real editor (`mention`, `rename`, `rename-ui`, `caret`, `dnd`, `keys`, `nav` — pages, sidebar and the shortcut sheet by keys only, `date-mention` — dates typed after `@`; logs and shots in
`YAOP_DRIVE_DIR`); `rename` checks that a mention follows a rename of its target (run it
with `--server`/`--token` too: a sync echo must not undo a title being typed). `rename-ui` does
the same through keys and clicks only (`YAOP_DRIVE_DAILY=1`: in today's daily note,
`YAOP_DRIVE_PREFIX`: text before the mention).

## Shortcuts

| Action | Keys |
|---|---|
| All shortcuts and how to move | `Ctrl+/` or `F1` |
| Today's note / command palette / quick capture | `Ctrl+J` / `Ctrl+K` / `Ctrl+N` |
| Move between items / areas (sidebar, tabs, page, pane) | arrows, `Enter` / `F6`, `Shift+F6` |
| Bold, italic, underline, code, highlight | `Ctrl+B` `Ctrl+I` `Ctrl+U` `Ctrl+E` `Ctrl+Shift+H` |
| Indent / outdent block | `Tab` / `Shift+Tab` |
| Duplicate block | `Ctrl+D` |
| Lock (when the lock is enabled) | `Ctrl+Shift+L` |
| Markdown at line start | `# ` `- ` `1. ` `[] ` `> ` `! ` `>> ` `---` ```` ``` ```` `$$` |
| Inline | `/` blocks · `@` mention · `@today` `@mañana 9` `@now` `@friday` `@+3` `@15:30` `@1/12` · `#tag` |

## Where things are

See [../ARCHITECTURE.md](../ARCHITECTURE.md#desktop). In short: `src/workspace*.cpp` is the
app logic split by concern, `src/store.*` the encrypted local DB, `src/editormodel.*` the model
the QML editor binds to, and `qml/` the UI with all colors in `qml/Theme.qml`.
