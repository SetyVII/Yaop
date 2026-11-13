package org.yaop.core.model.whiteboard

/**
 * Color palette for the Whiteboard canvas elements (Cards, sticky notes, frames, ink).
 * Uses dark surfaces paired with the 10 semantic content & highlight colors.
 */
object WhiteboardPalette {

    // 1. Card & Sticky Note Tints (Calibrated dark surfaces with subtle ~18% color wash)
    const val STICKY_DARK = "#1C1C1E"   // --color-bg-modal (neutral dark card)
    const val STICKY_AMBER = "#2E2412"  // warm amber wash
    const val STICKY_COBALT = "#1B223E" // cobalt wash
    const val STICKY_ICE = "#162536"    // ice cyan wash
    const val STICKY_TEAL = "#112927"   // teal wash
    const val STICKY_LIME = "#192812"   // lime wash
    const val STICKY_PURPLE = "#261633" // purple wash
    const val STICKY_RED = "#2C1615"    // red wash

    // Aliases for compatibility
    const val STICKY_YELLOW = STICKY_AMBER
    const val STICKY_ORANGE = STICKY_AMBER
    const val STICKY_BLUE = STICKY_COBALT
    const val STICKY_GREEN = STICKY_LIME
    const val STICKY_ROSE = STICKY_RED

    val STICKY_COLORS = listOf(
        STICKY_DARK,
        STICKY_COBALT,
        STICKY_ICE,
        STICKY_TEAL,
        STICKY_LIME,
        STICKY_AMBER,
        STICKY_PURPLE,
        STICKY_RED,
    )

    // 2. Sections / Frames (Dark translucent containers with 1dp subtle rims)
    const val SECTION_SLATE = "#151518"  // neutral dark frame
    const val SECTION_COBALT = "#141829" // cobalt frame
    const val SECTION_TEAL = "#0E2120"   // teal frame
    const val SECTION_LIME = "#13210F"   // lime frame
    const val SECTION_AMBER = "#241D0E"  // amber frame
    const val SECTION_PURPLE = "#1D1129" // purple frame

    // Aliases for compatibility
    const val SECTION_INDIGO = SECTION_COBALT
    const val SECTION_EMERALD = SECTION_TEAL
    const val SECTION_CYAN = SECTION_TEAL

    val SECTION_COLORS = listOf(
        SECTION_SLATE,
        SECTION_COBALT,
        SECTION_TEAL,
        SECTION_LIME,
        SECTION_AMBER,
        SECTION_PURPLE,
    )

    // 3. Drawing Ink (10 semantic content colors + grey)
    const val INK_WHITE = "#F2F2F7"      // ColorTextPrimary
    const val INK_COBALT = "#3E58EB"     // ColorCobalt
    const val INK_ICE_BLUE = "#2AA7EE"   // ColorIce
    const val INK_TEAL = "#0FC8BA"       // ColorTeal
    const val INK_LIME = "#5DD400"       // ColorLime
    const val INK_YELLOW = "#ECD91B"     // ColorYellow
    const val INK_ORANGE = "#FFB522"     // ColorOrange
    const val INK_CORAL = "#F55522"      // ColorRed
    const val INK_PURPLE = "#AB50CC"     // ColorPurple
    const val INK_PINK = "#E51CA0"       // ColorPink
    const val INK_GREY = "#8E8E93"       // ColorGrey

    val INK_COLORS = listOf(
        INK_WHITE,
        INK_COBALT,
        INK_ICE_BLUE,
        INK_TEAL,
        INK_LIME,
        INK_YELLOW,
        INK_ORANGE,
        INK_CORAL,
        INK_PURPLE,
        INK_PINK,
        INK_GREY,
    )

    // 4. Highlighter Colors (Muted translucent washes)
    const val HIGHLIGHTER_YELLOW = "#ECD91B"
    const val HIGHLIGHTER_LIME = "#5DD400"
    const val HIGHLIGHTER_ICE = "#2AA7EE"
    const val HIGHLIGHTER_ORANGE = "#FFB522"
    const val HIGHLIGHTER_PINK = "#E51CA0"

    // Aliases
    const val HIGHLIGHTER_GREEN = HIGHLIGHTER_LIME
    const val HIGHLIGHTER_CYAN = HIGHLIGHTER_ICE

    val HIGHLIGHTER_COLORS = listOf(
        HIGHLIGHTER_YELLOW,
        HIGHLIGHTER_LIME,
        HIGHLIGHTER_ICE,
        HIGHLIGHTER_ORANGE,
        HIGHLIGHTER_PINK,
    )
}
