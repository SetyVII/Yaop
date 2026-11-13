package org.yaop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import org.yaop.ui.theme.AppFonts

/**
 * Ambient glyphs: a few "@" and "#" in Plex Mono 11, white at 25-85%,
 * scattered over empty backgrounds (Home, onboarding). Fixed positions, drawn once per size,
 * static: no animation and no cost while scrolling. Keep them OUT of scroll areas.
 */
@Composable
fun Modifier.ambientGlyphs(): Modifier {
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontFamily = AppFonts.PlexMono, fontSize = 11.sp) }
    return this.then(
        Modifier.drawWithCache {
            val spots = listOf(
                Triple(0.06f, 0.07f, "@"), Triple(0.88f, 0.05f, "#"), Triple(0.30f, 0.13f, "@"),
                Triple(0.94f, 0.22f, "@"), Triple(0.03f, 0.34f, "#"), Triple(0.80f, 0.40f, "@"),
                Triple(0.12f, 0.56f, "@"), Triple(0.92f, 0.60f, "#"), Triple(0.40f, 0.70f, "@"),
                Triple(0.07f, 0.82f, "#"), Triple(0.70f, 0.86f, "@"), Triple(0.95f, 0.94f, "@"),
            )
            val alphas = floatArrayOf(0.85f, 0.35f, 0.5f, 0.6f, 0.3f, 0.45f, 0.7f, 0.3f, 0.25f, 0.5f, 0.4f, 0.8f)
            val laid = spots.mapIndexed { i, (_, _, ch) -> measurer.measure(ch, style.copy(color = Color.White.copy(alpha = alphas[i]))) }
            onDrawBehind {
                spots.forEachIndexed { i, (x, y, _) ->
                    drawText(laid[i], topLeft = Offset(size.width * x, size.height * y))
                }
            }
        },
    )
}
