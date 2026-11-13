package org.yaop.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * Generated cover for objects without an image: a warm or cold
 * gradient of the type colour with a 6 dp dot dither at 35% black. Drawn once per size (drawWithCache),
 * so it costs nothing while scrolling.
 */
fun Modifier.designCover(base: Color): Modifier = drawWithCache {
    val deep = Color(0xFF1B1B6B)
    val brush = Brush.linearGradient(
        colors = listOf(base.copy(alpha = 0.95f), lerp(base, deep, 0.65f)),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
    )
    val step = 6.dp.toPx()
    val radius = 0.9.dp.toPx()
    val dot = Color.Black.copy(alpha = 0.35f)
    onDrawBehind {
        drawRect(brush)
        var y = step / 2
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) step / 2 else step
            while (x < size.width) {
                drawCircle(dot, radius, Offset(x, y))
                x += step
            }
            y += step
            row++
        }
    }
}
