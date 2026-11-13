package org.yaop.ui.editor

import org.yaop.ui.common.privacyImage
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.PkmObject
import org.yaop.core.model.toPlainText
import org.yaop.ui.common.extractAudioDurationSeconds
import org.yaop.ui.common.extractImageMime
import org.yaop.ui.common.extractImageSize
import org.yaop.ui.common.formatAudioMimeShort
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.formatMimeShort
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * EditorCoverPolicy defines the contract and architectural rule for any top-level hero banner,
 * media preview, or cover inside YAOP object editors.
 *
 * THE RULE:
 * When viewing an object, media and covers display in their full, rich hero format (large image,
 * waveform audio player, rich link preview, or custom cover banner).
 * However, whenever editing starts (signaled by either the software keyboard being active or an
 * editor field receiving focus, e.g. Title, Description, or any Content Block):
 *   1. The hero MUST smoothly collapse into a space-efficient compact mode (e.g. 52-56dp) with
 *      fluid spring motion, freeing up critical vertical screen space.
 *   2. The editor's focused element MUST be immediately brought into view above the software
 *      keyboard and above the pinned EditorToolbarRail.
 *   3. The user can manually tap the collapse/expand toggle anytime to inspect the full hero while
 *      editing, and when editing ends (keyboard closed, focus cleared), the hero automatically
 *      expands back to full format.
 */

enum class HeroDisplayMode {
    EXPANDED,
    COMPACT,
}

interface EditorHeroState {
    var mode: HeroDisplayMode
    val isCompact: Boolean get() = mode == HeroDisplayMode.COMPACT
    val isExpanded: Boolean get() = mode == HeroDisplayMode.EXPANDED
    fun toggle()
    fun notifyEditingState(isEditing: Boolean)
}

class EditorHeroStateImpl(
    initialMode: HeroDisplayMode = HeroDisplayMode.EXPANDED,
) : EditorHeroState {
    private val _mode = mutableStateOf(initialMode)
    override var mode: HeroDisplayMode
        get() = _mode.value
        set(value) { _mode.value = value }

    var userManuallyToggled by mutableStateOf(false)
        private set

    private var lastEditingState by mutableStateOf(false)

    override fun toggle() {
        userManuallyToggled = true
        mode = if (mode == HeroDisplayMode.EXPANDED) HeroDisplayMode.COMPACT else HeroDisplayMode.EXPANDED
    }

    override fun notifyEditingState(isEditing: Boolean) {
        if (isEditing != lastEditingState) {
            lastEditingState = isEditing
            if (isEditing) {
                if (!userManuallyToggled) {
                    mode = HeroDisplayMode.COMPACT
                }
            } else {
                userManuallyToggled = false
                mode = HeroDisplayMode.EXPANDED
            }
        }
    }
}

@Composable
fun rememberEditorHeroState(
    isEditing: Boolean,
    initialMode: HeroDisplayMode = if (isEditing) HeroDisplayMode.COMPACT else HeroDisplayMode.EXPANDED,
): EditorHeroState {
    val state = remember { EditorHeroStateImpl(initialMode) }
    LaunchedEffect(isEditing) {
        state.notifyEditingState(isEditing)
    }
    return state
}

/**
 * Generic container that smoothly animates between compact and expanded hero presentations
 * using spring physics, and provides a standardized manual toggle button.
 */
@Composable
fun CollapsibleHeroContainer(
    heroState: EditorHeroState,
    modifier: Modifier = Modifier,
    compactContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            ),
    ) {
        AnimatedContent(
            targetState = heroState.mode,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith
                    fadeOut(spring(stiffness = Spring.StiffnessMedium)))
            },
            label = "CollapsibleHeroTransition",
        ) { mode ->
            when (mode) {
                HeroDisplayMode.COMPACT -> compactContent()
                HeroDisplayMode.EXPANDED -> expandedContent()
            }
        }
    }
}

/**
 * Standardized Image Hero implementation adhering to [EditorCoverPolicy].
 * Automatically collapses to a sleek 56dp row during editing, keeping the editor in focus.
 */
