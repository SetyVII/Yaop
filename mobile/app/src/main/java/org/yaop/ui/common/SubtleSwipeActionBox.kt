package org.yaop.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Calculates a dampened horizontal offset for subtle tactile swipe gestures.
 * Ensures the dragged component never flies completely off the screen and provides
 * a smooth rubber-banding feel with an elastic resistance ceiling.
 */
fun calculateSubtleOffset(
    rawDx: Float,
    thresholdPx: Float,
    maxTravelPx: Float,
): Float {
    val sign = rawDx.sign
    val absRaw = abs(rawDx)
    val damped = if (absRaw <= thresholdPx) {
        absRaw * 0.82f
    } else {
        val extra = absRaw - thresholdPx
        (thresholdPx * 0.82f + extra * 0.28f).coerceAtMost(maxTravelPx)
    }
    return sign * damped
}

/**
 * Checks whether a touch coordinate in window space is within the Android system back gesture deadzone.
 * Swipes starting near the left or right edges (e.g. < 28dp) are left untouched so system predictive
 * back and edge-navigation take full precedence.
 */
fun isTouchInEdgeDeadZone(
    touchWindowX: Float,
    screenWidthPx: Float,
    deadZonePx: Float,
): Boolean {
    return touchWindowX <= deadZonePx || touchWindowX >= (screenWidthPx - deadZonePx)
}

/**
 * A refined, edge-aware swipe action container designed specifically for modern Android gesture navigation.
 *
 * Key design characteristics:
 * 1. Edge-Safe (Deadzone Exclusion): Touches starting near the screen edge (< [edgeDeadZoneDp]) are ignored
 *    so Android's system back gesture (Predictive Back) continues to function seamlessly without interference.
 * 2. Subtle Threshold: Triggers on a short, comfortable flick ([thresholdDp], ~48dp) rather than requiring
 *    full-screen drags.
 * 3. Rubber-band Damping: Visual displacement is capped at [maxTravelDp] (~68dp), revealing a clean status icon
 *    without throwing the card across the screen.
 * 4. Micro-Haptics: Delivers a crisp, subtle [HapticFeedbackType.TextHandleMove] tick right when the threshold is crossed.
 * 5. Elastic Spring Return: Upon release, smoothly animates back to rest using Compose spring physics.
 * 6. Vertical Scroll Friendly: Prioritizes vertical list scrolling so documents and tasks scroll smoothly.
 */
