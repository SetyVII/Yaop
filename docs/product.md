# YAOP — Product decisions

What YAOP is, what it does and why. Code, specs and other docs cite these sections as
"product §N", so section numbers are stable.

Status of each point:

- **DECIDED** — settled design decision.
- **EXISTS** — implemented; now a contract that must not break.
- **REJECTED** — considered and decided against.
- **ROADMAP** — wanted later, outside the current scope (see [ROADMAP.md](../ROADMAP.md)).
- **OPEN** — not decided yet.

---

## 1. What YAOP is

An object-based PKM for personal use, **local-first and end-to-end encrypted**, inspired by
Anytype (types, sets, collections, local-first E2EE), Capacities (mentions, daily note, queries)
and Thymer (fast capture). Native: Android (Kotlin + Compose) and Linux desktop (C++20 + Qt
Quick), no web views or Electron; an optional, hardened web client later (§18). Each app works
alone, without a server; the Go server is optional and only stores and forwards encrypted data it
cannot read (§15). The daily note is the front door. Every object shows a real preview. Search
without AI rests on queries and sets.

## 2. Principles

1. Correctness > performance > motion.
2. Native and fast with large amounts of information; the performance budget beats decoration.
3. Objects are the unit: media, tags and web links are objects with the same editor.
4. Nothing invisible: everything referenced (image, audio, PDF, link) shows a preview where it is
   mentioned.
5. The journal is the door: whatever comes in is recorded on the day it came in.
6. Search without AI: queries and sets are the main way to find things.
7. My data is readable only with my key: nothing leaves a device unencrypted, not even towards my
   own server. If an optimization needs the server to see content, it is dropped.
8. Always working: every milestone ends with an app usable end to end; compatibility may break
   before the first stable release (§16.4).

---

## 3. Architecture

### 3.1 Platforms — EXISTS

- Mobile: Kotlin + Jetpack Compose, Room + SQLCipher, FTS, a fully native virtualized block
  editor.
- Desktop: C++20 + Qt Quick/QML on top of `pkmcore`, SQLCipher local DB, offline outbox.
- Server: Go, SQLite WAL (1 writer / reader pool), blind op log, WS/SSE, mDNS.

### 3.2 Shared core — DECIDED

Two native apps + a shared spec. Mobile and desktop share no code; they share the **contract**:
`spec/` holds the wire contracts and JSON fixtures (objects, blocks, spans, merge, queries,
crypto, sync), and the same fixtures run in Go, Kotlin and C++. Any divergence breaks the build.

### 3.3 Sync — DECIDED

- HLC per block and per field: non-destructive causal merge; if two devices write the same
  block, the last write wins (single user: accepted). Contract: `spec/merge.md`.
- The merge runs on every client, deterministically, with the same fixtures. The server only
  orders (`seq`) and stores encrypted operations, idempotent by `op_id`.
- No text CRDT (Loro/Yjs rejected for now).
- WS/SSE stream for live updates between devices; mDNS for local discovery.

### 3.4 Security and history — DECIDED

- **Zero-knowledge E2EE**: encryption happens on the client with the vault key, which the server
  never has. Details in §15. The server's own `master.key` is only an extra at-rest layer for its
  admin records.
- Per-object snapshots: one per editing session; **30-day retention**, compressed (§16.3).
- Trash with tombstones — EXISTS; **30-day retention**.

### 3.5 Data format — DECIDED

- Encrypted SQLite is the only local source of truth (Room/SQLCipher on mobile, SQLCipher on
  desktop).
- Markdown is not a mirror; Markdown export is a ROADMAP feature.

---

## 4. Object model

### 4.1 Types

- **Base types, fixed and not deletable**: Tag, Image, Video, Audio, Web link, PDF, File, Journal,
  Person, Book, Project, Note, Task, Whiteboard, Table and Habit (§24). Their chips and aliases
  live in i18n resources (`mention_aliases_*`).
- Ready-made types such as Meeting or Recipe are **user types with a template** (§4.3), not base
  types.
- **User types**: as many as wanted, with their properties and templates. All types use the same
  editor.

