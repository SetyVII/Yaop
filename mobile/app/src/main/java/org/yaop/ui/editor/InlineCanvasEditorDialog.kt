package org.yaop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.toggleTaskStatus
import org.yaop.core.model.whiteboard.WhiteboardData
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import org.yaop.ui.whiteboard.ObjectPickerDialog
import org.yaop.ui.whiteboard.WhiteboardCanvas
import org.yaop.ui.whiteboard.WhiteboardState
import org.yaop.ui.whiteboard.WhiteboardToolbar
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun InlineCanvasEditorDialog(
    initialData: WhiteboardData,
    onSaveData: (WhiteboardData) -> Unit,
    onDismiss: () -> Unit,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    repository: PkmRepository,
    onNavigateToObject: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var screenSize by remember { mutableStateOf(IntSize(1080, 1920)) }
    var showAddCardDialog by remember { mutableStateOf(false) }
    var showAddSectionDialog by remember { mutableStateOf(false) }
    var newSectionTitle by remember { mutableStateOf("") }
    val defaultConvertedNoteTitle = stringResource(R.string.whiteboard_converted_note_default_title)

    val state = remember(initialData.id) {
        WhiteboardState(
            initialData = initialData,
            onCommit = { updatedData ->
                onSaveData(updatedData)
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(TypePalette.ColorBgPrimary)
                .onSizeChanged { screenSize = it },
        ) {
            WhiteboardCanvas(
                state = state,
                allObjects = allObjects,
                allTypes = allTypes,
                onNavigateToObject = onNavigateToObject,
                onToggleTask = { taskObj ->
                    coroutineScope.launch {
                        repository.saveObject(taskObj.toggleTaskStatus())
                    }
                },
                onConvertToNote = { stickyNode, stickyText ->
                    coroutineScope.launch {
                        val snippet = stickyText.trim()
                        val titleText = snippet.takeWhile { it != '\n' }.take(50).ifBlank {
                            defaultConvertedNoteTitle
                        }
                        val newObj = PkmObject(
                            typeId = CanonicalTypeIds.NOTE,
                            title = titleText.toTextSpan(),
                            blocks = if (snippet.isNotBlank()) listOf(Block.Paragraph(id = UUID.randomUUID().toString(), spans = snippet.toTextSpan())) else emptyList(),
                        )
                        repository.saveObject(newObj)
                        state.convertStickyToObject(stickyNode.id, newObj.id)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(TypePalette.ColorBgSecondary)
                        .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = TypePalette.ColorTextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(TypePalette.ColorBgSecondary)
                        .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "🎨", fontSize = 16.sp)
                    Text(
                        text = stringResource(R.string.whiteboard_inline_canvas_badge),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TypePalette.ColorTextPrimary,
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(TypePalette.ColorBgSecondary)
                            .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                            .clickable(enabled = state.canUndo) { state.undo() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = stringResource(R.string.action_undo),
                            tint = if (state.canUndo) TypePalette.ColorTextPrimary else TypePalette.ColorTextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(TypePalette.ColorBgSecondary)
                            .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                            .clickable(enabled = state.canRedo) { state.redo() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = stringResource(R.string.action_redo),
                            tint = if (state.canRedo) TypePalette.ColorTextPrimary else TypePalette.ColorTextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // Done Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AppColors.Accent, CircleShape)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.action_done),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            // Floating Toolbar
            WhiteboardToolbar(
                state = state,
                screenSize = screenSize,
                onOpenAddCardDialog = { showAddCardDialog = true },
                onOpenAddSectionDialog = { showAddSectionDialog = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            if (showAddCardDialog) {
                ObjectPickerDialog(
                    allObjects = allObjects,
                    allTypes = allTypes,
                    onDismiss = { showAddCardDialog = false },
                    onObjectSelected = { selectedObjId ->
                        showAddCardDialog = false
                        state.addObjectCard(selectedObjId, screenSize = screenSize)
                    },
                    onCreateNewObject = { newTitle, newTypeId ->
                        coroutineScope.launch {
                            showAddCardDialog = false
                            val newObj = PkmObject(
                                typeId = newTypeId,
                                title = newTitle.toTextSpan(),
                                blocks = emptyList(),
                            )
                            repository.saveObject(newObj)
                            state.addObjectCard(newObj.id, screenSize = screenSize)
                        }
                    },
                )
            }

            if (showAddSectionDialog) {
                val defaultSectionTitle = stringResource(R.string.whiteboard_default_section_title)
                AlertDialog(
                    onDismissRequest = { showAddSectionDialog = false },
                    title = {
                        Text(
                            text = stringResource(R.string.whiteboard_add_section_dialog_title),
                            color = TypePalette.ColorTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = newSectionTitle,
                            onValueChange = { newSectionTitle = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.whiteboard_section_title_hint),
                                    color = AppColors.TextSecondary,
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TypePalette.ColorTextPrimary,
                                unfocusedTextColor = TypePalette.ColorTextPrimary,
                                focusedBorderColor = TypePalette.ColorIce,
                                unfocusedBorderColor = TypePalette.ColorShapeHighlightMedium,
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                val title = newSectionTitle.ifBlank { defaultSectionTitle }
                                showAddSectionDialog = false
                                state.addSection(title = title, screenSize = screenSize)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TypePalette.ColorCobalt),
                        ) {
                            Text(text = stringResource(R.string.action_create))
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { showAddSectionDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        ) {
                            Text(text = stringResource(R.string.action_cancel), color = TypePalette.ColorTextSecondary)
                        }
                    },
                    containerColor = TypePalette.ColorBgModal,
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }
    }
}
