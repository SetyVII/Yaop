# Roadmap

What is not built yet. Product decisions behind each item are in [docs/product.md](docs/product.md)
(section numbers `§` point there). Platforms: **M** mobile, **D** desktop, **S** server/spec.

Rule for every item that crosses platforms: **contract first** (`spec/*.md` + a fixture), then
mobile and desktop, then a test that both pass.

## In 0.1.0

- E2EE vault: 24-word phrase, HKDF subkeys, XChaCha20 envelopes, blind ids, QR pairing (M, D, S).
- No typed tokens: pairing gives the new device its own token; the first device scans the server's setup QR (M, D, S).
- Blind sync server (`yaop-sync/1`): op log, idempotent push/pull, live stream, chunked media (S).
- Field/block merge with HLC and block anchors, identical on Go, Kotlin and C++ (fixtures).
- Local search and backlinks, journal, tasks, habits, trash with 30-day purge (M, D).
- Query engine + saved sets with gallery, table and list views (M, D).
- Encrypted `.yaop` backup that restores without a server (M, D).
- Optional app lock (PIN / biometrics), off by default (M, D).

## Verification on real devices

- [ ] End-to-end test on a real phone/tablet + desktop + local server: two devices
      converge, server sees no plaintext, both apps start without a server. Today it is only
      simulated in Go (`server/internal/sync/e2e_test.go`).
- [ ] Cross-device checks: QR pairing tablet ↔ desktop by camera; backup exported on one platform
      and restored on the other; a set created on one device shows on the other.
- [ ] `FLAG_SECURE` really blacks out screenshots when the lock option asks for it (M).

## Performance (§17)

- [ ] Search under 100 ms p95 at 50k objects. M: FTS5 table created by SQL in a Room migration
      (`unicode61 remove_diacritics 2`, `prefix='2 3'`), paged results with `bm25`, only
      id + title + snippet. D: SQLCipher built with FTS5 (today it falls back to `searchindex.cpp`).
- [ ] Cold start under 1.5 s on a mid-range tablet (today ≈3 s): open the DB off the main thread,
      do not load every object at start, Baseline Profile.
- [ ] Scale to 200k objects: projected columns for lists, Paging, incremental `backlinks` and
      `prop_index` tables, nothing calls `getAllActiveObjects()` on a hot path (M).
- [ ] Cheaper `runQuery` projection on mobile (no full `toServerJson` per object).
- [ ] Measure: open a 1k-block object (< 150 ms), typing latency (< 16 ms), desktop memory on a
      large vault. A `--bench` mode for the desktop and a script that fails when budgets regress.

## Journal and queries

- [ ] Query in a page = embed block of a saved set (`spec/query.md`), live results, list /
      gallery / table, `/query` picker: desktop done; mobile shows it as a plain embed. Missing:
      "save backlinks as query", live results on mobile.
- [ ] **Collections** (§19): desktop shows items in order, reorders (Alt+↑/↓), removes, adds by
      search and from the object menu (`pkmcore/editor/collection`, `CollectionView.qml`).
      Missing: multi-select add, add from capture, mobile, a per-key merge for `items` (today
      last writer wins on the whole list).
- [ ] Day timeline: captures, shares and edits with their time (§5).
- [ ] Editable daily template; time zone and "day starts at" setting with a shared fixture (§20).
- [ ] Base types **File** and **Table** (§4.1).
- [ ] Layout per type (§14). Saved views per type (sets with `type_view_of`, tabs on the type
      page, rename / delete, last view remembered per device): desktop done, mobile missing.
- [ ] Onboarding tutorial as real, deletable objects, es/en (§21.1).
- [ ] Create types and properties from the mobile UI (desktop already can).

## Object model

- [ ] Mobile reads the standard types from `spec/fixtures/types/standard.json` (`spec/types.md`)
      instead of `DefaultTypes`, with a test on the same fixture. C++, desktop and Go already do.
- [ ] Type templates (§4.3): desktop marks an object as a template (menu), lists them in a
      "Templates" tab of the type, offers "Start from" on empty objects and "Apply a template…"
      (`pkmcore` `applyTemplate`). Missing: mobile, the daily template. Editable icon per object.
- [ ] Change an object's type: contract `spec/schema.md` + fixture, done in Go and desktop; mobile
      still missing.
- [ ] Rename / reorder / change the kind of a property with preview (§16.2, extend
      `spec/schema.md`). Do not delete objects that have backlinks: show them first.
