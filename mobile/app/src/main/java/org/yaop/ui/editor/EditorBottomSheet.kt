package org.yaop.ui.editor

import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import org.yaop.ui.common.DesignModalBottomSheet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.ui.theme.AppColors

private val PRESET_EMOJIS = listOf(
    "📝", "📅", "📚", "✅", "💡", "📁", "🌐", "🎯",
    "🚀", "🧠", "⭐", "📌", "🎨", "🔬", "💻", "🔥",
)

/**
 * Editor bottom sheet.
 * Features:
 * - Description (show toggle)
 * - Icon > (with emoji selection)
 * - Cover > (with cover toggle)
 * - Properties >
 * - Undo / Redo button tile
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorBottomSheet(
    onDismissRequest: () -> Unit,
    showDescription: Boolean,
    onToggleDescription: (Boolean) -> Unit,
    currentEmoji: String?,
    onSelectEmoji: (String) -> Unit,
    hasCover: Boolean,
    onToggleCover: () -> Unit,
    canHaveCover: Boolean = true,
    propertiesCount: Int,
    onOpenProperties: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean = true,
    canRedo: Boolean = true,
    isPinned: Boolean = false,
    onTogglePin: (() -> Unit)? = null,
    onMoveToTrash: (() -> Unit)? = null,
    noteImagesRevealed: Boolean = false,
    onToggleNoteImages: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showEmojiPicker by remember { mutableStateOf(value = false) }

    DesignModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AppColors.TextTertiary) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            // 1. Description (show toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Notes,
                        contentDescription = stringResource(R.string.sheet_description),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = stringResource(R.string.sheet_description),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.sheet_show_toggle),
                        fontSize = 14.sp,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Switch(
                        checked = showDescription,
                        onCheckedChange = onToggleDescription,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppColors.Accent,
                            uncheckedThumbColor = AppColors.TextTertiary,
                            uncheckedTrackColor = AppColors.Surface,
                        ),
                    )
                }
            }

            HorizontalDivider(color = AppColors.BorderSubtle)

            // 2. Icono >
            SheetActionRow(
                icon = Icons.Default.Mood,
                label = stringResource(R.string.sheet_icon),
                trailingBadge = currentEmoji ?: stringResource(R.string.sheet_change_icon),
                isEmoji = currentEmoji != null,
                onClick = { showEmojiPicker = !showEmojiPicker },
            )

            AnimatedVisibility(
                visible = showEmojiPicker,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.Surface, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sheet_select_emoji),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(PRESET_EMOJIS) { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        color = if (emoji == currentEmoji) AppColors.Selection else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        onSelectEmoji(emoji)
                                        showEmojiPicker = false
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = emoji, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = AppColors.BorderSubtle)

            if (canHaveCover) {
                // 3. Portada >
                SheetActionRow(
                    icon = Icons.Default.Image,
                    label = stringResource(R.string.sheet_cover),
                    trailingBadge = if (hasCover) stringResource(R.string.sheet_remove_cover) else stringResource(R.string.sheet_add_cover),
                    onClick = onToggleCover,
                )

                HorizontalDivider(color = AppColors.BorderSubtle)
            }

            // 4. Propiedades >
            SheetActionRow(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = stringResource(R.string.sheet_properties),
                trailingBadge = stringResource(R.string.sheet_properties_count, propertiesCount),
                onClick = {
                    onDismissRequest()
                    onOpenProperties()
                },
            )

            Spacer(modifier = Modifier.height(18.dp))

            // 5. Deshacer / Rehacer button tile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Surface, RoundedCornerShape(12.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                        .clickable(enabled = canUndo, onClick = onUndo)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.sheet_undo),
                            tint = if (canUndo) AppColors.TextPrimary else AppColors.TextTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sheet_undo),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (canUndo) AppColors.TextPrimary else AppColors.TextTertiary,
                        )
                    }
                }

                VerticalDivider(
                    color = AppColors.BorderSubtle,
                    modifier = Modifier.height(28.dp),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                        .clickable(enabled = canRedo, onClick = onRedo)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.sheet_redo),
                            tint = if (canRedo) AppColors.TextPrimary else AppColors.TextTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.sheet_redo),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (canRedo) AppColors.TextPrimary else AppColors.TextTertiary,
                        )
                    }
                }
            }

            // Image privacy on: show/hide only this note's images until leaving it.
            if (onToggleNoteImages != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.Surface, RoundedCornerShape(10.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                        .clickable {
                            onToggleNoteImages()
                            onDismissRequest()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (noteImagesRevealed) androidx.compose.material.icons.Icons.Outlined.VisibilityOff else androidx.compose.material.icons.Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = AppColors.Ice,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(if (noteImagesRevealed) R.string.thumb_note_conceal else R.string.thumb_note_reveal),
                        fontSize = 13.sp,
                        color = AppColors.TextPrimary,
                    )
                }
            }

            // Optional Pin & Trash actions
            if ((onTogglePin != null) || (onMoveToTrash != null)) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (onTogglePin != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.Surface, RoundedCornerShape(10.dp))
                                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                                .clickable {
                                    onTogglePin()
                                    onDismissRequest()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = if (isPinned) AppColors.Accent else AppColors.TextSecondary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPinned) stringResource(R.string.unpin_from_top) else stringResource(R.string.pin_to_top),
                                    fontSize = 13.sp,
                                    color = AppColors.TextPrimary,
                                )
                            }
                        }
                    }

                    if (onMoveToTrash != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                .border(1.dp, AppColors.Danger.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .clickable {
                                    onMoveToTrash()
                                    onDismissRequest()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = AppColors.Danger,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.move_to_trash),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AppColors.Danger,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    label: String,
    trailingBadge: String,
    onClick: () -> Unit,
    isEmoji: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trailingBadge,
                fontSize = if (isEmoji) 18.sp else 13.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(end = 4.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
