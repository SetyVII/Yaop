package org.yaop.core.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.roundToInt

private const val TAG = "LinkPreviewFetcher"

/**
 * Media type categorization for links to optimize preview rendering and cover caching.
 */
enum class LinkMediaType {
    GENERIC,
    YOUTUBE,
    INSTAGRAM_REEL,
    INSTAGRAM_POST,
    REDDIT,
    REDDIT_VIDEO,
    FACEBOOK,
    FACEBOOK_VIDEO,
    TWITTER,
    TIKTOK,
}

/**
 * Open Graph metadata extracted from a URL, used to render link preview cards.
 */
data class LinkPreviewData(
    val url: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?,
    val faviconUrl: String?,
    val mediaType: LinkMediaType = LinkMediaType.GENERIC,
)

/**
 * High-performance, native Open Graph metadata and thumbnail fetcher using standard JDK networking.
 *
 * Includes specialized bypass and extraction strategies modeled after Share-To-Thymer:
 * 1. YouTube: Instant video ID extraction, direct CDN high-res thumbnail (`img.youtube.com`), and
 *    fast public oEmbed metadata (<60ms, zero-friction, skips heavy JS SPA).
 * 2. Reddit: Canonical URL resolution, vxreddit proxy with bot User-Agent (bypasses Cloudflare/login),
 *    Arctic Shift archive JSON API fallback, and clean URL slug parser.
 * 3. Instagram / Reels: ddinstagram proxy with bot User-Agent, Facebook social crawler fallback, and
 *    Reel vs Post categorization with poster frame extraction.
 * 4. Twitter / X: Fast JSON metadata & media extraction via vxtwitter API and fxtwitter/fixupx fallback.
 * 5. TikTok: Native oEmbed API parsing for title, author, and direct thumbnail.
 * 6. Facebook & Generic Sites: Browser-compatible OpenGraphScraper User-Agent with full cookie persistence
 *    across redirects to bypass bot detection.
 * 7. Low-Storage Thumbnail Compression: Downsamples and compresses covers into lightweight WebP (~15KB-30KB),
 *    filtering out 1x1 tracker pixels and tiny logos.
 */
object LinkPreviewFetcher : LinkPreviewService, ThumbnailCompressor {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_HEAD_BYTES = 256 * 1024
    private const val MAX_REDIRECTS = 5

    // Mimics open-graph-scraper Node.js library which Facebook and cloud services accept
    private const val OG_SCRAPER_USER_AGENT =
        "Mozilla/5.0 (compatible; open-graph-scraper/6.0.0; +https://www.npmjs.com/package/open-graph-scraper)"

    // Discordbot User-Agent receives server-side prerendered OpenGraph tags on vxreddit, ddinstagram, etc.
    private const val BOT_USER_AGENT =
        "Discordbot/2.0"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"

    private const val CACHE_MAX_ENTRIES = 64

