# Credits

## Inspirations

YAOP borrows ideas, not code, from apps that got objects and blocks right:

- **[Anytype](https://anytype.io)** — the closest relative and the main inspiration: everything
  is a typed object with properties, sets and collections over a query, local-first with
  end-to-end encryption and your own sync node.
- **[Capacities](https://capacities.io)** — object types as the way to think, the daily note
  as the front door, calm media-rich object pages.
- **[Notion](https://notion.so)** — block editor ergonomics: Enter/Backspace rules, `/` menu,
  markdown shortcuts, toggles.
- **[Heptabase](https://heptabase.com)** — whiteboards where cards are real objects.
- **[Vasta](https://github.com/ariedotme/vasta)** — the desktop Slate look: the sidebar with the
  daily places on top, the page as a panel, and the calendar's Day view with the month and habits
  beside it. Rebuilt in Qt Quick; no Vasta code is used (Vasta is AGPL-3.0, YAOP is MIT).
- **[Obsidian](https://obsidian.md) / [Logseq](https://logseq.com)** — journals, backlinks and
  owning your data.

The merge is in the spirit of CRDT sequence work (RGA-style anchors) and hybrid logical clocks
(Kulkarni et al., 2014). The crypto follows libsodium's XChaCha20-Poly1305, HKDF (RFC 5869)
and BIP39 for the recovery phrase.

## Third-party code and assets

| What | Where | License |
|---|---|---|
| QR Code generator (Project Nayuki) | `desktop/third_party/qrcodegen` | MIT |
| Tabler Icons | `desktop/assets/icons` | MIT |
| Inter, Inter Tight | `desktop/assets/fonts`, `mobile/.../res/font` | SIL OFL 1.1 |
| IBM Plex Mono | same | SIL OFL 1.1 |
| Doto | same | SIL OFL 1.1 |
| BIP39 English wordlist | `spec/fixtures/crypto/bip39_english.txt` | MIT (bitcoin/bips) |
| Qt 6 | desktop, pkmcore (linked) | LGPL-3.0 |
| libsodium, zstd, SQLCipher, QtKeychain | desktop (linked) | ISC, BSD, BSD-style, BSD |
| Jetpack Compose, Room, Coil, ZXing, BouncyCastle, zstd-jni, SQLCipher for Android | mobile (Gradle) | Apache-2.0 / MIT / BSD |
| go-sqlite3, gorilla/websocket, zeroconf, x/crypto | server (Go modules) | MIT / BSD |
