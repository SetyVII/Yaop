package org.yaop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Design System Color Tokens (`--color-*`).
 * Deep blue dark canvas paired with 10 semantic content & highlight colors.
 */
object TypePalette {
    // 1. UI Surface Variables (--color-bg-*, --color-shape-*, --color-text-*, --color-control-*)
    val ColorBgPrimary = Color(0xFF050A26)              // --color-bg-primary (deep ink canvas)
    val ColorBgSecondary = Color(0xFF0D1850)            // --color-bg-secondary (sheets, toolbars, panels)
    val ColorBgModal = Color(0xFF12215F)                // --color-bg-modal (cards, elevated pills)
    val ColorBgNavigation = Color(0xFF050A26)           // Bottom dock & persistent bars

    val ColorShapeHighlightLight = Color(0x14FFFFFF)    // --color-shape-highlight-light
    val ColorShapeHighlightMedium = Color(0x29FFFFFF)   // --color-shape-highlight-medium (1dp borders)
    val ColorShapeHighlightHeavy = Color(0x3DFFFFFF)    // --color-shape-highlight-heavy (active nav pill, focus border)

    val ColorTextPrimary = Color(0xFFFFFFFF)            // --color-text-primary (crisp white)
    val ColorTextSecondary = Color(0xFFAEBFE4)          // --color-text-secondary (metadata, icons)
    val ColorTextTertiary = Color(0xFF8A9CC8)           // --color-text-tertiary (placeholders, hints)

    val ColorControlAccent = Color(0xFFFFFFFF)          // --color-control-accent (primary button fill)
    val ColorControlAccentSubtle = Color(0x24FFFFFF)    // Translucent accent container tint
    val ColorControlActive = Color(0xFFBFE3FF)          // --color-control-active
    val ColorControlInactive = Color(0xFF7189BF)        // --color-control-inactive

    // 2. 10 Content & Highlight Color Variables (--color-*)
    val ColorYellow = Color(0xFFFFD666)                 // --color-yellow
    val ColorOrange = Color(0xFFFFB07A)                 // --color-orange
    val ColorRed = Color(0xFFFF786E)                    // --color-red
    val ColorPink = Color(0xFFFF8FB3)                    // --color-pink
    val ColorPurple = Color(0xFFC2B2FF)                 // --color-purple
    val ColorCobalt = Color(0xFF6EAAFF)                 // --color-cobalt (blue)
    val ColorIce = Color(0xFFBFE3FF)                    // --color-ice (sky/cyan)
    val ColorTeal = Color(0xFF7AF0D0)                   // --color-teal
    val ColorLime = Color(0xFF7FE0A6)                   // --color-lime (green)
    val ColorGrey = Color(0xFFFFFFFF)                   // --color-grey

    // 3. Calibrated Text Highlighting & Block Tints (Carefully balanced for white text legibility)
    // Inline text foregrounds (slightly softened on dark background to avoid eye strain)
    val TextYellow = Color(0xFFFFD666)
    val TextOrange = Color(0xFFFFB07A)
    val TextRed = Color(0xFFFFC4BC)
    val TextPink = Color(0xFFFF8FB3)
    val TextPurple = Color(0xFFC2B2FF)
    val TextCobalt = Color(0xFF6EAAFF)
    val TextIce = Color(0xFFBFE3FF)
    val TextTeal = Color(0xFF7AF0D0)
    val TextLime = Color(0xFF7FE0A6)
    val TextGrey = Color(0xFFA9C0EA)

    // Inline background highlights (==highlight== and background color spans)
    // Uses ~26% alpha so primary white text (#F2F2F7) remains 100% crisp and readable on top
    val HighlightYellow = ColorYellow.copy(alpha = 0.25f)
    val HighlightOrange = ColorOrange.copy(alpha = 0.26f)
    val HighlightRed = ColorRed.copy(alpha = 0.26f)
    val HighlightPink = ColorPink.copy(alpha = 0.25f)
    val HighlightPurple = ColorPurple.copy(alpha = 0.26f)
    val HighlightCobalt = ColorCobalt.copy(alpha = 0.28f)
    val HighlightIce = ColorIce.copy(alpha = 0.26f)
    val HighlightTeal = ColorTeal.copy(alpha = 0.25f)
    val HighlightLime = ColorLime.copy(alpha = 0.24f)
    val HighlightGrey = ColorGrey.copy(alpha = 0.28f)

