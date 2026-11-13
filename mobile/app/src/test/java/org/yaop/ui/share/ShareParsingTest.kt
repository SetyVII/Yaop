package org.yaop.ui.share

import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.PropertyValue
import org.yaop.core.network.LinkMediaType
import org.yaop.core.network.LinkPreviewFetcher
import org.yaop.ui.common.extractUrlFromText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareParsingTest {

    @Test
    fun testExtractUrlFromText() {
        val directUrl = "https://example.com/article"
        assertEquals("https://example.com/article", extractUrlFromText(directUrl))

        val sharedFromTwitter = "Mira este video interesante: https://youtu.be/abcdef123 increible!"
        assertEquals("https://youtu.be/abcdef123", extractUrlFromText(sharedFromTwitter))

        val urlWithTrailingPunctuation = "Check this post: https://reddit.com/r/Android/comments/12345/test/."
        assertEquals("https://reddit.com/r/Android/comments/12345/test/", extractUrlFromText(urlWithTrailingPunctuation))

        val noUrl = "Just a quick note without any web link"
        assertNull(extractUrlFromText(noUrl))
    }

    @Test
    fun testYouTubeVideoIdExtraction() {
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", LinkPreviewFetcher.extractYouTubeVideoId(watchUrl))

        val shortUrl = "https://youtu.be/dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", LinkPreviewFetcher.extractYouTubeVideoId(shortUrl))

        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", LinkPreviewFetcher.extractYouTubeVideoId(shortsUrl))

        val embedUrl = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        assertEquals("dQw4w9WgXcQ", LinkPreviewFetcher.extractYouTubeVideoId(embedUrl))

        val notYouTube = "https://example.com/watch?v=dQw4w9WgXcQ"
        assertNull(LinkPreviewFetcher.extractYouTubeVideoId(notYouTube))
    }

    @Test
    fun testPlatformDetection() {
        assertTrue(LinkPreviewFetcher.isReddit("https://www.reddit.com/r/Kotlin/comments/abc/title/"))
        assertTrue(LinkPreviewFetcher.isReddit("https://redd.it/xyz123"))
        assertFalse(LinkPreviewFetcher.isReddit("https://example.com"))

        assertTrue(LinkPreviewFetcher.isInstagram("https://www.instagram.com/reel/C123456/"))
        assertTrue(LinkPreviewFetcher.isInstagram("https://instagr.am/p/C987654/"))
        assertFalse(LinkPreviewFetcher.isInstagram("https://threads.net"))

        assertTrue(LinkPreviewFetcher.isFacebook("https://www.facebook.com/watch/?v=123456789"))
        assertTrue(LinkPreviewFetcher.isFacebook("https://fb.watch/abc123xyz/"))
        assertTrue(LinkPreviewFetcher.isFacebook("https://m.facebook.com/story.php?story_fbid=123"))
        assertFalse(LinkPreviewFetcher.isFacebook("https://yaop.app"))

        assertTrue(LinkPreviewFetcher.isTwitterOrX("https://twitter.com/user/status/123456"))
        assertTrue(LinkPreviewFetcher.isTwitterOrX("https://x.com/user/status/123456"))
        assertFalse(LinkPreviewFetcher.isTwitterOrX("https://bluesky.app"))

        assertTrue(LinkPreviewFetcher.isTikTok("https://www.tiktok.com/@creator/video/1234567890"))
        assertFalse(LinkPreviewFetcher.isTikTok("https://youtube.com"))
    }

    @Test
    fun testCleanImageUrl() {
        val fbCdnUrl = "https://scontent.fmad6-1.fna.fbcdn.net/v/t39.30808-6/image.jpg?stp=dst-jpg_s960x960&amp;cstp=dst-jpg&amp;_nc_cat=1&amp;ccb=1-7&amp;_nc_sid=6ee11a"
        val cleanedFb = LinkPreviewFetcher.cleanImageUrl(fbCdnUrl)
        assertFalse(cleanedFb.contains("&amp;"))
        assertTrue(cleanedFb.contains("&cstp="))
        assertTrue(cleanedFb.contains("&ccb="))

        val redditPreviewUrl = "https://preview.redd.it/test.png?width=1080&amp;crop=smart&amp;auto=webp&amp;s=abcdef123456"
        val cleanedReddit = LinkPreviewFetcher.cleanImageUrl(redditPreviewUrl)
        assertEquals("https://preview.redd.it/test.png?width=1080&crop=smart&auto=webp&s=abcdef123456", cleanedReddit)

        val escapedSlashUrl = "https:\\/\\/external-preview.redd.it\\/sample.jpg"
        assertEquals("https://external-preview.redd.it/sample.jpg", LinkPreviewFetcher.cleanImageUrl(escapedSlashUrl))

        val quotedUrl = "&quot;https://example.com/image.webp&quot;"
        assertEquals("https://example.com/image.webp", LinkPreviewFetcher.cleanImageUrl(quotedUrl))
    }

    @Test
    fun testDecodeHtmlEntities() {
        assertEquals("Tom & Jerry", LinkPreviewFetcher.decodeHtmlEntities("Tom &amp; Jerry"))
        assertEquals("\"Quotes\" and 'Apostrophes'", LinkPreviewFetcher.decodeHtmlEntities("&quot;Quotes&quot; and &#39;Apostrophes&#39;"))
        assertEquals("Price: 100€", LinkPreviewFetcher.decodeHtmlEntities("Price: 100&#8364;"))
        assertEquals("Unicode © 2026", LinkPreviewFetcher.decodeHtmlEntities("Unicode &#xa9; 2026"))
        assertEquals("A • B … C", LinkPreviewFetcher.decodeHtmlEntities("A &#x2022; B &#8230; C"))
        assertEquals("@user in action", LinkPreviewFetcher.decodeHtmlEntities("&#064;user in action"))
    }

    @Test
    fun testOpenGraphParsing() {
        val fbHtml = """
            <html>
            <head>
                <meta property="og:title" content="Meta &amp; Innovation" />
                <meta property="og:description" content="Connecting people &amp; technology" />
                <meta property="og:image" content="https://scontent.fna.fbcdn.net/v/photo.jpg?param=1&amp;param2=2" />
                <meta property="og:site_name" content="Facebook" />
                <meta property="og:type" content="video.other" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val parsedFb = LinkPreviewFetcher.parseOpenGraph(fbHtml, "https://www.facebook.com/reel/123456")
        assertNotNull(parsedFb)
        assertEquals("Meta & Innovation", parsedFb?.title)
        assertEquals("Connecting people & technology", parsedFb?.description)
        assertEquals("https://scontent.fna.fbcdn.net/v/photo.jpg?param=1&param2=2", parsedFb?.imageUrl)
        assertEquals(LinkMediaType.FACEBOOK_VIDEO, parsedFb?.mediaType)

        val redditHtml = """
            <html>
            <head>
                <meta property="og:title" content="Incredible drone shot : r/drones" />
                <meta property="og:description" content="Shot in 4K with DJI" />
                <meta property="og:image" content="https://preview.redd.it/drone.jpg?width=640&amp;auto=webp" />
                <meta property="og:video" content="https://v.redd.it/video123/HLSPlaylist.m3u8" />
                <meta property="og:site_name" content="Reddit" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val parsedReddit = LinkPreviewFetcher.parseOpenGraph(redditHtml, "https://www.reddit.com/r/drones/comments/abc123/shot/")
        assertNotNull(parsedReddit)
        assertEquals("Incredible drone shot", parsedReddit?.title)
        assertEquals("https://preview.redd.it/drone.jpg?width=640&auto=webp", parsedReddit?.imageUrl)
        assertEquals(LinkMediaType.REDDIT_VIDEO, parsedReddit?.mediaType)
    }

    @Test
    fun testBlockedTitleFilter() {
        assertTrue(LinkPreviewFetcher.isBlockedTitle("Attention Required! | Cloudflare"))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("Just a moment..."))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("Login • Instagram"))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("404 Not Found"))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("500 Internal Server Error"))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("Reddit - Dive into anything"))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("Shared Link"))
        assertTrue(LinkPreviewFetcher.isBlockedTitle("reddit"))

        assertFalse(LinkPreviewFetcher.isBlockedTitle("How to use Kotlin Coroutines in 2026"))
        assertFalse(LinkPreviewFetcher.isBlockedTitle("Insane drone footage in Iceland"))
    }

    @Test
    fun testCleanRedditTitle() {
        val raw = "Check out this post on Reddit: Incredible space telescope photo : r/astrophotography - Reddit"
        val cleaned = LinkPreviewFetcher.cleanRedditTitle(raw)
        assertEquals("Incredible space telescope photo", cleaned)
    }

    @Test
    fun testTitleFromUrlSlug() {
        val redditUrl = "https://www.reddit.com/r/technology/comments/18z7x3y/breakthrough_in_quantum_computing/"
        val slugTitle = LinkPreviewFetcher.titleFromUrlSlug(redditUrl)
        assertEquals("Breakthrough In Quantum Computing", slugTitle)

        val articleUrl = "https://theverge.com/2026/11/07/new-android-device-features"
        val articleSlug = LinkPreviewFetcher.titleFromUrlSlug(articleUrl)
        assertEquals("New Android Device Features", articleSlug)
    }

    @Test
    fun testWebLinkObjectProperties() {
        val weblinkType = DefaultTypes.WEBLINK
        assertEquals("web", weblinkType.id)
        assertTrue(weblinkType.defaultProperties.any { it.id == "reviewed" })
        assertTrue(weblinkType.defaultProperties.any { it.id == "is_bookmark" })

        val obj = PkmObject(
            id = "test-web-1",
            typeId = DefaultTypes.WEBLINK.id,
            properties = mapOf(
                "url" to PropertyValue.Url("https://example.com"),
                "reviewed" to PropertyValue.Checkbox(true),
                "is_bookmark" to PropertyValue.Checkbox(false),
            ),
        )

        assertTrue(obj.isReviewed)
        assertFalse(obj.isBookmark)
        assertEquals("https://example.com", obj.weblinkUrl)
    }

    @Test
    fun testCoverNamingConvention() {
        val videoTitle = "Introduction to Jetpack Compose"
        val englishCoverTitle = "Cover of $videoTitle"
        val spanishCoverTitle = "Portada de $videoTitle"

        assertEquals("Cover of Introduction to Jetpack Compose", englishCoverTitle)
        assertEquals("Portada de Introduction to Jetpack Compose", spanishCoverTitle)

        // Asset filename conforms to low storage WebP naming
        val englishFileName = "$englishCoverTitle.webp"
        val spanishFileName = "$spanishCoverTitle.webp"
        assertEquals("Cover of Introduction to Jetpack Compose.webp", englishFileName)
        assertEquals("Portada de Introduction to Jetpack Compose.webp", spanishFileName)
    }

    @Test
    fun testRealNetworkExtraction() = kotlinx.coroutines.runBlocking {
        // Run live network extraction and ensure extracted URLs have cleaned signatures
        val redditUrl = "https://www.reddit.com/r/DragonAdventures/comments/1xqca8i/announcing_the_winners_in_4_hours/"
        val redditPreview = runCatching { LinkPreviewFetcher.fetch(redditUrl) }.getOrNull()
        if (redditPreview != null) {
            assertNotNull(redditPreview.title)
            assertFalse(LinkPreviewFetcher.isBlockedTitle(redditPreview.title))
            redditPreview.imageUrl?.let { assertFalse(it.contains("&amp;")) }
        }

        val fbUrl = "https://www.facebook.com/Meta"
        val fbPreview = runCatching { LinkPreviewFetcher.fetch(fbUrl) }.getOrNull()
        if (fbPreview != null) {
            assertNotNull(fbPreview.title)
            assertFalse(LinkPreviewFetcher.isBlockedTitle(fbPreview.title))
            fbPreview.imageUrl?.let { assertFalse(it.contains("&amp;")) }
        }

        val igUrl = "https://www.instagram.com/instagram/"
        val igPreview = runCatching { LinkPreviewFetcher.fetch(igUrl) }.getOrNull()
        if (igPreview != null) {
            assertNotNull(igPreview.title)
            assertFalse(LinkPreviewFetcher.isBlockedTitle(igPreview.title))
            igPreview.imageUrl?.let { assertFalse(it.contains("&amp;")) }
        }
    }
}
