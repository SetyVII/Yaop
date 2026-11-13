package org.yaop.ui.editor

import org.yaop.ui.common.privacyImage
import org.yaop.ui.common.DesignModalBottomSheet
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import org.yaop.R
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.PkmObject
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toTextSpan
import org.yaop.core.network.LinkPreviewData
import org.yaop.core.network.LinkPreviewFetcher
import org.yaop.core.network.NetworkPreferences
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.attachImageCoverToWebLink
import org.yaop.ui.common.createNewWebLinkObject
import org.yaop.ui.common.extractUrlFromText
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.updateWebLinkObjectUrl
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Validates whether a raw string looks like a valid web link.
 */
fun isValidWebUrl(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return false
    val candidate = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        "https://$trimmed"
    } else {
        trimmed
    }
    return runCatching {
        val parsed = URL(candidate)
        parsed.host.isNotBlank() && parsed.host.contains(".")
    }.getOrDefault(false)
}

/**
 * Normalizes a user-entered URL into a standard https:// URL.
 */
fun normalizeWebUrl(raw: String): String {
    val trimmed = raw.trim()
    return if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        "https://$trimmed"
    } else {
        trimmed
    }
}

/**
 * Dedicated bottom sheet to create a WebLink object.
 *
 * Enforces strictly:
 * 1. A WebLink CANNOT exist without a valid URL link (mandated by rule & UX).
 * 2. It NEVER creates an "Untitled" object (derives from page title, domain, or URL).
 * 3. Shows live preview with OpenGraph image and allows picking a custom cover image.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateWebLinkSheet(
    initialTitle: String = "",
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onWebLinkCreated: (PkmObject) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = (context.applicationContext as org.yaop.YaopApp).repository
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var urlInput by remember { mutableStateOf(initialUrl) }
    var titleInput by remember { mutableStateOf(initialTitle) }
    var isBookmark by remember { mutableStateOf(false) }
    var isReviewed by remember { mutableStateOf(false) }

    var customImageUri by remember { mutableStateOf<Uri?>(null) }
    var previewData by remember { mutableStateOf<LinkPreviewData?>(null) }
    var isFetchingPreview by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var urlWasTouched by remember { mutableStateOf(false) }

    var clipboardCandidate by remember { mutableStateOf<String?>(null) }
    val urlFocusRequester = remember { FocusRequester() }

    // Inspect clipboard on launch
    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (!clipText.isNullOrBlank()) {
            val extracted = extractUrlFromText(clipText) ?: if (isValidWebUrl(clipText)) clipText else null
            if (!extracted.isNullOrBlank() && extracted != initialUrl) {
                clipboardCandidate = extracted
            }
        }
    }

    // Auto-focus the URL input upon opening
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { urlFocusRequester.requestFocus() }
    }

    val isCurrentUrlValid = remember(urlInput) { isValidWebUrl(urlInput) }

    // Debounced metadata & Open Graph preview fetcher
    LaunchedEffect(urlInput) {
        val trimmed = urlInput.trim()
        if (!isValidWebUrl(trimmed)) {
            previewData = null
            isFetchingPreview = false
            return@LaunchedEffect
        }

        isFetchingPreview = true
        delay(450) // Debounce typing

        val normalized = normalizeWebUrl(trimmed)
        val allowProxies = NetworkPreferences.areThirdPartyProxiesAllowed(context)
        val fetched = withContext(Dispatchers.IO) {
            runCatching { LinkPreviewFetcher.fetch(normalized, allowProxies) }.getOrNull()
        }

        previewData = fetched
        isFetchingPreview = false

        if (fetched?.title?.isNotBlank() == true && titleInput.isBlank()) {
            titleInput = fetched.title
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            customImageUri = uri
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun submitCreate() {
        if (!isCurrentUrlValid || isSaving) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        isSaving = true

        val cleanUrl = normalizeWebUrl(urlInput)
        coroutineScope.launch {
            val newObj = createNewWebLinkObject(
                context = context,
                repository = repository,
                url = cleanUrl,
                customTitle = titleInput.takeIf { it.isNotBlank() },
                isBookmark = isBookmark,
                isReviewed = isReviewed,
                downloadThumbnail = true,
                customImageUri = customImageUri,
                prefetchedPreview = previewData,
            )
            isSaving = false
            onWebLinkCreated(newObj)
        }
    }

    DesignModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.BorderHover, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "🌐",
                        fontSize = 20.sp,
                    )
                    Text(
                        text = stringResource(R.string.create_weblink_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // 2. URL Field (Mandatory)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = {
                        urlInput = it
                        urlWasTouched = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(urlFocusRequester),
                    label = { Text(stringResource(R.string.weblink_url_label)) },
                    placeholder = { Text(stringResource(R.string.weblink_url_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = if (isCurrentUrlValid) AppColors.Accent else AppColors.TextSecondary,
                        )
                    },
                    trailingIcon = {
                        if (urlInput.isNotBlank()) {
                            IconButton(onClick = { urlInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    tint = AppColors.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    isError = urlWasTouched && urlInput.isNotBlank() && !isCurrentUrlValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Ice,
                        unfocusedBorderColor = AppColors.BorderSubtle,
                        focusedLabelColor = AppColors.Accent,
                        cursorColor = AppColors.Accent,
                    ),
                )

                if (urlWasTouched && urlInput.isNotBlank() && !isCurrentUrlValid) {
                    Text(
                        text = stringResource(R.string.weblink_url_required_error),
                        fontSize = 12.sp,
                        color = AppColors.Danger,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }

                // Quick Clipboard paste chip
                if (!clipboardCandidate.isNullOrBlank() && urlInput.isBlank()) {
                    val candidate = clipboardCandidate!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.AccentSubtle, RoundedCornerShape(8.dp))
                                .clickable {
                                    urlInput = candidate
                                    urlWasTouched = true
                                    clipboardCandidate = null
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(text = "📋", fontSize = 12.sp)
                                Text(
                                    text = "${stringResource(R.string.weblink_paste_clipboard)}: ${candidate.take(32)}…",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AppColors.Accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 3. Live Link & Image Preview Card
            AnimatedVisibility(
                visible = isFetchingPreview || previewData != null || customImageUri != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.Surface, RoundedCornerShape(14.dp))
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    if (isFetchingPreview && previewData == null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AppColors.Accent,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.weblink_fetching_preview),
                                fontSize = 13.sp,
                                color = AppColors.TextSecondary,
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Cover Image display (custom image or fetched OpenGraph image)
                            val hasCustomImage = customImageUri != null
                            val webImageUrl = previewData?.imageUrl
                            if (hasCustomImage || !webImageUrl.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.3f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        model = customImageUri ?: webImageUrl,
                                        contentDescription = stringResource(R.string.weblink_cover_image),
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize().privacyImage(),
                                    )

                                    // Replace / Reset image chip
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.Black.copy(alpha = 0.7f))
                                            .clickable {
                                                imagePickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                                )
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.weblink_change_image),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                        )
                                    }
                                }
                            }

                            // Site domain & title preview
                            previewData?.siteName?.takeIf { it.isNotBlank() }?.let { site ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = AppColors.Accent,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = site,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = AppColors.TextSecondary,
                                    )
                                }
                            }

                            previewData?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    text = desc,
                                    fontSize = 12.sp,
                                    color = AppColors.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // 4. Custom Cover Image Button (if no image detected yet)
            if (customImageUri == null && previewData?.imageUrl.isNullOrBlank()) {
                OutlinedButton(
                    onClick = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.weblink_add_image))
                }
            }

            // 5. Title Field (Derived automatically if empty, never "Untitled")
            OutlinedTextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.weblink_title_label)) },
                placeholder = {
                    Text(
                        text = previewData?.title?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.weblink_title_hint),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { submitCreate() },
                ),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.Ice,
                    unfocusedBorderColor = AppColors.BorderSubtle,
                    focusedLabelColor = AppColors.Accent,
                    cursorColor = AppColors.Accent,
                ),
            )

            // 6. Toggles: Bookmark & Reviewed
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = isBookmark,
                    onClick = { isBookmark = !isBookmark },
                    label = { Text(stringResource(R.string.weblink_toggle_bookmark)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (isBookmark) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.AccentSubtle,
                        selectedLabelColor = AppColors.Accent,
                        selectedLeadingIconColor = AppColors.Accent,
                    ),
                )

                FilterChip(
                    selected = isReviewed,
                    onClick = { isReviewed = !isReviewed },
                    label = { Text(stringResource(R.string.weblink_toggle_reviewed)) },
                    leadingIcon = {
                        if (isReviewed) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppColors.AccentSubtle,
                        selectedLabelColor = AppColors.Accent,
                        selectedLeadingIconColor = AppColors.Accent,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 7. Actions: Cancel and Create
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }

                Button(
                    onClick = { submitCreate() },
                    enabled = isCurrentUrlValid && !isSaving,
                    modifier = Modifier.weight(1.5f),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = AppColors.Background,
                        disabledContainerColor = AppColors.Surface,
                        disabledContentColor = AppColors.TextTertiary,
                    ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AppColors.Background,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.weblink_save_action),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Top Hero Card displayed inside [ObjectDetailScreen] when viewing a WebLink object.
 *
 * Provides:
 * - Prominent cover image display with zoom and replacement picker.
 * - Interactive URL pill with Open in Browser and Copy Link actions.
 * - URL editing dialog.
 * - Reactive Bookmark & Reviewed toggles.
 */
