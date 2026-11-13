package org.yaop.ui.whiteboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.PkmObject
import org.yaop.core.model.getWhiteboardData
import org.yaop.core.model.toggleTaskStatus
import org.yaop.core.model.toTextSpan
import org.yaop.core.model.whiteboard.WhiteboardData
import org.yaop.core.model.whiteboard.WhiteboardPalette
import org.yaop.core.model.withWhiteboardData
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.editor.ObjectDetailTab
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Top-level screen for interactive Infinite Canvas Visual Note-Taking and Whiteboards.
 * Aligned with the Capacities Whiteboards and Heptabase visual thinking paradigms.
 */
@Composable
fun WhiteboardScreen(
    objectId: String,
    repository: PkmRepository,
    onBack: () -> Unit,
    onNavigateToObject: (String) -> Unit,
    activeTab: ObjectDetailTab = ObjectDetailTab.CONTENT,
    onTabSelected: ((ObjectDetailTab) -> Unit)? = null,
    onOpenOptions: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val objState by repository.observeObject(objectId).collectAsState(initial = null)
    val allObjects by repository.getAllActiveObjects().collectAsState(initial = emptyList())
    val allTypes by repository.getAllTypes().collectAsState(initial = emptyList())

    val obj = objState

    if (obj == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TypePalette.ColorBgPrimary),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = TypePalette.ColorCobalt,
                strokeWidth = 2.5.dp,
            )
        }
        return
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val defaultConvertedNoteTitle = stringResource(R.string.whiteboard_converted_note_default_title)

    var screenSize by remember { mutableStateOf(IntSize(1080, 1920)) }
    var showAddCardDialog by remember { mutableStateOf(false) }
    var showAddSectionDialog by remember { mutableStateOf(false) }
    var newSectionTitle by remember { mutableStateOf("") }

    // Buffered local title state to prevent typing lag and IME backspace desynchronization
    var titleTextFieldValue by remember(obj.id) {
        mutableStateOf(TextFieldValue(text = obj.plainTitle, selection = TextRange(obj.plainTitle.length)))
    }
    var titleSaveJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(obj.plainTitle) {
        if (titleSaveJob?.isActive != true && obj.plainTitle != titleTextFieldValue.text) {
            titleTextFieldValue = TextFieldValue(
                text = obj.plainTitle,
                selection = TextRange(obj.plainTitle.length),
            )
        }
    }

    val onTitleChange: (TextFieldValue) -> Unit = { newTfv ->
        titleTextFieldValue = newTfv
        titleSaveJob?.cancel()
        titleSaveJob = coroutineScope.launch {
            delay(500L)
            repository.saveObject(obj.copy(title = newTfv.text.toTextSpan()))
        }
    }

    // Auto-save debouncing for whiteboard canvas data
    var saveDebounceJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleDebouncedSave(updatedData: WhiteboardData) {
        saveDebounceJob?.cancel()
        saveDebounceJob = coroutineScope.launch {
            delay(500L)
            repository.saveObject(obj.withWhiteboardData(updatedData))
        }
    }

    val state = remember(obj.id) {
        WhiteboardState(
            initialData = obj.getWhiteboardData(),
            onCommit = { updatedData ->
                scheduleDebouncedSave(updatedData)
            },
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(TypePalette.ColorBgPrimary)
            .onSizeChanged { screenSize = it },
    ) {
        val isWideScreen = maxWidth >= 760.dp

        // Infinite Canvas
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

        // Top Minimalist Header (Fades out when in fullscreen canvas mode)
        AnimatedVisibility(
            visible = !state.isFullScreen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            WhiteboardTopBar(
                titleValue = titleTextFieldValue,
                onTitleChange = onTitleChange,
                onBack = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    onBack()
                },
                onToggleFullscreen = {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                    state.isFullScreen = true
                },
                activeTab = activeTab,
                onTabSelected = onTabSelected,
                onOpenOptions = onOpenOptions,
            )
        }

        // Floating toolbar at the bottom
        WhiteboardToolbar(
            state = state,
            screenSize = screenSize,
            onOpenAddCardDialog = { showAddCardDialog = true },
            onOpenAddSectionDialog = {
                newSectionTitle = ""
                showAddSectionDialog = true
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Heptabase Side-by-Side Split View (Tablets / Landscape)
        val inspectingId = state.inspectingObjectId
        val inspectingObj = allObjects.find { it.id == inspectingId }
        val inspectingType = allTypes.find { it.id == inspectingObj?.typeId }

        if (isWideScreen && inspectingObj != null) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInHorizontally { it },
                exit = fadeOut() + slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                SideBySidePane(
                    obj = inspectingObj,
                    type = inspectingType,
                    onClose = { state.inspectingObjectId = null },
                    onOpenFullscreen = {
                        state.inspectingObjectId = null
                        onNavigateToObject(inspectingObj.id)
                    },
                )
            }
        } else if (!isWideScreen && inspectingObj != null) {
            // Heptabase Bottom Sheet (Phones / Portrait)
            InspectionSheet(
                obj = inspectingObj,
                type = inspectingType,
                onDismiss = { state.inspectingObjectId = null },
                onOpenFullscreen = {
                    state.inspectingObjectId = null
                    onNavigateToObject(inspectingObj.id)
                },
            )
        }

        // Add Card from Vault Dialog
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

        // Add Frame / Section Dialog
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

/**
 * Minimalist top bar for the whiteboard with inline title editing.
 */
@Composable
private fun WhiteboardTopBar(
    titleValue: TextFieldValue,
    onTitleChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    activeTab: ObjectDetailTab = ObjectDetailTab.CONTENT,
    onTabSelected: ((ObjectDetailTab) -> Unit)? = null,
    onOpenOptions: (() -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Main Navigation Bar Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(TypePalette.ColorBgSecondary)
                    .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                    .clickable {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = TypePalette.ColorTextPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }

            // Inline Title Capsule (Flexible width, never pushes buttons off-screen)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(TypePalette.ColorBgSecondary.copy(alpha = 0.90f))
                    .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(20.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "🎨", fontSize = 16.sp)
                BasicTextField(
                    value = titleValue,
                    onValueChange = onTitleChange,
                    textStyle = TextStyle(
                        color = TypePalette.ColorTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    cursorBrush = SolidColor(TypePalette.ColorTextPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Fullscreen Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(TypePalette.ColorBgSecondary)
                    .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                    .clickable {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onToggleFullscreen()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Fullscreen,
                    contentDescription = stringResource(R.string.whiteboard_enter_fullscreen),
                    tint = TypePalette.ColorTextPrimary,
                    modifier = Modifier.size(19.dp),
                )
            }

            // Options Button
            if (onOpenOptions != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(TypePalette.ColorBgSecondary)
                        .border(1.dp, TypePalette.ColorShapeHighlightMedium, CircleShape)
                        .clickable {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onOpenOptions()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.MoreHoriz,
                        contentDescription = stringResource(R.string.options),
                        tint = TypePalette.ColorTextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }

        // Dedicated Tab Switcher Row (Centered pill that never collides with title or actions)
        if (onTabSelected != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(TypePalette.ColorBgSecondary.copy(alpha = 0.92f))
                        .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(20.dp))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    WhiteboardPillTab(
                        title = stringResource(R.string.tab_content),
                        icon = Icons.AutoMirrored.Filled.Notes,
                        isSelected = activeTab == ObjectDetailTab.CONTENT,
                        onClick = { onTabSelected(ObjectDetailTab.CONTENT) },
                    )
                    WhiteboardPillTab(
                        title = stringResource(R.string.tab_properties),
                        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                        isSelected = activeTab == ObjectDetailTab.PROPERTIES,
                        onClick = { onTabSelected(ObjectDetailTab.PROPERTIES) },
                    )
                    WhiteboardPillTab(
                        title = stringResource(R.string.tab_references),
                        icon = Icons.Default.Link,
                        isSelected = activeTab == ObjectDetailTab.REFERENCES,
                        onClick = { onTabSelected(ObjectDetailTab.REFERENCES) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WhiteboardPillTab(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (isSelected) TypePalette.ColorCobalt else Color.Transparent
    val tint = if (isSelected) TypePalette.ColorTextPrimary else TypePalette.ColorTextSecondary

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            softWrap = false,
        )
    }
}