### 4.2 Properties — DECIDED

Kinds: text, number, date, select, multi-select, checkbox, URL and `object-ref` to any type
(e.g. `Book.author → @Person`, creatable on the fly). No computed properties, formulas or
rollups — REJECTED.

### 4.3 Templates — DECIDED

Each type defines the template its objects are born with:

- **Book**: author (object-ref Person), genre.
- **Web link**: link, cover image, description (OpenGraph), reviewed (checkbox), content type
  (article, video, podcast…), topics (multi-select).
- **Image/Audio/PDF**: the file plus the editor for its context.
- **Journal**: editable daily template.
- Editable icon/emoji and cover per object, besides automatic link covers.
- New types start with an empty, configurable template.

### 4.4 Blocks

- Block types and 10 semantic colors — EXISTS (`spec/text.md`).
- Indentation: the UI allows 4 levels; data keeps 0..6 (wire 1..8): an import or sync never
  flattens hierarchy — DECIDED.
- Block reference = mention + compact preview. Live embed/transclusion — REJECTED (keeps the
  data model simple).
- Moving blocks between objects (drag / cut-paste) is part of the editor.

### 4.5 Mentions and tags — EXISTS

- `@name`, `@type/name`, create any type on the fly, chips with preview, the last 3 used first,
  aliases per language (`@image/`, `@today`…).
- A type is written as its slug: lowercase, no accents, `-` for spaces (`@web-links/`,
  `@modelos-de-ia/`); `weblinks` or `web_links` also resolve. The `@` list shows matching types
  first (recently used first, user types included), then up to 10 objects that narrow while
  typing, then `+ Create "name" in <Type>`. Picking a type narrows the list to it.
- A mention stores the target id and its title as label; editors refresh the label to the
  current title when they load, so a rename shows everywhere.
- Tags per block and per object, always pointing to the object.
- **A tag exists only when the user picks it — DECIDED**: typing `#word` opens the tag list,
  but it stays plain text until the user picks an existing tag or chooses to create one. No
  client turns `#word` in text into a tag, a tag link or a tag pill on its own: not typed text,
  not a captured web description, not a property.
- **Rich previews in references — DECIDED**: every reference (object mention, tag, object placed
  on a whiteboard) shows in the target's backlinks as a contextual preview: source title + snippet
  of the block that holds the reference. E.g. on object `Mark`, a mention in "talk to @mark about
  the meeting" of `Project Y` shows as `Project Y — …talk to @mark about the meeting…`.

### 4.6 One editor — DECIDED

Every object has the same block editor, tags and images included. Only the hero changes (large
media preview, tag metadata), never the blocks. Pure WYSIWYG: markdown marks are never shown or
typed — EXISTS.

---

## 5. Journal — DECIDED

- The main entry point; a calendar with activity marks per day — EXISTS.
- Sections of the day: tasks, referenced today, created today — EXISTS.
- **Day timeline** (ROADMAP): chronological log of captures, shares, audio and edits with time.
- Things captured from outside are NOT inserted as blocks in the journal: they live in their type
  and show in "created today".
- Editable daily template.

## 6. Tasks — DECIDED

- A task is an object with a due date and status (not started / in progress / done) — EXISTS.
- Shown on Home (at most 5 + "N more") and in the journal of their day — EXISTS.
- ROADMAP if asked for: natural-language dates, weekly planner, push reminders, subtasks.

## 7. Queries and views — DECIDED

- **Embedded query blocks** in any note, with live, filterable results (ROADMAP).
- **Saved sets**: filters over types with table / gallery / list views — EXISTS — plus kanban
  (group by status) and calendar (by date) — ROADMAP. Contract: `spec/query.md`.
- Queries are the main way to find information without AI.
- Customizable dashboard: **OPEN** (§12).

---

## 8. Media, web links and capture

### 8.1 Web links — EXISTS

- Async OpenGraph: description in a property, image as cover, link in a property, "reviewed" as
  part of a personal flow.
- ALWAYS a local copy of the cover (WebP q75, 480 px, 15–30 KB): once copied, the remote is not
  needed (posts disappear); if extraction fails, the user sets a cover.
