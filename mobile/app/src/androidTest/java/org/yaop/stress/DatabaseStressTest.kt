package org.yaop.stress

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.yaop.YaopApp
import org.yaop.core.database.ObjectEntity
import org.yaop.core.database.ObjectFtsEntity
import org.yaop.core.database.ObjectLinkEntity
import org.yaop.core.model.PkmObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

private const val TAG = "StressTest:Database"

@RunWith(AndroidJUnit4::class)
class DatabaseStressTest {

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

    @Test
    fun testStress_1000_Objects_BulkInsert_And_Query() = runBlocking {
        val count = 1000
        Log.i(TAG, "=== Starting Stress Test: 1,000 Objects ===")

        val memBefore = getUsedMemoryMb()
        val objects = StressTestDataGenerator.generateObjects(count, prefix = "stress_1k")

        val json = app.json
        val entities = objects.map { obj ->
            ObjectEntity(
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
        }

        val dao = app.database.objectDao()

        val insertTimeMs = measureTimeMillis {
            dao.upsertObjects(entities)
        }
        val memAfterInsert = getUsedMemoryMb()

        Log.i(TAG, "Inserted $count objects in $insertTimeMs ms (${count * 1000L / insertTimeMs.coerceAtLeast(1)} obj/sec)")
        Log.i(TAG, "Memory delta after insert: ${memAfterInsert - memBefore} MB")

        // Measure Query & Deserialization time
        var deserializedCount = 0
        val queryTimeMs = measureTimeMillis {
            val snapshot = dao.getAllActiveObjectsSnapshot().filter { it.id.startsWith("stress_1k") }
            deserializedCount = snapshot.size
            // Deserialize each JSON payload
            val loadedObjects = snapshot.mapNotNull { entity ->
                runCatching { json.decodeFromString<PkmObject>(entity.payloadJson) }.getOrNull()
            }
            assertEquals(count, loadedObjects.size)
        }

        val memAfterQuery = getUsedMemoryMb()
        Log.i(TAG, "Queried and deserialized $deserializedCount objects in $queryTimeMs ms")
        Log.i(TAG, "Memory delta after full deserialization: ${memAfterQuery - memBefore} MB")

        assertTrue("Query time should be under 5 seconds for 1k items", queryTimeMs < 5000)
    }

    @Test
    fun testStress_10000_Objects_Scale_And_MemoryImpact() = runBlocking {
        val count = 10000
        Log.i(TAG, "=== Starting Extreme Stress Test: 10,000 Objects ===")

        val memInitial = getUsedMemoryMb()
        Log.i(TAG, "Initial Memory: %.2f MB".format(memInitial))

        val objects = StressTestDataGenerator.generateObjects(count, prefix = "stress_10k")
        val json = app.json

        val entities = objects.map { obj ->
            ObjectEntity(
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
        }

        val dao = app.database.objectDao()

        // Batch insert in chunks of 1000 for SQLite parameter safety
        val insertTimeMs = measureTimeMillis {
            entities.chunked(1000).forEach { chunk ->
                dao.upsertObjects(chunk)
            }
        }
        val memAfterInsert = getUsedMemoryMb()
        Log.i(TAG, "Inserted $count objects in chunks: $insertTimeMs ms")
        Log.i(TAG, "Heap Memory after bulk insert: %.2f MB (Delta: +%.2f MB)".format(memAfterInsert, memAfterInsert - memInitial))

        // Measure impact of deserializing 10,000 objects into RAM (what getAllActiveObjects does)
        val memBeforeFullLoad = getUsedMemoryMb()
        val queryTimeMs = measureTimeMillis {
            val snapshot = dao.getAllActiveObjectsSnapshot().filter { it.id.startsWith("stress_10k") }
            val loadedObjects = snapshot.mapNotNull { entity ->
                runCatching { json.decodeFromString<PkmObject>(entity.payloadJson) }.getOrNull()
            }
            assertEquals(count, loadedObjects.size)
        }
        val memAfterFullLoad = getUsedMemoryMb()

        Log.i(TAG, "Loaded & Deserialized all $count objects in: $queryTimeMs ms")
        Log.i(TAG, "RAM consumed by 10,000 in-memory PkmObject instances: +%.2f MB".format(memAfterFullLoad - memBeforeFullLoad))
        Log.i(TAG, "Peak Heap Memory: %.2f MB".format(memAfterFullLoad))

        // Single object lookup by ID among 10,000 objects
        val sampleId = "stress_10k_5000"
        val lookupTimeMs = measureTimeMillis {
            val entity = dao.getObjectById(sampleId)
            assertNotNull(entity)
        }
        Log.i(TAG, "Indexed single lookup by ID among 10,000 objects: $lookupTimeMs ms")
        assertTrue("Single indexed lookup should take less than 50ms", lookupTimeMs < 50)
    }

    @Test
    fun testStress_KnowledgeGraph_MegaHub_50References_AndBacklinks() = runBlocking {
        Log.i(TAG, "=== Starting Cross-Reference Knowledge Graph Stress Test ===")

        val targetCount = 100
        val targets = StressTestDataGenerator.generateObjects(targetCount, prefix = "target_ref")
        val json = app.json

        val dao = app.database.objectDao()
        dao.upsertObjects(targets.map { obj ->
            ObjectEntity(
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

        // Create MegaHub note with 50+ mentions
        val megaHub = StressTestDataGenerator.createMegaHubNote(
            hubId = "hub_stress_node",
            targetObjectIds = targets.map { it.id },
        )

        // Save through repository to trigger index and backlink creation
        val saveHubTimeMs = measureTimeMillis {
            app.repository.saveObject(megaHub)
        }
        Log.i(TAG, "Saved MegaHub note with 50+ references & indexed links in: $saveHubTimeMs ms")

        // Query backlinks for one of the targets
        val sampleTargetId = targets[5].id
        val backlinks = app.repository.getBacklinks(sampleTargetId).first()
        Log.i(TAG, "Backlinks found for target $sampleTargetId: ${backlinks.size} (expected >= 1)")
        assertTrue(backlinks.any { it.id == "hub_stress_node" })

        // Query outgoing objects from hub
        val loadedHub = app.repository.getObject("hub_stress_node")
        assertNotNull(loadedHub)
        assertEquals("hub_stress_node", loadedHub?.id)
    }

    @Test
    fun testStress_FullTextSearch_10000Objects() = runBlocking {
        Log.i(TAG, "=== Testing Full-Text Search on 10,000 Objects ===")
        val query = "stress_token_3"

        // Split the cost: SQL (FTS query + row fetch) vs. decoding the entities into objects.
        val dao = app.database.objectDao()
        val q = "stress_token_3*"
        var entities = emptyList<org.yaop.core.database.ObjectEntity>()
        val sqlMs = measureTimeMillis { entities = dao.search(q).first() }
        val sqlWarmMs = measureTimeMillis { dao.search(q).first() }
        Log.i(TAG, "FTS SQL only: $sqlMs ms cold, $sqlWarmMs ms warm, ${entities.size} rows")
        val coldRepoMs = measureTimeMillis { app.repository.search(query).first() }
        Log.i(TAG, "FTS via repository (decode + dedup), second run: $coldRepoMs ms")

        val searchTimeMs = measureTimeMillis {
            // Test repository search flow
            val results = app.repository.search(query).first()
            Log.i(TAG, "FTS query for '$query' returned ${results.size} matches")
        }
        Log.i(TAG, "FTS query execution time: $searchTimeMs ms")
        assertTrue("FTS query on 10,000 items should execute in under 3000 ms", searchTimeMs < 3000)
    }
}