@Composable
fun SubtleSwipeActionBox(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    // Right swipe configuration (swiping left-to-right)
    onSwipeRight: (() -> Unit)? = null,
    swipeRightIcon: ImageVector = Icons.Default.Check,
    swipeRightColor: Color = AppColors.Accent,
    swipeRightBackground: Color = AppColors.Accent.copy(alpha = 0.16f),
    swipeRightLabel: String? = null,
    // Left swipe configuration (swiping right-to-left)
    onSwipeLeft: (() -> Unit)? = null,
    swipeLeftIcon: ImageVector = Icons.Default.MoreHoriz,
    swipeLeftColor: Color = AppColors.TextSecondary,
    swipeLeftBackground: Color = AppColors.SurfaceElevated,
    swipeLeftLabel: String? = null,
    // Tuning parameters
    thresholdDp: Dp = 48.dp,
    maxTravelDp: Dp = 68.dp,
    edgeDeadZoneDp: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    // Keep the content in the exact same composition slot when swipe is enabled/disabled.
    // Switching between two different Box branches used to dispose the BasicTextField and drop
    // focus as soon as a block was focused (which closed the keyboard).
    LaunchedEffect(enabled) {
        if (!enabled) offsetX.snapTo(0f)
    }

    var componentWindowBounds by remember { mutableStateOf(Rect.Zero) }

    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)

    val thresholdPx = with(density) { thresholdDp.toPx() }
    val maxTravelPx = with(density) { maxTravelDp.toPx() }
    val deadZonePx = with(density) { edgeDeadZoneDp.toPx() }
    // The real window width: touch coordinates are window coordinates, so the dead zone must be measured against
    // the same space (also correct on tablets and split screen, where screenWidthDp is not the window).
    val screenWidthPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toFloat()

    Box(
        modifier = modifier
            .clip(shape)
            .onGloballyPositioned { coordinates ->
                componentWindowBounds = coordinates.boundsInWindow()
            }
            .pointerInput(thresholdPx, maxTravelPx, deadZonePx, screenWidthPx, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    val touchWindowX = componentWindowBounds.left + down.position.x

                    // Ignore touches starting near the left or right screen edges to guarantee
                    // that Android's system back gesture / gesture navigation always works unimpeded.
                    if (isTouchInEdgeDeadZone(touchWindowX, screenWidthPx, deadZonePx)) {
                        return@awaitEachGesture
                    }

                    var totalDx = 0f
                    var totalDy = 0f
                    var isEngaged = false
                    var hasPassedSlop = false
                    var hasTriggeredHaptic = false
                    val touchSlop = viewConfiguration.touchSlop

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        val dx = change.position.x - change.previousPosition.x
                        val dy = change.position.y - change.previousPosition.y
                        totalDx += dx
                        totalDy += dy

                        if (!hasPassedSlop) {
                            val dist = hypot(totalDx, totalDy)
                            if (dist > touchSlop) {
                                // Only engage if horizontal swipe clearly dominates vertical motion
                                if (abs(totalDx) > abs(totalDy) * 1.35f) {
                                    val allowsRight = totalDx > 0 && currentOnSwipeRight != null
                                    val allowsLeft = totalDx < 0 && currentOnSwipeLeft != null
                                    if (allowsRight || allowsLeft) {
                                        isEngaged = true
                                        hasPassedSlop = true
                                        change.consume()
                                    } else {
                                        return@awaitEachGesture
                                    }
                                } else {
                                    // Vertical scrolling takes precedence
                                    return@awaitEachGesture
                                }
                            }
                        } else if (isEngaged) {
                            change.consume()
                            val damped = calculateSubtleOffset(totalDx, thresholdPx, maxTravelPx)
                            coroutineScope.launch { offsetX.snapTo(damped) }

                            val crossed = abs(totalDx) >= thresholdPx
                            if (crossed && !hasTriggeredHaptic) {
                                hasTriggeredHaptic = true
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (!crossed && hasTriggeredHaptic) {
                                hasTriggeredHaptic = false
                            }
                        }
                    }

                    if (isEngaged) {
                        if (hasTriggeredHaptic) {
                            if (totalDx > 0) {
                                currentOnSwipeRight?.invoke()
                            } else {
                                currentOnSwipeLeft?.invoke()
                            }
                        }
                        // Animate smoothly back to resting position with subtle spring physics
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    }
                }
            },
    ) {
        val currentOffset = offsetX.value
        val isRevealingRight = currentOffset > 0f && onSwipeRight != null
        val isRevealingLeft = currentOffset < 0f && onSwipeLeft != null

        // Background reveal layer for Right-Swipe (e.g. Complete task)
        if (isRevealingRight) {
            val progress = (abs(currentOffset) / (thresholdPx * 0.82f)).coerceIn(0f, 1f)
            val bgAlpha = progress * 0.22f
            val iconScale = 0.5f + (progress * 0.5f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(swipeRightBackground.copy(alpha = bgAlpha), shape)
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = progress
                    },
                ) {
                    Icon(
                        imageVector = swipeRightIcon,
                        contentDescription = swipeRightLabel,
                        tint = swipeRightColor,
                        modifier = Modifier.size(20.dp),
                    )
                    if (!swipeRightLabel.isNullOrBlank() && progress > 0.65f) {
                        Text(
                            text = swipeRightLabel,
                            color = swipeRightColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Background reveal layer for Left-Swipe (e.g. Priority / More options)
        if (isRevealingLeft) {
            val progress = (abs(currentOffset) / (thresholdPx * 0.82f)).coerceIn(0f, 1f)
            val bgAlpha = progress * 0.22f
            val iconScale = 0.5f + (progress * 0.5f)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(swipeLeftBackground.copy(alpha = bgAlpha), shape)
                    .padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                        alpha = progress
                    },
                ) {
                    if (!swipeLeftLabel.isNullOrBlank() && progress > 0.65f) {
                        Text(
                            text = swipeLeftLabel,
                            color = swipeLeftColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Icon(
                        imageVector = swipeLeftIcon,
                        contentDescription = swipeLeftLabel,
                        tint = swipeLeftColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Foreground content layer, offset horizontally by the subtle gesture
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(currentOffset.roundToInt(), 0) },
        ) {
            content()
        }
    }
}
