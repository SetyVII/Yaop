package org.yaop.ui.common

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.yaop.R
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min

/** Thumbnail / image privacy mode. */
enum class ThumbnailMode { SHOW, BLUR, HIDE }

/** Where the mode applies: everywhere (default) or only in navigation views. */
enum class PrivacyScope { ALL, LISTS }

/**
 * Device-local image privacy state. Never synced: showing the phone does not blur the desktop.
 * Backed by SharedPreferences; read as Compose state so a switch never reloads data or loses scroll.
 */
object ImagePrivacy {
    private const val PREFS = "yaop_ui_prefs"
    private const val K_MODE = "thumb_mode"
    private const val K_DISCRETE = "thumb_discrete"
    private const val K_SCOPE = "thumb_scope"
    private const val K_RESET = "thumb_reset_on_exit"

    private var prefs: SharedPreferences? = null

    var mode by mutableStateOf(ThumbnailMode.SHOW)
        private set
    var discreteMode by mutableStateOf(ThumbnailMode.BLUR)
        private set
    var scope by mutableStateOf(PrivacyScope.ALL)
        private set
    var resetOnExit by mutableStateOf(false)
        private set

    val isPrivate: Boolean get() = mode != ThumbnailMode.SHOW

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        mode = p.enumOr(K_MODE, ThumbnailMode.SHOW)
        discreteMode = p.enumOr(K_DISCRETE, ThumbnailMode.BLUR).takeIf { it != ThumbnailMode.SHOW } ?: ThumbnailMode.BLUR
        scope = p.enumOr(K_SCOPE, PrivacyScope.ALL)
        resetOnExit = p.getBoolean(K_RESET, false)
        // A cold start means the app was closed (swiped away or killed): honour "back to Show".
        if (resetOnExit && mode != ThumbnailMode.SHOW) updateMode(ThumbnailMode.SHOW)
    }

    /** Eye button / gesture / shortcut: SHOW ↔ the chosen discrete mode. */
    fun toggle() = updateMode(if (mode == ThumbnailMode.SHOW) discreteMode else ThumbnailMode.SHOW)

    fun updateMode(value: ThumbnailMode) {
        mode = value
        prefs?.edit()?.putString(K_MODE, value.name)?.apply()
    }

    fun updateDiscreteMode(value: ThumbnailMode) {
        if (value == ThumbnailMode.SHOW) return
        discreteMode = value
        prefs?.edit()?.putString(K_DISCRETE, value.name)?.apply()
        if (mode != ThumbnailMode.SHOW) updateMode(value)
    }

    fun updateScope(value: PrivacyScope) {
        scope = value
        prefs?.edit()?.putString(K_SCOPE, value.name)?.apply()
    }

    fun updateResetOnExit(value: Boolean) {
        resetOnExit = value
        prefs?.edit()?.putBoolean(K_RESET, value)?.apply()
    }

    /** Called when the app leaves the foreground for good (not on rotation). */
    fun onAppExit() {
        if (resetOnExit && mode != ThumbnailMode.SHOW) updateMode(ThumbnailMode.SHOW)
    }

    private inline fun <reified E : Enum<E>> SharedPreferences.enumOr(key: String, fallback: E): E =
        getString(key, null)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: fallback
}

/**
 * Where an image is drawn. Navigation views use the default; the open object provides
 * `inObject = true`, and `noteRevealed` while the user chose "show images of this note" (§8.2).
 */
data class ImageSurface(val inObject: Boolean = false, val noteRevealed: Boolean = false)

val LocalImageSurface = compositionLocalOf { ImageSurface() }

/** Mode that applies to images at this point of the tree. */
@Composable
fun effectiveThumbnailMode(): ThumbnailMode {
    val surface = LocalImageSurface.current
    val mode = ImagePrivacy.mode
    return when {
        mode == ThumbnailMode.SHOW -> ThumbnailMode.SHOW
        surface.inObject && (surface.noteRevealed || ImagePrivacy.scope == PrivacyScope.LISTS) -> ThumbnailMode.SHOW
        else -> mode
    }
}

/** Marks the subtree as the open object (editor, header, sheets and dialogs it opens). */
@Composable
fun ObjectImageSurface(noteRevealed: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalImageSurface provides ImageSurface(inObject = true, noteRevealed = noteRevealed), content = content)
}

/**
 * Applies the privacy mode to an image composable (Image, AsyncImage, a Box holding one).
 * - SHOW: no-op.
 * - BLUR: strong blur + 45 % ink veil + eye-off glyph. Holding the image peeks at it while pressed
 *   (§3 "Asomarse"); the press is observed, not consumed, so taps still reach the card.
 *   Below Android 12 there is no cheap blur: the veil becomes nearly opaque.
 * - HIDE: the content is not drawn at all; a dim box with an image glyph keeps the layout.
 */
