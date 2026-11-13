# YAOP — Yet Another Object PKM

A personal knowledge manager where **most of that is 'cuasi' an object**: notes, journal days, tasks,
people, books, images, web links, tags. Native apps for Android and desktop, local-first and
end-to-end encrypted, with an optional self-hosted sync server that cannot read your data.

- **Android**: Kotlin + Jetpack Compose.
- **Desktop**: C++20 + Qt Quick. No Electron, no web view.
- **Server**: Go. A blind relay: it orders and forwards encrypted operations, nothing else.
- **[pkmcore](pkmcore/)**: the reusable C++ core — object model, block editor, rich text,
  conflict-free merge, queries, vault crypto. Use it in your own app.

## Quickstart

```bash
# Desktop — Qt 6.5+, CMake, Ninja, libsodium, SQLCipher, zstd, QtKeychain
cd desktop && ./run.sh

# Android — open mobile/ in Android Studio, or:
cd mobile && ./gradlew installDebug

# Sync server (optional) — Go 1.24+ and gcc
cd server && go run ./cmd/yaop-server -data-dir ./data
# or: cd server && docker compose up -d --build      (port 8765)
```

Without a server the apps work fully offline. To sync, run the server (on a machine you
control, behind HTTPS — see `server/deploy/`), create the vault on one device and pair the
others with the QR code.

Tests: `tools/check.sh` (or `tools/check.sh server core desktop mobile` one by one).

## Documentation

| Read | For |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | How it fits together, key functions, how to extend. |
| [docs/](docs/README.md) | Documentation hub (backend, UI customization map, C++ core, desktop, mobile). |
| [docs/code/server.md](docs/code/server.md) | Blind sync server (`yaop-server`): APIs, storage, limits, mDNS, deploy. |
| [docs/code/ui-customization.md](docs/code/ui-customization.md) | Where colors, fonts, shapes, dock, and UI controls live for theming. |
| [pkmcore/README.md](pkmcore/README.md) | Using the C++ core on its own. |
| [spec/](spec/) | Wire contracts and fixtures shared by all platforms. |
| [docs/product.md](docs/product.md) | Product decisions and why. |
| [ROADMAP.md](ROADMAP.md) | What is not built yet. |
| [CREDITS.md](CREDITS.md) | Inspirations and third-party code. |

## Status

**0.1.0** — first public version (sync protocol `yaop-sync/1`, document format `yaop-doc/1`).
Usable daily on Android and Linux desktop. Not audited: read the security notes in
[ROADMAP.md](ROADMAP.md) before trusting it with anything critical.

## License

[MIT](LICENSE).
