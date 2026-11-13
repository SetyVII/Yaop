package org.yaop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.yaop.R

/** Typography: Inter Tight for content, Plex Mono for interface labels. */
@OptIn(ExperimentalTextApi::class)
object AppFonts {
    val InterTight = FontFamily(
        listOf(400, 500, 600).map { w ->
            Font(
                R.font.inter_tight, FontWeight(w),
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        },
    )
    /** One accent word per screen title, in `ice` (spec §3). Never in paragraphs or data. */
    val Doto = FontFamily(
        Font(R.font.doto, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    )
    val PlexMono = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )
}

/** Mono label: 10.5 sp, +10% tracking. Callers upper-case the text. */
val AppLabelStyle = TextStyle(
    fontFamily = AppFonts.PlexMono, fontWeight = FontWeight.Medium,
    fontSize = 10.5.sp, letterSpacing = 1.05.sp,
)

/** Mono button text: 12 sp, +8% tracking. */
val AppButtonStyle = TextStyle(
    fontFamily = AppFonts.PlexMono, fontWeight = FontWeight.Medium,
    fontSize = 12.sp, letterSpacing = 0.96.sp,
)

private fun TextStyle.inter(weight: FontWeight? = null) =
    copy(fontFamily = AppFonts.InterTight, fontWeight = weight ?: fontWeight)

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.inter(), displayMedium = base.displayMedium.inter(),
    displaySmall = base.displaySmall.inter(),
    headlineLarge = base.headlineLarge.inter(FontWeight.Medium),
    headlineMedium = base.headlineMedium.inter(FontWeight.Medium),
    headlineSmall = base.headlineSmall.inter(FontWeight.Medium),
    titleLarge = base.titleLarge.inter(FontWeight.SemiBold),
    titleMedium = base.titleMedium.inter(FontWeight.SemiBold),
    titleSmall = base.titleSmall.inter(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.inter().copy(fontSize = 15.sp, lineHeight = 22.5.sp, letterSpacing = 0.sp),
    bodyMedium = base.bodyMedium.inter().copy(fontSize = 13.sp, letterSpacing = 0.sp),
    bodySmall = base.bodySmall.inter(),
    labelLarge = AppButtonStyle, labelMedium = AppLabelStyle, labelSmall = AppLabelStyle,
)
