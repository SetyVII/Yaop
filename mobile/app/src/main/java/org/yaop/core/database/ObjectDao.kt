package org.yaop.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ObjectDao {

    @Query("SELECT * FROM objects WHERE trashed_at IS NULL ORDER BY is_pinned DESC, updated_at DESC")
    fun getAllActiveObjects(): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE trashed_at IS NULL")
    suspend fun getAllActiveObjectsSnapshot(): List<ObjectEntity>

    @Query("SELECT * FROM objects")
    suspend fun getAllObjectsSnapshot(): List<ObjectEntity>

    @Query("SELECT * FROM objects WHERE type_id IN (:typeIds) AND trashed_at IS NULL ORDER BY is_pinned DESC, updated_at DESC")
    fun getObjectsByTypes(typeIds: List<String>): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE type_id = :typeId AND trashed_at IS NULL ORDER BY is_pinned DESC, updated_at DESC")
    fun getObjectsByType(typeId: String): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE id = :id")
    suspend fun getObjectById(id: String): ObjectEntity?

    @Query("SELECT COUNT(*) FROM objects WHERE type_id IN (:typeIds) AND trashed_at IS NULL")
    suspend fun getCountByTypes(typeIds: List<String>): Int

    @Query("SELECT * FROM objects WHERE type_id IN (:typeIds) AND LOWER(plain_title) = LOWER(:plainTitle) AND trashed_at IS NULL LIMIT 1")
    suspend fun findObjectByTypesAndTitle(typeIds: List<String>, plainTitle: String): ObjectEntity?

    @Query("SELECT * FROM objects WHERE LOWER(plain_title) = LOWER(:plainTitle) AND trashed_at IS NULL LIMIT 1")
    suspend fun findObjectByTitle(plainTitle: String): ObjectEntity?

    @Query("SELECT * FROM objects WHERE id = :id")
    fun observeObjectById(id: String): Flow<ObjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertObject(entity: ObjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertObjects(entities: List<ObjectEntity>)

    @Query("UPDATE objects SET trashed_at = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: String, timestamp: String)

    @Query("UPDATE objects SET trashed_at = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("DELETE FROM objects WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM objects WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun getTrashObjects(): Flow<List<ObjectEntity>>

    @Query("SELECT * FROM objects WHERE trashed_at IS NOT NULL")
    suspend fun getTrashObjectsSnapshot(): List<ObjectEntity>

    // Links & Backlinks
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<ObjectLinkEntity>)

    @Query("DELETE FROM object_links WHERE source_id = :sourceId")
    suspend fun deleteLinksForSource(sourceId: String)

    @Query("""
        SELECT DISTINCT o.* FROM objects o
        INNER JOIN object_links l ON o.id = l.source_id
        WHERE l.target_id = :targetId AND o.trashed_at IS NULL
    """)
    fun getBacklinkObjects(targetId: String): Flow<List<ObjectEntity>>

    // Full text search. The MATCH runs once in a subquery: joining on fts.id (not indexable in
    // FTS4) scanned the whole FTS table per object — 308 s for 1100 hits among 10k objects.
    @Query("""
        SELECT o.* FROM objects o
        WHERE o.id IN (SELECT id FROM objects_fts WHERE objects_fts MATCH :query)
          AND o.trashed_at IS NULL
        ORDER BY o.updated_at DESC
        LIMIT :limit
    """)
    fun search(query: String, limit: Int = SEARCH_LIMIT): Flow<List<ObjectEntity>>

    @Query("DELETE FROM objects_fts WHERE id = :id")
    suspend fun deleteFts(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(fts: ObjectFtsEntity)

    @Transaction
    suspend fun saveObjectWithIndex(
        entity: ObjectEntity,
        links: List<ObjectLinkEntity>,
        fts: ObjectFtsEntity,
    ) {
        upsertObject(entity)
        deleteLinksForSource(entity.id)
        if (links.isNotEmpty()) {
            insertLinks(links)
        }
        deleteFts(entity.id)
        insertFts(fts)
    }

    // Object Types
    @Query("SELECT * FROM object_types")
    fun getAllTypes(): Flow<List<ObjectTypeEntity>>

    @Query("SELECT * FROM object_types WHERE id = :id")
    suspend fun getTypeById(id: String): ObjectTypeEntity?

    @Query("DELETE FROM object_types WHERE id IN (:ids)")
    suspend fun deleteTypesByIds(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertType(type: ObjectTypeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTypes(types: List<ObjectTypeEntity>)

    // Assets
    @Query("SELECT * FROM assets WHERE object_id = :objectId")
    fun getAssetsForObject(objectId: String): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getAssetById(id: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAsset(asset: AssetEntity)

    @Query("DELETE FROM assets WHERE id = :id")
    suspend fun deleteAsset(id: String)

    // ------------------------------------------------------------------
    // Outbox of encrypted ops + vault metadata
    // ------------------------------------------------------------------

    @Query("SELECT * FROM outbox_ops ORDER BY created_at ASC")
    suspend fun getOutboxOps(): List<OutboxOpEntity>

    @Query("SELECT COUNT(*) FROM outbox_ops")
    suspend fun outboxCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutboxOp(op: OutboxOpEntity)

    @Query("DELETE FROM outbox_ops WHERE op_id IN (:opIds)")
    suspend fun deleteOutboxOps(opIds: List<String>)

    @Query("UPDATE outbox_ops SET attempts = attempts + 1, last_error = :reason WHERE op_id = :opId")
    suspend fun markOutboxOpFailed(opId: String, reason: String?)

    @Query("SELECT id FROM objects")
    suspend fun getAllObjectIds(): List<String>

    @Query("SELECT * FROM vault_meta WHERE id = 1")
    suspend fun getVaultMeta(): VaultMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVaultMeta(meta: VaultMetaEntity)

    @Query("UPDATE vault_meta SET last_seq = :lastSeq WHERE id = 1")
    suspend fun updateVaultLastSeq(lastSeq: Long)

    @Query("SELECT * FROM vault_domain_seq")
    suspend fun getDomainSeqs(): List<VaultDomainSeqEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDomainSeq(seq: VaultDomainSeqEntity)

    @Query("SELECT last_seq FROM vault_domain_seq WHERE domain = :domain")
    suspend fun getDomainSeq(domain: String): Long?
}

/**
 * Hits decoded per search (perf budget spec §17.2): the MATCH is cheap, decoding each hit's JSON
 * is not — 1100 hits among 10k objects took 2.7 s. The newest [SEARCH_LIMIT] are shown; refining the
 * query narrows them.
 */
const val SEARCH_LIMIT = 100
