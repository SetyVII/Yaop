package org.yaop.core.repository

import org.yaop.core.model.AssetMeta
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Contract for querying, observing, and persisting [PkmObject] instances,
 * including full-text search, backlinks, and trash management.
 */
interface ObjectRepository {
    fun getAllActiveObjects(): Flow<List<PkmObject>>
    suspend fun getAllActiveObjectsSnapshot(): List<PkmObject>
    fun getObjectsByType(typeId: String): Flow<List<PkmObject>>
    fun observeObject(id: String): Flow<PkmObject?>
    suspend fun getObject(id: String): PkmObject?
    fun getCachedObject(id: String): PkmObject? = null
    fun getCachedObjectsByType(typeId: String): List<PkmObject> = emptyList()
    fun getCachedAllActiveObjects(): List<PkmObject> = emptyList()
    suspend fun saveObject(obj: PkmObject)
    fun getTrashObjects(): Flow<List<PkmObject>>
    suspend fun moveToTrash(id: String)
    suspend fun restoreFromTrash(id: String)
    suspend fun hardDelete(id: String)
    fun getBacklinks(targetId: String): Flow<List<PkmObject>>
    fun search(query: String): Flow<List<PkmObject>>
}

/**
 * Contract for managing object schema definitions and built-in primordial types.
 */
interface ObjectTypeRepository {
    fun getAllTypes(): Flow<List<ObjectType>>
    fun getCachedTypes(): List<ObjectType> = emptyList()
    suspend fun saveType(type: ObjectType)
    suspend fun syncDefaultTypes()
}

/**
 * Contract for binary media asset metadata tracking.
 */
interface AssetRepository {
    fun getAssetsForObject(objectId: String): Flow<List<AssetMeta>>
    suspend fun getAsset(assetId: String): AssetMeta?
    suspend fun saveAsset(asset: AssetMeta)
    suspend fun deleteAsset(id: String)
}

/**
 * Contract for taxonomy / tag management.
 */
interface TagRepository {
    suspend fun getOrCreateTagObject(name: String, colorHex: String? = null): PkmObject
}

/**
 * Contract for export and import to canonical sync wire format.
 */
interface SyncTransferService {
    suspend fun exportObjectForServer(id: String): JSONObject?
    suspend fun importObjectFromServer(rawJson: JSONObject): PkmObject
}
