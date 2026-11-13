package org.yaop.ui.editor

import org.yaop.ui.common.privacyImage
import org.yaop.ui.common.DesignModalBottomSheet
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.crypto.EncryptedMediaStore
import org.yaop.core.model.Block
import org.yaop.core.model.PkmObject
import org.yaop.core.model.toPlainText
import org.yaop.ui.common.extractImageMime
import org.yaop.ui.common.extractImageSize
import org.yaop.ui.common.extractImageTags
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.formatMimeShort
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shared helpers for the media blocks (Image / Audio / Pdf). The raw bytes
 * live encrypted in [EncryptedMediaStore]; these composables decrypt on demand
 * into RAM and never persist unencrypted data on disk.
 */

private fun mediaStoreOf(context: Context): EncryptedMediaStore =
    (context.applicationContext as YaopApp).mediaStore

private fun createEphemeralCacheFile(context: Context, prefix: String, suffix: String): File {
    val dir = File(context.cacheDir, "media_tmp").apply { if (!exists()) mkdirs() }
    return File.createTempFile(prefix, suffix, dir)
}

fun formatDurationSeconds(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val minutes = safe / 60
    val seconds = safe % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
private val imageCacheSizeKb = (maxMemoryKb / 6).coerceIn(32 * 1024, 128 * 1024)
private val fullImageMemoryCache = object : LruCache<String, Bitmap>(imageCacheSizeKb) {
    override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
}

/** Decrypted bytes for an asset, loaded once per assetId on IO. [refreshKey]
 *  flips after a new import so already-composed blocks re-read their bytes.
 *  If bytes are missing locally, asynchronously streams them from the server on-demand. */
@Composable
private fun rememberDecryptedBytes(assetId: String, refreshKey: Boolean = false): ByteArray? {
    val context = LocalContext.current
    return produceState<ByteArray?>(initialValue = null, key1 = assetId, key2 = refreshKey) {
        if (assetId.isBlank()) return@produceState
        value = withContext(Dispatchers.IO) {
            val app = context.applicationContext as? YaopApp
            var bytes = mediaStoreOf(context).readMediaAny(assetId)
            if (bytes == null && app != null) {
                // Progressive on-demand fetch from Go server
                app.syncManager.downloadAssetIfMissing(assetId)
                bytes = mediaStoreOf(context).readMediaAny(assetId)
            }
            bytes
        }
    }.value
}

/** Decodes an image bitmap with high fidelity (up to 2560px for tablet screens). */
@Composable
internal fun rememberDecodedImageForPreview(assetId: String, refreshKey: Boolean = false): Bitmap? =
    rememberDecodedImage(assetId, refreshKey)

@Composable
private fun rememberDecodedImage(assetId: String, refreshKey: Boolean = false): Bitmap? {
    if (assetId.isBlank()) return null
    val cached = if (!refreshKey) fullImageMemoryCache.get(assetId) else null
    val bytes = rememberDecryptedBytes(assetId, refreshKey)
    return produceState<Bitmap?>(initialValue = cached, key1 = bytes, key2 = refreshKey) {
        if (cached != null && !refreshKey) {
            value = cached
            return@produceState
        }
        val data = bytes ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val maxSide = 2560 // Match native 2.5K tablet resolution
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            if (decoded != null) {
                fullImageMemoryCache.put(assetId, decoded)
            }
            decoded
        }
    }.value
}

/** Renders the first page of a decrypted PDF plus its page count using an isolated ephemeral file. */
@Composable
internal fun rememberPdfFirstPage(assetId: String, refreshKey: Boolean = false): Pair<Bitmap, Int>? {
    val context = LocalContext.current
    val bytes = rememberDecryptedBytes(assetId, refreshKey)
    return produceState<Pair<Bitmap, Int>?>(initialValue = null, key1 = bytes, key2 = refreshKey) {
        val data = bytes ?: return@produceState
        value = withContext(Dispatchers.IO) {
            var temp: File? = null
            try {
                temp = createEphemeralCacheFile(context, "pdf_preview_", ".pdf")
                temp.writeBytes(data)
                ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        if (renderer.pageCount <= 0) return@withContext null
                        renderer.openPage(0).use { page ->
                            val scale = 720f / page.width.coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap to renderer.pageCount
                        }
                    }
                }
            } catch (_: Exception) {
                null
            } finally {
                temp?.delete()
            }
        }
    }.value
}