@Composable
fun WebLinkHeroCard(
    obj: PkmObject,
    onObjectUpdated: (PkmObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = (context.applicationContext as org.yaop.YaopApp).repository
    val haptic = LocalHapticFeedback.current

    val rawUrl = obj.weblinkUrl.orEmpty()
    val isUrlValid = remember(rawUrl) { rawUrl.isNotBlank() && isValidWebUrl(rawUrl) }
    val coverAssetId = obj.cover?.assetId?.takeIf { !it.startsWith("cover_") }
    val decodedThumb = if (!coverAssetId.isNullOrBlank()) rememberDecodedThumbnail(coverAssetId) else null

    var isZoomOpen by remember { mutableStateOf(false) }
    var showEditUrlDialog by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val updated = attachImageCoverToWebLink(context, repository, obj, uri)
                if (updated != null) {
                    onObjectUpdated(updated)
                }
            }
        }
    }

    if (isZoomOpen && !coverAssetId.isNullOrBlank()) {
        FullScreenImagePreviewDialog(
            assetId = coverAssetId,
            caption = obj.plainTitle,
            onDismiss = { isZoomOpen = false },
        )
    }

    if (showEditUrlDialog) {
        EditWebLinkDialog(
            currentUrl = rawUrl,
            onDismiss = { showEditUrlDialog = false },
            onSave = { newUrl ->
                showEditUrlDialog = false
                coroutineScope.launch {
                    val updated = updateWebLinkObjectUrl(
                        context = context,
                        repository = repository,
                        obj = obj,
                        newUrl = newUrl,
                    )
                    onObjectUpdated(updated)
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Cover Image Header (if present, show with zoom + replace; otherwise provide add image banner)
        if (decodedThumb != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isZoomOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = decodedThumb.bitmap.asImageBitmap(),
                    contentDescription = obj.plainTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().privacyImage(),
                )

                // Top right zoom chip
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable { isZoomOpen = true }
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
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.image_hero_zoom),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }

                // Bottom right change image chip
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable {
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(R.string.weblink_change_image),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // 2. Warning if WebLink has no URL assigned (obligatory link rule)
        if (!isUrlValid) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .border(1.dp, AppColors.Danger.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .clickable { showEditUrlDialog = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = AppColors.Danger,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.weblink_no_url_warning),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Danger,
                    )
                    Text(
                        text = stringResource(R.string.weblink_set_url_action),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.Accent,
                    )
                }
            }
        } else {
            // 3. Interactive Web URL Bar
            val domain = runCatching { URL(rawUrl).host.removePrefix("www.") }.getOrDefault("")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(10.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "🌐", fontSize = 16.sp)
                    Column {
                        if (domain.isNotBlank()) {
                            Text(
                                text = domain,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.Accent,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = rawUrl,
                            fontSize = 12.sp,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", rawUrl))
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            Toast.makeText(context, context.getString(R.string.weblink_link_copied), Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.weblink_copy_link),
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        onClick = { showEditUrlDialog = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.weblink_edit_link),
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            val parsedUri = runCatching { Uri.parse(rawUrl) }.getOrNull()
                            if (parsedUri != null) {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, parsedUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInBrowser,
                            contentDescription = stringResource(R.string.weblink_open_in_browser),
                            tint = AppColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // 4. Quick Toggles & Cover action footer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Bookmark Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (obj.isBookmark) AppColors.AccentSubtle else AppColors.SurfaceElevated,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            val current = obj.isBookmark
                            val updatedProps = obj.properties.toMutableMap()
                            updatedProps["is_bookmark"] = PropertyValue.Checkbox(!current)
                            val updatedObj = obj.copy(properties = updatedProps)
                            coroutineScope.launch {
                                repository.saveObject(updatedObj)
                                onObjectUpdated(updatedObj)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "🔖", fontSize = 12.sp)
                        Text(
                            text = stringResource(R.string.share_toggle_bookmark),
                            fontSize = 11.sp,
                            fontWeight = if (obj.isBookmark) FontWeight.Bold else FontWeight.Normal,
                            color = if (obj.isBookmark) AppColors.Accent else AppColors.TextSecondary,
                        )
                    }
                }

                // Reviewed Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (obj.isReviewed) AppColors.AccentSubtle else AppColors.SurfaceElevated,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable {
                            val current = obj.isReviewed
                            val updatedProps = obj.properties.toMutableMap()
                            updatedProps["reviewed"] = PropertyValue.Checkbox(!current)
                            val updatedObj = obj.copy(properties = updatedProps)
                            coroutineScope.launch {
                                repository.saveObject(updatedObj)
                                onObjectUpdated(updatedObj)
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (obj.isReviewed) "✓" else "○",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (obj.isReviewed) AppColors.Accent else AppColors.TextSecondary,
                        )
                        Text(
                            text = stringResource(if (obj.isReviewed) R.string.badge_reviewed else R.string.badge_to_review),
                            fontSize = 11.sp,
                            fontWeight = if (obj.isReviewed) FontWeight.Bold else FontWeight.Normal,
                            color = if (obj.isReviewed) AppColors.Accent else AppColors.TextSecondary,
                        )
                    }
                }
            }

            // If no cover is attached, offer 1-tap "Add image"
            if (decodedThumb == null) {
                Text(
                    text = stringResource(R.string.weblink_add_image),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            imagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Clean dialog to edit the URL of an existing WebLink object.
 */
@Composable
fun EditWebLinkDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var urlText by remember { mutableStateOf(currentUrl) }
    val isValid = remember(urlText) { isValidWebUrl(urlText) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(18.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.weblink_edit_dialog_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary,
            )

            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.weblink_url_label)) },
                placeholder = { Text(stringResource(R.string.weblink_url_hint)) },
                singleLine = true,
                isError = urlText.isNotBlank() && !isValid,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppColors.Ice,
                    unfocusedBorderColor = AppColors.BorderSubtle,
                    focusedLabelColor = AppColors.Accent,
                    cursorColor = AppColors.Accent,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.action_cancel))
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (isValid) {
                            onSave(normalizeWebUrl(urlText))
                        }
                    },
                    enabled = isValid,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = AppColors.Background,
                    ),
                ) {
                    Text(text = stringResource(R.string.weblink_update_action))
                }
            }
        }
    }
}
