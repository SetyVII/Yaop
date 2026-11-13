# Android guidelines

Rules for anyone (human or agent) changing the Android app.
**Priority: correctness > performance > motion.** If an animation, border or graphics layer
competes with scrolling or open time, performance wins. Measure before "smoothing" the UI.

## 1. No literal strings, two languages

- Every visible text (titles, labels, placeholders, buttons, dialogs, `contentDescription`, menu
  items) lives in resources and is read with `stringResource(R.string.<id>)`.
- Every string is added to both `res/values/` (English, the default) and `res/values-es/`
  (Spanish) with the same key.
- Never hard-code `Locale.ENGLISH`/`Locale.US` in composables or formatters. Read the device
  locale reactively:
  ```kotlin
  val configuration = LocalConfiguration.current
  val locale = remember(configuration) { configuration.locales[0] ?: Locale.getDefault() }
  ```
  and use `DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)`.
- Never compare translated text (`if (title == "Related Tasks")`); pass ids, types or enums.
- `@type/name` and quick dates (`@today`) are language-driven: type aliases live in
  `mention_aliases_<type>` resources (the first alias is the chip label, the rest are synonyms)
  and dates in `date_today` / `date_tomorrow` / `date_yesterday`, compared accent-insensitively
  (`stripMentionAccents()`). No alias lists in Kotlin.
- Adding a language: translate every `mention_aliases_*` (singular, plural, a short form, and keep
  the English id last as a fallback) and the `date_*` strings. No code changes needed.

## 2. Block editor

