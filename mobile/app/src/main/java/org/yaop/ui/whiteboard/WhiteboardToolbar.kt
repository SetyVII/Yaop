package org.yaop.ui.whiteboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.whiteboard.WhiteboardPalette
import org.yaop.core.model.whiteboard.WhiteboardTool
import org.yaop.ui.theme.TypePalette
import kotlin.math.roundToInt

/**
 * Floating translucent toolbar for the Whiteboard canvas.
 */
@Composable
fun WhiteboardToolbar(
    state: WhiteboardState,
    screenSize: IntSize,
    onOpenAddCardDialog: () -> Unit,
    onOpenAddSectionDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Quick Drawing & Styling Sub-bar (appears when ink/highlighter or sticky is active)
        val showColorPicker = state.activeTool == WhiteboardTool.PEN ||
            state.activeTool == WhiteboardTool.HIGHLIGHTER ||
            state.activeTool == WhiteboardTool.STICKY

        AnimatedVisibility(
            visible = showColorPicker,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            WhiteboardColorSubBar(
                state = state,
            )
        }

        // Main Navigation & Tool Dock (Horizontally scrollable for small screens / mobile)
        Row(
            modifier = Modifier
                .shadow(
                    elevation = 16.dp,
                    shape = RoundedCornerShape(26.dp),
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.7f),
                )
                .clip(RoundedCornerShape(26.dp))
                .background(TypePalette.ColorBgNavigation.copy(alpha = 0.94f))
                .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(26.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Tool items
            ToolbarToolItem(
                icon = Icons.Default.NearMe,
                label = stringResource(R.string.whiteboard_tool_select),
                isSelected = state.activeTool == WhiteboardTool.SELECT,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.SELECT
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.PanTool,
                label = stringResource(R.string.whiteboard_tool_pan),
                isSelected = state.activeTool == WhiteboardTool.PAN,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.PAN
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.Add,
                label = stringResource(R.string.whiteboard_tool_card),
                isSelected = state.activeTool == WhiteboardTool.CARD,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.SELECT
                    onOpenAddCardDialog()
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.CropSquare,
                label = stringResource(R.string.whiteboard_tool_sticky),
                isSelected = state.activeTool == WhiteboardTool.STICKY,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.STICKY
                    state.addSticky(screenSize = screenSize)
                },
            )
            ToolbarToolItem(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                label = stringResource(R.string.whiteboard_tool_connector),
                isSelected = state.activeTool == WhiteboardTool.CONNECTOR,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.CONNECTOR
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.Brush,
                label = stringResource(R.string.whiteboard_tool_pen),
                isSelected = state.activeTool == WhiteboardTool.PEN,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.PEN
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.Edit,
                label = stringResource(
                    if (state.isFingerBrushMode) R.string.tablet_brush_mode_title else R.string.tablet_stylus_mode_title
                ),
                isSelected = !state.isFingerBrushMode,
                onClick = {
                    dismissKeyboard()
                    state.isFingerBrushMode = !state.isFingerBrushMode
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.CropFree,
                label = stringResource(R.string.whiteboard_tool_section),
                isSelected = state.activeTool == WhiteboardTool.SECTION,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.SELECT
                    onOpenAddSectionDialog()
                },
            )
            ToolbarToolItem(
                icon = Icons.Default.DeleteOutline,
                label = stringResource(R.string.whiteboard_tool_eraser),
                isSelected = state.activeTool == WhiteboardTool.ERASER,
                onClick = {
                    dismissKeyboard()
                    state.activeTool = WhiteboardTool.ERASER
                },
            )

            // Divider
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .width(1.dp)
                    .background(TypePalette.ColorShapeHighlightMedium),
            )

            // Undo / Redo
            IconButton(
                onClick = {
                    dismissKeyboard()
                    state.undo()
                },
                enabled = state.canUndo,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.action_undo),
                    tint = if (state.canUndo) TypePalette.ColorTextPrimary else TypePalette.ColorTextTertiary,
                    modifier = Modifier.size(17.dp),
                )
            }
            IconButton(
                onClick = {
                    dismissKeyboard()
                    state.redo()
                },
                enabled = state.canRedo,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(R.string.action_redo),
                    tint = if (state.canRedo) TypePalette.ColorTextPrimary else TypePalette.ColorTextTertiary,
                    modifier = Modifier.size(17.dp),
                )
            }

            // Zoom Controls Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(TypePalette.ColorShapeHighlightLight, RoundedCornerShape(14.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${(state.viewport.zoom * 100).roundToInt()}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TypePalette.ColorTextPrimary,
                    modifier = Modifier
                        .clickable {
                            dismissKeyboard()
                            state.resetZoom()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                IconButton(
                    onClick = {
                        dismissKeyboard()
                        state.zoomToFit(screenSize)
                    },
                    modifier = Modifier.size(26.dp),
                ) {
                    Icon(
                        Icons.Default.FitScreen,
                        contentDescription = stringResource(R.string.whiteboard_zoom_fit),
                        tint = TypePalette.ColorIce,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            // Fullscreen toggle
            IconButton(
                onClick = {
                    dismissKeyboard()
                    state.isFullScreen = !state.isFullScreen
                },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = if (state.isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = stringResource(if (state.isFullScreen) R.string.whiteboard_exit_fullscreen else R.string.whiteboard_enter_fullscreen),
                    tint = TypePalette.ColorTextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolbarToolItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) TypePalette.ColorCobalt else Color.Transparent
    val tint = if (isSelected) TypePalette.ColorTextPrimary else TypePalette.ColorTextSecondary

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun WhiteboardColorSubBar(
    state: WhiteboardState,
) {
    Row(
        modifier = Modifier
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.4f),
            )
            .clip(RoundedCornerShape(20.dp))
            .background(TypePalette.ColorBgSecondary)
            .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(20.dp))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = if (state.activeTool == WhiteboardTool.STICKY) {
            WhiteboardPalette.STICKY_COLORS
        } else if (state.activeTool == WhiteboardTool.HIGHLIGHTER) {
            WhiteboardPalette.HIGHLIGHTER_COLORS
        } else {
            WhiteboardPalette.INK_COLORS
        }

        colors.forEach { colorHex ->
            val color = Color(colorHex.toColorInt())
            val isChosen = if (state.activeTool == WhiteboardTool.STICKY) {
                state.activeStickyColor == colorHex
            } else {
                state.activeInkColor == colorHex
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color, CircleShape)
                    .border(
                        width = if (isChosen) 2.5.dp else 1.dp,
                        color = if (isChosen) TypePalette.ColorTextPrimary else TypePalette.ColorShapeHighlightMedium,
                        shape = CircleShape,
                    )
                    .clickable {
                        if (state.activeTool == WhiteboardTool.STICKY) {
                            state.activeStickyColor = colorHex
                        } else {
                            state.activeInkColor = colorHex
                        }
                    },
            )
        }

        if (state.activeTool == WhiteboardTool.PEN) {
            Spacer(modifier = Modifier.width(4.dp))
            listOf(
                2.5f to stringResource(R.string.whiteboard_stroke_thin),
                4.5f to stringResource(R.string.whiteboard_stroke_medium),
                8.0f to stringResource(R.string.whiteboard_stroke_thick),
            ).forEach { (width, label) ->
                val isSelected = state.activeStrokeWidth == width
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) TypePalette.ColorCobalt else TypePalette.ColorShapeHighlightLight)
                        .clickable { state.activeStrokeWidth = width }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TypePalette.ColorTextPrimary,
                    )
                }
            }
        }
    }
}