- [ ] Block references (§14): `block_id` on mention runs is in `spec/text.md` and round-trips on Go,
      C++ and Kotlin; desktop counts them (`blockReferenceCounts`). UI (copy reference, counter,
      jump to block) missing on both apps.
- [ ] Version history (§16.3): desktop keeps local snapshots (one per editing session, zstd, 30 days)
      and restores by object or block (`pkmcore/editor/history`, `workspace_history.cpp`); desktop UI
      previews a version with changed/removed blocks and restores one block or all (`VersionPreview.qml`).
      Missing: diffs with a full snapshot every 10, syncing snapshots (needs a contract), mobile.
- [ ] Object menu "…" (Ctrl+.), "Contains" panel (objects a page mentions or embeds) and text
      statistics with dates (`pkmcore/editor/textstats`): desktop done, mobile missing.
- [ ] Kanban and calendar views for sets and collections; editable table columns.
- [ ] Duplicate objects: done on desktop (`pkmcore/editor/objectops`), mobile still missing.
- [ ] Merge objects and tags (rewrites mentions to the survivor).
- [ ] Find and replace inside an object (desktop has find only).
- [ ] Property kinds `content`, `media`, `email`, `phone` (clickable).
- [ ] Multi-select of **objects** in lists (move to collection, trash, change type).

## Desktop

- [ ] Two-pane split and pop-out windows (§9).
- [ ] Editable whiteboard that saves `canvas_data`; placed objects count as references (§10).
      Today the canvas is a visual prototype on both platforms.
- [ ] Document-level undo/redo.
- [ ] Translation catalogs (`.ts`, es/en).
- [ ] Trusted node (§23): thumbnails, video posters, PDF text, compaction, integrity checks;
      mobile consumes them and keeps 90 days of originals (§17.3).

## Capture and recall

- [ ] Unlinked mentions (text that names an object without linking it, no AI): data and "link it"
      on desktop (`pkmcore/editor/unlinked`); not yet in `docs/product.md`, UI and mobile missing.
- [ ] Value view: clicking a topic, category or other select value shows the objects that carry it
      and the values they share, as a radial graph, and saves it as a query. Desktop done
      (`pkmcore/editor/optionview`, `OptionView.qml`); mobile missing.
- [ ] Global and local graph (§10): data done on desktop (`pkmcore/editor/graph`, `Workspace::graphData`); UI and mobile missing.
- [ ] Verify: pasting an image in the desktop editor creates an Image object (§8.2).
- [ ] OCR to suggest image titles; reading progress for web links and PDFs.
- [x] Browser extension that hands captures to the local desktop (§22.5); web highlights.
- [ ] CalDAV / calendar in the journal (§14).

## Ecosystem

- [ ] Markdown export; import with explicit property mapping (Obsidian, Notion, Capacities).
- [ ] Android widgets and quick-settings tiles.
- [ ] MCP server for agents; public API (block contract as tokens).
- [ ] Relay embedded in the desktop (topology D, §22.3); hardened web client (§18).

## Security

- [ ] Key rotation when a device is revoked (`key_epoch`). Design in `spec/crypto.md` §Rotation;
      use a libsodium sealed box or HPKE, no home-made crypto. Needed before anyone uses YAOP
      with other people or as a service.
- [ ] External review of the crypto composition (envelope, HKDF domains, blind ids, pairing).
- [ ] Wrap the desktop's fallback key file (no keychain) with a passphrase.

## UI polish

- [ ] Inline math rendered on mobile (today shown as `$latex$`).
- [ ] Accessibility: semantic labels on every new control (§21.2, still open).

## Tech debt

- [ ] Big mobile files: `BlockListEditor.kt` (4k lines), `BlockItemView.kt` (3k),
      `DailyScreen.kt`, `EditorDialogs.kt`, `EditorToolbar.kt`, `SpacesScreen.kt`. Split a piece
      out when a feature touches it, with a test — never in a blind refactor.
- [ ] Desktop: `editormodel.cpp` (1.9k lines) and `workspace_objects.cpp` (950) can be split the
      same way `workspace.cpp` was.
- [ ] Mobile treats any list of strings as a relation; read property kinds from the type instead.
- [ ] Mobile `customPropertyDefs` only travel as passthrough.
- [ ] `summary` travels as plain text (add `summary_runs` if formatting is wanted).
- [ ] The server keeps an extra AES-GCM `master.key` for its own admin records; it could go.

## Open decisions (§12)

- Customizable dashboard: yes or no (default: no).
- Multiple spaces (default: one, with `space_id` reserved).
- Windows: web client only, or the native Qt client too.
