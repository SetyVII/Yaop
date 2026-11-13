package org.yaop.ui.whiteboard

import org.yaop.ui.common.privacyImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.yaop.R
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyKeys
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toPlainText
import org.yaop.core.model.whiteboard.CardViewMode
import org.yaop.core.model.whiteboard.WhiteboardMath
import org.yaop.core.model.whiteboard.WhiteboardNode
import org.yaop.core.model.whiteboard.WhiteboardNodeContent
import org.yaop.core.model.whiteboard.WhiteboardPalette
import org.yaop.core.model.whiteboard.WhiteboardSection
import org.yaop.core.model.whiteboard.WhiteboardTool
import org.yaop.ui.common.TypeBadge
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import kotlin.math.roundToInt

/**
 * Renders a [WhiteboardNode] positioned accurately in screen space based on the canvas viewport.
 */
@Composable
fun WhiteboardNodeView(
    node: WhiteboardNode,
    state: WhiteboardState,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onNavigateToObject: (String) -> Unit,
    onToggleTask: (PkmObject) -> Unit,
    onConvertToNote: ((WhiteboardNode, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewport = state.viewport
    val screenPos = WhiteboardMath.canvasToScreen(
        androidx.compose.ui.geometry.Offset(node.x, node.y),
        viewport,
    )
    val screenWidth = node.width * viewport.zoom
    val screenHeight = node.height * viewport.zoom
    val isSelected = state.selectedNodeId == node.id
    val isConnectingFrom = state.connectingFromNodeId == node.id

    Box(
        modifier = modifier
            .offset { IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt()) }
            .size(width = screenWidth.dp, height = screenHeight.dp)
            .pointerInput(node.id, state.activeTool) {
                if (state.activeTool == WhiteboardTool.SELECT) {
                    detectDragGestures(
                        onDragStart = {
                            state.selectNode(node.id)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val canvasDeltaX = dragAmount.x / state.viewport.zoom
                            val canvasDeltaY = dragAmount.y / state.viewport.zoom
                            state.moveNode(node.id, canvasDeltaX, canvasDeltaY)
                        },
                        onDragEnd = {
                            state.finishNodeMove()
                        },
                    )
                }
            }
            .clickable {
                if (state.activeTool == WhiteboardTool.CONNECTOR && state.connectingFromNodeId != null) {
                    state.completeConnection(node.id)
                } else if (state.activeTool == WhiteboardTool.ERASER) {
                    state.deleteNode(node.id)
                } else {
                    state.selectNode(node.id)
                }
            },
    ) {
        when (val content = node.content) {
            is WhiteboardNodeContent.ObjectCard -> {
                val obj = allObjects.find { it.id == content.objectId }
                val type = allTypes.find { it.id == obj?.typeId }
                WhiteboardObjectCard(
                    node = node,
                    obj = obj,
                    type = type,
                    viewMode = content.viewMode,
                    isSelected = isSelected,
                    isConnectingFrom = isConnectingFrom,
                    zoom = viewport.zoom,
                    onInspect = { state.inspectingObjectId = content.objectId },
                    onOpenFull = { onNavigateToObject(content.objectId) },
                    onStartConnect = { state.startConnection(node.id) },
                    onToggleMode = { state.toggleCardViewMode(node.id) },
                    onDelete = { state.deleteNode(node.id) },
                    onToggleTask = { obj?.let(onToggleTask) },
                )
            }
            is WhiteboardNodeContent.Sticky -> {
                WhiteboardStickyCard(
                    node = node,
                    text = content.text,
                    colorHex = content.colorHex,
                    isSelected = isSelected,
                    isConnectingFrom = isConnectingFrom,
                    zoom = viewport.zoom,
                    onTextChange = { newText -> state.updateStickyText(node.id, newText) },
                    onColorChange = { newColor -> state.updateNodeColor(node.id, newColor) },
                    onStartConnect = { state.startConnection(node.id) },
                    onConvertToNote = if (onConvertToNote != null) { { onConvertToNote(node, content.text) } } else null,
                    onDelete = { state.deleteNode(node.id) },
                )
            }
            is WhiteboardNodeContent.Text -> {
                WhiteboardTextCard(
                    text = content.text,
                    fontSize = content.fontSize,
                    colorHex = content.colorHex,
                    isSelected = isSelected,
                    zoom = viewport.zoom,
                )
            }
        }
    }
}

/**
 * Object card representing an active entity from the vault.
 */
@Composable
private fun WhiteboardObjectCard(
    node: WhiteboardNode,
    obj: PkmObject?,
    type: ObjectType?,
    viewMode: CardViewMode,
    isSelected: Boolean,
    isConnectingFrom: Boolean,
    zoom: Float,
    onInspect: () -> Unit,
    onOpenFull: () -> Unit,
    onStartConnect: () -> Unit,
    onToggleMode: () -> Unit,
    onDelete: () -> Unit,
    onToggleTask: () -> Unit,
) {
    val borderColor = when {
        isConnectingFrom -> TypePalette.ColorCobalt
        isSelected -> TypePalette.ColorCobalt
        else -> TypePalette.ColorShapeHighlightMedium
    }

    val canonicalType = obj?.let { it.typeId }
    val isImage = canonicalType == CanonicalTypeIds.IMAGE
    val imageAssetId = if (obj != null) {
        if (isImage) resolveImageAssetId(obj) else obj.cover?.assetId?.takeIf { !it.startsWith("cover_") }
    } else null
    val thumbnail = if (!imageAssetId.isNullOrBlank()) rememberDecodedThumbnail(imageAssetId) else null

    val isWeb = canonicalType == CanonicalTypeIds.WEB
    val webUrl = if (isWeb && obj != null) obj.weblinkUrl.orEmpty() else ""

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (isSelected) 8.dp else 2.dp,
                    shape = RoundedCornerShape(14.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = if (isSelected) TypePalette.ColorCobalt.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.6f),
                )
                .clip(RoundedCornerShape(14.dp))
                .background(TypePalette.ColorBgModal)
                .border(
                    width = if (isSelected || isConnectingFrom) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(14.dp),
                ),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (zoom < 0.6f) 6.dp else 10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Header: Type badge & Emoji
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (obj?.icon?.emoji != null) {
                            Text(text = obj.icon.emoji, fontSize = if (zoom < 0.6f) 11.sp else 15.sp)
                        } else if (type != null) {
                            TypeBadge(type = type)
                        }
                        if (type != null && obj?.icon?.emoji != null && zoom >= 0.6f) {
                            Text(
                                text = type.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TypePalette.ColorTextSecondary,
                            )
                        }
                    }

                    if (obj != null && canonicalType == CanonicalTypeIds.TASK) {
                        Checkbox(
                            checked = obj.isTaskDone,
                            onCheckedChange = { onToggleTask() },
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = TypePalette.ColorLime,
                                uncheckedColor = TypePalette.ColorTextSecondary,
                            ),
                        )
                    }
                }

                // If this is an Image or has a Cover thumbnail, render real visual preview
                if (thumbnail != null && zoom >= 0.45f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TypePalette.ColorBgPrimary),
                    ) {
                        Image(
                            bitmap = thumbnail.bitmap.asImageBitmap(),
                            contentDescription = obj?.plainTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().privacyImage(),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Title & Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (thumbnail == null) Modifier.weight(1f) else Modifier),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (isWeb && webUrl.isNotBlank() && zoom >= 0.6f && thumbnail == null) {
                        val domain = webUrl.removePrefix("https://").removePrefix("http://").takeWhile { it != '/' && it != '?' }
                        Text(
                            text = domain,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TypePalette.ColorIce,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }

                    val displayTitle = obj?.plainTitle?.ifBlank { stringResource(R.string.untitled_object) }
                        ?: stringResource(R.string.untitled_object)
                    Text(
                        text = displayTitle,
                        fontSize = if (zoom < 0.6f) 11.sp else 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TypePalette.ColorTextPrimary,
                        maxLines = if (thumbnail != null || viewMode == CardViewMode.COMPACT) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (thumbnail == null && viewMode == CardViewMode.DETAILED && obj != null && obj.plainSummary.isNotBlank() && zoom >= 0.7f) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = obj.plainSummary,
                            fontSize = 11.sp,
                            color = TypePalette.ColorTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Selection Action Bar (floating pill above card)
        if (isSelected) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-36).dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(TypePalette.ColorBgSecondary, RoundedCornerShape(20.dp))
                    .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onInspect, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Visibility,
                        contentDescription = stringResource(R.string.whiteboard_open_side_by_side),
                        tint = TypePalette.ColorIce,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onOpenFull, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = stringResource(R.string.whiteboard_open_fullscreen),
                        tint = TypePalette.ColorTextPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(onClick = onStartConnect, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.whiteboard_action_connect),
                        tint = TypePalette.ColorPurple,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onToggleMode, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.whiteboard_action_mode),
                        tint = TypePalette.ColorTextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.whiteboard_delete_element),
                        tint = TypePalette.ColorRed,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Interactive color-coded sticky note on the infinite canvas.
 */