- Full-article capture and HTML snapshots — REJECTED.

### 8.2 Media — EXISTS

- Image/Video/Audio/PDF are global: every upload creates a canonical object (`media_<assetId>`)
  and its relations to notes show on the object itself.
- Ctrl+V of an image in the desktop editor → Image object (to verify, ROADMAP).
- OCR — ROADMAP: first use case, suggesting an image's title from its text. Audio transcription —
  REJECTED for now.
- Media dedup by hash and EXIF cleanup — ROADMAP.

### 8.3 Capture — DECIDED

- **Android share sheet** from any app: selected text → Note, image → Image, PDF → PDF, link →
  Web link. Async: the object exists at once and is enriched in the background — EXISTS.
- Quick note: radial capture on mobile, Ctrl+N on desktop — EXISTS.
- Home-screen widgets and quick-settings tiles — ROADMAP.

## 9. Desktop — DECIDED

- One window with a two-pane split by default (split: ROADMAP).
- Any pane can pop out into its own window, editable and live-synced (same local DB, observers) —
  ROADMAP.

## 10. Graph and whiteboards

- Graph: **global** (colored by type) + **local per object** (neighbours in the detail rail).
  References show with a block preview (§4.5). Advanced filters — ROADMAP.
- Whiteboards are a base type, so they are graph nodes like any object. Each object placed on a
  whiteboard is a graph edge and counts like a mention (graph, backlinks, "referenced today").
  Connectors inside the whiteboard stay internal (presentation, not knowledge). Today the canvas
  is a visual prototype — editable whiteboards are ROADMAP.

---

## 11. Roadmap

See [ROADMAP.md](../ROADMAP.md). Rule: every milestone ends with an app that works end to end on
Android and desktop and meets the budgets of §17.

---

## 12. Open questions

1. Customizable dashboard: yes or no.
2. Multiple spaces: recommendation = one space, with `space_id` reserved in ids.
3. Bulk import on first run (Obsidian/Notion/Capacities): mapping is decided (§14), formats open.
4. Windows: web client (§18) or a native Qt client later.

