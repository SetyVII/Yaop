package org.yaop.ui.share

import org.yaop.ui.common.privacyImage
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toTextSpan
import org.yaop.core.network.LinkMediaType
import org.yaop.core.network.LinkPreviewData
import org.yaop.core.network.LinkPreviewFetcher
import org.yaop.core.network.NetworkPreferences
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.createNewAudioObjectFromUri
import org.yaop.ui.common.createNewImageObjectFromUri
import org.yaop.ui.common.createNewPdfObjectFromUri
import org.yaop.ui.common.extractAudioDurationFromBytes
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.resolveFileNameFromUri
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

@Composable
fun ShareReceiverSheet(
    incomingShare: IncomingShare,
    repository: PkmRepository,
    onDismiss: () -> Unit,
    onSaved: (savedObject: PkmObject, openImmediately: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // Common editable state
    var title by remember {
        mutableStateOf(
            when (incomingShare) {
                is IncomingShare.WebLink -> incomingShare.initialTitle.orEmpty()
                is IncomingShare.Image -> resolveFileNameFromUri(context, incomingShare.uri, "image")
                is IncomingShare.Pdf -> resolveFileNameFromUri(context, incomingShare.uri, "document").removeSuffix(".pdf")
                is IncomingShare.Audio -> resolveFileNameFromUri(context, incomingShare.uri, "audio")
                is IncomingShare.Text -> incomingShare.text.lineSequence().firstOrNull()?.take(60).orEmpty()
                is IncomingShare.MultipleImages -> context.getString(R.string.share_type_image)
                IncomingShare.Unsupported -> ""
            },
        )
    }

    // WebLink specific state
    var isBookmark by remember { mutableStateOf(false) }
    var isReviewed by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<LinkPreviewData?>(null) }
    var isFetchingPreview by remember { mutableStateOf(false) }
    var downloadedThumbBytes by remember { mutableStateOf<ByteArray?>(null) }
    var saveAsImageObject by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // Fetch OpenGraph and low-res thumbnail if WebLink
    LaunchedEffect(incomingShare) {
        if (incomingShare is IncomingShare.WebLink) {
            isFetchingPreview = true
            val isKnownMediaPlatform = LinkPreviewFetcher.extractYouTubeVideoId(incomingShare.url) != null ||
                LinkPreviewFetcher.isInstagram(incomingShare.url) ||
                LinkPreviewFetcher.isReddit(incomingShare.url) ||
                LinkPreviewFetcher.isFacebook(incomingShare.url) ||
                LinkPreviewFetcher.isTwitterOrX(incomingShare.url) ||
                LinkPreviewFetcher.isTikTok(incomingShare.url)
            if (isKnownMediaPlatform) {
                saveAsImageObject = true
            }

            val allowThirdPartyProxies = NetworkPreferences.areThirdPartyProxiesAllowed(context)
            val fetched = LinkPreviewFetcher.fetch(incomingShare.url, allowThirdPartyProxies)
            previewData = fetched
            isFetchingPreview = false

            if (fetched?.title?.isNotBlank() == true && title.isBlank()) {
                title = fetched.title
            }

            val isVideoOrSocial = fetched?.mediaType in setOf(
                LinkMediaType.YOUTUBE,
                LinkMediaType.INSTAGRAM_REEL,
                LinkMediaType.INSTAGRAM_POST,
                LinkMediaType.REDDIT,
                LinkMediaType.REDDIT_VIDEO,
                LinkMediaType.FACEBOOK,
                LinkMediaType.FACEBOOK_VIDEO,
                LinkMediaType.TWITTER,
                LinkMediaType.TIKTOK,
            ) || isKnownMediaPlatform

            if (isVideoOrSocial) {
                saveAsImageObject = true
            }

            val imgUrl = fetched?.imageUrl
            if (!imgUrl.isNullOrBlank()) {
                val thumbBytes = LinkPreviewFetcher.downloadCompressedThumbnail(imgUrl)
                downloadedThumbBytes = thumbBytes
                if (thumbBytes != null && thumbBytes.isNotEmpty() && isVideoOrSocial) {
                    saveAsImageObject = true
                }
            }
        }
    }

    val thumbBitmap = remember(downloadedThumbBytes) {
        downloadedThumbBytes?.let { bytes ->
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }
    }

    // Dismiss when touching outside scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* prevent click through */ },
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Drag handle pill
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.BorderHover, RoundedCornerShape(2.dp)),
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Header: Type badge & Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val (emoji, typeLabel) = when (incomingShare) {
                        is IncomingShare.WebLink -> "🌐" to stringResource(R.string.share_type_weblink)
                        is IncomingShare.Image, is IncomingShare.MultipleImages -> "🖼️" to stringResource(R.string.share_type_image)
                        is IncomingShare.Pdf -> "📄" to stringResource(R.string.share_type_pdf)
                        is IncomingShare.Audio -> "🎙️" to stringResource(R.string.share_type_audio)
                        is IncomingShare.Text -> "📝" to stringResource(R.string.share_type_note)
                        IncomingShare.Unsupported -> "⚠️" to stringResource(R.string.share_receiver_title)
                    }

                    Text(text = emoji, fontSize = 20.sp)
                    Text(
                        text = typeLabel,
                        fontSize = 17.sp,
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

            Spacer(modifier = Modifier.height(14.dp))

            // Body preview based on share type
            when (incomingShare) {
                is IncomingShare.WebLink -> {
                    WebLinkShareBody(
                        url = incomingShare.url,
                        title = title,
                        onTitleChange = { title = it },
                        previewData = previewData,
                        isFetchingPreview = isFetchingPreview,
                        thumbBitmap = thumbBitmap,
                        downloadedThumbSize = downloadedThumbBytes?.size,
                        isBookmark = isBookmark,
                        onBookmarkChange = { isBookmark = it },
                        isReviewed = isReviewed,
                        onReviewedChange = { isReviewed = it },
                        saveAsImageObject = saveAsImageObject,
                        onSaveAsImageObjectChange = { saveAsImageObject = it },
                    )
                }

                is IncomingShare.Image -> {
                    SingleMediaShareBody(
                        uri = incomingShare.uri,
                        title = title,
                        onTitleChange = { title = it },
                        isImage = true,
                    )
                }

                is IncomingShare.Pdf -> {
                    SingleMediaShareBody(
                        uri = incomingShare.uri,
                        title = title,
                        onTitleChange = { title = it },
                        isImage = false,
                        iconEmoji = "📄",
                    )
                }

                is IncomingShare.Audio -> {
                    SingleMediaShareBody(
                        uri = incomingShare.uri,
                        title = title,
                        onTitleChange = { title = it },
                        isImage = false,
                        iconEmoji = "🎙️",
                    )
                }

                is IncomingShare.MultipleImages -> {
                    MultipleImagesShareBody(
                        uris = incomingShare.uris,
                        title = title,
                        onTitleChange = { title = it },
                    )
                }

                is IncomingShare.Text -> {
                    TextShareBody(
                        text = incomingShare.text,
                        title = title,
                        onTitleChange = { title = it },
                    )
                }

                IncomingShare.Unsupported -> {
                    Text(
                        text = stringResource(R.string.share_save_error_toast),
                        color = AppColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons: Cancel, Save & Open, Save
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        color = AppColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = {
                        if (!isSaving) {
                            isSaving = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            coroutineScope.launch {
                                val saved = saveSharePayload(
                                    context = context,
                                    repository = repository,
                                    incomingShare = incomingShare,
                                    title = title,
                                    isBookmark = isBookmark,
                                    isReviewed = isReviewed,
                                    previewData = previewData,
                                    downloadedThumbBytes = downloadedThumbBytes,
                                    saveAsImageObject = saveAsImageObject,
                                )
                                isSaving = false
                                if (saved != null) {
                                    onSaved(saved, true)
                                }
                            }
                        }
                    },
                    enabled = !isSaving && incomingShare !is IncomingShare.Unsupported,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, AppColors.BorderHover),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.TextPrimary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.share_save_and_open_button),
                        color = AppColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (!isSaving) {
                            isSaving = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            coroutineScope.launch {
                                val saved = saveSharePayload(
                                    context = context,
                                    repository = repository,
                                    incomingShare = incomingShare,
                                    title = title,
                                    isBookmark = isBookmark,
                                    isReviewed = isReviewed,
                                    previewData = previewData,
                                    downloadedThumbBytes = downloadedThumbBytes,
                                    saveAsImageObject = saveAsImageObject,
                                )
                                isSaving = false
                                if (saved != null) {
                                    onSaved(saved, false)
                                }
                            }
                        }
                    },
                    enabled = !isSaving && incomingShare !is IncomingShare.Unsupported,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = Color.White,
                    ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.share_save_button),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebLinkShareBody(
    url: String,
    title: String,
    onTitleChange: (String) -> Unit,
    previewData: LinkPreviewData?,
    isFetchingPreview: Boolean,
    thumbBitmap: Bitmap?,
    downloadedThumbSize: Int?,
    isBookmark: Boolean,
    onBookmarkChange: (Boolean) -> Unit,
    isReviewed: Boolean,
    onReviewedChange: (Boolean) -> Unit,
    saveAsImageObject: Boolean,
    onSaveAsImageObjectChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val host = remember(url) {
        runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")
    }

    val mediaType = previewData?.mediaType ?: when {
        LinkPreviewFetcher.extractYouTubeVideoId(url) != null -> LinkMediaType.YOUTUBE
        LinkPreviewFetcher.isInstagram(url) -> if (url.contains("/reel", ignoreCase = true)) LinkMediaType.INSTAGRAM_REEL else LinkMediaType.INSTAGRAM_POST
        LinkPreviewFetcher.isReddit(url) -> if (url.contains("v.redd.it", ignoreCase = true)) LinkMediaType.REDDIT_VIDEO else LinkMediaType.REDDIT
        LinkPreviewFetcher.isFacebook(url) -> if (url.contains("/reel", ignoreCase = true) || url.contains("/watch", ignoreCase = true) || url.contains("fb.watch", ignoreCase = true)) LinkMediaType.FACEBOOK_VIDEO else LinkMediaType.FACEBOOK
        LinkPreviewFetcher.isTwitterOrX(url) -> LinkMediaType.TWITTER
        LinkPreviewFetcher.isTikTok(url) -> LinkMediaType.TIKTOK
        else -> LinkMediaType.GENERIC
    }

    val mediaBadgeText = when (mediaType) {
        LinkMediaType.YOUTUBE -> stringResource(R.string.share_media_youtube)
        LinkMediaType.INSTAGRAM_REEL -> stringResource(R.string.share_media_instagram_reel)
        LinkMediaType.INSTAGRAM_POST -> stringResource(R.string.share_media_instagram_post)
        LinkMediaType.REDDIT -> stringResource(R.string.share_media_reddit)
        LinkMediaType.REDDIT_VIDEO -> stringResource(R.string.share_media_reddit_video)
        LinkMediaType.FACEBOOK -> stringResource(R.string.share_media_facebook)
        LinkMediaType.FACEBOOK_VIDEO -> stringResource(R.string.share_media_facebook_video)
        LinkMediaType.TWITTER -> stringResource(R.string.share_media_twitter)
        LinkMediaType.TIKTOK -> stringResource(R.string.share_media_tiktok)
        LinkMediaType.GENERIC -> stringResource(R.string.share_type_weblink)
    }

    val mediaBadgeEmoji = when (mediaType) {
        LinkMediaType.YOUTUBE -> "🎬"
        LinkMediaType.INSTAGRAM_REEL -> "📸"
        LinkMediaType.INSTAGRAM_POST -> "📷"
        LinkMediaType.REDDIT -> "💬"
        LinkMediaType.REDDIT_VIDEO -> "📹"
        LinkMediaType.FACEBOOK -> "👥"
        LinkMediaType.FACEBOOK_VIDEO -> "🎥"
        LinkMediaType.TWITTER -> "🐦"
        LinkMediaType.TIKTOK -> "🎵"
        LinkMediaType.GENERIC -> "🌐"
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Thumbnail & Title Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Media Cover Thumbnail Banner
                if (thumbBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp)),
                    ) {
                        Image(
                            bitmap = thumbBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.cover_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().privacyImage(),
                        )

                        // Top-start Media Category Pill
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(text = mediaBadgeEmoji, fontSize = 11.sp)
                                Text(
                                    text = mediaBadgeText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                )
                            }
                        }

                        // Bottom-end Storage Size Pill
                        if (downloadedThumbSize != null && downloadedThumbSize > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                val sizeLabel = formatFileSize(downloadedThumbSize.toLong()) ?: "${downloadedThumbSize / 1024} KB"
                                Text(
                                    text = stringResource(R.string.share_cover_badge, sizeLabel),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                } else if (isFetchingPreview) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = AppColors.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.share_fetching_preview),
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                }

                // Domain & URL chip with Open in Browser option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = host.ifBlank { url },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.action_open_in_browser),
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                // Editable Title
                Column {
                    Text(
                        text = stringResource(R.string.share_title_label),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BasicTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                        ),
                        cursorBrush = SolidColor(AppColors.Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (title.isEmpty()) {
                                Text(
                                    text = host.ifBlank { url },
                                    fontSize = 15.sp,
                                    color = AppColors.TextTertiary,
                                )
                            }
                            inner()
                        },
                    )
                }

                // Description preview if available
                val desc = previewData?.description
                if (!desc.isNullOrBlank()) {
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

        // Toggles: Bookmark & Reviewed
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onBookmarkChange(!isBookmark) }
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = isBookmark,
                    onCheckedChange = onBookmarkChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = AppColors.Accent,
                        uncheckedColor = AppColors.BorderHover,
                    ),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.share_toggle_bookmark),
                    fontSize = 13.sp,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(AppColors.BorderSubtle),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onReviewedChange(!isReviewed) }
                    .padding(vertical = 4.dp),
            ) {
                Checkbox(
                    checked = isReviewed,
                    onCheckedChange = onReviewedChange,
                    colors = CheckboxDefaults.colors(
                        checkedColor = AppColors.Accent,
                        uncheckedColor = AppColors.BorderHover,
                    ),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.share_toggle_reviewed),
                    fontSize = 13.sp,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Toggle: Save cover as permanent Image Object (especially key for videos & posts)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(10.dp))
                .clickable { onSaveAsImageObjectChange(!saveAsImageObject) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = saveAsImageObject,
                onCheckedChange = onSaveAsImageObjectChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = AppColors.Accent,
                    uncheckedColor = AppColors.BorderHover,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.share_toggle_save_image_object),
                    fontSize = 13.sp,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                val effectiveTitle = title.trim().ifBlank { host }
                val namedLabel = if (effectiveTitle.isNotBlank()) {
                    stringResource(
                        R.string.share_toggle_save_image_object_named,
                        stringResource(R.string.cover_of_title, effectiveTitle),
                    )
                } else {
                    stringResource(R.string.share_toggle_save_image_object_hint)
                }
                Text(
                    text = namedLabel,
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SingleMediaShareBody(
    uri: Uri,
    title: String,
    onTitleChange: (String) -> Unit,
    isImage: Boolean,
    iconEmoji: String = "🖼️",
) {
    val context = LocalContext.current
    val fileName = remember(uri) { resolveFileNameFromUri(context, uri) }
    val fileSize = remember(uri) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isImage) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp)),
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().privacyImage(),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = iconEmoji, fontSize = 28.sp)
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (fileSize != null && fileSize > 0L) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.share_file_size, formatFileSize(fileSize).orEmpty()),
                            fontSize = 12.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
            }
        }

        // Editable Title
        Column {
            Text(
                text = stringResource(R.string.share_title_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                ),
                cursorBrush = SolidColor(AppColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MultipleImagesShareBody(
    uris: List<Uri>,
    title: String,
    onTitleChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface, RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                uris.take(3).forEach { uri ->
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp)),
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().privacyImage(),
                        )
                    }
                }
                if (uris.size > 3) {
                    Text(
                        text = "+${uris.size - 3}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        }

        // Editable Title
        Column {
            Text(
                text = stringResource(R.string.share_title_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                ),
                cursorBrush = SolidColor(AppColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TextShareBody(
    text: String,
    title: String,
    onTitleChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Editable Title
        Column {
            Text(
                text = stringResource(R.string.share_title_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            BasicTextField(
                value = title,
                onValueChange = onTitleChange,
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                ),
                cursorBrush = SolidColor(AppColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }

        // Content preview
        Column {
            Text(
                text = stringResource(R.string.share_note_content_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private suspend fun saveSharePayload(
    context: Context,
    repository: PkmRepository,
    incomingShare: IncomingShare,
    title: String,
    isBookmark: Boolean,
    isReviewed: Boolean,
    previewData: LinkPreviewData?,
    downloadedThumbBytes: ByteArray?,
    saveAsImageObject: Boolean = false,
): PkmObject? = withContext(Dispatchers.IO) {
    when (incomingShare) {
        is IncomingShare.WebLink -> {
            val host = runCatching { URL(incomingShare.url).host.removePrefix("www.") }.getOrDefault("")
            val siteName = previewData?.siteName?.takeIf { it.isNotBlank() } ?: host
            val finalTitle = title.trim().ifBlank { previewData?.title?.takeIf { it.isNotBlank() } ?: host.ifBlank { "Web Link" } }

            var thumbnailAssetId: String? = null
            var createdImageObjectId: String? = null

            if (downloadedThumbBytes != null && downloadedThumbBytes.isNotEmpty()) {
                val assetId = UUID.randomUUID().toString()
                val mediaStore = (context.applicationContext as YaopApp).mediaStore
                mediaStore.saveMedia(assetId, downloadedThumbBytes, "image/webp")

                // Mandatory naming: "Portada de X" / "Cover of X" where X is the title of the video/article/post
                val coverTitle = context.getString(R.string.cover_of_title, finalTitle)
                val coverFileName = "$coverTitle.webp"

                repository.saveAsset(
                    AssetMeta(
                        id = assetId,
                        name = coverFileName,
                        mimeType = "image/webp",
                        sizeBytes = downloadedThumbBytes.size.toLong(),
                    ),
                )
                thumbnailAssetId = assetId

                if (saveAsImageObject) {
                    val imageObjId = "media_$assetId"
                    createdImageObjectId = imageObjId
                    val imageObj = PkmObject(
                        id = imageObjId,
                        typeId = DefaultTypes.IMAGE.id,
                        title = coverTitle.toTextSpan(),
                        icon = ObjectIcon(emoji = "🖼️"),
                        properties = mapOf(
                            "asset_id" to PropertyValue.RichText(assetId.toTextSpan()),
                            "file_name" to PropertyValue.RichText(coverFileName.toTextSpan()),
                            "mime" to PropertyValue.RichText("image/webp".toTextSpan()),
                            "size" to PropertyValue.Number(downloadedThumbBytes.size.toDouble()),
                            "source_url" to PropertyValue.Url(incomingShare.url),
                        ),
                    )
                    repository.saveObject(imageObj)
                }
            }

            val newId = UUID.randomUUID().toString()
            val props = buildMap<String, PropertyValue> {
                put("url", PropertyValue.Url(incomingShare.url))
                if (siteName.isNotBlank()) {
                    put("site_name", PropertyValue.RichText(siteName.toTextSpan()))
                }
                put("reviewed", PropertyValue.Checkbox(isReviewed))
                put("is_bookmark", PropertyValue.Checkbox(isBookmark))
                if (createdImageObjectId != null) {
                    put("cover_image", PropertyValue.RichText(createdImageObjectId.toTextSpan()))
                }
            }

            val bookmarkBlock = Block.Bookmark(
                id = UUID.randomUUID().toString(),
                url = incomingShare.url,
                title = previewData?.title.orEmpty().ifBlank { finalTitle },
                description = previewData?.description.orEmpty(),
                domain = siteName,
                thumbnailUrl = previewData?.imageUrl,
                faviconUrl = previewData?.faviconUrl,
                targetObjectId = newId,
            )

            val contentBlocks = buildList {
                add(bookmarkBlock)
                if (createdImageObjectId != null) {
                    add(
                        Block.ObjectEmbed(
                            id = UUID.randomUUID().toString(),
                            targetObjectId = createdImageObjectId,
                            viewMode = "card",
                        ),
                    )
                }
            }

            val newObj = PkmObject(
                id = newId,
                typeId = DefaultTypes.WEBLINK.id,
                title = finalTitle.toTextSpan(),
                icon = ObjectIcon(emoji = "🌐"),
                cover = thumbnailAssetId?.let { CoverSpec(assetId = it) },
                properties = props,
                blocks = contentBlocks,
            )
            repository.saveObject(newObj)
            newObj
        }

        is IncomingShare.Image -> {
            createNewImageObjectFromUri(
                context = context,
                repository = repository,
                uri = incomingShare.uri,
                customTitle = title,
            )
        }

        is IncomingShare.MultipleImages -> {
            // Save first as primary, or save each
            var firstObj: PkmObject? = null
            for (uri in incomingShare.uris) {
                val obj = createNewImageObjectFromUri(
                    context = context,
                    repository = repository,
                    uri = uri,
                    customTitle = if (firstObj == null) title else null,
                )
                if (firstObj == null) firstObj = obj
            }
            firstObj
        }

        is IncomingShare.Pdf -> {
            createNewPdfObjectFromUri(
                context = context,
                repository = repository,
                uri = incomingShare.uri,
                customTitle = title,
            )
        }

        is IncomingShare.Audio -> {
            createNewAudioObjectFromUri(
                context = context,
                repository = repository,
                uri = incomingShare.uri,
                customTitle = title,
            )
        }

        is IncomingShare.Text -> {
            val newId = UUID.randomUUID().toString()
            val newObj = PkmObject(
                id = newId,
                typeId = DefaultTypes.NOTE.id,
                title = title.ifBlank { "Note" }.toTextSpan(),
                icon = ObjectIcon(emoji = "📝"),
                blocks = listOf(
                    Block.Paragraph(
                        id = UUID.randomUUID().toString(),
                        spans = incomingShare.text.toTextSpan(),
                    ),
                ),
            )
            repository.saveObject(newObj)
            newObj
        }

        IncomingShare.Unsupported -> null
    }
}
