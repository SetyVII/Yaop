package org.yaop.ui.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * YAOP motion tokens. Every animation in the app must use one of these specs
 * (see mobile/GUIDELINES.md §5). Feel: iOS / Dynamic Island — springs with a
 * touch of bounce, interruptible, never a hard cut.
 */
object AppMotion {
    /** Micro-interactions: press feedback, toggles, chips, checkboxes. */
    fun <T> snappy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.82f, stiffness = 700f, visibilityThreshold = visibilityThreshold)

    /** Indicators, dock droplet, radial items, things that "travel". Visible bounce. */
    fun <T> bouncy(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = 380f, visibilityThreshold = visibilityThreshold)

    /** Screens, containers, layout/size changes. Almost no bounce. */
    fun <T> gentle(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = visibilityThreshold)

    /** Celebrations: check marks, counters, success states. */
    fun <T> playful(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = 420f, visibilityThreshold = visibilityThreshold)

    /** Finger-follow (scrubbing): very stiff so it tracks the pointer without lag. */
    fun <T> tracking(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1f, stiffness = 1400f, visibilityThreshold = visibilityThreshold)

    val offsetGentle: FiniteAnimationSpec<IntOffset> get() = gentle(IntOffset.VisibilityThreshold)
    val offsetBouncy: FiniteAnimationSpec<IntOffset> get() = bouncy(IntOffset.VisibilityThreshold)

    // Fades are the only place where tweens are allowed (opacity has no physics).
    const val FADE_FAST_MS = 120
    const val FADE_MS = 200
    const val SCRIM_MS = 180

    /** Delay between consecutive items of a staggered entrance. */
    const val STAGGER_MS = 35L
    /** Items beyond this index appear without stagger (avoid slow long lists). */
    const val STAGGER_MAX_ITEMS = 8

    /** Scale applied to anything tappable while pressed. */
    const val PRESSED_SCALE = 0.96f

    /** Scrim alpha behind menus / radial / sheets. */
    const val SCRIM_ALPHA = 0.5f

    /** Corner radius used to clip containers during a container transform. */
    val ContainerCorner: Dp = 18.dp
}

// ---------------------------------------------------------------------------------------------
// Press feedback
// ---------------------------------------------------------------------------------------------

/**
 * Applies the standard "sink" feedback while [interactionSource] is pressed.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = AppMotion.PRESSED_SCALE,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = AppMotion.snappy(),
        label = "PressScale",
    )
    // Only create a graphics layer while the element is actually being pressed/animated.
    // An always-present layer per card adds significant RenderThread work on mid-range devices.
    if (pressed || scale != 1f) {
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    } else {
        this
    }
}

/**
 * Convenient overload of [pressScale] that automatically manages its own [MutableInteractionSource].
 */
fun Modifier.pressScale(
    pressedScale: Float = AppMotion.PRESSED_SCALE,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    pressScale(interactionSource, pressedScale)
}

/**
 * Standard tappable surface for YAOP: sinks on press, springs back, light haptic tick.
 * Use instead of a bare `clickable` for cards, rows, tiles and custom buttons.
 */
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    haptic: Boolean = true,
    pressedScale: Float = AppMotion.PRESSED_SCALE,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val hapticFeedback = LocalHapticFeedback.current
    this
        .pressScale(interactionSource, pressedScale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
        ) {
            if (haptic) hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/**
 * Staggered entrance: fades + rises the element once, delayed by its [index].
 */
fun Modifier.staggeredAppear(index: Int): Modifier = composed {
    val startDelay = if (index < AppMotion.STAGGER_MAX_ITEMS) index * AppMotion.STAGGER_MS else 0L
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(startDelay)
        progress.animateTo(1f, AppMotion.gentle())
    }
    val p = progress.value
    // Drop the layer entirely once the entrance finishes; keeping one per item permanently is
    // expensive on low-end GPUs.
    if (p >= 1f) {
        this
    } else {
        graphicsLayer {
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * 16.dp.toPx()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Animated counter
// ---------------------------------------------------------------------------------------------

/** Number that rolls up/down when it changes instead of jumping. */
@Composable
fun AnimatedCounter(
    count: Int,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            val up = targetState > initialState
            (
                slideInVertically(AppMotion.offsetBouncy) { h -> if (up) h else -h } +
                    fadeIn(tween(AppMotion.FADE_FAST_MS))
                ).togetherWith(
                slideOutVertically(AppMotion.offsetBouncy) { h -> if (up) -h else h } +
                    fadeOut(tween(AppMotion.FADE_FAST_MS)),
            )
        },
        label = "AnimatedCounter",
        modifier = modifier,
    ) { value ->
        Text(text = value.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight)
    }
}

// ---------------------------------------------------------------------------------------------
// Shared element / container transform plumbing
// ---------------------------------------------------------------------------------------------

/** Shared transition scope provided by MainAppContainer (null in previews / other activities). */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** AnimatedVisibilityScope of the top-level navigation AnimatedContent. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Remembers which on-screen element launched the next navigation so the opened screen can
 * grow out of it (and shrink back into it on back). Keys are "<origin>:<objectId>" so the
 * same object can appear in several sections without key clashes.
 */
class SharedOriginRegistry {
    var pendingKey: String? = null

    fun mark(origin: String, objectId: String) {
        pendingKey = sharedObjectKey(origin, objectId)
    }

    fun consume(): String? = pendingKey.also { pendingKey = null }
}

val LocalSharedOrigin = compositionLocalOf { SharedOriginRegistry() }

fun sharedObjectKey(origin: String, objectId: String): String = "$origin:$objectId"

/**
 * Marks this element as the container of [objectId] in [origin]. When the user opens it,
 * the detail screen grows from these bounds (container transform). No-op outside the
 * navigation host.
 */
fun Modifier.sharedObjectBounds(origin: String, objectId: String, shape: Shape? = null): Modifier =
    sharedContainer(sharedObjectKey(origin, objectId), shape ?: RoundedCornerShape(AppMotion.ContainerCorner))

@OptIn(ExperimentalSharedTransitionApi::class)
fun Modifier.sharedContainer(key: String?, shape: Shape = RoundedCornerShape(AppMotion.ContainerCorner)): Modifier =
    composed {
        val sharedScope = LocalSharedTransitionScope.current
        val animatedScope = LocalNavAnimatedScope.current
        if (key == null || sharedScope == null || animatedScope == null) {
            this
        } else {
            with(sharedScope) {
                this@composed.sharedBounds(
                    sharedContentState = rememberSharedContentState(key),
                    animatedVisibilityScope = animatedScope,
                    enter = fadeIn(tween(AppMotion.FADE_MS)),
                    exit = fadeOut(tween(AppMotion.FADE_FAST_MS)),
                    boundsTransform = BoundsTransform { _, _ -> AppMotion.gentle() },
                    clipInOverlayDuringTransition = OverlayClip(shape),
                )
            }
        }
    }
