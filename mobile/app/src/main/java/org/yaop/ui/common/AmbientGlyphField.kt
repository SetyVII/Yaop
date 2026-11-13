package org.yaop.ui.common

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.ui.theme.AppFonts
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Living background for the welcome screens: digits and "@ #" drifting slowly, and a few molecules
 * (small atom clusters joined by lines) turning on the spot. Pure drawing: the frame clock only
 * invalidates the draw phase (no recomposition), the glyph layouts are measured once, and it falls back to a
 * still picture when the system has animations off (Settings -> animator duration scale 0).
 */
@Composable
fun Modifier.ambientGlyphField(count: Int = 34, molecules: Int = 4): Modifier {
    val context = LocalContext.current
    val animate = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
            .getOrDefault(true)
    }
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) nanos = withFrameNanos { it } - start
    }
    val measurer = rememberTextMeasurer()
    val style = remember { TextStyle(fontFamily = AppFonts.PlexMono, fontSize = 11.sp) }
    val chars = remember { "0123456789@#01".toCharArray() }
    val seeds = remember { Random(7) }
    val glyphs = remember(count) {
        List(count) {
            Glyph(
                x = seeds.nextFloat(), y = seeds.nextFloat(), char = chars[seeds.nextInt(chars.size)],
                alpha = 0.18f + seeds.nextFloat() * 0.6f, speed = 0.4f + seeds.nextFloat(), phase = seeds.nextFloat() * 6.28f,
            )
        }
    }
    val mols = remember(molecules) {
        List(molecules) {
            Molecule(
                cx = 0.12f + seeds.nextFloat() * 0.76f, cy = 0.1f + seeds.nextFloat() * 0.8f,
                atoms = 3 + seeds.nextInt(3), radius = 18f + seeds.nextFloat() * 14f,
                spin = (if (seeds.nextBoolean()) 1f else -1f) * (0.15f + seeds.nextFloat() * 0.2f), phase = seeds.nextFloat() * 6.28f,
            )
        }
    }
    return this.then(
        Modifier.drawWithCache {
            val layouts = glyphs.map { measurer.measure(it.char.toString(), style) }
            onDrawBehind {
                val t = nanos / 1_000_000_000f
                glyphs.forEachIndexed { i, g ->
                    // Slow lissajous drift around the home position, plus a gentle twinkle.
                    val dx = sin(t * 0.20f * g.speed + g.phase) * 14.dp.toPx()
                    val dy = cos(t * 0.16f * g.speed + g.phase * 1.3f) * 18.dp.toPx()
                    val twinkle = 0.65f + 0.35f * sin(t * 0.9f * g.speed + g.phase)
                    val color = Color.White.copy(alpha = (g.alpha * twinkle).coerceIn(0f, 1f))
                    drawText(layouts[i], color = color, topLeft = Offset(size.width * g.x + dx, size.height * g.y + dy))
                }
                mols.forEach { m ->
                    val cx = size.width * m.cx + sin(t * 0.12f + m.phase) * 10.dp.toPx()
                    val cy = size.height * m.cy + cos(t * 0.10f + m.phase) * 12.dp.toPx()
                    val r = m.radius.dp.toPx()
                    val pts = List(m.atoms) { k ->
                        val a = m.phase + t * m.spin + k * (6.2832f / m.atoms)
                        Offset(cx + cos(a) * r, cy + sin(a) * r)
                    }
                    val line = Color.White.copy(alpha = 0.22f)
                    val atom = Color.White.copy(alpha = 0.5f)
                    pts.forEach { p -> drawLine(line, Offset(cx, cy), p, strokeWidth = 1.dp.toPx()) }
                    pts.forEachIndexed { k, p -> drawLine(line, p, pts[(k + 1) % pts.size], strokeWidth = 1.dp.toPx()) }
                    drawCircle(atom, 3.2f.dp.toPx(), Offset(cx, cy))
                    pts.forEach { p -> drawCircle(atom, 2.2f.dp.toPx(), p) }
                }
            }
        },
    )
}

private class Glyph(val x: Float, val y: Float, val char: Char, val alpha: Float, val speed: Float, val phase: Float)
private class Molecule(val cx: Float, val cy: Float, val atoms: Int, val radius: Float, val spin: Float, val phase: Float)