    // Whole-block background tints (subtle 14% wash for colored blocks/callouts)
    val BlockYellow = ColorYellow.copy(alpha = 0.12f)
    val BlockOrange = ColorOrange.copy(alpha = 0.12f)
    val BlockRed = ColorRed.copy(alpha = 0.12f)
    val BlockPink = ColorPink.copy(alpha = 0.12f)
    val BlockPurple = ColorPurple.copy(alpha = 0.12f)
    val BlockCobalt = ColorCobalt.copy(alpha = 0.12f)
    val BlockIce = ColorIce.copy(alpha = 0.12f)
    val BlockTeal = ColorTeal.copy(alpha = 0.12f)
    val BlockLime = ColorLime.copy(alpha = 0.12f)
    val BlockGrey = ColorGrey.copy(alpha = 0.12f)
}

object AppColors {
    // Dark Theme Palette mapped to Design System Tokens
    val Background = TypePalette.ColorBgPrimary               // Primary canvas & doc background
    val Surface = Color(0x0FFFFFFF)                              // Base `surface`: white 6% to group elements
    val SurfaceSolid = TypePalette.ColorBgSecondary                // Cards, sheets & grouped containers
    val SurfaceElevated = TypePalette.ColorBgModal            // Dialogs, elevated pills, slash popup
    val NavigationBar = TypePalette.ColorBgNavigation         // Dock, bottom bar
    val BorderSubtle = TypePalette.ColorShapeHighlightMedium  // Discrete 1dp separators & outlines
    val BorderHover = TypePalette.ColorShapeHighlightHeavy    // Focused borders
    val Hover = Color(0x0FFFFFFF)                                // Row hover & touch feedback
    val Selection = TypePalette.ColorShapeHighlightHeavy      // Active item background

    // Typography Colors
    val TextPrimary = TypePalette.ColorTextPrimary            // Main readable content
    val TextSecondary = TypePalette.ColorTextSecondary        // Metadata, icons, subtle helpers
    val TextTertiary = TypePalette.ColorTextTertiary          // Placeholder text, disabled hints

    // Accent & Semantic Highlights
    val Accent = TypePalette.ColorControlAccent               // Primary control accent
    val AccentSubtle = TypePalette.ColorControlAccentSubtle   // Active chip background tint
    val Success = Color(0xFF7FE0A6)                              // Done status, online dot, verified tags
    val Warning = TypePalette.ColorOrange                     // In-progress, alerts
    val Danger = Color(0xFFFF8A7A)
    val Ice = TypePalette.ColorIce                           // Mentions, links, active indicators (`ice`)
    val AccentInk = Color(0xFF0B1F5C)                           // Text on the white primary button (`accent-ink`)
    val Violet = Color(0xFF8F7CFF)                              // "Pinned" tag

    // Formatting & Code
    val CodeBackground = Color(0x8C03081E)                       // ink at 55%
    val CalloutBackground = Color(0x246EAAFF)                    // blue 14%
    val CalloutBorder = Color(0x406EAAFF)                        // blue 25%
    val KeyboardBar = Color(0xFF08103A)
    val KeyboardBarBorder = TypePalette.ColorShapeHighlightMedium
}

/** App background gradient: soft vertical sky-to-ink gradient. */
object AppBackgroundBrush {
    val Background: androidx.compose.ui.graphics.Brush = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color(0xFF0B1A4F), Color(0xFF071240), Color(0xFF050A26)),
    )
}

/** One colour per base object type; types without one use [AppColors.Ice]. */
object AppTypeColors {
    private val byType = mapOf(
        "note" to Color(0xFFBFE3FF), "book" to Color(0xFF9B8CFF), "person" to Color(0xFFFFB38A),
        "weblink" to Color(0xFF7AF0D0), "image" to Color(0xFFFF8FB3), "daily" to Color(0xFFE8EEFF),
        "journal" to Color(0xFFE8EEFF), "tag" to Color(0xFFDCEC4E), "project" to Color(0xFFFFD166),
    )
    fun of(typeId: String?): Color = byType[typeId] ?: AppColors.Ice
}
