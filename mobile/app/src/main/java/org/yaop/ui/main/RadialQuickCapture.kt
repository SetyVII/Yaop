package org.yaop.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.AppMotion
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

enum class RadialMode { CLOSED, FAN, WHEEL }

/** One option in the radial menu. [typeId] null = "more" (opens the full create dialog). */
data class RadialItem(
    val typeId: String?,
    val emoji: String,
    val label: String,
    val color: Color,
)

/**
 * Shared state between the dock's (+) button (gesture source) and the radial overlay
 * (renderer + hit-testing). All coordinates are in root (window) pixels.
 */
@Stable
class RadialMenuState {
    var mode by mutableStateOf(RadialMode.CLOSED)
        private set

    /** Center of the (+) button in root coordinates. */
    var anchor by mutableStateOf(Offset.Zero)

    var hoveredIndex by mutableIntStateOf(-1)
        private set

    /** Wheel item centers in root coordinates, published by the overlay each layout. */
    internal var wheelCenters: List<Offset> = emptyList()
    internal var hitRadiusPx: Float = 0f

    /** Card grid (default) instead of the radial fan/wheel; set from the capture-style preference. */
    var cardsStyle by mutableStateOf(true)

    val isOpen: Boolean get() = mode != RadialMode.CLOSED

    fun openFan() {
        hoveredIndex = -1
        mode = RadialMode.FAN
    }

    fun openWheel() {
        hoveredIndex = -1
        // With cards there is no wheel: a long press shows the same panel.
        mode = if (cardsStyle) RadialMode.FAN else RadialMode.WHEEL
    }

    fun close() {
        hoveredIndex = -1
        mode = RadialMode.CLOSED
    }

    /** Updates the hovered wheel item. Returns true when it changed (for haptics). */
    fun updatePointer(rootPosition: Offset): Boolean {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        wheelCenters.forEachIndexed { index, center ->
            val d = hypot(center.x - rootPosition.x, center.y - rootPosition.y)
            if (d < bestDistance) {
                bestDistance = d
                best = index
            }
        }
        val newHover = if (bestDistance <= hitRadiusPx) best else -1
        val changed = newHover != hoveredIndex
        hoveredIndex = newHover
        return changed
    }
}

private data class Ring(val radiusDp: Float, val capacity: Int, val startDeg: Float, val endDeg: Float)

// Rings sized so the outermost item stays inside a ~400dp wide phone.
private val FAN_RING = Ring(radiusDp = 122f, capacity = 6, startDeg = 165f, endDeg = 15f)
private val WHEEL_RINGS = listOf(
    Ring(radiusDp = 108f, capacity = 6, startDeg = 162f, endDeg = 18f),
    Ring(radiusDp = 176f, capacity = 8, startDeg = 160f, endDeg = 20f),
    Ring(radiusDp = 244f, capacity = 7, startDeg = 130f, endDeg = 50f),
)

/** Offset in dp (x right, y down) of each item relative to the anchor. */
private fun radialOffsetsDp(count: Int, rings: List<Ring>): List<Offset> {
    val result = mutableListOf<Offset>()
    var remaining = count
    for (ring in rings) {
        if (remaining <= 0) break
        val n = minOf(remaining, ring.capacity)
        for (i in 0 until n) {
            val deg = if (n == 1) 90f else ring.startDeg + (ring.endDeg - ring.startDeg) * i / (n - 1)
            val rad = Math.toRadians(deg.toDouble())
            result += Offset(
                x = (cos(rad) * ring.radiusDp).toFloat(),
                y = (-sin(rad) * ring.radiusDp).toFloat(),
            )
        }
        remaining -= n
    }
    return result
}

internal fun maxWheelItems(): Int = WHEEL_RINGS.sumOf { it.capacity }

internal fun String?.toTypeColor(fallback: Color): Color =
    runCatching { Color(this!!.toColorInt()) }.getOrDefault(fallback)

private val ITEM_SIZE = 52.dp
private val BUBBLE_COLUMN_WIDTH = 84.dp

/**
 * Full-screen overlay that renders the radial quick-capture menu around the dock's (+).
 * - FAN: tap-to-pick quick actions in a semicircle (staggered spring entrance).
 * - WHEEL: every object type in concentric arcs; the finger (tracked by the dock) hovers
 *   items, release creates. The hovered item grows and its name appears in a pill.
 */
