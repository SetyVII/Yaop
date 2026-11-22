package org.yaop.core.network

/**
 * Options configuring link preview extraction and third-party proxy usage.
 */
data class PreviewFetchOptions(
    val allowThirdPartyProxies: Boolean = true,
)

/**
 * Strategy contract for domain-specific link metadata extractors (YouTube, Reddit, X, etc.).
 */
interface LinkPreviewStrategy {
    val mediaType: LinkMediaType
    fun canHandle(url: String): Boolean
    suspend fun fetchPreview(url: String, options: PreviewFetchOptions): LinkPreviewData?
}

/**
 * High-level service contract for resolving rich Open Graph previews from URLs.
 */
interface LinkPreviewService {
    suspend fun fetch(url: String, allowThirdPartyProxies: Boolean = true): LinkPreviewData?
}

/**
 * Contract for compressing, scaling, and optimizing image thumbnails for local storage.
 */
interface ThumbnailCompressor {
    fun compressImageBytes(
        rawBytes: ByteArray,
        maxSidePx: Int = 0,
        quality: Int = 90,
    ): ByteArray?
}