@Composable
fun CollapsibleImageHero(
    heroState: EditorHeroState,
    imageBitmap: Bitmap?,
    assetId: String?,
    title: String,
    obj: PkmObject,
    onZoom: () -> Unit,
    onReplace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CollapsibleHeroContainer(
        heroState = heroState,
        modifier = modifier,
        compactContent = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                    .clickable { heroState.toggle() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Miniature thumbnail
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap.asImageBitmap(),
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(0.5.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp)).privacyImage(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.AccentSubtle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Metadata snippet & tap prompt
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    val mimeShort = formatMimeShort(extractImageMime(obj))
                    val fileSize = formatFileSize(extractImageSize(obj))
                    val metaLine = if (imageBitmap != null) {
                        listOfNotNull("${imageBitmap.width}×${imageBitmap.height} px", mimeShort, fileSize).joinToString(" • ")
                    } else {
                        stringResource(R.string.image_tap_to_upload)
                    }
                    Text(
                        text = metaLine,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.action_expand_cover),
                        fontSize = 10.sp,
                        color = AppColors.Accent,
                    )
                }

                // Actions: Zoom, Replace, Expand chevron
                if (!assetId.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(AppColors.SurfaceElevated)
                            .clickable { onZoom() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = stringResource(R.string.image_hero_zoom),
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.image_replace),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onReplace() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(AppColors.SurfaceElevated)
                        .clickable { heroState.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_expand_cover),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        expandedContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(AppColors.Surface, RoundedCornerShape(16.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(16.dp))
                    .then(
                        if (!assetId.isNullOrBlank()) Modifier.clickable { onZoom() } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (imageBitmap != null) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 320.dp)
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap = imageBitmap.asImageBitmap(),
                                contentDescription = title,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 160.dp, max = 320.dp).privacyImage(),
                            )

                            // Top action pills: Collapse toggle on left, Zoom on right
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .clickable { heroState.toggle() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowUp,
                                            contentDescription = stringResource(R.string.action_collapse_cover),
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.action_collapse_cover),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.65f))
                                        .clickable { onZoom() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ZoomIn,
                                            contentDescription = stringResource(R.string.image_hero_zoom),
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = stringResource(R.string.image_hero_zoom),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                        )
                                    }
                                }
                            }
                        }

                        // Meta & actions footer below hero preview
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val mimeShort = formatMimeShort(extractImageMime(obj))
                            val fileSize = formatFileSize(extractImageSize(obj))
                            val metaText = listOfNotNull(
                                "${imageBitmap.width}×${imageBitmap.height} px",
                                mimeShort,
                                fileSize,
                            ).joinToString(" • ")

                            Text(
                                text = metaText,
                                fontSize = 11.sp,
                                color = AppColors.TextTertiary,
                                maxLines = 1,
                            )

                            Text(
                                text = stringResource(R.string.image_replace),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.Accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onReplace() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                } else if (!assetId.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = AppColors.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onReplace() }
                            .padding(vertical = 36.dp, horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(AppColors.AccentSubtle),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = null,
                                tint = AppColors.Accent,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Text(
                            text = stringResource(R.string.image_tap_to_upload),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.image_add_first),
                            fontSize = 12.sp,
                            color = AppColors.Accent,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Standardized Cover Hero implementation for generic objects (Notes, Books, etc.) with a cover.
 * Smoothly collapses from full height (~130dp) to a compact 36dp strip during editing.
 */
@Composable
fun CollapsibleCoverHero(
    heroState: EditorHeroState,
    coverAssetId: String?,
    modifier: Modifier = Modifier,
) {
    val coverThumbnail = if (!coverAssetId.isNullOrBlank()) rememberDecodedThumbnail(coverAssetId) else null

    CollapsibleHeroContainer(
        heroState = heroState,
        modifier = modifier,
        compactContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                    .clickable { heroState.toggle() },
            ) {
                if (coverThumbnail != null) {
                    Image(
                        bitmap = coverThumbnail.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cover_image),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(AppColors.Accent.copy(alpha = 0.35f), AppColors.Selection),
                                ),
                            ),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_expand_cover),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
        expandedContent = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp)),
            ) {
                if (coverThumbnail != null) {
                    Image(
                        bitmap = coverThumbnail.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cover_image),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(AppColors.Accent.copy(alpha = 0.35f), AppColors.Selection),
                                ),
                            ),
                    )
                }

                // Collapse chevron in top-right
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { heroState.toggle() }
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = stringResource(R.string.action_collapse_cover),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.action_collapse_cover),
                            fontSize = 10.sp,
                            color = Color.White,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Reusable modifier extension to guarantee that any focusable editor target (Title, Description, Block)
 * is smoothly brought into view above the keyboard and editor toolbar whenever focused.
 */
@Composable
fun Modifier.editorFocusAutoScroll(
    bringIntoViewRequester: BringIntoViewRequester = remember { BringIntoViewRequester() },
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    onFocusChanged: ((Boolean) -> Unit)? = null,
): Modifier {
    return this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged { state ->
            onFocusChanged?.invoke(state.isFocused)
            if (state.isFocused) {
                coroutineScope.launch {
                    delay(120L)
                    runCatching { bringIntoViewRequester.bringIntoView() }
                }
            }
        }
}