@Composable
private fun WhiteboardStickyCard(
    node: WhiteboardNode,
    text: String,
    colorHex: String,
    isSelected: Boolean,
    isConnectingFrom: Boolean,
    zoom: Float,
    onTextChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onStartConnect: () -> Unit,
    onConvertToNote: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    val cardColor = runCatching { Color(colorHex.toColorInt()) }.getOrDefault(TypePalette.ColorBgModal)
    val textColor = TypePalette.ColorTextPrimary
    val focusManager = LocalFocusManager.current

    var stickyTextFieldValue by remember(node.id) {
        mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length)))
    }

    LaunchedEffect(text) {
        if (text != stickyTextFieldValue.text) {
            stickyTextFieldValue = stickyTextFieldValue.copy(
                text = text,
                selection = TextRange(text.length.coerceAtMost(stickyTextFieldValue.selection.max)),
            )
        }
    }

    LaunchedEffect(isSelected) {
        if (!isSelected) {
            focusManager.clearFocus(force = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (isSelected) 8.dp else 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                )
                .clip(RoundedCornerShape(12.dp))
                .background(cardColor)
                .border(
                    width = if (isSelected || isConnectingFrom) 1.5.dp else 1.dp,
                    color = if (isSelected) TypePalette.ColorCobalt else TypePalette.ColorShapeHighlightMedium,
                    shape = RoundedCornerShape(12.dp),
                ),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                BasicTextField(
                    value = stickyTextFieldValue,
                    onValueChange = { newValue ->
                        stickyTextFieldValue = newValue
                        if (newValue.text != text) {
                            onTextChange(newValue.text)
                        }
                    },
                    enabled = isSelected,
                    modifier = Modifier.fillMaxSize(),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = (13f * zoom).coerceIn(10f, 22f).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = if (isSelected) SolidColor(textColor) else SolidColor(Color.Transparent),
                    decorationBox = { innerTextField ->
                        if (stickyTextFieldValue.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.whiteboard_sticky_placeholder),
                                color = TypePalette.ColorTextTertiary,
                                fontSize = (13f * zoom).coerceIn(10f, 22f).sp,
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }

        // Selection Action Bar
        if (isSelected) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-36).dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(TypePalette.ColorBgSecondary, RoundedCornerShape(20.dp))
                    .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(20.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Color dots
                WhiteboardPalette.STICKY_COLORS.take(5).forEach { colHex ->
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Color(colHex.toColorInt()), CircleShape)
                            .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                            .clickable { onColorChange(colHex) },
                    )
                }
                IconButton(onClick = onStartConnect, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.whiteboard_action_connect),
                        tint = TypePalette.ColorPurple,
                        modifier = Modifier.size(15.dp),
                    )
                }
                if (onConvertToNote != null) {
                    IconButton(onClick = onConvertToNote, modifier = Modifier.size(26.dp)) {
                        Icon(
                            Icons.Default.DriveFileRenameOutline,
                            contentDescription = stringResource(R.string.whiteboard_convert_to_note),
                            tint = TypePalette.ColorIce,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.whiteboard_delete_element),
                        tint = TypePalette.ColorRed,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/**
 * Text node on canvas.
 */
@Composable
private fun WhiteboardTextCard(
    text: String,
    fontSize: Int,
    colorHex: String,
    isSelected: Boolean,
    zoom: Float,
) {
    val color = runCatching { Color(colorHex.toColorInt()) }.getOrDefault(TypePalette.ColorTextPrimary)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isSelected) Modifier.border(1.dp, TypePalette.ColorCobalt, RoundedCornerShape(6.dp))
                else Modifier,
            )
            .padding(4.dp),
    ) {
        val defaultText = stringResource(R.string.whiteboard_default_text_label)
        Text(
            text = text.ifBlank { defaultText },
            color = color,
            fontSize = (fontSize * zoom).coerceIn(10f, 36f).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Renders a [WhiteboardSection] bounding box frame on the canvas plane.
 */
@Composable
fun WhiteboardSectionView(
    section: WhiteboardSection,
    state: WhiteboardState,
    modifier: Modifier = Modifier,
) {
    val viewport = state.viewport
    val screenPos = WhiteboardMath.canvasToScreen(
        androidx.compose.ui.geometry.Offset(section.x, section.y),
        viewport,
    )
    val screenWidth = section.width * viewport.zoom
    val screenHeight = section.height * viewport.zoom
    val isSelected = state.selectedSectionId == section.id
    val sectionColor = runCatching { Color(section.colorHex.toColorInt()) }.getOrDefault(Color(0xFF1E293B))

    Box(
        modifier = modifier
            .offset { IntOffset(screenPos.x.roundToInt(), screenPos.y.roundToInt()) }
            .size(width = screenWidth.dp, height = screenHeight.dp)
            .pointerInput(section.id, state.activeTool) {
                if (state.activeTool == WhiteboardTool.SELECT) {
                    detectDragGestures(
                        onDragStart = {
                            state.selectedSectionId = section.id
                            state.selectedNodeId = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val canvasDeltaX = dragAmount.x / state.viewport.zoom
                            val canvasDeltaY = dragAmount.y / state.viewport.zoom
                            state.moveSection(section.id, canvasDeltaX, canvasDeltaY)
                        },
                        onDragEnd = {
                            state.finishSectionMove()
                        },
                    )
                }
            }
            .clickable {
                state.selectedSectionId = section.id
                state.selectedNodeId = null
            },
    ) {
        // Section Container Frame
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(sectionColor.copy(alpha = 0.28f))
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) TypePalette.ColorCobalt else TypePalette.ColorShapeHighlightMedium,
                    shape = RoundedCornerShape(16.dp),
                ),
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(sectionColor.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = section.title.uppercase(),
                    fontSize = (11f * viewport.zoom).coerceIn(9f, 15f).sp,
                    fontWeight = FontWeight.Bold,
                    color = TypePalette.ColorTextPrimary,
                    letterSpacing = 1.sp,
                )
                if (isSelected) {
                    IconButton(
                        onClick = { state.deleteSection(section.id) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.whiteboard_delete_section),
                            tint = TypePalette.ColorTextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
