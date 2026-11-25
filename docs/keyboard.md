# Keyboard-first desktop — design (PROPOSED)

Goal: with the window focused (e.g. after Alt+Tab from a browser) you can do everything without
the mouse. The mouse stays fully supported; it is just never required.

Scope: desktop only (Qt Quick). No wire contract changes, so no `spec/` or fixture work.
Vim keys are out of scope for now, but the design leaves room for them (§9).

---

## 1. What is wrong today (inventory, Nov 2026)

| # | Problem | Where |
|---|---|---|
| 1 | Opening an object or a day does not put the caret anywhere; you must click a block. | `EditorPage.qml`, `CalendarPage.qml` (embedded `BlocksView`) |
| 2 | ↑/↓ into a non-text block (image, code, table, math, embed, bookmark, canvas, divider) asks `session.forceFocus()`, but only `TextBlockView` listens, so focus is lost ("miss"). | `BlocksView.focusNeighbor`, `TextBlockView.onFocusChanged` |
| 3 | Moving between blocks puts the caret at the end/start, not in the same column. | `TextBlockView` Up/Down (`2147483647` / `0`) |
| 4 | No block navigation mode: Esc does not select the current block; moving a block is only in the ⋮ menu. | `BlocksView.Keys`, `BlockDelegate` menu |
| 5 | Global `Escape` Shortcut in `Main.qml` likely wins over the editor's Esc (clear selection, close popups). | `Main.qml:264` |
| 6 | Global `Ctrl+D` (Today) likely shadows Duplicate block in the editor. | `Main.qml:255`, `TextBlockView`, `BlocksView` |
| 7 | Lists, grids, sidebar, tabs, calendar cells: no keyboard focus or arrow navigation at all. | `LibraryPage`, `HomePage`, `TasksPage`, `InboxPage`, `SetsPage`, `JournalPage`, `Sidebar`, `ShellSidebar`, `ShellTabs`, `CalendarPage` |
| 8 | Command palette: Enter opens only the first result; no ↑/↓; no actions. | `CommandPalette.qml` |
| 9 | After Alt+Tab back into the window, focus is wherever Qt left it (often nothing). | `Main.qml` |
| 10 | No visible focus ring; no shortcut sheet; shortcuts are scattered in 6+ files. | everywhere |

Items 5 and 6 are "likely": a `Shortcut` gets the key before `Keys.onPressed` unless the focused
item accepts `ShortcutOverride`. Confirm with the key log (§8) before fixing.

---

## 2. Principles

1. **There is always a focused thing.** Every page declares a default focus target; the window
   restores the last focused item on activation, else the page default. Never "nothing".
2. **Enter goes in, Esc comes out.** Enter on anything opens/edits it and moves focus inside.
   Esc closes the innermost layer (popup → text selection → block mode) and never closes a page.
   Leaving a page is Alt+← (back), like a browser.
3. **Standard first.** Use the keys Linux apps and browsers already use (GNOME/KDE, Firefox,
   VS Code, Notion). No chords that GNOME/KDE take (Super+*, Ctrl+Alt+←/→/↑/↓, Ctrl+Alt+T,
   Alt+F2, Alt+Tab, Alt+Space).
4. **Single letters only in navigation mode** (lists, calendar grid, block mode), never while
   typing text. This is also the hook for Vim later.
5. **One keymap table.** Every shortcut is declared once; the global `Shortcut`s, the command
   palette, the shortcut sheet and the hints in context menus all read it. A selftest fails on two
   bindings with the same keys in the same scope.
6. **Focus is visible** when it came from the keyboard: one ring (`Theme.focusRing`, same radius as
   the item), hidden after a mouse click until the next key press.

---

## 3. Focus model

The window is split into **regions**. Exactly one region is active; inside it one item has focus.

```
 ┌ Sidebar ─┐┌ Tabs ─────────────────────────────┐
 │          │├ Page ──────────────┐┌ Side pane ──┐│
 │  nav     ││ list / grid /       ││ editor      ││
 │  pinned  ││ calendar / editor   ││             ││
 └──────────┘└─────────────────────┘└─────────────┘┘
 Overlays (palette, quick capture, settings, menus, popups) sit above and trap focus.
```

