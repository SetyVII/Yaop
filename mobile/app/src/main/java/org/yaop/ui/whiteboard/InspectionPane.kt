package org.yaop.ui.whiteboard

import org.yaop.ui.common.DesignModalBottomSheet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.model.SpannedBlock
import org.yaop.ui.common.TypeBadge
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors

/**
 * Heptabase-style Side-by-Side Split View (on Tablet / Landscape) or Bottom Sheet (on Phone).
 * Allows deep inspection and reading of object blocks directly from the whiteboard canvas.
 */
@Composable
fun SideBySidePane(
    obj: PkmObject,
    type: ObjectType?,
    onClose: () -> Unit,
    onOpenFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(TypePalette.ColorBgSecondary)
            .border(
                width = 1.dp,
                color = TypePalette.ColorShapeHighlightMedium,
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
            )
            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)),
        color = TypePalette.ColorBgSecondary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
        ) {
            InspectionHeader(
                obj = obj,
                type = type,
                onClose = onClose,
                onOpenFullscreen = onOpenFullscreen,
            )

            Spacer(modifier = Modifier.height(16.dp))

            InspectionBlockContent(
                blocks = obj.blocks,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Mobile Bottom Sheet inspection variant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectionSheet(
    obj: PkmObject,
    type: ObjectType?,
    onDismiss: () -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    DesignModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            InspectionHeader(
                obj = obj,
                type = type,
                onClose = onDismiss,
                onOpenFullscreen = onOpenFullscreen,
            )

            Spacer(modifier = Modifier.height(16.dp))

            InspectionBlockContent(
                blocks = obj.blocks,
                modifier = Modifier.height(380.dp),
            )
        }
    }
}

@Composable
private fun InspectionHeader(
    obj: PkmObject,
    type: ObjectType?,
    onClose: () -> Unit,
    onOpenFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (obj.icon?.emoji != null) {
                Text(text = obj.icon.emoji, fontSize = 20.sp)
            } else if (type != null) {
                TypeBadge(type = type)
            }
            Text(
                text = obj.plainTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TypePalette.ColorTextPrimary,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenFullscreen, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = stringResource(R.string.whiteboard_open_fullscreen),
                    tint = TypePalette.ColorIce,
                    modifier = Modifier.size(17.dp),
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = TypePalette.ColorTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun InspectionBlockContent(
    blocks: List<Block>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (blocks.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.whiteboard_empty_note_content),
                    color = AppColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        } else {
            items(blocks.size, key = { blocks[it].id }) { index ->
                val block = blocks[index]
                when (block) {
                    is SpannedBlock -> {
                        val text = block.spans.joinToString("") {
                            (it as? org.yaop.core.model.InlineSpan.Text)?.text ?: ""
                        }
                        if (block is Block.Heading) {
                            Text(
                                text = text,
                                fontSize = if (block.level == org.yaop.core.model.HeadingLevel.H1) 18.sp else 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TypePalette.ColorTextPrimary,
                            )
                        } else if (block is Block.Todo) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = if (block.checked) "☑" else "☐",
                                    color = if (block.checked) TypePalette.ColorLime else TypePalette.ColorTextSecondary,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = text,
                                    color = if (block.checked) TypePalette.ColorTextTertiary else TypePalette.ColorTextPrimary,
                                    fontSize = 13.sp,
                                )
                            }
                        } else {
                            Text(
                                text = text,
                                color = TypePalette.ColorTextPrimary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                    is Block.Code -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.CodeBackground)
                                .padding(10.dp),
                        ) {
                            Text(
                                text = block.code,
                                color = TypePalette.ColorIce,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    else -> {
                        // Other blocks preview
                    }
                }
            }
        }
    }
}
