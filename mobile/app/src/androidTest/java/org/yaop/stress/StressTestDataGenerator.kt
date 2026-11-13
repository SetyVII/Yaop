package org.yaop.stress

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.yaop.core.model.Block
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.CoverSpec
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.HybridLogicalClock
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toTextSpan
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/**
 * Utility for generating large-scale synthetic test datasets to stress test
 * database persistence, memory footprint, media encryption, and cross-reference navigation.
 */
object StressTestDataGenerator {

    /**
     * Generates a batch of synthetic [PkmObject] instances.
     * Types are evenly distributed among Notes, Tasks, Books, Projects, and Journals.
     */
    fun generateObjects(
        count: Int,
        prefix: String = "stress_obj",
        blocksPerObject: Int = 3,
    ): List<PkmObject> {
        val types = listOf(
            CanonicalTypeIds.NOTE,
            CanonicalTypeIds.TASK,
            CanonicalTypeIds.BOOK,
            CanonicalTypeIds.PROJECT,
            CanonicalTypeIds.JOURNAL,
        )

        val nowIso = Instant.now().toString()
        val list = ArrayList<PkmObject>(count)

        for (i in 1..count) {
            val typeId = types[i % types.size]
            val objId = "${prefix}_${i}"
            val titleText = "Stress Item #$i: Performance testing subject $typeId"

            val blocks = mutableListOf<Block>()
            blocks += Block.Heading(
                id = "$objId-h1",
                level = HeadingLevel.H1,
                spans = listOf(InlineSpan.Text("Overview for $objId")),
            )

            for (b in 1..blocksPerObject) {
                blocks += Block.Paragraph(
                    id = "$objId-p$b",
                    spans = listOf(
                        InlineSpan.Text("Detailed test content line $b containing synthetic searchable tokens: keyword_$i stress_token_${b % 10}. "),
                        InlineSpan.Text("Formatted bold segment ", isBold = true),
                        InlineSpan.Tag(tagId = "tag_stress_${i % 20}", tagName = "stress_${i % 20}"),
                    ),
                )
            }

            val props = mutableMapOf<String, PropertyValue>()
            props["prop_stress_index"] = PropertyValue.Number(i.toDouble())
            if (typeId == CanonicalTypeIds.TASK) {
                props["status"] = PropertyValue.Select(if (i % 2 == 0) "done" else "todo")
            }

            list += PkmObject(
                id = objId,
                typeId = typeId,
                title = titleText.toTextSpan(),
                icon = ObjectIcon(emoji = if (typeId == CanonicalTypeIds.TASK) "✅" else "📄"),
                blocks = blocks,
                properties = props,
                createdAt = nowIso,
                updatedAt = nowIso,
                hlc = HybridLogicalClock.now(),
            )
        }
        return list
    }