- **Model**: rich text is structured `InlineSpan`s (`Text` with marks/link/colors, `Mention`,
  `Tag`, `Date`). Pure WYSIWYG: markdown marks (`**`, `~~`, `==`, `` ` ``) are never shown or
  typed; inline formatting comes from the toolbar.
- **Flat list**: blocks have an `indentLevel`; a `Toggle` hides the following deeper blocks;
  groups share a `groupId` and travel flat with `group_id` (`spec/text.md`). Indent is allowed on
  text blocks (paragraph, heading, lists, todo, quote, callout, toggle).
- **Enter** splits spans at the caret and focuses the new block. In an open toggle it creates a
  child; in a closed one, a sibling. Lists and quotes continue their type, or turn into a
  paragraph when empty. In code and math, Enter on an empty last line leaves the block.
- **Backspace at index 0** (Notion order): (1) remove one indent level; (2) turn the block into a
  paragraph (or leave the group); (3) merge into the previous block, or delete it when empty.
- Focus is managed with a `FocusRequester` per block id.
- **Line-start shortcuts**: `# ` … `#### `, `[] `, `[x] `, `- `, `* `, `1. `, `a. `, `i. `, `> `,
  `! `, `>> ` (group), `▶ ` (toggle), `---`, ```` ``` ````, `$$`.
- **One-row dynamic toolbar** that morphs (~150 ms) by context: normal, format, color, `@`/`#`
  results, create-as, block type, code/math, table, multi-select, undo notice. Keep it.
- The block catalog (`/` or `+`), date picker and audio recorder open in the keyboard area; the
  object/link picker is a half-height sheet; tapping a mention or link shows a compact preview
  card (Open, Edit, Remove).
- `BlockListEditor` stays virtualized (`LazyColumn`, `useLazyLayout = true`, `headerContent`),
  keeps the native IME caret and its typing fast paths.

## 3. Compose basics

- Do not apply status-bar padding twice; when a parent `Scaffold` already applies
  `innerPadding`, use `.consumeWindowInsets(innerPadding)`.
- Give `.background(…, shape)` a shape and `.clip(shape)` for rounded elements.

## 4. Privacy, web links and thumbnails

- Nobody is forced through third-party services. `NetworkPreferences`
  (`allow_third_party_link_proxies`, default `true`) controls preview proxies. When it is off:
  YouTube uses its official oEmbed and image CDN; Reddit, Instagram and X are queried directly
  (or the title comes from the URL); TikTok uses its official oEmbed; any other site is fetched
  directly.
- Link thumbnails are downloaded and stored as lossy WebP (quality 75, max side 480 px,
  ~15–30 KB) so a deleted post keeps its cover. Images smaller than 32×32 are dropped.
- Decode at display size: `rememberDecodedThumbnail(assetId, targetMaxSidePx = …)` with the real
  size (Home 320/160, cards 384, picker 256, editor 128–192). Never the 1024 px default in lists,
  and one size per card type so the cache entry is shared.
- A saved link cover becomes an Image object named `Cover of <Title>` (`R.string.cover_of_title`);
  the web object points to it with `cover = CoverSpec(assetId = …)` and references it in its body.

## 5. Motion (with a performance budget)

Animate **direct interaction**, not content that is recycled while scrolling.

- Use only the springs in `ui/theme/Motion.kt` (`AppMotion`): `snappy` (press, chips, toggles),
  `bouncy` (spatial moves: dock droplet, radial bubbles, counters), `gentle` (screen transitions,
  shared bounds, size changes), `playful` (completion), `tracking` (finger scrubbing). `tween`
  only for opacity (`FADE_FAST_MS = 120`, `FADE_MS = 200`, `SCRIM_MS = 180`).
- `pressScale` / `staggeredAppear` create a `graphicsLayer` only while animating. Never a
  permanent layer per card or row. No `animateContentSize` on rows of lazy lists.
- Opening an object from a card grows from that card (`sharedObjectBounds` +
  `LocalSharedOrigin.current.mark`) and folds back on return; a new object from the dock `+`
  grows from the button.
- Dock tabs (Home, Daily, Tasks, Search) switch with a short fade and slight slide. Only visited
  tabs stay alive; a hidden tab is destroyed when its fade ends. No pre-warming with `delay()`.
- Dock: dragging across it moves the droplet with the finger (`tracking`), highlights each slot
  with a haptic tick, and releasing activates the tab. The `+` button: tap = fan of quick actions
  (note, task, image, audio, link, more); hold and drag = rings with every object type, release
  on one to create it.
- Pressables sink to `0.96` while pressed. Numeric counters roll their digits. A task checked on
  Home stays visible 1.1 s before folding away, so it can be undone.
- `staggeredAppear` only on Home's section items (35 ms apart, at most 8), never on lazy rows.
  `animateItem()` only where insert/remove/reorder is the point (Tasks, Search), with `key`.

## 6. Home and Search

- Home order: greeting + date; today's journal card; today's and overdue tasks (max 5, quick-add
  field); continue (recently edited); pinned; recently created with "See all".
- Limits: Home `MAX_TODAY_TASKS = 5`, `MAX_CONTINUE = 6`, `MAX_IMAGES = 8`, `MAX_RECENT = 6`;
  Daily reference columns show 4 with "See all", 20 in the References/Created tabs. Do not raise
  them without profiling.
- Content cards (Home, Daily, `ObjectCard`, `ImageGalleryCard`) have no outer border or shadow:
  they are separated by tone (`Surface`, `SurfaceElevated`). Rounded borders are expensive in long
  lists.
- Home has no Space/Types tabs: the space browser, filters and type catalog live in Search
  (`SpacesScreen`).

## 7. Performance rules

- Profile (frame time, recompositions, layers) before adding any animation, border or layer.
- Home, Tasks, Search and the editor are lazy lists. Never `Column` + `verticalScroll` there,
  never a lazy list inside a vertical scroll. Every lazy list has a stable `key`, and
  `contentType` when item kinds differ.
- No `Brush.verticalGradient` scrim on Home image cards (solid scrim); no `shadowElevation` on the
  editor toolbar; small repeated decorations use `drawBehind`; only the selected/today calendar
  cell is clipped.
- Data: use the cached `Flow`s from `PkmRepository`; never create a `Flow` inside `remember` during
  composition. Home lists come sorted from the repository. Keep `isUiEquivalent` based on
  `revision`, `updatedAt`, `isPinned`, `trashedAt`, `syncStatus`. Search is debounced 180 ms.
  Heavy scans (`computeDailyRelations`, inferred tags) run in `produceState` +
  `Dispatchers.Default`.
- Editor: never force the caret to the end on tap; keep `derivedStateOf` for selection and active
  formats and stable callbacks; renumber lists only on structural changes; keep the 180 ms batch
  of `commitBlocks(…, deferUiRecomposition = true)`; read IME insets through `snapshotFlow`, not
  during composition; inferred tags are debounced 250 ms off the main thread.
- Build: keep the Gradle/Kotlin JVM memory and worker settings in `gradle.properties`; measure
  before changing them.

### Checklist before touching UI

- [ ] Is the list still lazy, with `key` (+ `contentType`)?
- [ ] Did I add a per-item animation or a permanent graphics layer? Is it measured?
- [ ] Did I bring back borders, shadows or gradients that cost frames?
- [ ] Did I create a `Flow` in composition or break the search debounce?
- [ ] Did I move a heavy scan onto the main thread?
- [ ] Did I touch the native caret, the IME or the editor's virtualization?