Settled: snapshot and trash retention (30 days, §16.3); vault key method (24-word phrase, §15.2);
trusted node (the desktop, §23); reference device (a mid-range Android tablet, §17.2); habits
(§24). Visual styling is left to external design tools and customizable directly via the theme tokens.
Desktop has two looks per device (Settings › Appearance): **Slate** (default: neutral gray, full-height
sidebar, the page as a rounded panel, Calendar/Inbox/Tasks on top, Today = the calendar's Day view)
and **Sky** (the original deep blue with mono labels).

---

## 13. Compared with Capacities / Anytype

- **Public API + developer docs**: DECIDED (ROADMAP).
- **Chat and multi-user collaboration**: REJECTED — YAOP is personal and single-user.
- **Duplicate and merge objects** (same type, merging tags): DECIDED (ROADMAP).
- **Find & replace inside an object**: DECIDED (ROADMAP).
- **Reading progress**: web links keep the reading position and PDFs the current page — ROADMAP.
- **Advanced queries**: sort, group, limit, random order; save the backlinks view as a query;
  each set keeps its view config — DECIDED (`spec/query.md`).
- **Embedded AI** (assistant, local RAG, auto-fill): ROADMAP after the first stable release;
  search without AI stays a principle. OCR titles are the only AI use planned.
- **Offline-first as an explicit contract** (works 100% offline, HLC/LWW per block): DECIDED.
- **"Content" property** (blocks as a property value) and media property: DECIDED (ROADMAP).
- **Publishing objects to the web**: REJECTED (§14).

---

## 14. Integrations and other decisions

- **CalDAV / Google Calendar** in the journal: DECIDED (ROADMAP, high priority).
- **Web highlights + browser extension**: DECIDED (ROADMAP).
- Other inputs (Readwise, messaging/email → journal, two-way task managers): ROADMAP.
- **Sharing objects by link / publishing**: REJECTED — YAOP shares nothing outwards.
- **PDF/CSV export**: later; Markdown export first.
- **Layout per type** (default page layout, saved views, type dashboard): DECIDED (ROADMAP).
- **Email and phone properties** (clickable): DECIDED (ROADMAP).
- **Import with explicit property mapping** (e.g. CSV → phone/email, Markdown + zipped media):
  DECIDED — mapping is a safety requirement to avoid corrupting data on import.
- **Block references with a counter** and jump to the original context: DECIDED. Live
  embed/transclusion stays REJECTED.
- **Public API**: block contract as tokens (`Text`/`Link`/`Math`/`Code`) with hierarchy; append
  only into text or group blocks — DECIDED as the API contract.

---

## 15. Security: local-first + E2EE — DECIDED

### 15.1 Model

- **Local-first**: every client is complete and works forever without a server. A server is
  optional, to sync devices and keep a remote backup.
- **E2EE**: everything that leaves a device (objects, sync ops, snapshots, assets, thumbnails) is
  encrypted. The server stores and forwards blobs it cannot read.
- **Covered threats**: a compromised or stolen server, intercepted traffic (even if TLS fails),
  leaked server backups.
- **Partly covered**: a stolen device, mitigated by the app lock (§15.5) and revocation (§15.3).
  An unlocked device compromised by malware with root access is **out of scope**.
- **Metadata the server does see (accepted)**: random ids, blob sizes, receive times, `seq`,
  device ids, total object and op counts. **It does not see** titles, types, properties, blocks,
  file names, MIME types, journal dates or the reference graph.

### 15.2 Keys

- **Vault Key (VK)**: 256 random bits, generated on the first device. Never leaves a device
  unwrapped.
- **Per-domain subkeys** derived with HKDF-SHA256; XChaCha20-Poly1305 with AAD per domain and id
  (`spec/crypto.md`).
- **Local storage**: the VK is wrapped by the system keystore (Android Keystore; Linux Secret
  Service, or a passphrase-wrapped file without a keychain).
- **Recovery: a 24-word phrase** (BIP39, 256 bits of entropy = the VK), shown once when the
  vault is created. Onboarding requires confirming it (typing random words) before continuing. It
  can be shown again from Settings. A passphrase with Argon2id is REJECTED. Contract: **without
  the phrase and without a device, the data is unrecoverable**, and the app says so.

### 15.3 Devices

- **Adding a device**: from an enrolled one, with a QR holding an ephemeral key (X25519 exchange)
  and a short confirmation code; the VK travels wrapped. Alternative: typing the phrase.
- **Revoking**: the server invalidates the device token and the VK rotates (ROADMAP: key
  rotation). Every blob carries a `key_epoch`.
- **Server authentication**: a token per device, independent from the VK. Server access never
  gives content access.

### 15.4 What the server can and cannot do — DECIDED

Rule: the server only does work that **does not need to read content**.

| Yes (without seeing content) | No (needs plaintext → done on the client) |
|---|---|
| Order operations (`seq`), idempotency, WS/SSE fan-out | HLC merge |
| Store and serve blobs and chunks with Range/ETag | Full-text search, backlinks, queries, sets |
| Purge snapshots and tombstones older than 30 days (by receive time) | Thumbnails, OCR, OpenGraph extraction |
| Deduplicate identical encrypted blobs | — |
| Keep a bootstrap snapshot so a new device need not replay the log | — |

Heavy work that devices should not do is handled by a **trusted node** you own (§23), never by
the server.

### 15.5 Local lock

- Optional lock with biometrics or PIN on open and after N minutes idle (off by default).
- Android: optional `FLAG_SECURE` (hides content in the app switcher and blocks screenshots).
- The local cache is always encrypted (SQLCipher on both clients).

### 15.6 Backup and export

- **Full encrypted backup** (`.yaop`): the whole vault plus assets, encrypted with the VK, restored
  with the phrase, no server needed.
- Markdown export (ROADMAP) is plaintext, so the app asks for explicit confirmation.

### 15.7 What depends on the server — DECIDED

Rule: **no feature depends on the server**, except those that connect devices by definition.

| Feature | Without server | With server |
|---|---|---|
| Create and edit objects, journal, tasks | ✅ | ✅ |
| Search, queries, sets, backlinks, graph | ✅ local | ✅ local |
| Web link previews | ✅ the device fetches the web directly | ✅ same (the server would have to see the URL) |
| Thumbnails, covers | ✅ made by the device | ✅ same |
| Sync between devices | ❌ | ✅ |
| Automatic remote backup | ❌ (manual `.yaop` backup) | ✅ |
| Trusted node help (§23) | ❌ (or over LAN, ROADMAP) | ✅ |
| Web client (§18) | ❌ | ✅ |

If the server goes down or disappears, the app keeps working. When it comes back, pending changes
sync.

---

## 16. Model evolution and history — DECIDED

### 16.1 Changing an object's type

- An object can change type (e.g. Note → Project). The new type's properties are **added** with
  their defaults and **blocks are kept**.
- Properties of the old type missing in the new one are **not deleted**: they stay as "loose
  properties" in a collapsible section, removable by hand.
- Mentions keep working (they point to the id, not the type).

### 16.2 Editing types and properties

- Type properties can be added, renamed, reordered, changed in kind and removed.
- Kind conversions: text ↔ select and multi-select (by value), number/date → text (lossless),
  text → number/date (non-convertible values kept as text in a loose property). If a conversion
  loses data, a preview of what is affected is shown and a snapshot is taken first.
- Removing a property from a type: values go to the trash for 30 days.
- **Deleting an object with references** is not allowed while it has backlinks; the references
  are listed so they can be resolved first.
- A user type can be deleted only when empty. Base types are never deleted.

### 16.3 History

- A snapshot per object when an editing session closes; viewable and restorable per object or per
  block.
- Retention: **30 days**, on server and client.
- **Compression**: diff against the previous snapshot + zstd, **always before encrypting**. A full
  snapshot every 10 diffs keeps restores fast.
- Editor undo/redo is per session and does not sync.

### 16.4 Protocol and data versions

- `schema_version` on every object and `protocol_version` in the handshake (`/v1/sync/status`).
  An incompatible client gets an explicit error; data is never corrupted silently.
- **Before the first stable release**: compatibility may break when there is a technical reason,
  without migrating old data.
- **After it**: versioned local migrations, tested with `spec/` fixtures.

---

## 17. Performance — DECIDED

### 17.1 Target scale (design for this, not for today)

- 100+ types, 200k objects, 2M blocks, 50 GB of media. Notes and the journal grow every day.
- Nothing on the hot path may scale with vault size: opening the app, opening the journal, typing,
  searching.

### 17.2 Budgets (p95 on the reference device: a mid-range Android tablet)

The tablet is the floor: if it meets the budget, newer phones and desktops do too. Reference:
Snapdragon 710, 6 GB RAM, Android 15 (SDK 35).

| Operation | Budget |
|---|---|
| Cold start → today's journal visible | < 1.5 s |
| Open a 1k-block object | < 150 ms to first paint |
| Typing latency in the editor | < 16 ms (no dropped frames) |
| Incremental search (results while typing) | < 100 ms |
| Query or set with 10k results | first page < 200 ms (paged/virtualized) |
| Incremental sync after a day offline | never blocks the UI; applied in the background |
| New device bootstrap | from the bootstrap snapshot, not by replaying the log |

### 17.3 Rules

- Incremental indexes (FTS, backlinks, properties) updated on save; never full rebuilds at
  startup.
- Lazy decryption: only what is shown, in pages.
- **On-demand media**: encrypted thumbnails (~15–30 KB) always download; originals only when
  opened. LRU cache with a configurable limit and an option to pin objects offline.
- Benchmarks with a synthetic vault at §17.1 scale (mobile `androidTest` stress, desktop bench).

### 17.4 Search — DECIDED: local

With E2EE the server cannot search, so search is **local on every client**: SQLite FTS5
(`unicode61 remove_diacritics`, optional trigram tokenizer), Spanish and English, over titles,
blocks and text properties. Structured filters (type, property, date) go through the query
engine. PDF and OCR text is extracted by the client or the trusted node and indexed locally —
ROADMAP. "Blind indexes" on the server (HMAC of terms) are REJECTED: they leak which terms you
use and how often.

---

## 18. Platforms — DECIDED

- **Native Android** (Kotlin + Compose): phone and tablet — EXISTS.
- **Native Linux desktop** (C++20 + Qt Quick) — EXISTS.
- **Web client** — ROADMAP, **high risk**, with mandatory rules:
  - The server delivers the JavaScript, so a compromised server could serve code that steals the
    key. Mitigation: **disabled by default**, served only on LAN or VPN, strict CSP, SRI, no
    third-party resources, reproducible builds verifiable by hash.
  - The VK lives only in memory (never in `localStorage` or unencrypted IndexedDB), with idle
    lock and logout on tab close.
  - Decryption in the browser with WebCrypto; the server stays blind.
  - First scope: reading, search and light editing.
- **Windows**: via the web client; a native Qt client is OPEN.
- **iOS / macOS**: REJECTED for now.

---

## 19. Collections — DECIDED

- **Set** = the results of a filter (dynamic, §7). **Collection** = a manual list of objects of
  any type, in the order the user chooses.
- An object can be in several collections. Collections have the same views as sets.
- Collections are objects (they appear in the graph and can be mentioned). Add to a collection
  from the object menu, multi-select or capture. ROADMAP.

---

## 20. Journal: time zone and start of day — DECIDED

- The time zone is set in Settings (default: the system's).
- "The day starts at…" is configurable (e.g. 04:00): what is created at 1 a.m. counts for the
  previous day.
- Timestamps are stored in UTC with the origin time zone, so travelling does not rewrite the past.

---

## 21. Onboarding, tutorial and glossary

### 21.1 Tutorial — DECIDED

- First run: create or restore the vault → show the recovery phrase → connect a server
  (optional, skippable) — EXISTS.
- **Interactive tutorial** as real, deletable objects in the vault (journal, mentions, types,
  queries, collections, capture), in English and Spanish — ROADMAP.

### 21.2 Accessibility — OPEN

Not addressed yet. What is asked now is not to block it: no hard-coded text sizes, semantic
labels on new controls.

### 21.3 Glossary

| Term | Meaning |
|---|---|
| **Vault** | All your knowledge: objects, types, assets. One per user. |
| **Object** | The unit of information. Has an id, a type, properties and blocks. Media, tags and web links are objects too. |
| **Type** | An object's template: its properties, layout and views. Base types are fixed; user types are yours. |
| **Property** | A field with a kind (text, number, date, select, object-ref…) defined by the type. |
| **Loose property** | A value kept after a type or kind change that no longer belongs to the current type (§16). |
| **Block** | The editor's unit (paragraph, task, code…), with an indent level. |
| **Span** | An inline piece inside a block: marked text, mention, tag or date. |
| **Mention** | An `@` reference to an object or block; it creates a backlink. |
| **Backlink / reference** | Another object pointing to this one, shown with the source title and a snippet. |
| **Query** | A saved or embedded filter that returns objects live. |
| **Set** | A browsable view of a filter's results (table, gallery, kanban…). |
| **Collection** | A manual, ordered list of objects. |
| **Journal** | The object of the day; the app's front door. |
| **Snapshot** | A saved version of an object; kept 30 days. |
| **Tombstone** | The deletion mark that syncs; the trash lasts 30 days. |
| **HLC** | Hybrid Logical Clock: the clock that orders changes between devices. |
| **VK** | Vault Key: the key that encrypts everything. Only your devices have it. |
| **Trusted node** | One of your devices, holding the VK, that does heavy work and uploads encrypted results. |
| **Blind server** | The sync server: stores and forwards encrypted data without being able to read it. |

---

## 22. Usage flow and sync topologies — DECIDED

### 22.1 Canonical scenario (the one that drives the design)

1. **Out, without connection**: I write on the phone (journal, notes, tasks). Everything is saved
   locally and waits in the persistent **outbox**, which survives app restarts and reboots.
2. **Scrolling**: I see something interesting → share → the object exists at once on the phone
   (Web link, Image, Note) and shows in "created today". OpenGraph is fetched when there is a
   network.
3. **At home I turn on the PC**: the desktop starts, finds the server and syncs on its own. What I
   captured yesterday is on the PC, ready to sort and connect.
4. **Working on the PC**: while both are online, changes show live (WS/SSE).
5. **I leave**: the phone already has what I did on the PC, and works offline again.

Acceptance: this whole scenario, with the phone in airplane mode in step 1.

### 22.2 Both clients are complete

The desktop is not a thin client: like the phone, it has its own encrypted database and outbox and
works without a server. Sync carries an idempotent `seq` + `op_id` journal (retrying after losing
Wi-Fi duplicates nothing), an ordered outbox (objects before the operations that link them),
tolerance for links not yet resolved, QR pairing with the TLS fingerprint, mDNS and USB.

### 22.3 Topologies (same protocol, interchangeable server)

The server is blind and dumb (§15.4), so **any one works and they can be swapped without losing
anything**: pair the new one and re-send everything, since content already travels encrypted.

| Topology | How | When it syncs |
|---|---|---|
| **A. Cloud server / VPS** | Docker or systemd on an always-on server | Always: the phone uploads as soon as it has a network |
| **B. Local server on the PC** | The relay runs on the PC or another home machine | Only when the PC is on and the phone is on the same LAN (or VPN such as Tailscale/WireGuard) |
| **C. USB** | `adb reverse tcp:8765` | When the cable is plugged in |
| **D. Relay embedded in the desktop** — ROADMAP | The desktop starts the relay itself | Like B, without installing anything |

Requirements for a server exposed to the internet:
- **HTTPS only**, with a valid certificate (Caddy or Let's Encrypt in front). Never plain HTTP
  outside `127.0.0.1`.
- Firewall: only 443 (and 22 to administer); optionally behind a VPN.
- A token per device with revocation (§15.3); rate-limit failed authentication.
- The provider (or whoever compromises the server) only sees encrypted blobs and the metadata of
  §15.1. What needs protecting there is **availability**, not confidentiality.
- Back up the data volume; the desktop is a full copy too (§22.4).

- **Discovery order on the phone**: `127.0.0.1` (USB) → mDNS on the LAN (`_yaop-sync._tcp`) →
  the configured remote URL.
- In topology B, with the PC off, the phone simply accumulates in the outbox; the UI shows "N
  pending changes", not an error.
- Several servers for the same vault at once: possible in theory (client-side merge, idempotent
  ops) — ROADMAP.

### 22.4 Device roles

- **Desktop = full copy**: all objects and **all media originals**. Your natural backup and the
  trusted-node candidate (§23).
- **Mobile = working copy**: all objects (they are small), thumbnails always, originals of the
  last 90 days + pinned ones; the rest on demand. Older originals are released from the local
  cache (not deleted from the server or desktop). The window is configurable — ROADMAP.
- With topology B, "on demand" away from home cannot download anything: the object shows the
  thumbnail and "available when synced", never an error or an empty hole.

### 22.5 Capture from the PC browser

A browser extension sends the capture **to the local desktop** (native messaging or `127.0.0.1`
with a token); the desktop creates the object, fetches OpenGraph, encrypts and syncs. If the
desktop is closed, the extension queues and delivers later — ROADMAP.

### 22.6 Visible sync status

Always visible, without opening settings: last sync, connected server, pending uploads, pending
media downloads, and errors with their reason. "The server rejected N operation(s): <reason>" is
never hidden behind a summary.

---

## 23. Trusted node — DECIDED

### 23.1 What it is

**One of your devices, enrolled like any other**, that also does the vault's heavy work and uploads
the results **encrypted** so the others use them ready-made. By default the node is the **Linux
desktop**: it has the full copy (§22.4), the VK in its keystore and spare CPU.

- It has no special privileges on the server: for the relay it is just another device.
- **The server cannot be a node — REJECTED**: a node holds the VK, and putting it on the server
  would break principle 7.
- One node per vault; several would also work (§23.4) but are not needed.

### 23.2 Who does what

Rule: **the creating device does the cheap part at once** (never an empty hole); **the node does
the expensive part later**.

| Work | Creating device (phone/tablet) | Node (desktop) |
|---|---|---|
| Image thumbnail | A small one (≤480 px, WebP) at once | Extra sizes (grid, hero) |
| Web link OpenGraph | Fetched at once (§15.7) | Retries failures, full-resolution cover |
| Video | Nothing (type icon) | Poster frame and duration |
| PDF | First page as thumbnail | Full text (for search) and page thumbnails |
| OCR | — | Image text and a title suggestion |
| Media hash and dedup (ROADMAP) | Hash on upload | Finds duplicates and proposes merging |
| EXIF cleanup (ROADMAP) | — | A copy without EXIF, keeping the original |
| Full snapshots (§16.3) | Small diffs | The full snapshot every 10 diffs |
| Log compaction and bootstrap snapshot | — | Regenerated now and then so new devices start fast |
| Re-encryption after VK rotation | — | Re-encrypts old data in the background |
| Integrity check | — | Decrypts and validates everything periodically |

Nobody delegates their own FTS index, backlinks and queries (local by design, §17.4). The node only
saves them the expensive part, such as extracting a PDF's text.

### 23.3 Derived artifacts, not a job queue

There is no job queue the server must understand. The node produces **derived artifacts**:

```
derived/<objectId>/<kind>   →  encrypted blob (HKDF subkey "derived")
  kind:            thumb.grid | thumb.hero | pdf.text | pdf.pages | ocr.text | video.poster | ...
  source_hash:     hash of the original it came from
  producer:        "yaop-node/<version>/<engine>"
```

- **The node scans**: for each object, if an artifact is missing or its `source_hash` does not
  match the current original, it builds it. Idempotent: if it stops halfway, it continues next
  time.
- **Clients consume**: a valid artifact is used; otherwise the cheap local version is shown, with
  "processing…" only where needed.
- **Priority**: a client can ask "this one first" with an encrypted `node.request{objectId,
  kind}` sync op; the node serves it before its normal scan.
- Artifacts travel through normal sync and follow the media policy of §22.4.

### 23.4 Why there are no conflicts

An artifact is a **pure function** of (original, `producer`). If two devices compute it, the
results are equivalent and either wins (LWW). When an engine improves, `producer`'s version goes
up and the node slowly regenerates the old artifacts.

### 23.5 When the node works

- Only while the desktop is open (a background service is ROADMAP). Whatever it uploads reaches
  the other devices later through the server.
- **Unobtrusive**: low priority (`nice`, one thread by default), pauses while you type, respects a
  configurable CPU limit.
- **Without a node the app is complete**: only the extras (OCR, PDF text, large sizes) take longer.

### 23.6 Per-device setting: "heavy work on this device"

| Value | Default on |
|---|---|
| Never (wait for the node) | Old tablet |
| Only while charging on Wi-Fi | Phone |
| Always (this is the node) | Desktop |

### 23.7 Status

The `derived/` convention, `node.request` and bootstrap snapshots exist in the protocol and both
clients. The desktop doing node work (thumbnails, PDF text, compaction, verification) and OCR are
ROADMAP.

---

## 24. Habits — DECIDED

Details and screens in [habits.md](habits.md). In short:

- **Base type `Habit`**, with the same block editor. Template: record kind (`check` / `count` /
  `time`), goal and unit, frequency (`daily`, given weekdays, N per week, N per month), time of
  day, start date and state (active / paused / archived). Type color: green `#86E8A8`.
- **Records**: one property per day on the habit itself, `d:YYYY-MM-DD` (local date, §20), with
  value `true`, a number or `"skip"`; unchecking = removing the key. The existing per-key merge
  handles them (`spec/merge.md`): two days checked on different devices never clash. They are not
  stored in the journal.
- **Streak, rate and calendar are computed when shown** (no computed fields, §4.2). `skip` does
  not break the streak.
- **Where**: a strip of rings on Home between today's journal and tasks; a Habits view; the day's
  section in the journal; on desktop, a sidebar entry with a weekly grid.
- **ROADMAP**: reminders, widgets, negative habits, compacting records per year.