    /**
     * Generates a valid compressed WebP image in bytes with custom dimensions and colored background.
     */
    fun generateSyntheticImageBytes(
        width: Int = 200,
        height: Int = 200,
        label: String = "TestImg",
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.rgb((label.hashCode() * 37) % 256, (label.hashCode() * 73) % 256, (label.hashCode() * 101) % 256)
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(label, width / 2f, height / 2f, textPaint)

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    /**
     * Generates synthetic audio bytes with standard M4A/AAC header prefix.
     */
    fun generateSyntheticAudioBytes(durationSeconds: Int = 10): ByteArray {
        // Mock AAC audio stream header and payload of approximately 8KB per second
        val payloadSize = durationSeconds * 8000
        val bytes = ByteArray(payloadSize)
        // Set standard ftyp header signature for MP4/M4A
        val header = byteArrayOf(
            0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, // ... ftyp
            0x4D, 0x34, 0x41, 0x20, 0x00, 0x00, 0x00, 0x00, // M4A ....
            0x4D, 0x34, 0x41, 0x20, 0x6D, 0x70, 0x34, 0x32, // M4A  mp42
            0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x00, 0x00  // isom....
        )
        System.arraycopy(header, 0, bytes, 0, header.size.coerceAtMost(payloadSize))
        // Fill remaining with pseudo-random audio frames
        for (i in header.size until payloadSize) {
            bytes[i] = ((i xor 0x5A) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Creates an Image object metadata record referencing an asset ID.
     */
    fun createImageObject(
        assetId: String,
        title: String,
        byteSize: Int,
    ): PkmObject {
        val nowIso = Instant.now().toString()
        val objId = "media_$assetId"
        return PkmObject(
            id = objId,
            typeId = DefaultTypes.IMAGE.id,
            title = title.toTextSpan(),
            icon = ObjectIcon(emoji = "🖼️"),
            properties = mapOf(
                "asset_id" to PropertyValue.RichText(assetId.toTextSpan()),
                "file_name" to PropertyValue.RichText("$title.webp".toTextSpan()),
                "mime" to PropertyValue.RichText("image/webp".toTextSpan()),
                "size" to PropertyValue.Number(byteSize.toDouble()),
            ),
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = HybridLogicalClock.now(),
        )
    }

    /**
     * Creates an Audio object metadata record referencing an asset ID.
     */
    fun createAudioObject(
        assetId: String,
        title: String,
        durationSeconds: Int,
        byteSize: Int,
    ): PkmObject {
        val nowIso = Instant.now().toString()
        val objId = "media_$assetId"
        return PkmObject(
            id = objId,
            typeId = DefaultTypes.AUDIO.id,
            title = title.toTextSpan(),
            icon = ObjectIcon(emoji = "🎙️"),
            properties = mapOf(
                "asset_id" to PropertyValue.RichText(assetId.toTextSpan()),
                "file_name" to PropertyValue.RichText("$title.m4a".toTextSpan()),
                "mime" to PropertyValue.RichText("audio/m4a".toTextSpan()),
                "duration" to PropertyValue.Number(durationSeconds.toDouble()),
                "size" to PropertyValue.Number(byteSize.toDouble()),
            ),
            blocks = listOf(
                Block.Audio(
                    id = "audio-blk-$assetId",
                    assetId = assetId,
                    durationSeconds = durationSeconds,
                    caption = listOf(InlineSpan.Text(title)),
                )
            ),
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = HybridLogicalClock.now(),
        )
    }

    /**
     * Generates a "Mega-Hub" Note referencing multiple other target objects through:
     * - Inline mentions (@targetId)
     * - Embedded cards (Block.ObjectEmbed)
     * - Direct web link bookmarks (Block.Bookmark)
     * - Tags
     */
    fun createMegaHubNote(
        hubId: String = "hub_master_note",
        targetObjectIds: List<String>,
        imageAssetIds: List<String> = emptyList(),
        audioAssetIds: List<String> = emptyList(),
    ): PkmObject {
        val nowIso = Instant.now().toString()
        val blocks = mutableListOf<Block>()

        blocks += Block.Heading(
            id = "$hubId-h1",
            level = HeadingLevel.H1,
            spans = listOf(InlineSpan.Text("Mega Hub Note with Dense Cross-References")),
        )

        // Multiple paragraphs with inline mentions
        targetObjectIds.take(50).chunked(5).forEachIndexed { chunkIndex, chunk ->
            val spans = mutableListOf<InlineSpan>()
            spans += InlineSpan.Text("Reference group $chunkIndex: ")
            chunk.forEachIndexed { idx, targetId ->
                spans += InlineSpan.Mention(targetObjectId = targetId, fallbackText = "Target-$targetId")
                if (idx < chunk.size - 1) {
                    spans += InlineSpan.Text(", ")
                }
            }
            blocks += Block.Paragraph(id = "$hubId-p-mentions-$chunkIndex", spans = spans)
        }

        // Object embeds (Cards)
        targetObjectIds.take(10).forEachIndexed { idx, targetId ->
            blocks += Block.ObjectEmbed(
                id = "$hubId-embed-$idx",
                targetObjectId = targetId,
                viewMode = "card",
            )
        }

        // Image attachments
        imageAssetIds.take(20).forEachIndexed { idx, assetId ->
            blocks += Block.Image(
                id = "$hubId-img-$idx",
                assetId = assetId,
                caption = listOf(InlineSpan.Text("Attached Gallery Image #$idx")),
            )
        }

        // Audio attachments
        audioAssetIds.take(5).forEachIndexed { idx, assetId ->
            blocks += Block.Audio(
                id = "$hubId-aud-$idx",
                assetId = assetId,
                durationSeconds = 15,
                caption = listOf(InlineSpan.Text("Voice Memo #$idx")),
            )
        }

        // Web Bookmarks
        for (w in 1..15) {
            blocks += Block.Bookmark(
                id = "$hubId-bookmark-$w",
                url = "https://example.org/resource/$w",
                title = "Example External Link #$w",
                description = "Synthetic bookmark for stress testing preview caching and link handling",
                domain = "example.org",
            )
        }

        return PkmObject(
            id = hubId,
            typeId = CanonicalTypeIds.NOTE,
            title = "Mega Hub: Dense Knowledge Graph Node".toTextSpan(),
            icon = ObjectIcon(emoji = "🌐"),
            blocks = blocks,
            properties = mapOf(
                "relation_many" to PropertyValue.ObjectRelation(targetObjectIds.take(30)),
                "tags" to PropertyValue.MultiSelect(listOf("hub", "stress", "benchmark", "graph")),
            ),
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = HybridLogicalClock.now(),
        )
    }

    /**
     * Creates an image collection object referencing many image objects.
     */
    fun createImageCollectionObject(
        collectionId: String = "coll_stress_images",
        title: String = "Collection with many images",
        imageObjectIds: List<String>,
        coverAssetId: String? = null,
    ): PkmObject {
        val nowIso = Instant.now().toString()
        val blocks = mutableListOf<Block>()

        blocks += Block.Heading(
            id = "$collectionId-h1",
            level = HeadingLevel.H1,
            spans = listOf(InlineSpan.Text(title)),
        )

        imageObjectIds.chunked(10).forEachIndexed { chunkIdx, chunk ->
            blocks += Block.Paragraph(
                id = "$collectionId-p-$chunkIdx",
                spans = listOf(
                    InlineSpan.Text("Image Batch $chunkIdx containing ${chunk.size} visual assets: "),
                    InlineSpan.Tag("gallery"),
                ),
            )
            chunk.forEachIndexed { imgIdx, imgObjId ->
                blocks += Block.ObjectEmbed(
                    id = "$collectionId-embed-$chunkIdx-$imgIdx",
                    targetObjectId = imgObjId,
                    viewMode = "card",
                )
            }
        }

        return PkmObject(
            id = collectionId,
            typeId = CanonicalTypeIds.COLLECTION,
            title = title.toTextSpan(),
            icon = ObjectIcon(emoji = "🖼️"),
            cover = coverAssetId?.let { CoverSpec(assetId = it) },
            blocks = blocks,
            properties = mapOf(
                "images_relation" to PropertyValue.ObjectRelation(imageObjectIds),
            ),
            createdAt = nowIso,
            updatedAt = nowIso,
            hlc = HybridLogicalClock.now(),
        )
    }
}