| Key | Action |
|---|---|
| F6 / Shift+F6 | Next / previous region (Sidebar → Tabs → Page → Side pane), like browsers. |
| Ctrl+Shift+E | Focus sidebar (VS Code's explorer). |
| Ctrl+B | Show/hide sidebar (keeps focus where it was; if it was in the sidebar, goes to Page). |
| Alt+Tab back into the window | Restores the last focused item of the active region. |

Each region remembers its last focused item (`Window.activeFocusItem` captured on change, stored
per region and per tab). Overlays return focus to the item that opened them when closed.

**Default focus per page** (when nothing is remembered):

| Page | Default |
|---|---|
| Calendar Day / Today | the day note editor (see §4.1) |
| Calendar Month/Week/3 days | the selected day cell |
| Editor (any object) | §4.1 |
| Library, Home, Tasks, Inbox, Sets, Journal list, Habits | first item of the main list |
| Settings, Onboarding, Lock | first field |

---

## 4. Editor

### 4.1 Where the caret lands when an object opens

1. New or untitled object → the title, caret at end.
2. Object opened before in this run → the last block and caret offset it had (kept per object in
   the session, also per tab).
3. Otherwise → end of the first text block; if the object has no blocks, create an empty
   paragraph and focus it (Enter on the title already does this).

Applies to: Enter on a list item, Ctrl+K result, Ctrl+D / Today, back/forward, tab switch, opening
in the side pane (focus goes to the pane).

### 4.2 Two modes

- **Edit mode**: caret inside a block (text, code, caption, table cell, LaTeX).
- **Block mode**: one or more whole blocks selected (the existing multi-selection, highlighted),
  no caret. This is Notion's "Esc to select".

```
          Esc                       Esc
 Edit ───────────► Block mode ───────────► (selection cleared, focus stays on the
  ▲                   │                     editor; next ↑/↓ re-enters block mode)
  └──── Enter ────────┘
```

Esc order inside the editor: open popup (slash, mention, format bar) → text selection → enter block
mode on the current block → clear block selection.

### 4.3 Keys in edit mode (text blocks)

Already working (keep): Enter, Backspace/Delete at edges, Tab/Shift+Tab,
Ctrl+B/I/U/E, Ctrl+Shift+H, Markdown prefixes, `/` `@` `#` popups, Ctrl+A twice = all blocks,
Shift+↑/↓ at an edge = start block selection, Ctrl+C/X/V.

New or changed:

| Key | Action |
|---|---|
| ↑ / ↓ at first/last visual line | Previous/next block, **keeping the caret's x** (goal column, reset on ←/→/typing/click). Lands in any block type (§4.5). |
| ← at offset 0 / → at end | End of previous / start of next block. |
| Home / End | Start / end of visual line (Qt default, keep). |
| Ctrl+Home / Ctrl+End | First / last block of the document (not only of this block). |
| PgUp / PgDn | Scroll one viewport and move the caret to the block at the same screen height. |
| Ctrl+Shift+↑ / ↓ | Move current block (with its children) up/down (`moveBlocks`). |
| Ctrl+D | Duplicate block (resolve the conflict with Today, §7). |
| Ctrl+Enter | Todo: toggle checked. Toggle: expand/collapse. Mention under caret: open it. |
| Ctrl+Shift+Enter | Open the mention under the caret in the side pane. |
| Ctrl+K | Command palette (with a text selection: "link to…" as the first action). |
| Ctrl+Shift+K | Insert link on selection (today only via format bar). |
| Ctrl+Alt+0…3 | Turn into paragraph / H1 / H2 / H3 (Google Docs). |
| Ctrl+Alt+4 / 5 / 6 | Turn into bulleted / numbered / todo list. |
| Ctrl+. | Block actions menu for the current block (the ⋮ menu). Also Menu key / Shift+F10. |
| Alt+↑ / ↓ | Go to title / to the properties table (if shown). |
| Ctrl+F, F3, Shift+F3 | Find in object, next, previous (find exists; add the key flow). |

### 4.4 Keys in block mode

| Key | Action |
|---|---|
| ↑ / ↓ | Move the selection to the previous/next visible block. |
| Shift+↑ / ↓ | Extend the selection. |
| Home / End | First / last block. |
| Enter | Edit the block (caret at end); for non-text blocks, their primary action (§4.5). |
| Ctrl+Shift+↑ / ↓ | Move the selected blocks. |
| Tab / Shift+Tab | Indent / outdent (exists). |
| Delete / Backspace | Delete blocks (exists), focus goes to the block above in block mode. |
| Ctrl+C / X / V / D / A | Copy / cut / paste after / duplicate / all (exist). |
| `/` | "Turn into…" menu for the selection. |
| Space | Todo: toggle. Toggle block: expand/collapse. Image: open viewer. |
| Ctrl+. or Menu key | Block actions menu. |
| Esc | Clear selection. |

### 4.5 Non-text blocks are focusable

Every block delegate gets a focusable shell (`BlockDelegate` as a `FocusScope`) that listens to
`session.focusBlockId`. Arriving with ↑/↓ puts it in **block mode on that block** (ring, no caret),
so navigation never gets lost. Then:

| Block | Enter | Esc (from inside) | Also |
|---|---|---|---|
| Image / video | Open viewer (Esc closes) | — | Ctrl+Enter: edit caption |
| Audio | Play / pause | — | ←/→ seek 5 s while focused |
| PDF | Open viewer | — | PgUp/PgDn pages in viewer |
| Code | Edit code (caret at end) | Back to block mode | Tab indents inside code; Ctrl+Enter leaves to next block |
| Math | Edit LaTeX | Back to block mode | — |
| Table | Edit first cell | Back to block mode | Tab / Shift+Tab next/prev cell, ↑/↓ row, Ctrl+Enter add row |
| Embed / bookmark | Open target (Ctrl+Enter new tab, Shift+Enter side pane) | — | — |
| Canvas | Open whiteboard | — | — |
| Divider | Nothing (just a stop) | — | — |

### 4.6 Title and properties

- Title: Enter / ↓ → first block (exists for Enter); ↓ added. Alt+↓ → properties.
- Properties table (`PropertyTable.qml`): ↑/↓ rows, Enter edits the value, Esc cancels, Tab
  next property, Alt+↑ back to title, Alt+↓ to blocks.
- Tags row (`TagRow.qml`): already keyboard-driven inside the picker; add focus + Enter to open it,
  Backspace on a focused tag removes it.
- Task bar (status, due day): reachable with Tab from the title; Space cycles status, Enter opens
  the date picker (arrows move days).

---

## 5. Lists, grids and pages

One shared behavior (`KeyList` — a small QML component or attached handler) used by every list and
grid. Items must be `ListView`/`GridView` (or a Repeater with a tracked `currentIndex`).

| Key | Action |
|---|---|
| ↑ / ↓ (← / → in grids) | Move current item. |
| Home / End, PgUp / PgDn | First / last, one page. |
| Enter | Open in page, **focus into the editor** (§4.1). |
| Ctrl+Enter | Open in new tab (same as Ctrl+click). |
| Shift+Enter | Open in side pane (same as Shift+click). |
| Space | Tasks: toggle done. Others: quick preview (later, with hover preview). |
| F2 | Rename inline. |
| Delete | Move to trash, toast with Undo (Ctrl+Z while the toast shows). |
| Ctrl+Shift+P | Pin / unpin in sidebar. |
| Menu key / Shift+F10 | Context menu (the shared menu of PENDIENTES §1). |
| Typing letters | Type-ahead: jump to the next item whose title starts with them (file managers). |
| Ctrl+F | Focus the page's filter/search field (Library, Sets); ↓ from it goes to the list. |

Pages:

- **Library**: type chips are a row (←/→ in the chip row, ↓ into the grid/list).
- **Tasks**: sections are one flat list for navigation; Space toggles; Ctrl+Shift+↑/↓ is not used.
- **Home / Journal / Inbox / Sets / Habits**: each card group is a list; Tab moves between groups.
- **Sidebar** (`Sidebar`, `ShellSidebar`): one list (nav entries, then pinned); Enter opens and
  moves focus to Page.
- **Tabs** (`ShellTabs`) when the region is focused: ←/→ select, Enter activate, Delete close.

### 5.1 Calendar

| Key | Action |
|---|---|
| ← → ↑ ↓ | Previous/next day; ↑/↓ = ±7 days in Month/Week, ±1 hour in time grids. |
| PgUp / PgDn | Previous/next period (month, week, 3 days, day). |
| Home / T | Today. |
| D / W / M / 3 | Day / Week / Month / Three days view (navigation mode only). |
| Enter | Day view of that day with the caret in its note (creates it, like today). |
| N | New task on the selected day (Quick capture with the date filled). |
| Tab | Into the selected day's items (chips) as a list; Enter opens. |

---

## 6. Global keys (final table)

| Key | Action | Notes |
|---|---|---|
| Ctrl+K, Ctrl+P | Command palette: search + actions | Ctrl+P added (VS Code/Sublime habit) |
| Ctrl+Shift+P | Palette in actions-only mode | |
| Ctrl+N | Quick capture | exists |
| Ctrl+Shift+N | New note and open it in the editor | |
| Ctrl+D | Today (day note, caret in it) | outside the editor only, see §7 |
| Ctrl+1 … 5 | Home, Journal, Library, Whiteboard, Tasks | exists; Slate maps to its own top entries |
| Ctrl+J, Ctrl+L | Journal, Library | exist; Ctrl+L is kept (no text "link" binding uses it) |
| Alt+1 … 9 | Go to tab N (9 = last) | Firefox on Linux |
| Ctrl+T / Ctrl+W | New tab / close tab | exist |
| Ctrl+Shift+T | Reopen closed tab | |
| Ctrl+Tab / Ctrl+Shift+Tab, Ctrl+PgDn / Ctrl+PgUp | Next / previous tab | PgUp/PgDn added |
| Alt+← / Alt+→ | Back / forward | exist; also mouse back/forward buttons |
| Ctrl+\\ | Open current object in side pane / close side pane | |
| F6 / Shift+F6, Ctrl+Shift+E, Ctrl+B | Regions, sidebar (§3) | |
| Ctrl+, | Settings | GNOME/VS Code |
| Ctrl+/ (and `?` in navigation mode) | Shortcut sheet | PENDIENTES §4 |
| Ctrl+Shift+L, Ctrl+Shift+H | Lock, privacy | exist |
| Ctrl+Z / Ctrl+Shift+Z (Ctrl+Y) | Undo / redo in the open document: typing bursts are one step, Enter / Backspace / paste / a markdown shortcut each its own; the caret goes back to the change | desktop |
| Esc | Close the innermost overlay/popup only | §2.2 |

### 6.1 Command palette

- ↑/↓ choose result, Enter open, Ctrl+Enter new tab, Shift+Enter side pane, Tab completes.
- Results mix objects and **actions** (every keymap entry with a title, plus "Turn into…",
  "Move to trash", "Open settings › Appearance"…). Each action row shows its shortcut on the right.
- `>` as first character = actions only (VS Code). Empty query = recent objects + recent actions.

### 6.2 Overlays

Quick capture, settings sheet, date pickers, menus, viewers: focus the first field/item on open;
Tab/Shift+Tab cycle inside (focus trap); Esc closes and returns focus to the opener; Ctrl+Enter
submits multi-field forms.

---

## 7. Conflicts and decisions

| Conflict | Decision |
|---|---|
| Ctrl+D: Today vs Duplicate block | Inside the editor (edit or block mode) Ctrl+D = Duplicate (Notion/VS Code habit). Today stays Ctrl+D elsewhere, and also gets **Alt+Home** (browser "home") which works everywhere. |
| Esc: global close vs editor | The global `Escape` Shortcut goes away; each overlay handles its own Esc. |
| Ctrl+2 and Ctrl+J both Journal | Keep both (aliases are fine; the sheet shows both). |
| Ctrl+Alt+arrows | Not used: GNOME/KDE switch workspaces with them. |
| Ctrl+Shift+↑/↓ in Qt text = select to paragraph edge | Our blocks are one paragraph each, so moving the block is the better use. |
| Alt alone | Never bound; no menu bar mnemonics (Alt must not steal focus). |
| Ctrl+Shift+H: privacy vs highlight | Privacy wins (app-wide panic key); highlight has no key until we pick one. |

---

## 8. Implementation plan

Order matters: the key log and the keymap come first so every later phase is testable.

**F0 — Base — DONE** (`src/keymap.*`, `src/keyinput.*`, `qml/FocusRing.qml`, `--drive keys`).
Per-region focus memory and F6 moved to F2 (they need focusable lists first).
1. `desktop/qml/Keymap.js` (or a `Keymap` singleton): one table `{ id, keys[], scope, title,
   action }`. Scopes: `global`, `editor`, `blockmode`, `list`, `calendar`, `overlay`.
   `Main.qml` builds its `Shortcut`s from the `global` rows.
2. Selftest: no duplicate keys per scope; every row has a title (for the palette/sheet).
3. `--keylog` flag: logs each key press with the focused item's object name and whether a
   Shortcut, a Keys handler or nobody took it. Used to confirm §1 items 5–6 and the Alt "misses".
4. Fix the Esc and Ctrl+D conflicts (§7).
5. Focus restore on window activation + per-region memory (§3). Focus ring token + component.

**F1 — Editor — DONE except where noted** (`--drive editor-keys`: 12 checks).
Not yet: properties table / tags / task bar keys (§4.6, only title ↔ blocks), Ctrl+Shift+K, the
Ctrl+F / F3 flow, caret memory per tab (it is per object), audio seek. Turn-into keys are
Ctrl+Alt+0…6 (text, H1–H3, bullets, numbers, todo): Ctrl+Shift+7/8/9 clash with `/` `(` `)` on
Spanish and other layouts.
1. Caret on open (§4.1), caret memory per object/tab.
2. Focusable shell for every block delegate; `forceFocus` handled for all block types (§4.5).
3. Goal column on ↑/↓, ←/→ across blocks, Ctrl+Home/End, PgUp/PgDn.
4. Block mode (§4.4): Esc enters it on the current block; Enter leaves it.
5. Move blocks, turn into, block menu keys (§4.3).
6. Title / properties / tags / task bar flow (§4.6).

**F2 — Lists and pages**
1. `KeyList` behavior, applied to Library, Tasks, Home, Inbox, Sets, Journal, Habits.
2. Sidebar and tabs regions, F6.
3. Calendar keys (§5.1).

**F3 — Palette and overlays**
1. Palette ↑/↓ + actions from the keymap + `>` mode.
2. Shortcut sheet (Ctrl+/) generated from the keymap.
3. Overlay focus trap and return-to-opener; shared context menu reachable by Menu/Shift+F10,
   with shortcuts on the right (PENDIENTES §1).

**F4 — Vim (later, off by default)** — see §9.

### 8.1 Tests

- Pure parts (keymap conflicts, goal-column math, region order, next-item-in-grid) are plain
  functions with selftest cases.
- Scenario tests in `driver.cpp` with real key delivery. The current `sendWindowKey` sends straight
  to `focusObject()`, which **skips the Shortcut map**; scenarios must go through the window
  (`QWindowSystemInterface::handleKeyEvent`) so global shortcuts and overrides behave as in use.
  Scenarios: start → Enter → typing lands in today's note; ↓ through an image and a code block
  without losing focus; Esc → block mode → Ctrl+Shift+↓ moves it; Library: ↓ ↓ Enter → caret in
  the editor; Alt+← returns with the list item still current.

---

## 9. Room for Vim (not now)

A setting "Vim keys" adds aliases only in navigation scopes: `j/k` (↓/↑), `h/l` (←/→ in grids and
calendar), `gg`/`G` (Home/End), `i`/`a` (Enter edit mode at start/end), `o`/`O` (new block
below/above), `dd`, `yy`, `p` in block mode, `/` find. Because block mode and list mode already
exist and letters are only bound there, this is a second keymap layer, not new architecture.

---

## 10. Open questions

1. Ctrl+D inside the editor: Duplicate (proposed) or keep Today everywhere and use Ctrl+Shift+D
   for Duplicate?
2. Second Esc in the editor: just clear the selection (proposed) or also move focus to the page's
   list (e.g. the calendar grid)?
3. Single-letter keys in the calendar (D/W/M/3/T/N): OK, or only modified keys?
4. Should `docs/product.md` §9 get the line "Desktop is keyboard-first — DECIDED, see
   docs/keyboard.md" once this is approved?