@Composable
fun RadialQuickCaptureOverlay(
    state: RadialMenuState,
    quickItems: List<RadialItem>,
    wheelItems: List<RadialItem>,
    onPick: (RadialItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var overlaySize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Keep rendering the last open mode while its items animate out.
    var renderedMode by remember { mutableStateOf(RadialMode.FAN) }
    if (state.mode != RadialMode.CLOSED) renderedMode = state.mode

    val scrimAlpha by animateFloatAsState(
        targetValue = if (state.isOpen) AppMotion.SCRIM_ALPHA else 0f,
        animationSpec = tween(AppMotion.SCRIM_MS),
        label = "RadialScrim",
    )

    val items = if (renderedMode == RadialMode.WHEEL) wheelItems.take(maxWheelItems()) else quickItems
    val offsetsDp = remember(items.size, renderedMode) {
        radialOffsetsDp(items.size, if (renderedMode == RadialMode.WHEEL) WHEEL_RINGS else listOf(FAN_RING))
    }

    // Publish wheel hit-test geometry (root px) for the dock gesture.
    if (renderedMode == RadialMode.WHEEL) {
        with(density) {
            state.hitRadiusPx = 34.dp.toPx()
            state.wheelCenters = offsetsDp.map { o -> state.anchor + Offset(o.x.dp.toPx(), o.y.dp.toPx()) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOrigin = it.positionInRoot(); overlaySize = it.size },
    ) {
        if (scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = scrimAlpha }
                    .background(Color.Black)
                    .then(
                        if (state.mode == RadialMode.FAN) {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { state.close() }
                        } else {
                            Modifier
                        },
                    ),
            )
        }

        val anchorLocal = state.anchor - overlayOrigin

        if (state.cardsStyle) {
            // Card grid: a translucent panel springing up above the dock.
            // Guard against pre-layout frames where overlaySize is still 0 while anchor is set.
            val bottomPad = if (overlaySize.height > 0 && state.anchor != Offset.Zero) {
                (with(density) { (overlaySize.height - anchorLocal.y).toDp() } + 40.dp).coerceAtLeast(0.dp)
            } else {
                96.dp
            }
            CaptureCardsPanel(
                items = remember(quickItems, wheelItems) { captureCardItems(quickItems, wheelItems) },
                visible = state.isOpen,
                onPick = onPick,
                onClose = { state.close() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad),
            )
        } else {

        // Hint / hovered label pill above the arc.
        val topRadius = if (renderedMode == RadialMode.WHEEL) 244f else 122f
        val hovered = items.getOrNull(state.hoveredIndex)
        val hint = when {
            renderedMode == RadialMode.FAN -> stringResource(R.string.radial_hold_hint)
            hovered != null -> hovered.label
            else -> stringResource(R.string.radial_cancel_hint)
        }
        val labelY = with(density) { anchorLocal.y - (topRadius + 64f).dp.toPx() }
        val labelAlpha by animateFloatAsState(
            targetValue = if (state.isOpen) 1f else 0f,
            animationSpec = AppMotion.gentle(),
            label = "RadialLabelAlpha",
        )
        if (labelAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(0, labelY.roundToInt()) }
                    .fillMaxSize()
                    .wrapContentSize(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = labelAlpha
                        translationY = (1f - labelAlpha) * 12.dp.toPx()
                    },
            ) {
                RadialLabelPill(text = hint, item = hovered.takeIf { renderedMode == RadialMode.WHEEL })
            }
        }

        items.forEachIndexed { index, item ->
            val o = offsetsDp.getOrNull(index) ?: return@forEachIndexed
            androidx.compose.runtime.key(renderedMode, item.typeId ?: "more", index) {
                RadialBubble(
                    item = item,
                    index = index,
                    visible = state.mode == renderedMode,
                    isHovered = renderedMode == RadialMode.WHEEL && state.hoveredIndex == index,
                    showLabel = true,
                    anchorLocal = anchorLocal,
                    targetOffsetDp = o,
                    onClick = if (renderedMode == RadialMode.FAN) ({ onPick(item) }) else null,
                )
            }
        }
        }
    }
}

@Composable
private fun RadialBubble(
    item: RadialItem,
    index: Int,
    visible: Boolean,
    isHovered: Boolean,
    showLabel: Boolean,
    anchorLocal: Offset,
    targetOffsetDp: Offset,
    onClick: (() -> Unit)?,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(index * 18L)
            progress.animateTo(1f, AppMotion.bouncy())
        } else {
            progress.animateTo(0f, AppMotion.snappy())
        }
    }
    val hoverScale by animateFloatAsState(
        targetValue = if (isHovered) 1.32f else 1f,
        animationSpec = AppMotion.bouncy(),
        label = "RadialHoverScale",
    )
    val haptic = LocalHapticFeedback.current
    val p = progress.value
    if (p <= 0.001f && !visible) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .offset {
                val tx = anchorLocal.x + targetOffsetDp.x.dp.toPx() * p
                val ty = anchorLocal.y + targetOffsetDp.y.dp.toPx() * p
                val halfW = BUBBLE_COLUMN_WIDTH.toPx() / 2f
                val halfH = ITEM_SIZE.toPx() / 2f
                IntOffset((tx - halfW).roundToInt(), (ty - halfH).roundToInt())
            }
            .width(BUBBLE_COLUMN_WIDTH)
            .graphicsLayer {
                val s = (0.3f + 0.7f * p) * hoverScale
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, ITEM_SIZE.toPx() / 2f / size.height.coerceAtLeast(1f))
            },
    ) {
        Box(
            modifier = Modifier
                .size(ITEM_SIZE)
                .clip(CircleShape)
                // Design (§5): translucent bubbles; the colour belongs to content, so the bubble stays neutral.
                .background(androidx.compose.ui.graphics.Color(0xE60B1A4A), CircleShape)
                .background(androidx.compose.ui.graphics.Color(if (isHovered) 0x40FFFFFF else 0x1FFFFFFF), CircleShape)
                .border(
                    width = if (isHovered) 2.dp else 1.dp,
                    color = if (isHovered) AppColors.Accent else AppColors.BorderSubtle,
                    shape = CircleShape,
                )
                .then(
                    if (onClick != null && visible) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClick()
                        }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = item.emoji, fontSize = 22.sp)
        }
        if (showLabel) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = item.label.uppercase(),
                style = org.yaop.ui.theme.AppLabelStyle,
                color = if (isHovered) AppColors.Accent else AppColors.TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RadialLabelPill(text: String, item: RadialItem?) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(shape)
            .background(AppColors.SurfaceElevated, shape)
            .border(1.dp, (item?.color ?: AppColors.BorderSubtle).copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (item != null) {
            Text(text = item.emoji, fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (item != null) FontWeight.SemiBold else FontWeight.Medium,
            color = if (item != null) AppColors.TextPrimary else AppColors.TextSecondary,
        )
    }
}