/** Page count of a decrypted PDF, computed on IO without shared filename collisions. */
suspend fun readPdfPageCount(context: Context, bytes: ByteArray): Int? =
    withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            temp = createEphemeralCacheFile(context, "pdf_count_", ".pdf")
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { it.pageCount }
            }
        } catch (_: Exception) {
            null
        } finally {
            temp?.delete()
        }
    }

private fun extractAudioDurationSeconds(file: File, fallbackElapsedMs: Long): Int {
    val retriever = MediaMetadataRetriever()
    val durationMs = try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
    val effectiveMs = durationMs?.takeIf { it > 0L } ?: fallbackElapsedMs
    return (effectiveMs / 1000.0).roundToInt().coerceAtLeast(1)
}

/** Image block: decrypted bitmap, configurable width, upload progress, caption, and tap to zoom. */
@Composable
fun ImageBlockView(
    block: Block.Image,
    modifier: Modifier = Modifier,
    refreshKey: Boolean = false,
    onTapImage: (() -> Unit)? = null,
) {
    val bitmap = rememberDecodedImage(block.assetId, refreshKey)
    val captionText = remember(block.caption) { block.caption.toPlainText() }
    Column(
        modifier = modifier
            .fillMaxWidth(block.widthPercent.coerceIn(0.25f, 1f))
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
            .then(
                if (onTapImage != null) Modifier.clickable(onClick = onTapImage) else Modifier,
            ),
    ) {
        val current = bitmap
        if (block.isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.media_uploading_progress, (block.uploadProgress * 100).roundToInt()),
                    fontSize = 13.sp,
                    color = AppColors.Accent,
                )
            }
        } else if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.media_loading),
                    fontSize = 13.sp,
                    color = AppColors.TextTertiary,
                )
            }
        } else {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = captionText.ifBlank { stringResource(R.string.block_type_image) },
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().privacyImage(),
            )
        }
        if (captionText.isNotBlank()) {
            Text(
                text = captionText,
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Visual embedded card for an Image object referenced in the text editor via `@imagename` or `Block.ObjectEmbed`.
 * Displays the image preview, object title, type badge, format tags, zoom action, and tap-to-open object navigation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReferencedImageBlockView(
    imageObject: PkmObject,
    assetId: String,
    modifier: Modifier = Modifier,
    refreshKey: Boolean = false,
    onTapImage: (() -> Unit)? = null,
    onOpenImageObject: (() -> Unit)? = null,
) {
    val bitmap = rememberDecodedImage(assetId, refreshKey)
    val tags = remember(imageObject) { extractImageTags(imageObject) }
    val fileSizeFormatted = remember(imageObject) { formatFileSize(extractImageSize(imageObject)) }
    val mimeShort = remember(imageObject) { formatMimeShort(extractImageMime(imageObject)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp)),
    ) {
        // 1. Header Bar: Emoji + Object Title + Type Badge + Open Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.SurfaceElevated)
                .clickable(enabled = onOpenImageObject != null) { onOpenImageObject?.invoke() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppColors.AccentSubtle),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = imageObject.icon?.emoji ?: "🖼️",
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = imageObject.plainTitle.ifBlank { stringResource(R.string.block_type_image) },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!mimeShort.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AppColors.Background)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = mimeShort,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.TextSecondary,
                        )
                    }
                }
                if (onOpenImageObject != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.action_open_object),
                        tint = AppColors.Accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // 2. Visual Image Container (Tap to Zoom)
        val currentBitmap = bitmap
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.SurfaceElevated)
                .then(
                    if (onTapImage != null && currentBitmap != null) {
                        Modifier.clickable(onClick = onTapImage)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (currentBitmap != null) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = imageObject.plainTitle,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().privacyImage(),
                )

                // Top-right floating Zoom pill
                if (onTapImage != null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.65f))
                            .clickable(onClick = onTapImage)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ZoomIn,
                            contentDescription = stringResource(R.string.image_hero_zoom),
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = stringResource(R.string.image_hero_zoom),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.media_loading),
                        fontSize = 12.sp,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }

        // 3. Footer with Tags & File Size (if present)
        if (tags.isNotEmpty() || !fileSizeFormatted.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        tags.take(3).forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 11.sp,
                                color = AppColors.Accent,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (!fileSizeFormatted.isNullOrBlank()) {
                    Text(
                        text = fileSizeFormatted,
                        fontSize = 10.sp,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }
    }
}

