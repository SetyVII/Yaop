package org.yaop.ui.common

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Patterns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import org.yaop.YaopApp
import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import org.yaop.core.network.LinkPreviewData
import org.yaop.core.network.LinkPreviewFetcher
import org.yaop.core.repository.PkmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Data holder for a decoded image thumbnail optimized for grid cards.
 * Preserves the original image dimensions and aspect ratio while downsampling
 * the bitmap in memory to avoid OOM with large 4K images.
 */
data class DecodedThumbnail(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
    val aspectRatio: Float,
)

/**
 * Resolves the underlying asset ID for a media object (`typeId == "image"`).
 * Non-image objects (e.g. books, notes, tasks) will never return an asset ID here,
 * ensuring their content image blocks remain in the note body without leaking into a hero header.
 */
fun resolveImageAssetId(
    obj: PkmObject,
    objectAssets: List<AssetMeta> = emptyList(),
): String? {
    val isImage = obj.typeId == "image" || obj.typeId == "type_image"
    if (!isImage) return null

    // 1. Direct property lookup
    val propAssetId = (obj.property("asset_id") as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
        ?: (obj.property("asset_id") as? PropertyValue.Media)?.assetId?.takeIf { it.isNotBlank() }
        ?: (obj.properties["asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
        ?: (obj.properties["prop_asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
    if (!propAssetId.isNullOrBlank()) return propAssetId

    // 2. Canonical ID fallback if prefixed with media_
    if (obj.id.startsWith("media_")) {
        return obj.id.removePrefix("media_")
    }

    // 3. Object assets list
    val assetFromList = objectAssets.firstOrNull { it.mimeType.startsWith("image/") }?.id
        ?: objectAssets.firstOrNull()?.id
    if (!assetFromList.isNullOrBlank()) return assetFromList

    return null
}

/**
 * Extracts tags associated with this object.
 */
fun extractImageTags(obj: PkmObject): List<String> {
    val propVal = obj.property("tags") ?: obj.properties["tags"] ?: obj.properties["prop_tags"]
    return when (propVal) {
        is PropertyValue.MultiSelect -> propVal.selectedOptionIds
        else -> emptyList()
    }
}

/**
 * Extracts MIME type from object properties.
 */
fun extractImageMime(obj: PkmObject): String? {
    val raw = (obj.property("mime") as? PropertyValue.RichText)?.spans?.toPlainText()
        ?: (obj.properties["mime"] as? PropertyValue.RichText)?.spans?.toPlainText()
    return raw?.takeIf { it.isNotBlank() }
}

/**
 * Extracts file size in bytes from object properties.
 */
fun extractImageSize(obj: PkmObject): Long? {
    val num = (obj.property("size") as? PropertyValue.Number)?.value
        ?: (obj.properties["size"] as? PropertyValue.Number)?.value
    return num?.toLong()?.takeIf { it > 0 }
}

/**
 * Formats byte size into human readable string (KB / MB).
 */
fun formatFileSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    return if (bytes < 1024 * 1024) {
        "${bytes / 1024} KB"
    } else {
        String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    }
}

/**
 * Formats mime string to short label (PNG, JPG, WEBP, GIF, SVG).
 */
fun formatMimeShort(mime: String?): String? {
    if (mime.isNullOrBlank()) return null
    return when {
        mime.contains("png") -> "PNG"
        mime.contains("webp") -> "WEBP"
        mime.contains("jpeg") || mime.contains("jpg") -> "JPG"
        mime.contains("gif") -> "GIF"
        mime.contains("svg") -> "SVG"
        mime.contains("heic") || mime.contains("heif") -> "HEIC"
        else -> null
    }
}

private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
// Allocate 1/8th of available runtime memory to the thumbnail cache (standard Android practice)
private val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(1024 * 16)

val thumbnailMemoryCache: LruCache<String, DecodedThumbnail> = object : LruCache<String, DecodedThumbnail>(cacheSizeKb) {
    override fun sizeOf(key: String, value: DecodedThumbnail): Int {
        return (value.bitmap.byteCount / 1024).coerceAtLeast(1)
    }
}

/**
 * Decodes a downsampled thumbnail of an asset stored in EncryptedMediaStore.
 * Uses an in-memory LruCache to ensure 0ms instantaneous display when navigating back
 * to notes or scrolling through image galleries.
 * Performs I/O and bounds inspection off the main thread.
 * If media bytes are missing locally, progressive on-demand fetch from Go server is triggered.
 */
@Composable
fun rememberDecodedThumbnail(
    assetId: String,
    targetMaxSidePx: Int = 1024,
    refreshKey: Boolean = false,
): DecodedThumbnail? {
    if (assetId.isBlank()) return null
    // "Hide": nothing is decoded, callers fall back to their text/icon layout.
    if (shouldSkipImageDecode()) return null
    val cacheKey = "$assetId-$targetMaxSidePx"
    val cached = if (!refreshKey) thumbnailMemoryCache.get(cacheKey) else null

    val context = LocalContext.current
    return produceState<DecodedThumbnail?>(
        initialValue = cached,
        key1 = assetId,
        key2 = refreshKey,
        key3 = targetMaxSidePx,
    ) {
        if (!refreshKey) {
            val fastHit = thumbnailMemoryCache.get(cacheKey)
            if (fastHit != null) {
                value = fastHit
                return@produceState
            }
        }
        val decoded = withContext(Dispatchers.IO) {
            val app = context.applicationContext as? YaopApp
            val mediaStore = app?.mediaStore ?: return@withContext null
            var data = mediaStore.readMediaAny(assetId)
            if (data == null && app != null) {
                // Progressive on-demand fetch from Go server
                app.syncManager.downloadAssetIfMissing(assetId)
                data = mediaStore.readMediaAny(assetId)
            }
            if (data == null) return@withContext null

            // Inspect bounds first without allocating bitmap memory
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            val origW = bounds.outWidth.coerceAtLeast(1)
            val origH = bounds.outHeight.coerceAtLeast(1)
            val ratio = origW.toFloat() / origH.toFloat()

            // Calculate power-of-2 downsampling factor
            var sample = 1
            while ((origW / sample) > targetMaxSidePx || (origH / sample) > targetMaxSidePx) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
            val sampledBitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return@withContext null

            DecodedThumbnail(
                bitmap = sampledBitmap,
                originalWidth = origW,
                originalHeight = origH,
                aspectRatio = ratio,
            )
        }
        if (decoded != null) {
            thumbnailMemoryCache.put(cacheKey, decoded)
        }
        value = decoded
    }.value
}

/**
 * Creates a brand-new Image object from a picked image URI.
 * Guarantees that:
 * 1. An Image object is NEVER created without an image.
 * 2. Only ONE object is created (no duplicates).
 * 3. The title the user typed wins over the raw filename.
 */
suspend fun createNewImageObjectFromUri(
    context: Context,
    repository: PkmRepository,
    uri: Uri,
    customTitle: String? = null,
): PkmObject? = withContext(Dispatchers.IO) {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null

    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
    val assetId = UUID.randomUUID().toString()
    val fileName = uri.lastPathSegment ?: assetId
    val mediaStore = (context.applicationContext as YaopApp).mediaStore
    mediaStore.saveMedia(assetId, bytes, mime)

    val cleanCustom = customTitle?.trim()?.takeIf {
        it.isNotBlank() && it != "Untitled" && it != "Título" && it != "Untitled object"
    }
    val finalTitle = cleanCustom ?: fileName
    val newId = "media_$assetId"

    val props = buildMap<String, PropertyValue> {
        put("asset_id", PropertyValue.RichText(assetId.toTextSpan()))
        put("file_name", PropertyValue.RichText(fileName.toTextSpan()))
        put("mime", PropertyValue.RichText(mime.toTextSpan()))
        put("size", PropertyValue.Number(bytes.size.toDouble()))
    }

    val newObj = PkmObject(
        id = newId,
        typeId = DefaultTypes.IMAGE.id,
        title = finalTitle.toTextSpan(),
        icon = ObjectIcon(emoji = "🖼️"),
        properties = props,
    )

    repository.saveAsset(
        AssetMeta(
            id = assetId,
            objectId = newId,
            mediaObjectId = newId,
            name = fileName,
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
        ),
    )
    repository.saveObject(newObj)
    newObj
}

/**
 * Resolves the underlying asset ID for an Audio object (`typeId == "audio"`).
 * Non-audio objects (e.g. notes, tasks) will never return an asset ID here,
 * ensuring their content audio blocks remain in the note body without leaking into a hero header.
 */
fun resolveAudioAssetId(
    obj: PkmObject,
    objectAssets: List<AssetMeta> = emptyList(),
): String? {
    val isAudio = obj.typeId == "audio" || obj.typeId == "type_audio"
    if (!isAudio) return null

    // 1. Direct property lookup
    val propAssetId = (obj.property("asset_id") as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
        ?: (obj.property("asset_id") as? PropertyValue.Media)?.assetId?.takeIf { it.isNotBlank() }
        ?: (obj.properties["asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
        ?: (obj.properties["prop_asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
    if (!propAssetId.isNullOrBlank()) return propAssetId

    // 2. Canonical ID fallback if prefixed with media_
    if (obj.id.startsWith("media_")) {
        return obj.id.removePrefix("media_")
    }

    // 3. Object assets list
    val assetFromList = objectAssets.firstOrNull { it.mimeType.startsWith("audio/") }?.id
        ?: objectAssets.firstOrNull()?.id
    if (!assetFromList.isNullOrBlank()) return assetFromList

    // 4. First audio block fallback for legacy audio objects
    val firstAudioBlock = obj.blocks.filterIsInstance<Block.Audio>().firstOrNull { it.assetId.isNotBlank() }
    if (firstAudioBlock != null) return firstAudioBlock.assetId

    return null
}

/**
 * Extracts duration in seconds from an Audio object's properties or blocks.
 */
fun extractAudioDurationSeconds(obj: PkmObject): Int {
    val num = (obj.property("duration") as? PropertyValue.Number)?.value
        ?: (obj.properties["duration"] as? PropertyValue.Number)?.value
        ?: (obj.properties["prop_duration"] as? PropertyValue.Number)?.value
    if (num != null && num > 0) return num.roundToInt().coerceAtLeast(1)
    val blockDur = obj.blocks.filterIsInstance<Block.Audio>().firstOrNull()?.durationSeconds ?: 0
    return blockDur.coerceAtLeast(0)
}

/**
 * Formats audio mime string to short label (M4A, MP3, WAV, OGG, AAC, FLAC, OPUS).
 */
fun formatAudioMimeShort(mime: String?): String? {
    if (mime.isNullOrBlank()) return null
    val lower = mime.lowercase(Locale.ROOT)
    return when {
        lower.contains("mp4") || lower.contains("m4a") -> "M4A"
        lower.contains("mpeg") || lower.contains("mp3") -> "MP3"
        lower.contains("wav") -> "WAV"
        lower.contains("ogg") -> "OGG"
        lower.contains("aac") -> "AAC"
        lower.contains("flac") -> "FLAC"
        lower.contains("opus") -> "OPUS"
        lower.contains("webm") -> "WEBM"
        else -> null
    }
}

/**
 * Extracts audio duration in seconds from raw audio bytes via an ephemeral cache file.
 */
fun extractAudioDurationFromBytes(
    context: Context,
    bytes: ByteArray,
    fallbackSeconds: Int = 1,
): Int {
    if (bytes.isEmpty()) return fallbackSeconds.coerceAtLeast(1)
    val dir = File(context.cacheDir, "media_tmp").apply { if (!exists()) mkdirs() }
    var tempFile: File? = null
    return try {
        tempFile = File.createTempFile("dur_probe_", ".m4a", dir)
        tempFile.writeBytes(bytes)
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(tempFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (durationMs != null && durationMs > 0L) {
            (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
        } else {
            fallbackSeconds.coerceAtLeast(1)
        }
    } catch (_: Exception) {
        fallbackSeconds.coerceAtLeast(1)
    } finally {
        tempFile?.delete()
    }
}

/**
 * Creates a brand-new Audio object from raw audio bytes (recorded or imported).
 * Guarantees that:
 * 1. An Audio object is NEVER created without audio.
 * 2. Only ONE canonical object is created (no duplicates).
 * 3. The user's assigned custom title wins over the raw filename or auto-title.
 */
suspend fun createNewAudioObjectFromBytes(
    context: Context,
    repository: PkmRepository,
    bytes: ByteArray,
    durationSeconds: Int,
    mime: String = "audio/mp4",
    customTitle: String? = null,
    rawFileName: String? = null,
): PkmObject? = withContext(Dispatchers.IO) {
    if (bytes.isEmpty()) return@withContext null

    val assetId = UUID.randomUUID().toString()
    val effectiveDuration = if (durationSeconds > 0) {
        durationSeconds
    } else {
        extractAudioDurationFromBytes(context, bytes, fallbackSeconds = 1)
    }
    val fileName = rawFileName?.takeIf { it.isNotBlank() } ?: "rec_$assetId.m4a"
    val mediaStore = (context.applicationContext as YaopApp).mediaStore
    mediaStore.saveMedia(assetId, bytes, mime)

    val cleanCustom = customTitle?.trim()?.takeIf {
        it.isNotBlank() &&
            it != "Untitled" &&
            it != "Sin título" &&
            it != "Título" &&
            it != "Untitled object"
    }
    val finalTitle = cleanCustom ?: fileName
    val newId = "media_$assetId"

    val props = buildMap<String, PropertyValue> {
        put("asset_id", PropertyValue.RichText(assetId.toTextSpan()))
        put("file_name", PropertyValue.RichText(fileName.toTextSpan()))
        put("mime", PropertyValue.RichText(mime.toTextSpan()))
        put("size", PropertyValue.Number(bytes.size.toDouble()))
        put("duration", PropertyValue.Number(effectiveDuration.toDouble()))
    }

    val newObj = PkmObject(
        id = newId,
        typeId = DefaultTypes.AUDIO.id,
        title = finalTitle.toTextSpan(),
        icon = ObjectIcon(emoji = "🎙️"),
        properties = props,
    )

    repository.saveAsset(
        AssetMeta(
            id = assetId,
            objectId = newId,
            mediaObjectId = newId,
            name = fileName,
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
            durationSeconds = effectiveDuration,
        ),
    )
    repository.saveObject(newObj)
    newObj
}

/**
 * Creates a brand-new Audio object from a picked audio file URI.
 */
suspend fun createNewAudioObjectFromUri(
    context: Context,
    repository: PkmRepository,
    uri: Uri,
    customTitle: String? = null,
): PkmObject? = withContext(Dispatchers.IO) {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null
    if (bytes.isEmpty()) return@withContext null

    val mime = context.contentResolver.getType(uri) ?: "audio/mp4"
    val fileName = resolveFileNameFromUri(context, uri, fallbackPrefix = "audio")
    val durationSeconds = extractAudioDurationFromBytes(context, bytes, fallbackSeconds = 1)
    createNewAudioObjectFromBytes(
        context = context,
        repository = repository,
        bytes = bytes,
        durationSeconds = durationSeconds,
        mime = mime,
        customTitle = customTitle,
        rawFileName = fileName,
    )
}

/**
 * Resolves the real human-readable filename from a content:// or file:// URI.
 */
fun resolveFileNameFromUri(
    context: Context,
    uri: Uri,
    fallbackPrefix: String = "file",
): String {
    var name: String? = null
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        }
    }
    if (name.isNullOrBlank()) {
        name = uri.lastPathSegment
    }
    return name?.takeIf { it.isNotBlank() } ?: "$fallbackPrefix-${System.currentTimeMillis()}"
}

/**
 * Resolves the underlying asset ID for a PDF object (`typeId == "pdf"`).
 */
fun resolvePdfAssetId(
    obj: PkmObject,
    objectAssets: List<AssetMeta> = emptyList(),
): String? {
    val isPdf = obj.typeId == "pdf" || obj.typeId == "type_pdf"
    if (!isPdf) return null

    val propAssetId = (obj.property("asset_id") as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
        ?: (obj.properties["asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
    if (!propAssetId.isNullOrBlank()) return propAssetId

    if (obj.id.startsWith("media_")) {
        return obj.id.removePrefix("media_")
    }

    val assetFromList = objectAssets.firstOrNull { it.mimeType == "application/pdf" }?.id
        ?: objectAssets.firstOrNull()?.id
    if (!assetFromList.isNullOrBlank()) return assetFromList

    val firstPdfBlock = obj.blocks.filterIsInstance<Block.Pdf>().firstOrNull { it.assetId.isNotBlank() }
    if (firstPdfBlock != null) return firstPdfBlock.assetId

    return null
}

/**
 * Creates a brand-new PDF object from a picked or shared PDF URI.
 */
suspend fun createNewPdfObjectFromUri(
    context: Context,
    repository: PkmRepository,
    uri: Uri,
    customTitle: String? = null,
): PkmObject? = withContext(Dispatchers.IO) {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null
    if (bytes.isEmpty()) return@withContext null

    val mime = "application/pdf"
    val assetId = UUID.randomUUID().toString()
    val rawFileName = resolveFileNameFromUri(context, uri, fallbackPrefix = "document")
    val cleanCustom = customTitle?.trim()?.takeIf {
        it.isNotBlank() && it != "Untitled" && it != "Sin título" && it != "Título" && it != "Untitled object"
    }
    val finalTitle = cleanCustom ?: rawFileName.removeSuffix(".pdf").removeSuffix(".PDF")
    val newId = "media_$assetId"

    val mediaStore = (context.applicationContext as YaopApp).mediaStore
    mediaStore.saveMedia(assetId, bytes, mime)

    val props = buildMap<String, PropertyValue> {
        put("asset_id", PropertyValue.RichText(assetId.toTextSpan()))
        put("file_name", PropertyValue.RichText(rawFileName.toTextSpan()))
        put("mime", PropertyValue.RichText(mime.toTextSpan()))
        put("size", PropertyValue.Number(bytes.size.toDouble()))
    }

    val newObj = PkmObject(
        id = newId,
        typeId = DefaultTypes.PDF.id,
        title = finalTitle.toTextSpan(),
        icon = ObjectIcon(emoji = "📄"),
        properties = props,
        blocks = listOf(
            Block.Pdf(
                id = UUID.randomUUID().toString(),
                assetId = assetId,
                fileName = rawFileName,
            ),
        ),
    )

    repository.saveAsset(
        AssetMeta(
            id = assetId,
            objectId = newId,
            mediaObjectId = newId,
            name = rawFileName,
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
        ),
    )
    repository.saveObject(newObj)
    newObj
}

/**
 * Creates a brand-new Web Link object from a URL.
 * Guarantees that:
 * 1. A WebLink object is NEVER created without a link ("no existe weblink sin link, obligatorio").
 * 2. It NEVER creates an "Untitled" object; falls back cleanly to the fetched page title, host, or URL.
 * 3. Fetches Open Graph metadata, downloads/saves compressed thumbnail or user custom image.
 * 4. Stores the link in object properties and creates a corresponding Bookmark block.
 */
suspend fun createNewWebLinkObject(
    context: Context,
    repository: PkmRepository,
    url: String,
    customTitle: String? = null,
    isBookmark: Boolean = false,
    isReviewed: Boolean = false,
    downloadThumbnail: Boolean = true,
    customImageUri: Uri? = null,
    customImageBytes: ByteArray? = null,
    prefetchedPreview: LinkPreviewData? = null,
): PkmObject = withContext(Dispatchers.IO) {
    val cleanUrl = url.trim().let {
        if (!it.startsWith("http://", ignoreCase = true) && !it.startsWith("https://", ignoreCase = true)) {
            "https://$it"
        } else {
            it
        }
    }
    val allowThirdPartyProxies = org.yaop.core.network.NetworkPreferences.areThirdPartyProxiesAllowed(context)
    val preview = prefetchedPreview ?: runCatching { LinkPreviewFetcher.fetch(cleanUrl, allowThirdPartyProxies) }.getOrNull()

    val cleanCustom = customTitle?.trim()?.takeIf {
        it.isNotBlank() && it != "Untitled" && it != "Sin título" && it != "Título" && it != "Untitled object"
    }
    val pageTitle = cleanCustom
        ?: preview?.title?.trim()?.takeIf { it.isNotBlank() && !LinkPreviewFetcher.isBlockedTitle(it) }
        ?: runCatching { URL(cleanUrl).host.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: cleanUrl

    val siteName = preview?.siteName?.takeIf { it.isNotBlank() }
        ?: runCatching { URL(cleanUrl).host.removePrefix("www.") }.getOrDefault("")
    val description = preview?.description.orEmpty()

    val mediaStore = (context.applicationContext as YaopApp).mediaStore
    var thumbnailAssetId: String? = null

    // 1. User custom image takes highest priority if provided
    if (customImageBytes != null && customImageBytes.isNotEmpty()) {
        val assetId = UUID.randomUUID().toString()
        mediaStore.saveMedia(assetId, customImageBytes, "image/webp")
        repository.saveAsset(
            AssetMeta(
                id = assetId,
                name = "weblink_custom_$assetId.webp",
                mimeType = "image/webp",
                sizeBytes = customImageBytes.size.toLong(),
            ),
        )
        thumbnailAssetId = assetId
    } else if (customImageUri != null) {
        val readBytes = runCatching {
            context.contentResolver.openInputStream(customImageUri)?.use { it.readBytes() }
        }.getOrNull()
        if (readBytes != null && readBytes.isNotEmpty()) {
            val assetId = UUID.randomUUID().toString()
            val mime = context.contentResolver.getType(customImageUri) ?: "image/jpeg"
            mediaStore.saveMedia(assetId, readBytes, mime)
            repository.saveAsset(
                AssetMeta(
                    id = assetId,
                    name = "weblink_custom_$assetId",
                    mimeType = mime,
                    sizeBytes = readBytes.size.toLong(),
                ),
            )
            thumbnailAssetId = assetId
        }
    }

    // 2. Fall back to downloading compressed Open Graph thumbnail from web
    if (thumbnailAssetId == null && downloadThumbnail && !preview?.imageUrl.isNullOrBlank()) {
        val thumbBytes = LinkPreviewFetcher.downloadCompressedThumbnail(preview.imageUrl)
        if (thumbBytes != null && thumbBytes.isNotEmpty()) {
            val assetId = UUID.randomUUID().toString()
            mediaStore.saveMedia(assetId, thumbBytes, "image/webp")
            repository.saveAsset(
                AssetMeta(
                    id = assetId,
                    name = "thumb_$assetId.webp",
                    mimeType = "image/webp",
                    sizeBytes = thumbBytes.size.toLong(),
                ),
            )
            thumbnailAssetId = assetId
        }
    }

    val newId = UUID.randomUUID().toString()
    val props = buildMap<String, PropertyValue> {
        put("url", PropertyValue.Url(cleanUrl))
        if (siteName.isNotBlank()) {
            put("site_name", PropertyValue.RichText(listOf(InlineSpan.Text(siteName))))
        }
        put("reviewed", PropertyValue.Checkbox(isReviewed))
        put("is_bookmark", PropertyValue.Checkbox(isBookmark))
    }

    val newObj = PkmObject(
        id = newId,
        typeId = DefaultTypes.WEBLINK.id,
        title = listOf(InlineSpan.Text(pageTitle)),
        icon = ObjectIcon(emoji = "🌐"),
        cover = thumbnailAssetId?.let { CoverSpec(assetId = it) },
        properties = props,
        blocks = listOf(
            Block.Bookmark(
                id = UUID.randomUUID().toString(),
                url = cleanUrl,
                title = preview?.title?.ifBlank { pageTitle } ?: pageTitle,
                description = description,
                domain = siteName,
                thumbnailUrl = preview?.imageUrl,
                faviconUrl = preview?.faviconUrl,
                targetObjectId = newId,
            ),
        ),
    )

    repository.saveObject(newObj)
    newObj
}

/**
 * Updates an existing WebLink object with a new URL, re-fetching metadata if the URL changed.
 */
suspend fun updateWebLinkObjectUrl(
    context: Context,
    repository: PkmRepository,
    obj: PkmObject,
    newUrl: String,
    newCustomTitle: String? = null,
): PkmObject = withContext(Dispatchers.IO) {
    val cleanUrl = newUrl.trim().let {
        if (!it.startsWith("http://", ignoreCase = true) && !it.startsWith("https://", ignoreCase = true)) {
            "https://$it"
        } else {
            it
        }
    }
    val allowThirdPartyProxies = org.yaop.core.network.NetworkPreferences.areThirdPartyProxiesAllowed(context)
    val preview = runCatching { LinkPreviewFetcher.fetch(cleanUrl, allowThirdPartyProxies) }.getOrNull()

    val siteName = preview?.siteName?.takeIf { it.isNotBlank() }
        ?: runCatching { URL(cleanUrl).host.removePrefix("www.") }.getOrDefault("")
    val description = preview?.description.orEmpty()

    val cleanCustom = newCustomTitle?.trim()?.takeIf {
        it.isNotBlank() && it != "Untitled" && it != "Sin título" && it != "Título"
    }
    val resolvedTitle = cleanCustom
        ?: if (obj.plainTitle.isNotBlank() && obj.plainTitle != "Untitled" && obj.plainTitle != "Sin título") {
            obj.plainTitle
        } else {
            preview?.title?.takeIf { it.isNotBlank() && !LinkPreviewFetcher.isBlockedTitle(it) }
                ?: siteName.ifBlank { cleanUrl }
        }

    val mediaStore = (context.applicationContext as YaopApp).mediaStore
    var thumbnailAssetId = obj.cover?.assetId?.takeIf { !it.startsWith("cover_") }

    if (thumbnailAssetId == null && !preview?.imageUrl.isNullOrBlank()) {
        val thumbBytes = LinkPreviewFetcher.downloadCompressedThumbnail(preview.imageUrl)
        if (thumbBytes != null && thumbBytes.isNotEmpty()) {
            val assetId = UUID.randomUUID().toString()
            mediaStore.saveMedia(assetId, thumbBytes, "image/webp")
            repository.saveAsset(
                AssetMeta(
                    id = assetId,
                    name = "thumb_$assetId.webp",
                    mimeType = "image/webp",
                    sizeBytes = thumbBytes.size.toLong(),
                ),
            )
            thumbnailAssetId = assetId
        }
    }

    val updatedProps = obj.properties.toMutableMap()
    updatedProps["url"] = PropertyValue.Url(cleanUrl)
    if (siteName.isNotBlank()) {
        updatedProps["site_name"] = PropertyValue.RichText(siteName.toTextSpan())
    }

    // Update or insert Bookmark block
    val existingBookmarkIdx = obj.blocks.indexOfFirst { it is Block.Bookmark }
    val newBookmarkBlock = Block.Bookmark(
        id = if (existingBookmarkIdx != -1) obj.blocks[existingBookmarkIdx].id else UUID.randomUUID().toString(),
        url = cleanUrl,
        title = preview?.title?.ifBlank { resolvedTitle } ?: resolvedTitle,
        description = description,
        domain = siteName,
        thumbnailUrl = preview?.imageUrl,
        faviconUrl = preview?.faviconUrl,
        targetObjectId = obj.id,
    )

    val updatedBlocks = if (existingBookmarkIdx != -1) {
        obj.blocks.toMutableList().apply { set(existingBookmarkIdx, newBookmarkBlock) }
    } else {
        listOf(newBookmarkBlock) + obj.blocks
    }

    val updatedObj = obj.copy(
        title = resolvedTitle.toTextSpan(),
        cover = thumbnailAssetId?.let { CoverSpec(assetId = it) } ?: obj.cover,
        properties = updatedProps,
        blocks = updatedBlocks,
    )
    repository.saveObject(updatedObj)
    updatedObj
}

/**
 * Attaches or replaces a custom image cover on a WebLink object.
 */
suspend fun attachImageCoverToWebLink(
    context: Context,
    repository: PkmRepository,
    obj: PkmObject,
    imageUri: Uri,
): PkmObject? = withContext(Dispatchers.IO) {
    val bytes = runCatching {
        context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
    }.getOrNull() ?: return@withContext null

    val mime = context.contentResolver.getType(imageUri) ?: "image/jpeg"
    val assetId = UUID.randomUUID().toString()
    val mediaStore = (context.applicationContext as YaopApp).mediaStore
    mediaStore.saveMedia(assetId, bytes, mime)

    repository.saveAsset(
        AssetMeta(
            id = assetId,
            objectId = obj.id,
            mediaObjectId = obj.id,
            name = "cover_$assetId",
            mimeType = mime,
            sizeBytes = bytes.size.toLong(),
        ),
    )

    val updatedObj = obj.copy(cover = CoverSpec(assetId = assetId))
    repository.saveObject(updatedObj)
    updatedObj
}

/**
 * Extracts a web URL from raw text if present. Returns the first valid http/https URL.
 */
fun extractUrlFromText(text: String): String? {
    val trimmed = text.trim()
    val raw = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
        ?: run {
            val pattern = runCatching { Patterns.WEB_URL }.getOrNull() ?: return null
            val matcher = pattern.matcher(trimmed)
            if (matcher.find()) {
                val candidate = matcher.group()
                if (candidate.startsWith("http://", ignoreCase = true) ||
                    candidate.startsWith("https://", ignoreCase = true)
                ) {
                    candidate
                } else {
                    "https://$candidate"
                }
            } else {
                null
            }
        } ?: return null

    return raw.trimEnd('.', ',', ')', ']', '>', ';', '!', '?')
}
