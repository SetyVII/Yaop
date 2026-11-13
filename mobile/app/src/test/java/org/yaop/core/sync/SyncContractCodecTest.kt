package org.yaop.core.sync

import org.yaop.core.model.Block
import org.yaop.core.model.PkmObject
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.toTextSpan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncContractCodecTest {

    @Test
    fun toServerJson_emitsSyncedStatusAndHlc() {
        val obj = PkmObject(
            id = "note-1",
            typeId = "note",
            title = "Test Note".toTextSpan(),
            syncStatus = "pending",
        )

        val json = SyncContractCodec.toServerJson(obj)
        assertEquals("synced", json.optString("sync_status"))
        assertTrue(json.has("hlc"))
        assertEquals(obj.hlc, json.optString("hlc"))
    }

    @Test
    fun fromServerJson_assignsSyncedStatusToImportedObject() {
        val raw = JSONObject().apply {
            put("id", "note-2")
            put("type_id", "note")
            put("title", "Remote Note")
            put("sync_status", "pending") // Even if remote had pending, imported becomes synced
        }

        val parsed = SyncContractCodec.fromServerJson(raw, existing = null)
        assertEquals("synced", parsed.syncStatus)
    }

    @Test
    fun fromServerJson_preservesLocalBlocksWhenActivelyEditing() {
        val localBlocks = listOf(
            Block.Paragraph(
                id = "b1",
                spans = listOf(InlineSpan.Text("Local in-progress typing")),
            ),
        )
        val existing = PkmObject(
            id = "note-3",
            typeId = "note",
            title = "My Local Title".toTextSpan(),
            blocks = localBlocks,
            syncStatus = "pending",
        )

        val serverRaw = JSONObject().apply {
            put("id", "note-3")
            put("type_id", "note")
            put("title", "Old Server Title")
            put("content", "Stale server content")
            put("updated_at", "2026-01-01T00:00:00Z")
        }

        val merged = SyncContractCodec.fromServerJson(
            raw = serverRaw,
            existing = existing,
            isActivelyEditing = true,
        )

        assertEquals("My Local Title", merged.plainTitle)
        assertEquals(localBlocks, merged.blocks)
        assertEquals("pending", merged.syncStatus)
    }

    // R3: a pending local edit is kept even when the server copy has a newer HLC; the push
    // carries it and the server merges both sides.
    @Test
    fun fromServerJson_keepsPendingLocalEvenWhenRemoteHlcIsNewer() {
        val existing = PkmObject(
            id = "note-4",
            typeId = "note",
            title = "Local offline edit".toTextSpan(),
            hlc = "0000000001000-0000-aaaaaaaa",
            syncStatus = "pending",
        )
        val serverRaw = JSONObject().apply {
            put("id", "note-4")
            put("type_id", "note")
            put("title", "Remote later edit")
            put("hlc", "0000000009000-0000-bbbbbbbb")
        }
        val merged = SyncContractCodec.fromServerJson(serverRaw, existing, isActivelyEditing = false)
        assertEquals("Local offline edit", merged.plainTitle)
        assertEquals("pending", merged.syncStatus)
    }

    @Test
    fun fromServerJson_syncedLocalTakesServerVersion() {
        val existing = PkmObject(
            id = "note-5",
            typeId = "note",
            title = "Old".toTextSpan(),
            hlc = "0000000009000-0000-aaaaaaaa",
            syncStatus = "synced",
        )
        val serverRaw = JSONObject().apply {
            put("id", "note-5")
            put("type_id", "note")
            put("title", "Merged on server")
            put("hlc", "0000000009000-0000-aaaaaaaa")
        }
        val merged = SyncContractCodec.fromServerJson(serverRaw, existing, isActivelyEditing = false)
        assertEquals("Merged on server", merged.plainTitle)
        assertEquals("synced", merged.syncStatus)
    }

    /**
     * The wire hlc is kept VERBATIM as the object's hlc (never rebased with the local HLC clock
     * on import: otherwise each platform's digest would diverge).
     */
    @Test
    fun fromServerJson_keepsWireHlcVerbatim() {
        val serverRaw = JSONObject().apply {
            put("id", "note-6")
            put("type_id", "note")
            put("title", "From server")
            put("hlc", "0000000012345-0007-dddddddd")
        }
        val merged = SyncContractCodec.fromServerJson(serverRaw, existing = null, isActivelyEditing = false)
        assertEquals("0000000012345-0007-dddddddd", merged.hlc)
    }

    // R4: live stream reconnect backoff.
    @Test
    fun liveBackoff_doublesWithJitterAndCap() {
        assertEquals(1000L, liveBackoffMs(0, 0.5))
        assertEquals(8000L, liveBackoffMs(3, 0.5))
        assertEquals(60000L, liveBackoffMs(30, 0.5))
        assertEquals(800L, liveBackoffMs(0, 0.0))
        assertTrue(liveBackoffMs(0, 0.999) <= 1200L)
    }

    @Test
    fun trashedAt_roundTripsThroughWireCodecAndMerge() {
        val trashed = PkmObject(
            id = "web-1",
            typeId = "web",
            title = "Deleted weblink".toTextSpan(),
            updatedAt = "2026-11-12T10:00:00Z",
            trashedAt = "2026-11-12T10:00:00Z",
        )
        val wire = SyncContractCodec.toServerJson(trashed)
        assertEquals("2026-11-12T10:00:00Z", wire.optString("trashed_at"))

        val decoded = SyncContractCodec.fromServerJson(wire, existing = null, isActivelyEditing = false)
        assertEquals("2026-11-12T10:00:00Z", decoded.trashedAt)

        val localActive = JSONObject().apply {
            put("id", "web-1")
            put("type_id", "web")
            put("title", "Deleted weblink")
            put("properties", JSONObject())
            put("hlc", "0000000001000-0001-deviceA")
        }
        val merged1 = MergeContract.merge(localActive, wire)
        val merged2 = MergeContract.merge(wire, localActive)
        assertEquals("2026-11-12T10:00:00Z", merged1.optString("trashed_at"))
        assertEquals("2026-11-12T10:00:00Z", merged2.optString("trashed_at"))
    }
}