/** Audio block: inline recorder when empty, compact player + linked object header when recorded. */
@Composable
fun AudioBlockView(
    block: Block.Audio,
    onRecordingFinished: (bytes: ByteArray, durationSeconds: Int) -> Unit,
    modifier: Modifier = Modifier,
    refreshKey: Boolean = false,
    audioTitle: String? = null,
    onOpenAudioObject: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartRealtimeMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeTempFile by remember { mutableStateOf<File?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching {
                val tempFile = createEphemeralCacheFile(context, "rec_${block.id}_", ".m4a")
                val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                newRecorder.setAudioEncodingBitRate(128_000)
                newRecorder.setAudioSamplingRate(44_100)
                newRecorder.setOutputFile(tempFile.absolutePath)
                newRecorder.prepare()
                newRecorder.start()
                activeTempFile = tempFile
                recorder = newRecorder
                recordingStartRealtimeMs = SystemClock.elapsedRealtime()
                elapsedSeconds = 0
                isRecording = true
            }
        }
    }

    fun stopRecording(keep: Boolean) {
        val currentRecorder = recorder ?: return
        val stopRealtimeMs = SystemClock.elapsedRealtime()
        val elapsedMs = (stopRealtimeMs - recordingStartRealtimeMs).coerceAtLeast(0L)
        runCatching { currentRecorder.stop() }
        currentRecorder.release()
        recorder = null
        isRecording = false
        val tempFile = activeTempFile
        activeTempFile = null
        if (tempFile != null) {
            try {
                if (keep && tempFile.exists() && tempFile.length() > 0) {
                    val actualDurationSeconds = extractAudioDurationSeconds(tempFile, elapsedMs)
                    val bytes = tempFile.readBytes()
                    onRecordingFinished(bytes, actualDurationSeconds)
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    DisposableEffect(block.id) {
        onDispose {
            recorder?.let { runCatching { it.stop() }; it.release() }
            activeTempFile?.delete()
        }
    }

    LaunchedEffect(isRecording, recordingStartRealtimeMs) {
        while (isRecording && recordingStartRealtimeMs > 0L) {
            val deltaMs = (SystemClock.elapsedRealtime() - recordingStartRealtimeMs).coerceAtLeast(0L)
            elapsedSeconds = (deltaMs / 1000L).toInt()
            delay(200L)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (block.assetId.isNotBlank() && !audioTitle.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onOpenAudioObject != null) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenAudioObject)
                                .padding(vertical = 2.dp)
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "🎙️", fontSize = 13.sp)
                Text(
                    text = audioTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onOpenAudioObject != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = AppColors.TextTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (block.assetId.isBlank()) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isRecording) AppColors.Danger else AppColors.Accent)
                        .clickable {
                            if (isRecording) {
                                stopRecording(keep = true)
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (isRecording) R.string.media_stop else R.string.media_tap_to_record,
                        ),
                        tint = AppColors.Background,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = if (isRecording) {
                        stringResource(R.string.media_recording) + " " + formatDurationSeconds(elapsedSeconds)
                    } else {
                        stringResource(R.string.media_tap_to_record)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isRecording) AppColors.Danger else AppColors.TextSecondary,
                )
            } else {
                AudioPlayerRow(
                    assetId = block.assetId,
                    fallbackDurationSeconds = block.durationSeconds,
                    modifier = Modifier.weight(1f),
                    refreshKey = refreshKey,
                )
            }
        }
    }
}

/**
 * Compact playback row for a stored audio asset.
 * Plays decrypted bytes directly from RAM via [MediaDataSource] so plaintext audio
 * is never written to disk and multiple audio blocks never collide.
 */
