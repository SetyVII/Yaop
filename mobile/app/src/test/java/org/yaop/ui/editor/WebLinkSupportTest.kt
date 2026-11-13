package org.yaop.ui.editor

import org.yaop.core.model.Block
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.PropertyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinkSupportTest {

    @Test
    fun testIsValidWebUrl() {
        assertTrue(isValidWebUrl("https://github.com"))
        assertTrue(isValidWebUrl("http://example.com/sub/page"))
        assertTrue(isValidWebUrl("kotlinlang.org"))
        assertTrue(isValidWebUrl("https://en.wikipedia.org/wiki/Kotlin_(programming_language)"))

        assertFalse(isValidWebUrl(""))
        assertFalse(isValidWebUrl("   "))
        assertFalse(isValidWebUrl("not a url"))
        assertFalse(isValidWebUrl("untitled"))
        assertFalse(isValidWebUrl("file://local/path"))
    }

    @Test
    fun testNormalizeWebUrl() {
        assertEquals("https://github.com", normalizeWebUrl("github.com"))
        assertEquals("https://example.com", normalizeWebUrl("https://example.com"))
        assertEquals("http://insecure.site", normalizeWebUrl("http://insecure.site"))
        assertEquals("https://blog.test.org/path", normalizeWebUrl("  blog.test.org/path  "))
    }

    @Test
    fun testWebLinkUrlGetterFallback() {
        // 1. URL from property
        val objWithProp = PkmObject(
            id = "w1",
            typeId = "web",
            properties = mapOf("url" to PropertyValue.Url("https://android.com")),
        )
        assertEquals("https://android.com", objWithProp.weblinkUrl)

        // 2. URL from bookmark block fallback when property is missing
        val objWithBookmarkBlock = PkmObject(
            id = "w2",
            typeId = "web",
            blocks = listOf(
                Block.Bookmark(
                    id = "b1",
                    url = "https://developer.android.com",
                    title = "Android Developers",
                ),
            ),
        )
        assertEquals("https://developer.android.com", objWithBookmarkBlock.weblinkUrl)

        // 3. Null when no URL anywhere
        val emptyObj = PkmObject(id = "w3", typeId = "web")
        assertNull(emptyObj.weblinkUrl)
    }

    @Test
    fun testWebLinkMandatoryConstraint() {
        // Enforce that a blank string is never considered a valid link
        assertFalse(isValidWebUrl(""))
        assertFalse(isValidWebUrl("Sin título"))
        assertFalse(isValidWebUrl("Untitled"))
    }
}
