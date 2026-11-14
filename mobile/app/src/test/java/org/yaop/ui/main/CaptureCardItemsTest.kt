package org.yaop.ui.main

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureCardItemsTest {
    private fun item(id: String) = RadialItem(id, "📄", id, Color.White)

    @Test
    fun quickFirstThenEveryOtherTypeOnce() {
        val quick = listOf(item("note"), item("task"))
        val all = listOf(item("task"), item("book"), item("custom_ai"), item("note"))
        assertEquals(listOf("note", "task", "book", "custom_ai"), captureCardItems(quick, all).map { it.typeId })
    }
}
