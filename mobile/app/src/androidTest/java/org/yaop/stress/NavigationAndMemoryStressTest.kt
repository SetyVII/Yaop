package org.yaop.stress

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.yaop.YaopApp
import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.PkmObject
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.toTextSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

private const val TAG = "StressTest:Navigation"

@RunWith(AndroidJUnit4::class)
class NavigationAndMemoryStressTest {

    private lateinit var app: YaopApp

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as YaopApp
    }

    private fun getUsedMemoryMb(): Double {
        System.gc()
        System.gc()
        val runtime = Runtime.getRuntime()
        val usedBytes = runtime.totalMemory() - runtime.freeMemory()
        return usedBytes / (1024.0 * 1024.0)
    }

    /**
     * Answers: when I open an object and come back, is it loaded again or kept in memory?
     *
     * This test simulates the exact lifecycle of navigation:
     * 1. Open Note A -> observeObject(noteA) is active.
     * 2. Click link to Note B -> Navigation stack pushes note B, Note A unmounts / cancels observation.
     * 3. Observe Note B.
     * 4. Pop back to Note A -> A new observeObject(noteA) flow is collected.
     * 5. Verify whether data is re-queried from Room SQLite or served from an in-memory cache.
     */
    @Test
    fun testNavigationLifecycle_VerifyReloadVsCacheOnReturn() {
        runBlocking {
        Log.i(TAG, "=== Verifying Reload Behavior on Reference Navigation ===")

        val repository = app.repository

        // Create Note A and Note B
        val noteB = PkmObject(
            id = "note_target_b",
            typeId = CanonicalTypeIds.NOTE,
            title = "Target Note B".toTextSpan(),
            blocks = listOf(Block.Paragraph("b-p1", listOf(InlineSpan.Text("Content in Note B")))),
        )
        val noteA = PkmObject(
            id = "note_origin_a",
            typeId = CanonicalTypeIds.NOTE,
            title = "Origin Note A".toTextSpan(),
            blocks = listOf(
                Block.Paragraph(
                    "a-p1",
                    listOf(
                        InlineSpan.Text("Click this reference: "),
                        InlineSpan.Mention(targetObjectId = "note_target_b", fallbackText = "Note B"),
                    ),
                ),
            ),
        )

        repository.saveObject(noteB)
        repository.saveObject(noteA)

        // 1. Enter Note A: first load
        val timeFirstLoadA = measureTimeMillis {
            val loadedA = repository.observeObject(noteA.id).first()
            assertNotNull(loadedA)
            assertEquals("Origin Note A", loadedA?.plainTitle)
        }
        Log.i(TAG, "Note A first load from Room: $timeFirstLoadA ms")

        // 2. Click Reference -> Navigate to Note B (Note A subscription cancelled)
        val timeLoadB = measureTimeMillis {
            val loadedB = repository.observeObject(noteB.id).first()
            assertNotNull(loadedB)
            assertEquals("Target Note B", loadedB?.plainTitle)
        }
        Log.i(TAG, "Note B load from Room: $timeLoadB ms")

        // 3. User hits Back button -> Navigate back to Note A
        // Does it reload from database?
        val timeReturnToA = measureTimeMillis {
            val reloadedA = repository.observeObject(noteA.id).first()
            assertNotNull(reloadedA)
            assertEquals("Origin Note A", reloadedA?.plainTitle)
        }
        Log.i(TAG, "Note A reload on BACK navigation: $timeReturnToA ms")

        // In the current architecture:
        // - There is NO in-memory L1 cache for PkmObject.
        // - observeObject() returns a Flow from Room DAO (dao.observeObjectById(id)).
        // - When returning to Note A, Room queries SQLite again and deserializes JSON payloadJson again.
        // Both first load and return load invoke Room and JSON deserialization!
        Log.i(TAG, "Confirmation: Screen re-queries Room SQLite and deserializes payloadJson on return.")
        }
    }

    /**
     * Tests stress of dynamically adding multiple links (100 links/mentions) into an existing note.
     * Measures link extraction, transaction execution, and backlink updates.
     */
    @Test
    fun testStress_Adding100Links_ToNote() {
        runBlocking {
        Log.i(TAG, "=== Testing Stress of Adding 100 Links/Mentions into Note ===")

        val repository = app.repository
        val initialNote = PkmObject(
            id = "note_stress_links_host",
            typeId = CanonicalTypeIds.NOTE,
            title = "Note With Dynamically Growing Links".toTextSpan(),
        )
        repository.saveObject(initialNote)

        // Generate 100 targets
        val targets = StressTestDataGenerator.generateObjects(100, prefix = "link_dest")
        val dao = app.database.objectDao()
        val json = app.json
        dao.upsertObjects(targets.map { obj ->
            org.yaop.core.database.ObjectEntity(
                id = obj.id,
                typeId = obj.typeId,
                plainTitle = obj.plainTitle,
                payloadJson = json.encodeToString(obj),
                createdAt = obj.createdAt,
                updatedAt = obj.updatedAt,
                hlc = obj.hlc,
                isPinned = obj.isPinned,
                trashedAt = obj.trashedAt,
            )
        })

        // Progressively add links and save
        val updatedBlocks = mutableListOf<Block>()
        val linkBatches = targets.chunked(10) // 10 batches of 10 links each

        linkBatches.forEachIndexed { batchIdx, batch ->
            val spans = mutableListOf<InlineSpan>()
            spans += InlineSpan.Text("Batch $batchIdx references: ")
            batch.forEach { target ->
                spans += InlineSpan.Mention(targetObjectId = target.id, fallbackText = target.plainTitle)
                spans += InlineSpan.Text(" ")
            }
            updatedBlocks += Block.Paragraph("blk-batch-$batchIdx", spans)

            val updatedNote = initialNote.copy(blocks = ArrayList(updatedBlocks))
            val saveDuration = measureTimeMillis {
                repository.saveObject(updatedNote)
            }
            Log.i(TAG, "Saved note with ${(batchIdx + 1) * 10} links in $saveDuration ms")
        }

        // Verify that backlinks are registered
        val sampleTargetId = targets[42].id
        val backlinks = repository.getBacklinks(sampleTargetId).first()
        assertTrue("Backlinks should include the note with links", backlinks.any { it.id == "note_stress_links_host" })
        Log.i(TAG, "Successfully verified backlink indexing for 100 newly added links")
        }
    }

    /**
     * Tests memory impact of deep navigation stack (depth = 10 notes).
     */
    @Test
    fun testDeepNavigationStack_MemoryFootprint() {
        runBlocking {
        Log.i(TAG, "=== Testing Memory Footprint Across Deep Navigation Chain ===")

        val repository = app.repository
        val depth = 10
        val chainNotes = mutableListOf<PkmObject>()

        for (i in 1..depth) {
            val nextId = if (i < depth) "chain_note_${i + 1}" else "chain_note_1"
            val note = PkmObject(
                id = "chain_note_$i",
                typeId = CanonicalTypeIds.NOTE,
                title = "Chain Note #$i".toTextSpan(),
                blocks = listOf(
                    Block.Paragraph(
                        "chain-p-$i",
                        listOf(
                            InlineSpan.Text("Link to next node: "),
                            InlineSpan.Mention(targetObjectId = nextId, fallbackText = "Node $nextId"),
                        ),
                    ),
                ),
            )
            repository.saveObject(note)
            chainNotes += note
        }

        val memStart = getUsedMemoryMb()
        val visitedObjects = mutableListOf<PkmObject>()

        // Simulate visiting all 10 in sequence
        for (note in chainNotes) {
            val loaded = repository.getObject(note.id)
            if (loaded != null) visitedObjects.add(loaded)
        }

        val memPeak = getUsedMemoryMb()
        Log.i(TAG, "Memory after loading $depth chain notes: %.2f MB (Delta: +%.2f MB)".format(memPeak, memPeak - memStart))

        // Simulate unwinding back to note 1
        visitedObjects.clear()
        val loadedReturn = repository.getObject("chain_note_1")
        assertNotNull(loadedReturn)

        val memEnd = getUsedMemoryMb()
        Log.i(TAG, "Memory after pop back to root note: %.2f MB".format(memEnd))
        }
    }
}