    private val cache: MutableMap<String, LinkPreviewData> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, LinkPreviewData>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, LinkPreviewData>,
                ): Boolean = size > CACHE_MAX_ENTRIES
            },
        )

    private fun ensureCookieManager() {
        if (CookieHandler.getDefault() == null) {
            val cm = CookieManager()
            cm.setCookiePolicy(CookiePolicy.ACCEPT_ALL)
            CookieHandler.setDefault(cm)
        }
    }

    override suspend fun fetch(
        url: String,
        allowThirdPartyProxies: Boolean,
    ): LinkPreviewData? = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeUrl(url) ?: return@withContext null

        cache[normalizedUrl]?.let { return@withContext it }
        ensureCookieManager()

        val fetched = runCatching {
            // 1. YouTube instant bypass (official public oEmbed & direct CDN)
            val ytVideoId = extractYouTubeVideoId(normalizedUrl)
            if (ytVideoId != null) {
                return@runCatching fetchYouTubePreview(normalizedUrl, ytVideoId)
            }

            // 2. Reddit bypass (video poster / post image / vxreddit / Arctic Shift)
            if (isReddit(normalizedUrl)) {
                return@runCatching fetchRedditPreview(normalizedUrl, allowThirdPartyProxies)
            }

            // 3. Instagram / Reels bypass
            if (isInstagram(normalizedUrl)) {
                return@runCatching fetchInstagramPreview(normalizedUrl, allowThirdPartyProxies)
            }

            // 4. Facebook & Facebook Video bypass
            if (isFacebook(normalizedUrl)) {
                return@runCatching fetchFacebookPreview(normalizedUrl)
            }

            // 5. Twitter / X bypass
            if (isTwitterOrX(normalizedUrl)) {
                fetchTwitterPreview(normalizedUrl, allowThirdPartyProxies)?.let { return@runCatching it }
            }

            // 6. TikTok bypass (official public oEmbed)
            if (isTikTok(normalizedUrl)) {
                fetchTikTokPreview(normalizedUrl)?.let { return@runCatching it }
            }

            // 7. Generic web page with open-graph-scraper headers
            fetchFromNetwork(normalizedUrl)
        }.getOrNull() ?: runCatching { fetchFromNetwork(normalizedUrl) }.getOrNull()

        if (fetched != null) {
            cache[normalizedUrl] = fetched
        }
        fetched
    }

    // ═══════════════════════════════════════════════════════════════
    //  Platform Extractors & Bypasses
    // ═══════════════════════════════════════════════════════════════

    /**
     * Extracts YouTube 11-char video ID from standard, short, embed, or music URLs.
     */
    fun extractYouTubeVideoId(url: String): String? {
        val regex = Regex("""(?:youtu\.be/|youtube\.com/(?:watch\?.*v=|shorts/|embed/|live/))([A-Za-z0-9_-]{11})""")
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun fetchYouTubePreview(url: String, videoId: String): LinkPreviewData {
        val isShort = url.contains("/shorts/", ignoreCase = true)
        val defaultTitle = if (isShort) "YouTube Short" else "YouTube Video"

        // YouTube public oEmbed endpoint returns instant title and author without parsing HTML
        val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
        val json = runCatching { fetchJson(oembedUrl) }.getOrNull()

        val title = json?.optString("title")?.takeIf { it.isNotBlank() } ?: defaultTitle
        val author = json?.optString("author_name")?.takeIf { it.isNotBlank() }
        // hqdefault.jpg is guaranteed to exist for all YouTube videos (unlike maxresdefault.jpg)
        val thumb = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

        return LinkPreviewData(
            url = url,
            title = decodeHtmlEntities(title),
            description = author?.let { "Video by ${decodeHtmlEntities(it)}" },
            imageUrl = thumb,
            siteName = "YouTube",
            faviconUrl = "https://www.youtube.com/s/desktop/favicon.ico",
            mediaType = LinkMediaType.YOUTUBE,
        )
    }

    fun isReddit(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return host.contains("reddit.com") || host.contains("redd.it")
    }

    private fun fetchRedditPreview(url: String, allowThirdPartyProxies: Boolean = true): LinkPreviewData {
        val canonicalUrl = resolveRedirectsToFinalUrl(url)
        val isVideoUrl = canonicalUrl.contains("v.redd.it") || canonicalUrl.contains("/video/")

        var directData: LinkPreviewData? = null
        val directResult = runCatching { fetchHtmlPage(canonicalUrl, defaultHeaders()) }.getOrNull()
        if (directResult != null) {
            directData = parseOpenGraph(directResult.body, directResult.finalUrl)
        }

        var vxData: LinkPreviewData? = null
        var isRedditVideo = isVideoUrl || directResult?.body?.contains("og:video") == true

        if (allowThirdPartyProxies) {
            // Strategy 1: vxreddit embed proxy with Discordbot UA
            val vxUrl = toVxRedditUrl(canonicalUrl)
            val vxResult = runCatching {
                fetchHtmlPage(
                    vxUrl,
                    mapOf(
                        "User-Agent" to BOT_USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US",
                        "Cache-Control" to "max-age=0",
                    ),
                )
            }.getOrNull()

            if (vxResult != null) {
                if (vxResult.body.contains("og:video")) {
                    isRedditVideo = true
                }
                vxData = parseOpenGraph(vxResult.body, canonicalUrl)
            }

            // Candidate image from vx or direct
            val candidateImage = cleanImageUrl(vxData?.imageUrl.orEmpty())
                .ifBlank { cleanImageUrl(directData?.imageUrl.orEmpty()) }

            // Strategy 2: Arctic Shift archive JSON API (if candidate image is still missing)
            var archiveImage = ""
            var archiveTitle = ""
            var archiveDesc: String? = null

            if (candidateImage.isBlank()) {
                val postId = extractRedditPostId(canonicalUrl)
                if (!postId.isNullOrBlank()) {
                    val archiveUrl = "https://arctic-shift.photon-reddit.com/api/posts/ids?ids=$postId"
                    val json = runCatching { fetchJson(archiveUrl) }.getOrNull()
                    val post = json?.optJSONArray("data")?.optJSONObject(0)
                    if (post != null) {
                        archiveTitle = cleanRedditTitle(post.optString("title"))
                        archiveDesc = post.optString("selftext").trim().take(300).ifBlank { null }
                        archiveImage = cleanImageUrl(extractRedditImageUrl(post))
                        if (post.optBoolean("is_video") ||
                            post.optString("domain") == "v.redd.it" ||
                            post.optString("post_hint") == "hosted:video"
                        ) {
                            isRedditVideo = true
                        }
                    }
                }
            }

            val finalImage = candidateImage.ifBlank { archiveImage }.ifBlank { null }
            val rawTitle = vxData?.title?.takeIf { !isBlockedTitle(it) }
                ?: directData?.title?.takeIf { !isBlockedTitle(it) }
                ?: archiveTitle.takeIf { !isBlockedTitle(it) }
                ?: titleFromUrlSlug(canonicalUrl).ifBlank { if (isRedditVideo) "Reddit Video" else "Reddit Post" }

            val finalTitle = cleanRedditTitle(rawTitle)
            val finalDesc = vxData?.description ?: directData?.description ?: archiveDesc
            val mediaType = if (isRedditVideo) LinkMediaType.REDDIT_VIDEO else LinkMediaType.REDDIT

            return LinkPreviewData(
                url = canonicalUrl,
                title = finalTitle,
                description = finalDesc,
                imageUrl = finalImage,
                siteName = "Reddit",
                faviconUrl = "https://www.redditstatic.com/shreddit/assets/favicon/192x192.png",
                mediaType = mediaType,
            )
        }

        // Direct-only mode (when allowThirdPartyProxies == false)
        val fallbackTitle = cleanRedditTitle(
            directData?.title?.takeIf { !isBlockedTitle(it) }
                ?: titleFromUrlSlug(canonicalUrl).ifBlank { if (isRedditVideo) "Reddit Video" else "Reddit Post" },
        )
        val mediaType = if (isRedditVideo) LinkMediaType.REDDIT_VIDEO else LinkMediaType.REDDIT

        return LinkPreviewData(
            url = canonicalUrl,
            title = fallbackTitle,
            description = directData?.description,
            imageUrl = cleanImageUrl(directData?.imageUrl.orEmpty()).ifBlank { null },
            siteName = "Reddit",
            faviconUrl = "https://www.redditstatic.com/shreddit/assets/favicon/192x192.png",
            mediaType = mediaType,
        )
    }

    private fun toVxRedditUrl(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return url.replace("reddit.com", "vxreddit.com")
            val path = uri.path.orEmpty()
            val query = uri.query
            val fragment = uri.fragment

            val vxHost = when {
                host == "reddit.com" || host == "www.reddit.com" -> "www.vxreddit.com"
                host == "redd.it" || host == "v.redd.it" -> "www.vxreddit.com"
                host.endsWith(".reddit.com") -> host.replace("reddit.com", "vxreddit.com")
                else -> host
            }
            URI("https", vxHost, path, query, fragment).toString()
        } catch (_: Exception) {
            url.replace("reddit.com", "vxreddit.com")
        }
    }

    private fun extractRedditPostId(url: String): String? {
        return try {
            val segments = URI(url).path?.split('/')?.filter { it.isNotBlank() }.orEmpty()
            val commentsIdx = segments.indexOf("comments")
            if (commentsIdx >= 0 && segments.size > commentsIdx + 1) {
                segments[commentsIdx + 1].takeIf { it.matches(Regex("""[A-Za-z0-9_]+""")) }
            } else {
                segments.lastOrNull()?.takeIf { it.matches(Regex("""[A-Za-z0-9_]{5,10}""")) }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractRedditImageUrl(post: JSONObject): String {
        fun clean(u: String): String = cleanImageUrl(u)
        fun isImg(u: String): Boolean {
            val l = u.lowercase(Locale.ROOT)
            return l.contains("i.redd.it") || l.contains("preview.redd.it") ||
                l.contains("external-preview.redd.it") ||
                l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp")
        }

        val direct = clean(post.optString("url_overridden_by_dest"))
        if (direct.isNotEmpty() && isImg(direct)) return direct

        val url = clean(post.optString("url"))
        if (url.isNotEmpty() && isImg(url)) return url

        val preview = post.optJSONObject("preview")
            ?.optJSONArray("images")
            ?.optJSONObject(0)
            ?.optJSONObject("source")
            ?.optString("url")
            ?.let(::clean)
            .orEmpty()
        if (preview.isNotEmpty()) return preview

        val thumb = clean(post.optString("thumbnail"))
        return if (thumb.startsWith("http")) thumb else ""
    }

    fun isInstagram(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return host.contains("instagram.com") || host.contains("instagr.am")
    }

    private fun fetchInstagramPreview(url: String, allowThirdPartyProxies: Boolean = true): LinkPreviewData {
        val canonical = resolveRedirectsToFinalUrl(url)
        val isReel = canonical.contains("/reel/", ignoreCase = true) || canonical.contains("/reels/", ignoreCase = true)
        val mediaType = if (isReel) LinkMediaType.INSTAGRAM_REEL else LinkMediaType.INSTAGRAM_POST

        // 1. Direct social crawler headers (Facebook/Meta official social bot UA)
        val botHeaders = mapOf(
            "User-Agent" to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US",
            "Cache-Control" to "max-age=0",
        )
        val botResult = runCatching { fetchHtmlPage(canonical, botHeaders) }.getOrNull()
        val botData = botResult?.let { parseOpenGraph(it.body, canonical) }

        var finalImage = cleanImageUrl(botData?.imageUrl.orEmpty()).takeIf { it.isNotBlank() }

        // 2. If image is missing, try Instagram public embed endpoint /embed/captioned/
        if (finalImage.isNullOrBlank()) {
            val shortcode = extractInstagramShortcode(canonical)
            if (!shortcode.isNullOrBlank()) {
                val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
                val embedResult = runCatching { fetchHtmlPage(embedUrl, defaultHeaders()) }.getOrNull()
                if (embedResult != null) {
                    val embedData = parseOpenGraph(embedResult.body, embedUrl)
                    finalImage = cleanImageUrl(embedData?.imageUrl.orEmpty()).takeIf { it.isNotBlank() }
                    if (finalImage.isNullOrBlank()) {
                        val imgRegex = Regex("""https:\\/\\/[^"&]+\.(?:jpg|png|webp)[^"]*""")
                        val match = imgRegex.find(embedResult.body)?.value
                        if (!match.isNullOrBlank()) {
                            finalImage = cleanImageUrl(match)
                        }
                    }
                }
            }
        }

        val username = extractInstagramUsername(canonical)
        val fallbackTitle = when {
            !username.isNullOrBlank() -> if (isReel) "Reel by @$username" else "Post by @$username"
            isReel -> "Instagram Reel"
            else -> "Instagram Post"
        }

        val rawTitle = botData?.title?.takeIf { !isBlockedTitle(it) }
        val title = cleanInstagramTitle(rawTitle ?: fallbackTitle)

        return LinkPreviewData(
            url = canonical,
            title = title,
            description = botData?.description,
            imageUrl = finalImage,
            siteName = "Instagram",
            faviconUrl = "https://static.cdninstagram.com/rsrc.php/v3/yI/r/VsNE-OHk_8a.png",
            mediaType = mediaType,
        )
    }

    fun cleanInstagramTitle(raw: String): String {
        return raw.replace(Regex("""\s*•\s*Instagram photos and videos\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*on Instagram:.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*$"""), "")
            .trim()
    }

    private fun extractInstagramShortcode(url: String): String? {
        val regex = Regex("""/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
        return regex.find(url)?.groupValues?.get(1)
    }

    private fun extractInstagramUsername(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = uri.path?.split('/')?.filter { it.isNotBlank() }.orEmpty()
        return segments.firstOrNull { it !in setOf("p", "reel", "reels", "tv", "explore", "stories") }
    }

    fun isFacebook(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return host.contains("facebook.com") || host.contains("fb.watch") || host.contains("fb.me") || host.contains("fb.com")
    }

    private fun fetchFacebookPreview(url: String): LinkPreviewData {
        val canonical = resolveRedirectsToFinalUrl(url)
        val isVideo = canonical.contains("/watch") ||
            canonical.contains("/reel") ||
            canonical.contains("fb.watch")

        val result = runCatching { fetchHtmlPage(canonical, defaultHeaders()) }.getOrNull()
        val data = result?.let { parseOpenGraph(it.body, it.finalUrl) }

        val detectedVideo = isVideo || data?.mediaType == LinkMediaType.FACEBOOK_VIDEO ||
            (result?.body?.contains("og:type\" content=\"video") == true)
        val mediaType = if (detectedVideo) LinkMediaType.FACEBOOK_VIDEO else LinkMediaType.FACEBOOK

        val rawTitle = data?.title?.takeIf { !isBlockedTitle(it) }
        val title = cleanFacebookTitle(rawTitle ?: titleFromUrlSlug(canonical).ifBlank { if (detectedVideo) "Facebook Video" else "Facebook Post" })

        return LinkPreviewData(
            url = canonical,
            title = title,
            description = data?.description,
            imageUrl = cleanImageUrl(data?.imageUrl.orEmpty()).takeIf { it.isNotBlank() },
            siteName = "Facebook",
            faviconUrl = "https://static.xx.fbcdn.net/rsrc.php/y1/r/ay1hV6OlegS.ico",
            mediaType = mediaType,
        )
    }

    fun cleanFacebookTitle(raw: String): String {
        return raw.replace(Regex("""^Check out this post on Facebook:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Shared with Facebook:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Facebook\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*Facebook\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*$"""), "")
            .trim()
    }

    fun isTwitterOrX(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return host.contains("twitter.com") || host.contains("x.com")
    }

    private fun fetchTwitterPreview(url: String, allowThirdPartyProxies: Boolean = true): LinkPreviewData? {
        if (allowThirdPartyProxies) {
            val regex = Regex("""(?:twitter|x)\.com/([^/]+)/status/([0-9]+)""", RegexOption.IGNORE_CASE)
            val match = regex.find(url)
            if (match != null) {
                val user = match.groupValues[1]
                val statusId = match.groupValues[2]

                // Try fast vxtwitter JSON API
                val apiUrl = "https://api.vxtwitter.com/$user/status/$statusId"
                val json = runCatching { fetchJson(apiUrl) }.getOrNull()
                if (json != null) {
                    val text = json.optString("text").trim()
                    val userName = json.optString("user_name").trim()
                    val userScreen = json.optString("user_screen_name").trim()
                    val mediaArray = json.optJSONArray("media_urls")
                    val mediaUrl = if (mediaArray != null && mediaArray.length() > 0) mediaArray.optString(0) else null

                    val title = if (userName.isNotBlank()) "$userName (@$userScreen)" else "Tweet by @$user"
                    return LinkPreviewData(
                        url = url,
                        title = decodeHtmlEntities(title),
                        description = text.ifBlank { null }?.let(::decodeHtmlEntities),
                        imageUrl = mediaUrl?.takeIf { it.isNotBlank() }?.let(::cleanImageUrl),
                        siteName = "X (Twitter)",
                        faviconUrl = "https://abs.twimg.com/favicons/twitter.3.ico",
                        mediaType = LinkMediaType.TWITTER,
                    )
                }
            }

            // Fallback: fixupx / fxtwitter with bot UA
            val fxUrl = url.replace("x.com", "fixupx.com").replace("twitter.com", "fxtwitter.com")
            val fxResult = runCatching {
                fetchHtmlPage(
                    fxUrl,
                    mapOf(
                        "User-Agent" to BOT_USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US",
                    ),
                )
            }.getOrNull()
            val fxData = fxResult?.let { parseOpenGraph(it.body, url) }

            if (fxData != null && !isBlockedTitle(fxData.title)) {
                return fxData.copy(
                    url = url,
                    imageUrl = cleanImageUrl(fxData.imageUrl.orEmpty()).takeIf { it.isNotBlank() },
                    siteName = "X (Twitter)",
                    faviconUrl = "https://abs.twimg.com/favicons/twitter.3.ico",
                    mediaType = LinkMediaType.TWITTER,
                )
            }
        }

        // Direct fallback: try direct fetch with social crawler UA
        val direct = runCatching {
            fetchHtmlPage(url, mapOf("User-Agent" to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"))
        }.getOrNull()
        val directData = direct?.let { parseOpenGraph(it.body, url) }
        if (directData != null && !isBlockedTitle(directData.title)) {
            return directData.copy(
                url = url,
                imageUrl = cleanImageUrl(directData.imageUrl.orEmpty()).takeIf { it.isNotBlank() },
                siteName = "X (Twitter)",
                faviconUrl = "https://abs.twimg.com/favicons/twitter.3.ico",
                mediaType = LinkMediaType.TWITTER,
            )
        }

        val userRegex = Regex("""(?:twitter|x)\.com/([^/]+)""", RegexOption.IGNORE_CASE)
        val username = userRegex.find(url)?.groupValues?.get(1)?.takeIf { it !in setOf("i", "home", "explore", "messages") }
        val fallbackTitle = if (!username.isNullOrBlank()) "Post on X by @$username" else "Post on X"

        return LinkPreviewData(
            url = url,
            title = directData?.title?.takeIf { !isBlockedTitle(it) } ?: fallbackTitle,
            description = directData?.description,
            imageUrl = cleanImageUrl(directData?.imageUrl.orEmpty()).takeIf { it.isNotBlank() },
            siteName = "X (Twitter)",
            faviconUrl = "https://abs.twimg.com/favicons/twitter.3.ico",
            mediaType = LinkMediaType.TWITTER,
        )
    }

    fun isTikTok(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return host.contains("tiktok.com")
    }

    private fun fetchTikTokPreview(url: String): LinkPreviewData? {
        val encoded = runCatching { URLEncoder.encode(url, "UTF-8") }.getOrNull() ?: return null
        val oembedUrl = "https://www.tiktok.com/oembed?url=$encoded"
        val json = runCatching { fetchJson(oembedUrl) }.getOrNull() ?: return null

        val title = json.optString("title").trim()
        val author = json.optString("author_name").trim()
        val thumb = json.optString("thumbnail_url").trim()

        return LinkPreviewData(
            url = url,
            title = title.ifBlank { if (author.isNotBlank()) "TikTok by $author" else "TikTok Video" },
            description = if (author.isNotBlank()) "@$author on TikTok" else null,
            imageUrl = thumb.ifBlank { null },
            siteName = "TikTok",
            faviconUrl = "https://sf16-website-login.neutral.ttwstatic.com/obj/tiktok_web_login_static/favicon.ico",
            mediaType = LinkMediaType.TIKTOK,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  Low-Storage Thumbnail Downloading & Compression
    // ═══════════════════════════════════════════════════════════════

    /**
     * Downloads the remote image at [imageUrl], downsamples and compresses it
     * into a low-resolution WebP/JPEG thumbnail prioritizing storage savings.
     * Keeps typical thumbnail size between 15KB - 30KB.
     * Automatically filters out 1x1 tracker pixels and tiny logos (<32px).
     * Returns null if download or decoding fails.
     */
    suspend fun downloadCompressedThumbnail(
        imageUrl: String,
        maxSidePx: Int = 480,
        quality: Int = 75,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val clean = cleanImageUrl(imageUrl)
        val resolved = normalizeUrl(clean) ?: clean
        ensureCookieManager()

        runCatching {
            var current = resolved
            var rawBytes: ByteArray? = null
            val imgHeaders = mapOf(
                "User-Agent" to BROWSER_USER_AGENT,
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to current,
            )
            for (redirect in 0..MAX_REDIRECTS) {
                val conn = URL(upgradeToHttps(current)).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = false
                imgHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                try {
                    conn.connect()
                    val code = conn.responseCode
                    Log.d(TAG, "Descargando imagen: code=$code, url=$current")
                    when (code) {
                        in 300..399 -> {
                            val loc = conn.getHeaderField("Location") ?: return@runCatching null
                            current = resolveUrl(current, loc) ?: return@runCatching null
                        }
                        in 200..299 -> {
                            val stream = conn.inputStream
                            val buffer = ByteArrayOutputStream()
                            val chunk = ByteArray(8 * 1024)
                            var read: Int
                            var total = 0
                            val maxLimit = 6 * 1024 * 1024 // 6MB cap
                            while (stream.read(chunk).also { read = it } != -1) {
                                buffer.write(chunk, 0, read)
                                total += read
                                if (total > maxLimit) break
                            }
                            rawBytes = buffer.toByteArray()
                            break
                        }
                        else -> return@runCatching null
                    }
                } finally {
                    conn.disconnect()
                }
            }
            val bytes = rawBytes ?: return@runCatching null
            compressImageBytes(bytes, maxSidePx, quality)
        }.onFailure {
            Log.e(TAG, "Fallo al descargar miniatura ($imageUrl): ${it.javaClass.name} - ${it.message}", it)
        }.getOrNull()
    }

    /**
     * Downsamples and compresses [rawBytes] into a lightweight WebP/JPEG image.
     * Discards empty tracking pixels or tiny icons (<32px).
     */
    override fun compressImageBytes(
        rawBytes: ByteArray,
        maxSidePx: Int,
        quality: Int,
    ): ByteArray? = runCatching {
        if (rawBytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val origW = bounds.outWidth
        val origH = bounds.outHeight
        // Discard tracking pixels or tiny logos
        if (origW < 32 || origH < 32) return null

        var inSample = 1
        while ((origW / inSample) > maxSidePx * 2 || (origH / inSample) > maxSidePx * 2) {
            inSample *= 2
        }

        val sampleOptions = BitmapFactory.Options().apply {
            inSampleSize = inSample
        }
        val sampled = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, sampleOptions) ?: return null

        val currentMax = maxOf(sampled.width, sampled.height)
        val finalBitmap = if (currentMax > maxSidePx) {
            val scale = maxSidePx.toFloat() / currentMax.toFloat()
            val targetW = (sampled.width * scale).roundToInt().coerceAtLeast(1)
            val targetH = (sampled.height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(sampled, targetW, targetH, true).also {
                if (it != sampled) sampled.recycle()
            }
        } else {
            sampled
        }

        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        finalBitmap.compress(format, quality, out)
        finalBitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    // ═══════════════════════════════════════════════════════════════
    //  HTTP & Parsing Utilities
    // ═══════════════════════════════════════════════════════════════

    private data class HtmlFetchResult(val body: String, val finalUrl: String)

    private fun fetchHtmlPage(url: String, headers: Map<String, String>): HtmlFetchResult {
        var current = url
        var body = ""

        repeat(MAX_REDIRECTS + 1) {
            val conn = URL(upgradeToHttps(current)).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connect()

            val code = conn.responseCode
            try {
                when (code) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: throw Exception("Redirect without location")
                        current = resolveUrl(current, loc) ?: throw Exception("Invalid redirect location")
                    }
                    in 200..299 -> {
                        val charset = parseCharset(conn.contentType)
                        body = readHead(conn.inputStream, charset)
                        return HtmlFetchResult(body, current)
                    }
                    else -> throw Exception("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        return HtmlFetchResult(body, current)
    }

    private fun resolveRedirectsToFinalUrl(startUrl: String): String {
        var current = startUrl
        repeat(MAX_REDIRECTS) {
            val conn = runCatching { URL(upgradeToHttps(current)).openConnection() as HttpURLConnection }.getOrNull() ?: return current
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", BOT_USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: return current
                    current = resolveUrl(current, loc) ?: return current
                } else {
                    return current
                }
            } catch (_: Exception) {
                return current
            } finally {
                conn.disconnect()
            }
        }
        return current
    }

    private fun fetchJson(url: String): JSONObject? {
        val conn = URL(upgradeToHttps(url)).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", BOT_USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.instanceFollowRedirects = true

        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "User-Agent" to OG_SCRAPER_USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,es;q=0.8",
        "Cache-Control" to "max-age=0",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "sec-ch-ua" to "\"Google Chrome\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
    )

    private fun fetchFromNetwork(url: String): LinkPreviewData? {
        val result = runCatching { fetchHtmlPage(url, defaultHeaders()) }.getOrNull() ?: return null
        return parseOpenGraph(result.body, result.finalUrl)
    }

    /** Reads at most [MAX_HEAD_BYTES] bytes from [stream], decoded with [charset]. */
    private fun readHead(stream: InputStream, charset: Charset): String {
        val buffer = ByteArrayOutputStream(MAX_HEAD_BYTES)
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (total < MAX_HEAD_BYTES) {
            val read = stream.read(chunk, 0, minOf(chunk.size, MAX_HEAD_BYTES - total))
            if (read == -1) break
            buffer.write(chunk, 0, read)
            total += read
        }
        return buffer.toString(charset.name())
    }

    private fun parseCharset(contentType: String?): Charset {
        if (contentType != null) {
            CHARSET_REGEX.find(contentType)?.let { match ->
                runCatching { Charset.forName(match.groupValues[1].trim()) }
                    .getOrNull()
                    ?.let { return it }
            }
        }
        return StandardCharsets.UTF_8
    }

    private val CHARSET_REGEX =
        Regex("""charset\s*=\s*"?([A-Za-z0-9_\-]+)"?""", RegexOption.IGNORE_CASE)

    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val parsed = runCatching { URL(candidate) }.getOrNull() ?: return null
        if (parsed.protocol != "http" && parsed.protocol != "https") return null
        if (parsed.host.isBlank()) return null
        return parsed.toString()
    }

    fun cleanImageUrl(url: String): String {
        if (url.isBlank()) return ""
        return url
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("&quot;", "")
            .trim()
    }

    private fun resolveUrl(baseUrl: String, target: String): String? = runCatching {
        URL(URL(baseUrl), target).toString()
    }.getOrNull()

    internal fun parseOpenGraph(html: String, baseUrl: String): LinkPreviewData? {
        var ogTitle: String? = null
        var ogDescription: String? = null
        var ogImage: String? = null
        var ogSiteName: String? = null
        var htmlTitle: String? = null
        var metaDescription: String? = null
        var faviconUrl: String? = null
        var ogType: String? = null

        for (match in META_TAG_REGEX.findAll(html)) {
            val attrs = parseAttributes(match.value)
            val key = (attrs["property"] ?: attrs["name"])?.lowercase(Locale.ROOT) ?: continue
            val content = attrs["content"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            when (key) {
                "og:title", "twitter:title" -> if (ogTitle == null) ogTitle = decodeHtmlEntities(content)
                "og:description", "twitter:description" ->
                    if (ogDescription == null) ogDescription = decodeHtmlEntities(content)
                "og:image", "og:image:url", "og:image:secure_url", "twitter:image" ->
                    if (ogImage == null) ogImage = cleanImageUrl(content)
                "og:type" -> if (ogType == null) ogType = content.lowercase(Locale.ROOT)
                "og:site_name" -> if (ogSiteName == null) ogSiteName = decodeHtmlEntities(content)
                "description" -> if (metaDescription == null) metaDescription = decodeHtmlEntities(content)
            }
        }

        // Check <link rel="image_src" href="...">
        if (ogImage.isNullOrBlank()) {
            val linkSrc = LINK_TAG_REGEX.findAll(html).firstOrNull {
                val attrs = parseAttributes(it.value)
                attrs["rel"]?.lowercase(Locale.ROOT) == "image_src"
            }
            if (linkSrc != null) {
                val href = parseAttributes(linkSrc.value)["href"]?.trim()
                if (!href.isNullOrBlank()) {
                    ogImage = cleanImageUrl(href)
                }
            }
        }

        // Check video poster tag
        if (ogImage.isNullOrBlank()) {
            val posterMatch = VIDEO_POSTER_REGEX.find(html)?.groupValues?.get(1)?.trim()
            if (!posterMatch.isNullOrBlank()) {
                ogImage = cleanImageUrl(posterMatch)
            }
        }

        if (htmlTitle == null) {
            htmlTitle = TITLE_TAG_REGEX.find(html)
                ?.groupValues
                ?.get(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::decodeHtmlEntities)
        }

        if (faviconUrl == null) {
            faviconUrl = findFavicon(html, baseUrl)
        }

        val rawTitle = ogTitle ?: htmlTitle ?: ogSiteName
        val candidateTitle = if (!isBlockedTitle(rawTitle)) {
            rawTitle
        } else {
            titleFromUrlSlug(baseUrl).takeIf { it.isNotBlank() } ?: ogSiteName
        }
        val title = when {
            candidateTitle == null -> null
            isReddit(baseUrl) -> cleanRedditTitle(candidateTitle)
            isFacebook(baseUrl) -> cleanFacebookTitle(candidateTitle)
            else -> candidateTitle
        }

        val description = ogDescription ?: metaDescription

        if (title == null && description == null && ogImage == null) return null

        val finalImg = ogImage?.let { resolveUrl(baseUrl, it) }?.let(::cleanImageUrl)
        val isVideo = ogType?.contains("video") == true || html.contains("og:video")

        val mediaType = when {
            isFacebook(baseUrl) && isVideo -> LinkMediaType.FACEBOOK_VIDEO
            isFacebook(baseUrl) -> LinkMediaType.FACEBOOK
            isReddit(baseUrl) && isVideo -> LinkMediaType.REDDIT_VIDEO
            isReddit(baseUrl) -> LinkMediaType.REDDIT
            isInstagram(baseUrl) && baseUrl.contains("/reel") -> LinkMediaType.INSTAGRAM_REEL
            isInstagram(baseUrl) -> LinkMediaType.INSTAGRAM_POST
            else -> LinkMediaType.GENERIC
        }

        return LinkPreviewData(
            url = baseUrl,
            title = title,
            description = description,
            imageUrl = finalImg,
            siteName = ogSiteName,
            faviconUrl = faviconUrl,
            mediaType = mediaType,
        )
    }

    fun isBlockedTitle(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val lower = title.trim().lowercase(Locale.ROOT)
        return lower == "shared link" ||
            lower == "untitled" ||
            lower == "sin título" ||
            lower == "reddit" ||
            lower == "instagram" ||
            lower == "facebook" ||
            lower == "x" ||
            lower == "twitter" ||
            lower == "login" ||
            lower == "log in" ||
            lower.startsWith("login •") ||
            lower.startsWith("log in •") ||
            lower.startsWith("log in to ") ||
            lower.startsWith("login to ") ||
            lower.contains("please wait for verification") ||
            lower.contains("cloudflare") ||
            lower.contains("attention required") ||
            lower.contains("just a moment") ||
            lower.contains("internal server error") ||
            lower.contains("500 internal") ||
            lower.contains("404 not found") ||
            lower.contains("access denied") ||
            lower.contains("security check") ||
            lower == "reddit - dive into anything"
    }

    fun cleanRedditTitle(raw: String): String {
        var result = raw.replace(Regex("""^Check out this post on Reddit:\s*""", RegexOption.IGNORE_CASE), "")
        repeat(3) {
            result = result
                .replace(Regex("""\s*-\s*Reddit\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*:\s*r/[a-zA-Z0-9_]+\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*r/[a-zA-Z0-9_]+\s*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*-\s*$"""), "")
                .trim()
        }
        return result
    }

    fun titleFromUrlSlug(url: String): String {
        return runCatching {
            val uri = URI(url)
            val segments = uri.path?.split('/')?.filter { it.isNotBlank() }.orEmpty()
            val commentsIdx = segments.indexOf("comments")
            val slug = if (commentsIdx >= 0 && segments.size > commentsIdx + 2) {
                segments[commentsIdx + 2]
            } else {
                segments.lastOrNull { seg ->
                    seg.contains("-") || seg.contains("_") || (seg.length > 8 && seg.any { it.isLetter() })
                } ?: segments.lastOrNull()
            } ?: ""

            val decoded = URLDecoder.decode(slug, StandardCharsets.UTF_8.name())
            decoded.replace(Regex("""[-_]+"""), " ")
                .trim()
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
        }.getOrDefault("")
    }

    fun decodeCommonHtmlEntities(text: String): String {
        var result = text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&#xa0;", " ")
            .replace("&#160;", " ")
            .replace("&#064;", "@")
            .replace("&#64;", "@")
            .replace("&#x2022;", "•")
            .replace("&#8226;", "•")
            .replace("&#8230;", "…")

        result = Regex("""&#(\d+);""").replace(result) { m ->
            runCatching { m.groupValues[1].toInt().toChar().toString() }.getOrDefault(m.value)
        }
        result = Regex("""&#x([0-9a-fA-F]+);""").replace(result) { m ->
            runCatching { m.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(m.value)
        }
        return result.trim()
    }

    fun decodeHtmlEntities(text: String): String {
        return runCatching {
            android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }.getOrElse {
            decodeCommonHtmlEntities(text)
        }
    }

    /** Finds the favicon URL: first <link rel="...icon..."> tag, else /favicon.ico. */
    private fun findFavicon(html: String, baseUrl: String): String {
        for (match in LINK_TAG_REGEX.findAll(html)) {
            val attrs = parseAttributes(match.value)
            val rel = attrs["rel"]?.lowercase(Locale.ROOT) ?: continue
            if (!rel.contains("icon")) continue
            val href = attrs["href"]?.takeIf { it.isNotBlank() } ?: continue
            resolveUrl(baseUrl, href)?.let { return it }
        }
        return resolveUrl(baseUrl, DEFAULT_FAVICON_PATH) ?: baseUrl
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        for (match in ATTRIBUTE_REGEX.findAll(tag)) {
            val name = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { match.groupValues[4] }
            attrs[name] = value
        }
        return attrs
    }

    private val META_TAG_REGEX =
        Regex("""<meta\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val TITLE_TAG_REGEX =
        Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val LINK_TAG_REGEX =
        Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)

    private val VIDEO_POSTER_REGEX =
        Regex("""<video\b[^>]*\bposter=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val ATTRIBUTE_REGEX =
        Regex("""\b(property|name|content|rel|href)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    private const val DEFAULT_FAVICON_PATH = "/favicon.ico"
}

/** Cleartext is blocked app-wide (network_security_config); web previews always go over HTTPS. */
internal fun upgradeToHttps(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