@Composable
fun AudioPlayerRow(
    assetId: String,
    fallbackDurationSeconds: Int,
    modifier: Modifier = Modifier,
    refreshKey: Boolean = false,
) {
    val bytes = rememberDecryptedBytes(assetId, refreshKey)
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(fallbackDurationSeconds * 1000) }

    DisposableEffect(bytes) {
        val data = bytes
        var createdPlayer: MediaPlayer? = null
        if (data != null && data.isNotEmpty()) {
            val newPlayer = MediaPlayer()
            runCatching {
                newPlayer.setDataSource(
                    object : MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                            if (position >= data.size) return -1
                            val available = (data.size - position.toInt()).coerceAtMost(size)
                            System.arraycopy(data, position.toInt(), buffer, offset, available)
                            return available
                        }

                        override fun getSize(): Long = data.size.toLong()

                        override fun close() = Unit
                    },
                )
                newPlayer.setOnCompletionListener { mp ->
                    isPlaying = false
                    positionMs = 0
                    runCatching { mp.seekTo(0) }
                }
                newPlayer.prepare()
                if (newPlayer.duration > 0) {
                    durationMs = newPlayer.duration
                }
                createdPlayer = newPlayer
                player = newPlayer
            }.onFailure {
                runCatching { newPlayer.release() }
            }
        }
        onDispose {
            isPlaying = false
            positionMs = 0
            createdPlayer?.let { mp ->
                runCatching { mp.stop() }
                runCatching { mp.release() }
            }
            if (player === createdPlayer) {
                player = null
            }
        }
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            val current = player
            if (current != null && runCatching { current.isPlaying }.getOrDefault(false)) {
                positionMs = runCatching { current.currentPosition }.getOrDefault(0)
            }
            delay(200L)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(AppColors.Accent)
                .clickable {
                    val current = player ?: return@clickable
                    if (isPlaying) {
                        runCatching { current.pause() }
                        isPlaying = false
                    } else {
                        runCatching {
                            current.start()
                            isPlaying = true
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.media_pause else R.string.media_play,
                ),
                tint = AppColors.Background,
                modifier = Modifier.size(20.dp),
            )
        }

        if (durationMs > 0) {
            Slider(
                value = positionMs.toFloat().coerceIn(0f, durationMs.toFloat()),
                onValueChange = { value ->
                    positionMs = value.toInt()
                    player?.let { runCatching { it.seekTo(positionMs) } }
                },
                valueRange = 0f..durationMs.toFloat(),
                modifier = Modifier.weight(1f),
            )
        }
        val displayedSeconds = if (isPlaying) {
            (positionMs / 1000.0).roundToInt()
        } else {
            (durationMs / 1000.0).roundToInt().coerceAtLeast(fallbackDurationSeconds)
        }
        Text(
            text = formatDurationSeconds(displayedSeconds),
            fontSize = 13.sp,
            color = AppColors.TextTertiary,
        )
    }
}

/** PDF block: first-page thumbnail, file name and page count. */
@Composable
fun PdfBlockView(
    block: Block.Pdf,
    modifier: Modifier = Modifier,
    refreshKey: Boolean = false,
) {
    val preview = rememberPdfFirstPage(block.assetId, refreshKey)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface)
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(14.dp)),
    ) {
        val page = preview?.first
        if (page != null) {
            Image(
                bitmap = page.asImageBitmap(),
                contentDescription = block.fileName,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().privacyImage(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.fileName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.media_pdf_pages, preview?.second ?: block.pageCount),
                fontSize = 12.sp,
                color = AppColors.TextTertiary,
            )
        }
    }
}

