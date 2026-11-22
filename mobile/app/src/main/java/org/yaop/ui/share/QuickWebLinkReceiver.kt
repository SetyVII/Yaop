package org.yaop.ui.share

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.PropertyValue
import org.yaop.core.network.LinkPreviewFetcher
import org.yaop.core.network.NetworkPreferences
import org.yaop.core.repository.PkmRepository
import org.yaop.ui.common.extractUrlFromText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.UUID

sealed interface QuickSaveResult {
    data class SuccessWithImage(val obj: PkmObject) : QuickSaveResult
    data class SuccessWithoutImage(val obj: PkmObject) : QuickSaveResult
    data class Failure(val error: Throwable?) : QuickSaveResult
}

/**
 * Headless background receiver for saving WebLinks directly to YAOP
 * without displaying sheets or dialogs, downloading OpenGraph metadata and
 * thumbnail asynchronously.
 */
class QuickWebLinkReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_QUICK_SAVE = "org.yaop.ACTION_QUICK_SAVE_WEBLINK"
        private const val TAG = "QuickWebLinkReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.action != ACTION_QUICK_SAVE) return

        val rawUrl = intent.getStringExtra("url") ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        val url = if (!rawUrl.isNullOrBlank()) extractUrlFromText(rawUrl) else null

        if (url.isNullOrBlank()) {
            Toast.makeText(context, R.string.quick_save_error_no_url, Toast.LENGTH_SHORT).show()
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext as YaopApp
        val repository = appContext.repository
        Log.d(TAG, "Starting background quick save for: $url")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = executeQuickSave(appContext, repository, url)
                withContext(Dispatchers.Main) {
                    when (result) {
                        is QuickSaveResult.SuccessWithImage -> {
                            Log.d(TAG, "Full success: saved with image")
                            Toast.makeText(appContext, R.string.quick_save_saved_toast, Toast.LENGTH_SHORT).show()
                        }
                        is QuickSaveResult.SuccessWithoutImage -> {
                            Log.d(TAG, "Partial success: saved without image")
                            Toast.makeText(appContext, R.string.quick_save_saved_no_image_toast, Toast.LENGTH_SHORT).show()
                        }
                        is QuickSaveResult.Failure -> {
                            Log.e(TAG, "Failed to save link", result.error)
                            Toast.makeText(appContext, R.string.quick_save_error_toast, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during quick save", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, R.string.quick_save_error_toast, Toast.LENGTH_SHORT).show()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

suspend fun executeQuickSave(
    context: YaopApp,
    repository: PkmRepository,
    url: String,
): QuickSaveResult = withContext(Dispatchers.IO) {
    runCatching {
        val allowThirdPartyProxies = NetworkPreferences.areThirdPartyProxiesAllowed(context)
        val previewData = runCatching { LinkPreviewFetcher.fetch(url, allowThirdPartyProxies) }.getOrNull()

        val host = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")
        val siteName = previewData?.siteName?.takeIf { it.isNotBlank() } ?: host
        val finalTitle = previewData?.title?.takeIf { it.isNotBlank() } ?: host.ifBlank { "Web Link" }

        val imgUrl = previewData?.imageUrl
        Log.d("QuickWebLinkReceiver", "Metadata obtenida: title='$finalTitle', site='$siteName', img='$imgUrl'")

        var thumbnailAssetId: String? = null
        var createdImageObjectId: String? = null
        var hadImageInMetadata = false
        var imageSavedSuccessfully = false

        if (!imgUrl.isNullOrBlank()) {
            hadImageInMetadata = true
            val downloadedThumbBytes = runCatching {
                LinkPreviewFetcher.downloadCompressedThumbnail(imgUrl)
            }.getOrNull()

            if (downloadedThumbBytes != null && downloadedThumbBytes.isNotEmpty()) {
                val assetId = UUID.randomUUID().toString()
                val mediaStore = context.mediaStore
                mediaStore.saveMedia(assetId, downloadedThumbBytes, "image/webp")

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

                val imageObjId = "media_$assetId"
                createdImageObjectId = imageObjId
                val imageObj = PkmObject(
                    id = imageObjId,
                    typeId = DefaultTypes.IMAGE.id,
                    title = listOf(InlineSpan.Text(coverTitle)),
                    icon = ObjectIcon(emoji = "🖼️"),
                    properties = mapOf(
                        "asset_id" to PropertyValue.RichText(listOf(InlineSpan.Text(assetId))),
                        "file_name" to PropertyValue.RichText(listOf(InlineSpan.Text(coverFileName))),
                        "mime" to PropertyValue.RichText(listOf(InlineSpan.Text("image/webp"))),
                        "size" to PropertyValue.Number(downloadedThumbBytes.size.toDouble()),
                        "source_url" to PropertyValue.Url(url),
                    ),
                )
                repository.saveObject(imageObj)
                imageSavedSuccessfully = true
                Log.d("QuickWebLinkReceiver", "Miniatura guardada exitosamente con assetId=$thumbnailAssetId")
            } else {
                Log.w("QuickWebLinkReceiver", "Could not download the thumbnail $imgUrl; saving the object without image")
            }
        }

        val newId = UUID.randomUUID().toString()
        val props = buildMap<String, PropertyValue> {
            put("url", PropertyValue.Url(url))
            if (siteName.isNotBlank()) {
                put("site_name", PropertyValue.RichText(listOf(InlineSpan.Text(siteName))))
            }
            put("reviewed", PropertyValue.Checkbox(false))
            put("is_bookmark", PropertyValue.Checkbox(false))
            if (createdImageObjectId != null) {
                put("cover_image", PropertyValue.RichText(listOf(InlineSpan.Text(createdImageObjectId))))
            }
        }

        val bookmarkBlock = Block.Bookmark(
            id = UUID.randomUUID().toString(),
            url = url,
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
            title = listOf(InlineSpan.Text(finalTitle)),
            icon = ObjectIcon(emoji = "🌐"),
            cover = thumbnailAssetId?.let { CoverSpec(assetId = it) },
            properties = props,
            blocks = contentBlocks,
        )
        repository.saveObject(newObj)
        Log.d("QuickWebLinkReceiver", "WebLink object saved: id=$newId, title='$finalTitle'")

        if (hadImageInMetadata && !imageSavedSuccessfully) {
            QuickSaveResult.SuccessWithoutImage(newObj)
        } else {
            QuickSaveResult.SuccessWithImage(newObj)
        }
    }.getOrElse { e ->
        Log.e("QuickWebLinkReceiver", "Fallo al guardar enlace web", e)
        QuickSaveResult.Failure(e)
    }
}
