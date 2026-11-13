# UI Customization & Styling Map

YAOP's user interface is intentionally modular so you (or an AI coding assistant) can fork, retheme, reshape navigation, swap fonts, or restyle components without touching domain logic or sync code.

Use this map to locate exactly where visual styling lives on both **Mobile (Android / Jetpack Compose)** and **Desktop (Qt 6 / QML)**.

---

## Quick Recipe Examples

- **"Change the app's background or accent colors":**
  - Mobile: edit `AppColors` and `AppBackgroundBrush` in [`Color.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Color.kt) (wired into MaterialTheme in [`Theme.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Theme.kt)).
  - Desktop: edit `Theme.qml` properties in [`Theme.qml`](../../desktop/qml/Theme.qml) and the gradient stops in [`AppBackground.qml`](../../desktop/qml/AppBackground.qml).
- **"Turn the mobile floating pill dock into a sharp square/rectangular bar":**
  - Mobile: open [`FloatingPillDock`](../../mobile/app/src/main/java/org/yaop/ui/main/MainAppContainer.kt#L683-L711), replace `RoundedCornerShape(34.dp)` (clip, background, border) and padding/width constraints with your preferred `Shape` (e.g., `RoundedCornerShape(0.dp)` or `RectangleShape`), and adjust the animated indicator droplet inside the same composable.
- **"Change card corner radius or remove surface translucency":**
  - Mobile: edit `AppShapes` in [`Theme.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Theme.kt) and container modifiers in [`ObjectCard.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/ObjectCard.kt).
  - Desktop: edit `radiusSm` / `radius` / `radiusLg` and `panel` / `panel2` / `panel3` in [`Theme.qml`](../../desktop/qml/Theme.qml), plus [`Panel.qml`](../../desktop/qml/Panel.qml) and [`ObjectCard.qml`](../../desktop/qml/ObjectCard.qml).
- **"Swap or customize fonts":**
  - Mobile: replace font `.ttf`/`.otf` files in `mobile/app/src/main/res/font/` and update `AppFonts` / `AppTypography` / `AppLabelStyle` in [`Type.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Type.kt).
  - Desktop: add/replace fonts in `desktop/assets/fonts/`, register them in `FontLoader` in [`Main.qml`](../../desktop/qml/Main.qml#L54-L58), and update `Theme.display`, `Theme.body`, `Theme.mono`, `Theme.pixel` in [`Theme.qml`](../../desktop/qml/Theme.qml).
- **"Change animations or spring feel":**
  - Mobile: edit `AppMotion` (`snappy`, `bouncy`, `gentle`, `playful`, `tracking`) in [`Motion.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Motion.kt).
  - Desktop: edit `Theme.fast` / `Theme.normal` / `Theme.slow` in [`Theme.qml`](../../desktop/qml/Theme.qml) and page transitions in [`Main.qml`](../../desktop/qml/Main.qml#L186-L191).

---

## 1. Android / Jetpack Compose (`mobile/app/src/main/java/org/yaop/ui/`)

### 1.1 Global Theme Tokens (`ui/theme/`)

| File | Key Symbols | What You Can Customize |
|---|---|---|
| [`Color.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Color.kt) | `AppColors`, `AppBackgroundBrush`, `AppTypeColors`, `WhiteboardSwatchColors` | Background gradient (`BackgroundTop`..`BackgroundBottom`), elevated surfaces (`Surface`, `SurfaceElevated`, `SurfaceHover`), borders (`BorderSubtle`, `BorderHover`), text hierarchy (`TextPrimary`, `TextSecondary`, `TextMuted`, `TextDisabled`), status/accents (`Accent`, `Danger`, `Success`, `TagGreen`), per-type colors (`AppTypeColors.colorForType`), and canvas swatch colors |
| [`Theme.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Theme.kt) | `YaopTheme`, `AppShapes`, `AppButtonStyle` | Material 3 `darkColorScheme` mapping, system status/navigation bar styling, default component `Shapes` (`small`, `medium`, `large`, `extraLarge`), primary/secondary button style defaults |
| [`Type.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Type.kt) | `AppFonts`, `AppTypography`, `AppLabelStyle` | Bundled `FontFamily` bindings (`DisplayFontFamily`, `BodyFontFamily`, `MonoFontFamily`, `AccentPixelFamily`), Material type scale, uppercase monospace micro-labels |
| [`Motion.kt`](../../mobile/app/src/main/java/org/yaop/ui/theme/Motion.kt) | `AppMotion`, `bouncyClickable` | Spring damping/stiffness specs, fade durations, stagger timings, press scale (`PRESSED_SCALE`), scrim alpha |

### 1.2 Navigation Shell, Dock & Quick Capture (`ui/main/`, `ui/tablet/`)

| File | Key Symbols | What You Can Customize |
|---|---|---|
| [`MainAppContainer.kt`](../../mobile/app/src/main/java/org/yaop/ui/main/MainAppContainer.kt) | `MainAppContainer`, `FloatingPillDock` | Root navigation state, Adaptive phone vs tablet layout switching, `FloatingPillDock` geometry, corner radius, background/border, and drag/tap indicator droplet |
| [`RadialQuickCapture.kt`](../../mobile/app/src/main/java/org/yaop/ui/main/RadialQuickCapture.kt) | `RadialQuickCaptureOverlay`, `RadialMenuState` | Center `+` fan and long-press type wheel layout, item angles/radius, backdrop scrim |
| [`CaptureCards.kt`](../../mobile/app/src/main/java/org/yaop/ui/main/CaptureCards.kt) & [`CaptureStyle.kt`](../../mobile/app/src/main/java/org/yaop/ui/main/CaptureStyle.kt) | Capture sheets & quick capture presets | Inline quick-create modals, default capture style cards |
| [`ui/tablet/`](../../mobile/app/src/main/java/org/yaop/ui/tablet) | `TabletNavigationRail`, `TabletDualPaneScaffold` | Rail width/appearance, dual-pane split ratios and dividers on tablets |

### 1.3 Reusable UI Building Blocks (`ui/common/`)

| File | Key Symbols | What You Can Customize |
|---|---|---|
| [`ObjectCard.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/ObjectCard.kt) | `ObjectCard` | Card padding, corner radius (`RoundedCornerShape`), badge layout, thumbnail sizing |
| [`Chips.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/Chips.kt) | `TypeBadge`, property/filter chips | Pill/chip height, borders, icon+label spacing |
| [`DesignSheet.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/DesignSheet.kt) | `DesignModalBottomSheet` | Modal bottom sheet background, corner radius, drag handle, scrim |
| [`DesignCover.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/DesignCover.kt) | `designCover` | Decorative cover headers / card gradients |
| [`ScreenTitle.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/ScreenTitle.kt) | `ScreenTitle` | Top screen title composition and mixed display/accent word styling |
| [`AmbientGlyphs.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/AmbientGlyphs.kt) & [`AmbientGlyphField.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/AmbientGlyphField.kt) | `ambientGlyphs`, `ambientGlyphField` | Decorative background glyphs shown behind empty/onboarding views |
| [`LinkPreviewCard.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/LinkPreviewCard.kt) | `LinkPreviewCard` | Weblink preview layout, favicon, domain metadata display |
| [`ImagePrivacy.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/ImagePrivacy.kt) & [`ImageMediaSupport.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/ImageMediaSupport.kt) | `ImagePrivacy`, image decoders/masks | Blur/masking behavior and reveal interactions when image privacy mode is active |
| [`SubtleSwipeActionBox.kt`](../../mobile/app/src/main/java/org/yaop/ui/common/SubtleSwipeActionBox.kt) | `SubtleSwipeActionBox` | Swipe-to-review / swipe-to-trash reveal backgrounds and thresholds |

### 1.4 Feature Screens (`ui/*`)
- **Home / Daily / Tasks / Habits / Spaces:** `ui/home/`, `ui/daily/`, `ui/tasks/`, `ui/habits/`, `ui/spaces/`
- **Block Editor & Inline Formatting:** `ui/editor/` (`ObjectDetailScreen.kt`, `BlockItemView.kt`, `EditorToolbar.kt`, `EditorBottomSheet.kt`, `EditorDialogs.kt`, `MediaBlockSupport.kt`, `WebLinkSupport.kt`)
- **Whiteboard:** `ui/whiteboard/` (`WhiteboardScreen.kt`, `WhiteboardToolbar.kt`, `WhiteboardNodes.kt`, `WhiteboardPalette` in `core/model/whiteboard/`)
- **Onboarding, Lock & Settings:** `ui/onboarding/`, `ui/lock/`, `ui/settings/`
- **Localized Strings:** [`res/values/strings.xml`](../../mobile/app/src/main/res/values/strings.xml) and [`res/values-es/strings.xml`](../../mobile/app/src/main/res/values-es/strings.xml) (see [`mobile/GUIDELINES.md`](../../mobile/GUIDELINES.md) §1: avoid literal UI strings in `.kt` files)

---

## 2. Desktop / Qt Quick QML (`desktop/qml/`)

All `.qml`, `.svg`, and font files matched by globs in [`desktop/CMakeLists.txt`](../../desktop/CMakeLists.txt#L65-L79) are compiled into Qt resources (`qrc:/`).

### 2.1 Theme Singleton & Global Shell

| File | Key Properties / Role | What You Can Customize |
|---|---|---|
| [`Theme.qml`](../../desktop/qml/Theme.qml) | Singleton (`QtObject`) | Colors (`page`, `bg`, `bg2`, `panel`, `panel2`, `panel3`, `sheet`, `ink`, `line*`, `text`, `dim`, `muted`, `faint`, `accent*`, `ice`, `violet`, `ok`, `late`, `danger`, `tagGreen`, per-type colors), font families (`display`, `body`, `mono`, `pixel`), radii (`radiusSm`, `radius`, `radiusLg`), adaptive breakpoints (`compact: winW < 1000`, `narrow: winW < 1280`), layout metrics (`sidebarW`, `topbarH`, `paneBarH`, `editorWidth`, `railWidth`), motion durations (`fast`, `normal`, `slow`), semantic block palette (`paletteNames`, `paletteHex`, `paletteTint`) |
| [`Main.qml`](../../desktop/qml/Main.qml) | `ApplicationWindow`, `FontLoader`, `StackLayout` | Window default/minimum size, loaded `.ttf`/`.otf` fonts, global keyboard shortcuts, page transition animations, top-level navigation routing |
| [`AppBackground.qml`](../../desktop/qml/AppBackground.qml) | Window backdrop `Canvas` | Full-window background linear gradient and optional empty-state `@`/`#` glyphs |
| [`GridBackdrop.qml`](../../desktop/qml/GridBackdrop.qml) | `GridBackdrop` | Content-area backdrop wrapper over `AppBackground` |
| [`Sidebar.qml`](../../desktop/qml/Sidebar.qml) | Left navigation rail/drawer | Navigation entries (`Home`, `Daily`, `To review`, `Tasks`, `Habits`, `Spaces`, `Sets`, `Whiteboards`), expandable `Types` list, pinned items |
| [`TitleBar.qml`](../../desktop/qml/TitleBar.qml) & [`TopBar.qml`](../../desktop/qml/TopBar.qml) | Top window/pane chrome | Window title bar controls, pane header bar buttons (search, capture, sync status, settings) |

### 2.2 Desktop Reusable Controls & Views (`desktop/qml/`)

- **Primitives:** `Panel.qml` (card surface + optional label/title/action header), `Button.qml`, `Chip.qml`, `IconButton.qml`, `NavItem.qml`, `TintedIcon.qml`, `StatCell.qml`, `DashedFrame.qml`, `Toast.qml`, `QueryField.qml`
- **Object Displays:** `ObjectCard.qml`, `ObjectRow.qml`, `TaskRow.qml`, `HabitPanel.qml`, `PrivateImage.qml`, `Privacy.qml`
- **Overlays & Modals:** `CommandPalette.qml`, `QuickCapture.qml`, `SettingsSheet.qml`, `LockScreen.qml`, `OnboardingWindow.qml`, `OnbField.qml`, `QrCode.qml`
- **Pages:** `HomePage.qml`, `JournalPage.qml`, `InboxPage.qml`, `TasksPage.qml`, `HabitsPage.qml`, `LibraryPage.qml`, `SetsPage.qml`, `WhiteboardPage.qml`, `BoardNode.qml`, `EditorPage.qml`
- **Block Editor Components (`desktop/qml/editor/`):** `BlocksView.qml`, `BlockDelegate.qml`, `TextBlockView.qml`, `CodeBlockView.qml`, `MathBlockView.qml`, `TableBlockView.qml`, `MediaBlockView.qml`, `BookmarkBlockView.qml`, `EmbedBlockView.qml`, `CanvasBlockView.qml`, `FormatBar.qml`, `SelectionBar.qml`, `SlashMenu.qml`, `MentionPopup.qml`, `MentionTip.qml`, `EditorRail.qml`
- **Icons & App Icon:** `desktop/assets/icons/*.svg` and `desktop/assets/yaop.svg`