/**
 * Bottom sheet for creating or replacing an Audio object.
 * Allows recording a new audio clip with the microphone OR selecting an audio file from the device.
 * Enforces the domain rule: an Audio object never exists without underlying audio bytes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAudioObjectSheet(
    initialTitle: String = "",
    showTitleField: Boolean = true,
    onDismiss: () -> Unit,
    onAudioReady: (bytes: ByteArray, durationSeconds: Int, mime: String, fileName: String?, customTitle: String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customTitle by remember(initialTitle) { mutableStateOf(initialTitle) }
    var isRecording by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var recordingStartRealtimeMs by remember { mutableLongStateOf(0L) }
    var accumulatedPausedMs by remember { mutableLongStateOf(0L) }
    var pauseStartedRealtimeMs by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeTempFile by remember { mutableStateOf<File?>(null) }

    fun startMicRecording() {
        runCatching {
            val tempFile = createEphemeralCacheFile(context, "sheet_rec_", ".m4a")
            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioEncodingBitRate(128_000)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setOutputFile(tempFile.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            activeTempFile = tempFile
            recorder = newRecorder
            recordingStartRealtimeMs = SystemClock.elapsedRealtime()
            accumulatedPausedMs = 0L
            pauseStartedRealtimeMs = 0L
            elapsedSeconds = 0
            isPaused = false
            isRecording = true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startMicRecording()
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val mime = context.contentResolver.getType(uri) ?: "audio/mp4"
                val fileName = uri.lastPathSegment ?: "audio.m4a"
                val temp = createEphemeralCacheFile(context, "pick_dur_", ".m4a")
                val dur = try {
                    temp.writeBytes(bytes)
                    extractAudioDurationSeconds(temp, 1000L)
                } finally {
                    temp.delete()
                }
                onAudioReady(bytes, dur, mime, fileName, customTitle)
            }
        }
    }

    fun finishRecording(save: Boolean) {
        val currentRecorder = recorder
        val stopRealtimeMs = SystemClock.elapsedRealtime()
        val totalPaused = accumulatedPausedMs + if (isPaused && pauseStartedRealtimeMs > 0L) {
            (stopRealtimeMs - pauseStartedRealtimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        val elapsedMs = (stopRealtimeMs - recordingStartRealtimeMs - totalPaused).coerceAtLeast(0L)
        if (currentRecorder != null) {
            runCatching { currentRecorder.stop() }
            runCatching { currentRecorder.release() }
        }
        recorder = null
        isRecording = false
        isPaused = false
        val tempFile = activeTempFile
        activeTempFile = null
        if (tempFile != null) {
            try {
                if (save && tempFile.exists() && tempFile.length() > 0) {
                    val actualDurationSeconds = extractAudioDurationSeconds(tempFile, elapsedMs)
                    val bytes = tempFile.readBytes()
                    onAudioReady(bytes, actualDurationSeconds, "audio/mp4", tempFile.name, customTitle)
                }
            } finally {
                tempFile.delete()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recorder?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            activeTempFile?.delete()
        }
    }

    LaunchedEffect(isRecording, isPaused, recordingStartRealtimeMs) {
        while (isRecording && !isPaused && recordingStartRealtimeMs > 0L) {
            val deltaMs = (SystemClock.elapsedRealtime() - recordingStartRealtimeMs - accumulatedPausedMs).coerceAtLeast(0L)
            elapsedSeconds = (deltaMs / 1000L).toInt()
            delay(200L)
        }
    }

    DesignModalBottomSheet(
        onDismissRequest = {
            finishRecording(save = false)
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(text = "🎙️", fontSize = 20.sp)
                Text(
                    text = stringResource(R.string.audio_sheet_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
            }

            if (showTitleField) {
                OutlinedTextField(
                    value = customTitle,
                    onValueChange = { customTitle = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.audio_placeholder_title),
                            color = AppColors.TextTertiary,
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.Ice,
                        unfocusedBorderColor = AppColors.BorderSubtle,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isRecording) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppColors.SurfaceElevated)
                        .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(16.dp))
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = formatDurationSeconds(elapsedSeconds),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPaused) AppColors.TextSecondary else AppColors.Danger,
                    )
                    Text(
                        text = stringResource(
                            if (isPaused) R.string.audio_recorder_paused else R.string.audio_recorder_recording_audio,
                        ),
                        fontSize = 13.sp,
                        color = AppColors.TextSecondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                val current = recorder ?: return@OutlinedButton
                                if (isPaused) {
                                    runCatching { current.resume() }
                                    if (pauseStartedRealtimeMs > 0L) {
                                        accumulatedPausedMs += (SystemClock.elapsedRealtime() - pauseStartedRealtimeMs).coerceAtLeast(0L)
                                        pauseStartedRealtimeMs = 0L
                                    }
                                    isPaused = false
                                } else {
                                    runCatching { current.pause() }
                                    pauseStartedRealtimeMs = SystemClock.elapsedRealtime()
                                    isPaused = true
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (isPaused) R.string.media_play else R.string.media_pause,
                                ),
                            )
                        }

                        Button(
                            onClick = { finishRecording(save = true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Accent,
                                contentColor = AppColors.Background,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.audio_save_recording),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPerm) {
                                startMicRecording()
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Accent,
                            contentColor = AppColors.Background,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.audio_record_new),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    OutlinedButton(
                        onClick = { filePickerLauncher.launch("audio/*") },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AudioFile,
                            contentDescription = null,
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.audio_pick_file),
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