fun Modifier.privacyImage(): Modifier = composed {
    val mode = effectiveThumbnailMode()
    var peeking by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val hidden = mode != ThumbnailMode.SHOW && !(mode == ThumbnailMode.BLUR && peeking)
    // 150 ms fade between modes (§4); AppMotion's reduced-motion setting maps to 0 elsewhere.
    val veil by animateFloatAsState(if (hidden) 1f else 0f, tween(150), label = "privacyVeil")
    val glyph = rememberVectorPainter(if (mode == ThumbnailMode.HIDE) Icons.Outlined.Image else Icons.Outlined.VisibilityOff)
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val veilColor = AppColors.Background
    val glyphColor = AppColors.TextSecondary

    val peek = if (mode == ThumbnailMode.BLUR) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val released = withTimeoutOrNull(longPressMs) {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.all { !it.pressed }) break
                    }
                    true
                }
                if (released == null) {
                    peeking = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.all { !it.pressed }) break
                    }
                    peeking = false
                }
            }
        }
    } else {
        Modifier
    }

    if (mode == ThumbnailMode.SHOW && veil == 0f) return@composed this
    this
        .then(peek)
        .drawWithContent {
            if (mode == ThumbnailMode.HIDE && hidden) {
                drawRect(AppColors.Surface)
            } else {
                drawContent()
                if (veil > 0f) drawRect(veilColor, alpha = veil * if (canBlur) 0.45f else 0.92f)
            }
            if (veil > 0f && size.minDimension >= 20.dp.toPx()) {
                val side = min(24.dp.toPx(), size.minDimension * 0.4f)
                translate((size.width - side) / 2f, (size.height - side) / 2f) {
                    with(glyph) { draw(Size(side, side), alpha = veil, colorFilter = ColorFilter.tint(glyphColor)) }
                }
            }
        }
        .then(if (canBlur && veil > 0f && mode == ThumbnailMode.BLUR) Modifier.blur((28 * veil).dp, BlurredEdgeTreatment.Rectangle) else Modifier)
}

/** True when decoding can be skipped altogether (HIDE): saves memory in long lists. */
@Composable
fun shouldSkipImageDecode(): Boolean = effectiveThumbnailMode() == ThumbnailMode.HIDE

/** Eye button for navigation headers (§4): tap toggles SHOW ↔ discrete mode, hold opens the three modes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePrivacyButton(
    modifier: Modifier = Modifier,
) {
    val active = ImagePrivacy.isPrivate
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (active) AppColors.Selection else AppColors.Surface, CircleShape)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.thumb_toggle),
                    onClick = { ImagePrivacy.toggle() },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = stringResource(R.string.thumb_toggle),
                tint = if (active) AppColors.Ice else AppColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(AppColors.SurfaceElevated)) {
            ThumbnailMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(stringResource(m.labelRes()), color = if (ImagePrivacy.mode == m) AppColors.Ice else AppColors.TextPrimary) },
                    leadingIcon = { Icon(m.icon(), contentDescription = null, tint = AppColors.TextSecondary) },
                    onClick = {
                        menu = false
                        if (m != ThumbnailMode.SHOW) ImagePrivacy.updateDiscreteMode(m)
                        ImagePrivacy.updateMode(m)
                    },
                )
            }
        }
    }
}

fun ThumbnailMode.labelRes(): Int = when (this) {
    ThumbnailMode.SHOW -> R.string.thumb_mode_show
    ThumbnailMode.BLUR -> R.string.thumb_mode_blur
    ThumbnailMode.HIDE -> R.string.thumb_mode_hide
}

private fun ThumbnailMode.icon(): ImageVector = when (this) {
    ThumbnailMode.SHOW -> Icons.Outlined.Visibility
    ThumbnailMode.BLUR -> Icons.Outlined.VisibilityOff
    ThumbnailMode.HIDE -> Icons.Outlined.Image
}

/**
 * Two-finger swipe down anywhere on the content toggles the mode (§4), with a short vibration.
 * Observes on the Initial pass without consuming, so one-finger scroll is untouched.
 */
fun Modifier.privacyToggleGesture(): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var start: Offset? = null
            var fired = false
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size == 2 && !fired) {
                    val centroid = Offset(pressed.sumOf { it.position.x.toDouble() }.toFloat() / 2f, pressed.sumOf { it.position.y.toDouble() }.toFloat() / 2f)
                    val s = start ?: centroid.also { start = it }
                    val dy = centroid.y - s.y
                    if (dy > thresholdPx && abs(centroid.x - s.x) < dy) {
                        fired = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ImagePrivacy.toggle()
                    }
                } else if (pressed.size != 2) {
                    start = null
                }
            }
        }
    }
}
