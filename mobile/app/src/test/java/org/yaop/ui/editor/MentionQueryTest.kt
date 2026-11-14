package org.yaop.ui.editor

import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionQueryTest {
    private fun type(id: String, name: String) = ObjectType(id, name, ObjectIcon(emoji = "📄"), "#FFFFFF")

    private val web = type("web", "Web Links")
    private val ai = type("custom_ai", "Modelos de IA")
    private val ref = type("custom_ref", "Modelos de referencia")
    private val types = listOf(web, ai, ref)

    @Test
    fun slugLowercasesStripsAccentsAndHyphenatesSpaces() {
        assertEquals("web-links", mentionTypeSlug("Web Links"))
        assertEquals("modelos-de-ia", mentionTypeSlug("Modelos de IA"))
        assertEquals("canciones-rapidas", mentionTypeSlug("Canciones  rápidas"))
    }

    @Test
    fun anySeparatorSpellingResolvesTheType() {
        for (q in listOf("web-links/x", "weblinks/x", "web links/x", "web_links/x", "Web-Links/x")) {
            assertEquals(q, web, parseMentionQuery(q, types).resolvedType)
        }
        val parsed = parseMentionQuery("modelos-de-ia/gpt", types)
        assertEquals(ai, parsed.resolvedType)
        assertEquals("gpt", parsed.nameQuery)
    }

    @Test
    fun userTypeKeywordIsItsSlug() {
        assertEquals("modelos-de-ia", preferredTypeMentionKeyword(ai, emptyMap()))
    }

    @Test
    fun prefixSuggestsEveryMatchingTypeIncludingByWord() {
        assertEquals(listOf(ai, ref), matchingTypesForMentionPrefix("mode", types))
        assertEquals(listOf(ref), matchingTypesForMentionPrefix("refe", types))
        assertTrue(matchingTypesForMentionPrefix("zzz", types).isEmpty())
    }

    @Test
    fun recentTypesComeFirst() {
        val out = matchingTypesForMentionPrefix("", types, recentTypeIds = listOf("custom_ref", "web"))
        assertEquals(listOf(ref, web, ai), out)
    }
}
