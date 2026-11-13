package org.yaop.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "objects",
    indices = [
        Index(value = ["type_id"]),
        Index(value = ["updated_at"]),
        Index(value = ["trashed_at"])
    ]
)
data class ObjectEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "type_id")
    val typeId: String,
    @ColumnInfo(name = "plain_title")
    val plainTitle: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
    val hlc: String,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,
    @ColumnInfo(name = "trashed_at")
    val trashedAt: String? = null
)

@Entity(
    tableName = "object_links",
    primaryKeys = ["source_id", "target_id", "link_type"],
    indices = [
        Index(value = ["target_id"]),
        Index(value = ["source_id"])
    ]
)
data class ObjectLinkEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "link_type")
    val linkType: String // "BLOCK_MENTION", "PROPERTY_RELATION", "TAG"
)

@Entity(tableName = "object_types")
data class ObjectTypeEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String
)

@Entity(
    tableName = "assets",
    indices = [Index(value = ["object_id"])]
)
data class AssetEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "object_id")
    val objectId: String?,
    val name: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false
)

@Fts4
@Entity(tableName = "objects_fts")
data class ObjectFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Int? = null,
    val id: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "type_id")
    val typeId: String
)

/**
 * Outbox of encrypted ops ready to upload: each row is an op whose envelope is ALREADY sealed
 * (the blob rests encrypted in the DB), ready for the batched push. Repeated saves of the same
 * (kind, real_id) collapse: the new row replaces the pending one, since the latest carries the
 * full object state.
 */
@Entity(
    tableName = "outbox_ops",
    indices = [
        Index(value = ["kind", "real_id"], unique = true),
        Index(value = ["created_at"]),
    ],
)
data class OutboxOpEntity(
    /** Random 32-hex chars op by op (server idempotency). */
    @PrimaryKey
    @ColumnInfo(name = "op_id")
    val opId: String,
    val kind: String,
    /** Real id of the object/type/asset: NEVER leaves the device (wire_id instead). */
    @ColumnInfo(name = "real_id")
    val realId: String,
    /** Blind wire_id of this op (32 hex chars). */
    @ColumnInfo(name = "wire_id")
    val wireId: String,
    @ColumnInfo(name = "key_epoch")
    val keyEpoch: Long,
    /** Base64 blob of the full envelope (already encrypted with the domain subkey). */
    val blob: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    /** Upload retries (shown as backoff in the status chip). */
    val attempts: Int = 0,
    /** Last readable failure reason (spec §22.6), null if it never failed. */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    /** Object hlc at sealing time: mark synced only if it is still the current one. */
    @ColumnInfo(name = "seal_hlc", defaultValue = "")
    val sealHlc: String = "",
)

/** Vault metadata: VK wrapped by the Android Keystore, epoch and global cursor. */
@Entity(tableName = "vault_meta")
data class VaultMetaEntity(
    /** Singleton row id (always 1). */
    @PrimaryKey
    val id: Int = 1,
    /** VK (32 bytes) wrapped with the Android Keystore AES-GCM key (vault.encrypt). */
    @ColumnInfo(name = "wrapped_vk")
    val wrappedVk: ByteArray?,
    @ColumnInfo(name = "key_epoch")
    val keyEpoch: Long = 1L,
    /** Global pull cursor (highest seq applied for this vault). */
    @ColumnInfo(name = "last_seq")
    val lastSeq: Long = 0L,
)

/** Decrypt/apply progress per crypto domain (last seq seen per domain). */
@Entity(tableName = "vault_domain_seq")
data class VaultDomainSeqEntity(
    @PrimaryKey
    val domain: String,
    @ColumnInfo(name = "last_seq")
    val lastSeq: Long = 0L,
)
