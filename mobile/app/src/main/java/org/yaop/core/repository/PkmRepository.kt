package org.yaop.core.repository

import org.yaop.core.crypto.AndroidKeyStoreVault
import org.yaop.core.crypto.vault.VaultCryptoSession
import org.yaop.core.crypto.vault.VaultKeyStore
import org.yaop.core.sync.MergeContract
import org.yaop.core.database.AssetEntity
import org.yaop.core.database.OutboxOpEntity
import org.yaop.core.database.AppDatabase
import org.yaop.core.database.ObjectEntity
import org.yaop.core.database.ObjectFtsEntity
import org.yaop.core.database.ObjectLinkEntity
import org.yaop.core.database.ObjectTypeEntity
import org.yaop.core.model.AssetMeta
import org.yaop.core.model.Block
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HybridLogicalClock
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.ObjectIcon
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.TagPalette
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import org.yaop.core.sync.SyncContractCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class PkmRepository(
    database: AppDatabase,
    private val json: Json,
    keyStoreVault: AndroidKeyStoreVault? = null,
) : ObjectRepository, ObjectTypeRepository, AssetRepository, TagRepository, SyncTransferService {
    private val dao = database.objectDao()

    /** E2EE vault: VK wrapped by the Android Keystore + persistent pull cursor. */
    val vaultKeyStore: VaultKeyStore = VaultKeyStore(dao, keyStoreVault)

    /** Locally edited types not yet pushed as kind=type ops (spec/sync.md). */
    private val dirtyTypeIds = ConcurrentHashMap<String, ObjectType>()
    var onObjectSavedListener: (() -> Unit)? = null
    var activeEditingObjectId: String? = null

    // High-performance in-memory object cache to eliminate cold SQLite reads and repetitive JSON decodes
    private val objectCache = ConcurrentHashMap<String, PkmObject>()

    // Stable Flow instances. Compose's collectAsState restarts collection whenever the Flow
    // instance changes, so creating a new Room Flow inside composition would re-run SQLCipher
    // queries on every recomposition. Caching them keeps the queries subscribed once.
    private val flowCache = ConcurrentHashMap<String, Flow<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> cachedFlow(key: String, create: () -> Flow<T>): Flow<T> =
        flowCache.getOrPut(key) { create() } as Flow<T>

    @Volatile
    private var cachedActiveObjectsList: List<PkmObject> = emptyList()

    @Volatile
    private var cachedTypesList: List<ObjectType> = DefaultTypes.ALL

    @Volatile
    private var defaultsSynced: Boolean = false

    private fun refreshCachedActiveObjectsList() {
        cachedActiveObjectsList = deduplicateMediaObjects(
            objectCache.values
                .asSequence()
                .filter { it.trashedAt == null }
                .sortedByDescending { it.updatedAt }
                .toList(),
        )
    }

    private fun isUiEquivalent(a: PkmObject?, b: PkmObject?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        // Every content mutation flows through saveObject(), which bumps `revision` and `updatedAt`.
        // These cheap fields are therefore a sufficient UI-equivalence signature, avoiding a deep
        // equals() over every block/span for each object on every Room emission.
        return a.id == b.id &&
            a.revision == b.revision &&
            a.updatedAt == b.updatedAt &&
            a.isPinned == b.isPinned &&
            a.trashedAt == b.trashedAt &&
            a.syncStatus == b.syncStatus
    }

    private fun isUiEquivalentList(a: List<PkmObject>, b: List<PkmObject>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (!isUiEquivalent(a[i], b[i])) return false
        }
        return true
    }

    private fun decodeOrGetCached(entity: ObjectEntity): PkmObject? {
        val cached = objectCache[entity.id]
        return if (cached != null && cached.updatedAt == entity.updatedAt) {
            cached
        } else {
            deserializeObject(entity.payloadJson)?.also { objectCache[entity.id] = it }
        }
    }

    private fun typeAliases(typeId: String): List<String> {
        val canonical = typeId
        return listOf(canonical, typeId).distinct()
    }

    override fun getAllActiveObjects(): Flow<List<PkmObject>> =
        cachedFlow("all_active") {
            dao.getAllActiveObjects().map { list ->
                val objects = list.mapNotNull(::decodeOrGetCached)
                deduplicateMediaObjects(objects).also { cachedActiveObjectsList = it }
            }.distinctUntilChanged(::isUiEquivalentList).flowOn(Dispatchers.Default)
        }

    override suspend fun getAllActiveObjectsSnapshot(): List<PkmObject> = withContext(Dispatchers.IO) {
        val list = dao.getAllActiveObjectsSnapshot()
        val objects = list.mapNotNull(::decodeOrGetCached)
        deduplicateMediaObjects(objects).also { cachedActiveObjectsList = it }
    }

    /** Includes trashed objects so sync outbox and bootstrap snapshots propagate tombstones. */
    suspend fun getAllObjectsSnapshot(): List<PkmObject> = withContext(Dispatchers.IO) {
        dao.getAllObjectsSnapshot().mapNotNull(::decodeOrGetCached)
    }

    /**
     * Recomputes every object's local links (backlinks index) from its current content. Local
     * only: nothing is saved, stamped or synced. Used once after a links rule changes, so stale
     * entries (e.g. old `#word` text links) go away without re-saving each note.
     */
    suspend fun rebuildLinkIndex() = withContext(Dispatchers.IO) {
        for (obj in dao.getAllObjectsSnapshot().mapNotNull(::decodeOrGetCached)) {
            val links = extractLinks(obj)
            dao.replaceLinks(obj.id, links)
        }
    }

    override fun getObjectsByType(typeId: String): Flow<List<PkmObject>> =
        cachedFlow("type:$typeId") {
            dao.getObjectsByTypes(typeAliases(typeId)).map { list ->
                val objects = list.mapNotNull(::decodeOrGetCached)
                val canonical = typeId
                if (canonical == DefaultTypes.IMAGE.id || canonical == DefaultTypes.AUDIO.id) {
                    deduplicateMediaObjects(objects)
                } else {
                    objects
                }
            }.distinctUntilChanged(::isUiEquivalentList).flowOn(Dispatchers.Default)
        }

    private fun isManagedMediaType(typeId: String): Boolean {
        val canonical = typeId
        return canonical == DefaultTypes.IMAGE.id || canonical == DefaultTypes.AUDIO.id
    }

    private fun resolveMediaAssetIdFromObject(obj: PkmObject): String? {
        val propAssetId = (obj.property("asset_id") as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
            ?: (obj.property("asset_id") as? PropertyValue.Media)?.assetId?.takeIf { it.isNotBlank() }
            ?: (obj.properties["asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
            ?: (obj.properties["prop_asset_id"] as? PropertyValue.RichText)?.spans?.toPlainText()?.takeIf { it.isNotBlank() }
        if (!propAssetId.isNullOrBlank()) return propAssetId

        if (obj.id.startsWith("media_")) {
            val stripped = obj.id.removePrefix("media_").trim()
            if (stripped.isNotBlank()) return stripped
        }

        val canonical = obj.typeId
        return when (canonical) {
            DefaultTypes.IMAGE.id -> obj.blocks.filterIsInstance<Block.Image>().firstOrNull { it.assetId.isNotBlank() }?.assetId
            DefaultTypes.AUDIO.id -> obj.blocks.filterIsInstance<Block.Audio>().firstOrNull { it.assetId.isNotBlank() }?.assetId
            else -> null
        }
    }

    private fun isAutoGeneratedMediaTitle(plainTitle: String, assetId: String, fileName: String?): Boolean {
        val plain = plainTitle.trim()
        if (plain.isBlank()) return true
        val cleanFileName = fileName?.trim()
        val fileNameWithoutExt = cleanFileName?.substringBeforeLast('.')?.trim()
        return plain.equals("Untitled", ignoreCase = true) ||
            plain.equals("Untitled object", ignoreCase = true) ||
            plain.equals("Título", ignoreCase = true) ||
            plain.equals("Sin título", ignoreCase = true) ||
            plain.equals(assetId, ignoreCase = true) ||
            (!cleanFileName.isNullOrBlank() && plain.equals(cleanFileName, ignoreCase = true)) ||
            (!fileNameWithoutExt.isNullOrBlank() && plain.equals(fileNameWithoutExt, ignoreCase = true)) ||
            DIGITS_ONLY_REGEX.matches(plain) ||
            AUTO_MEDIA_PREFIX_REGEX.matches(plain) ||
            AUTO_MEDIA_EXT_REGEX.matches(plain) ||
            AUTO_AUDIO_REC_REGEX.matches(plain)
    }

    private fun selectBestMediaObject(assetId: String, group: List<PkmObject>): PkmObject {
        if (group.size == 1) return group.first()
        return group.maxWithOrNull(
            compareBy<PkmObject> { obj ->
                val fileName = (obj.property("file_name") as? PropertyValue.RichText)?.spans?.toPlainText()?.trim()
                val isAuto = isAutoGeneratedMediaTitle(obj.plainTitle, assetId, fileName)
                if (!isAuto) 2 else 1
            }.thenByDescending { it.updatedAt }
        ) ?: group.first()
    }

    private fun deduplicateMediaObjects(objects: List<PkmObject>): List<PkmObject> {
        val nonMedia = mutableListOf<PkmObject>()
        val groupedByTypeAndAsset = mutableMapOf<Pair<String, String>, MutableList<PkmObject>>()

        for (obj in objects) {
            if (!isManagedMediaType(obj.typeId)) {
                nonMedia.add(obj)
                continue
            }
            val canonicalType = obj.typeId
            val assetId = resolveMediaAssetIdFromObject(obj)

            // An Image cannot exist without an image; an Audio cannot exist without an audio:
            // ignore orphan media objects without an underlying assetId
            if (!assetId.isNullOrBlank()) {
                groupedByTypeAndAsset.getOrPut(canonicalType to assetId) { mutableListOf() }.add(obj)
            }
        }

        val result = mutableListOf<PkmObject>()
        result.addAll(nonMedia)

        for ((key, group) in groupedByTypeAndAsset) {
            val (_, assetId) = key
            result.add(selectBestMediaObject(assetId, group))
        }
        return result
    }

    override fun observeObject(id: String): Flow<PkmObject?> =
        cachedFlow("object:$id") {
            dao.observeObjectById(id).map { entity ->
                if (entity == null) {
                    objectCache.remove(id)
                    null
                } else {
                    decodeOrGetCached(entity)
                }
            }.distinctUntilChanged(::isUiEquivalent).flowOn(Dispatchers.Default)
        }

    override suspend fun getObject(id: String): PkmObject? {
        val cached = objectCache[id]
        if (cached != null) return cached
        return withContext(Dispatchers.IO) {
            dao.getObjectById(id)?.let(::decodeOrGetCached)
        }
    }

    override fun getCachedObject(id: String): PkmObject? = objectCache[id]

    override fun getCachedObjectsByType(typeId: String): List<PkmObject> {
        val canonical = typeId
        val source = cachedActiveObjectsList.ifEmpty { objectCache.values.toList() }
        return source.filter { it.typeId == canonical }
    }

    override fun getCachedAllActiveObjects(): List<PkmObject> = cachedActiveObjectsList

    override fun getCachedTypes(): List<ObjectType> = cachedTypesList

    override suspend fun saveObject(obj: PkmObject) {
        val normalized = DefaultTypes.normalizeObject(obj)
        // Guard: never persist an orphan Image or Audio object without its media asset
        if (isManagedMediaType(normalized.typeId)) {
            val assetId = resolveMediaAssetIdFromObject(normalized)
            if (assetId.isNullOrBlank()) return
        }
        // Field/block stamps (spec/merge.md): diff the previous saved version on the wire JSON.
        val prev = getObject(normalized.id)
        val now = HybridLogicalClock.now()
        val stamp = MergeContract.stamp(
            prev = prev?.let { SyncContractCodec.toServerJson(it) },
            next = SyncContractCodec.toServerJson(normalized),
            prevMeta = MergeContract.Meta(
                prev?.fieldHlc.orEmpty(),
                prev?.blockHlc.orEmpty(),
                prev?.deletedBlocks.orEmpty(),
                prev?.blockAnchor.orEmpty().mapValues { org.yaop.core.model.BlockAnchor(it.value.after, it.value.hlc) },
            ),
            now = now,
        )
        if (prev != null && !stamp.changed) return
        val nowIso = Instant.now().toString()
        val updatedObj = normalized.copy(
            updatedAt = nowIso,
            hlc = now,
            revision = normalized.revision + 1L,
            syncStatus = if (normalized.syncStatus == "synced") "pending" else normalized.syncStatus,
            fieldHlc = stamp.meta.fieldHlc,
            blockHlc = stamp.meta.blockHlc,
            deletedBlocks = stamp.meta.deletedBlocks,
            blockAnchor = stamp.meta.blockAnchor.mapValues { org.yaop.core.model.BlockAnchor(it.value.after, it.value.hlc) },
            schemaVersion = org.yaop.core.sync.SyncContractCodec.SCHEMA_VERSION,
        )
        persistNormalizedObject(updatedObj, notifyListener = true)
    }

    /**
     * Marks [id] as synced only if it still is the version that was pushed ([pushedHlc]):
     * an edit saved while the push was in flight must stay pending, or it is never sent .
     */
    suspend fun markSynced(id: String, pushedHlc: String? = null) = withContext(Dispatchers.IO) {
        val obj = getObject(id) ?: return@withContext
        if (pushedHlc != null && obj.hlc != pushedHlc) return@withContext
        if (obj.syncStatus != "synced") {
            val syncedObj = obj.copy(syncStatus = "synced")
            objectCache[id] = syncedObj
            val payloadJson = json.encodeToString(syncedObj)
            val entity = dao.getObjectById(id)?.copy(payloadJson = payloadJson)
            if (entity != null) {
                dao.upsertObject(entity)
            }
        }
    }

    private suspend fun persistNormalizedObject(updatedObj: PkmObject, notifyListener: Boolean = true) = withContext(Dispatchers.IO) {
        objectCache[updatedObj.id] = updatedObj
        refreshCachedActiveObjectsList()
        val payloadJson = json.encodeToString(updatedObj)
        val entity = ObjectEntity(
            id = updatedObj.id,
            typeId = updatedObj.typeId,
            plainTitle = updatedObj.plainTitle,
            payloadJson = payloadJson,
            createdAt = updatedObj.createdAt,
            updatedAt = updatedObj.updatedAt,
            hlc = updatedObj.hlc,
            isPinned = updatedObj.isPinned,
            trashedAt = updatedObj.trashedAt,
        )

        val links = extractLinks(updatedObj)
        val plainContent = extractSearchableContent(updatedObj)
        val fts = ObjectFtsEntity(
            id = updatedObj.id,
            title = updatedObj.plainTitle,
            content = plainContent,
            typeId = updatedObj.typeId,
        )

        dao.saveObjectWithIndex(entity, links, fts)
        if (notifyListener) {
            onObjectSavedListener?.invoke()
        }
    }

    override suspend fun moveToTrash(id: String) = withContext(Dispatchers.IO) {
        val ts = Instant.now().toString()
        val existing = getObject(id)
        if (existing == null) {
            objectCache.remove(id)
            dao.moveToTrash(id, ts)
            refreshCachedActiveObjectsList()
            return@withContext
        }
        val effectiveTrashed = existing.trashedAt?.takeIf { it.isNotBlank() } ?: ts
        val hlc = HybridLogicalClock.now()
        val updated = existing.copy(
            trashedAt = effectiveTrashed,
            updatedAt = ts,
            hlc = hlc,
            // spec/merge.md §Stamp 2: the tombstone carries its own stamp so a later restore wins.
            fieldHlc = if (existing.trashedAt.isNullOrBlank()) existing.fieldHlc + (MergeContract.FIELD_TRASHED to hlc) else existing.fieldHlc,
            revision = existing.revision + 1L,
            syncStatus = "pending",
            schemaVersion = org.yaop.core.sync.SyncContractCodec.SCHEMA_VERSION,
        )
        persistNormalizedObject(updated, notifyListener = true)
    }

    override suspend fun restoreFromTrash(id: String) = withContext(Dispatchers.IO) {
        val existing = getObject(id)
        if (existing == null) {
            dao.restoreFromTrash(id)
            dao.getObjectById(id)?.let(::decodeOrGetCached)
            refreshCachedActiveObjectsList()
            return@withContext
        }
        val ts = Instant.now().toString()
        val hlc = HybridLogicalClock.now()
        val updated = existing.copy(
            trashedAt = null,
            updatedAt = ts,
            hlc = hlc,
            fieldHlc = existing.fieldHlc + (MergeContract.FIELD_TRASHED to hlc),
            revision = existing.revision + 1L,
            syncStatus = "pending",
            schemaVersion = org.yaop.core.sync.SyncContractCodec.SCHEMA_VERSION,
        )
        persistNormalizedObject(updated, notifyListener = true)
    }

    override suspend fun hardDelete(id: String) = withContext(Dispatchers.IO) {
        objectCache.remove(id)
        refreshCachedActiveObjectsList()
        dao.hardDelete(id)
        dao.deleteLinksForSource(id)
        dao.deleteFts(id)
    }

    /**
     * Local query (spec/query.md) over the active vault. Each object goes to the engine as its wire
     * JSON without `blocks` (no operator reads them), so a query over the whole vault stays cheap.
     * [today] is the local day; spec §20 (day start) applies once it exists.
     */
    suspend fun runQuery(
        query: org.json.JSONObject,
        today: java.time.LocalDate = java.time.LocalDate.now(),
    ): org.yaop.core.query.QueryEngine.Result = withContext(Dispatchers.Default) {
        val objects = getAllActiveObjectsSnapshot().map { obj ->
            SyncContractCodec.toServerJson(obj).apply {
                put("type_id", obj.typeId)
                optJSONObject("properties")?.remove("blocks")
            }
        }
        org.yaop.core.query.QueryEngine.evaluate(query, objects, today.toString())
    }

    // ---- Saved sets (spec/query.md) ----

    fun getSets(): Flow<List<PkmObject>> = getObjectsByType(CanonicalTypeIds.SET)

    suspend fun createSet(name: String, query: org.json.JSONObject = org.json.JSONObject().put("version", 1)): String {
        val id = java.util.UUID.randomUUID().toString()
        val base = PkmObject(
            id = id,
            typeId = CanonicalTypeIds.SET,
            title = name.toTextSpan(),
            icon = ObjectIcon(emoji = DefaultTypes.SET.icon.emoji),
        )
        saveObject(org.yaop.core.query.SavedSets.withQuery(base, query))
        return id
    }

    suspend fun updateSetQuery(setId: String, query: org.json.JSONObject) {
        val obj = getObject(setId) ?: return
        saveObject(org.yaop.core.query.SavedSets.withQuery(obj, query))
    }

    suspend fun setSetActiveView(setId: String, kind: String) {
        val obj = getObject(setId) ?: return
        saveObject(org.yaop.core.query.SavedSets.withActiveKind(obj, kind))
    }

    suspend fun renameObject(id: String, title: String) {
        val obj = getObject(id) ?: return
        val clean = title.trim()
        if (clean.isEmpty() || obj.plainTitle == clean) return
        saveObject(obj.copy(title = clean.toTextSpan()))
    }

    /** Objects of a saved set, in the query's order (a set never lists itself). */
    suspend fun runSet(setId: String): Result<List<PkmObject>> {
        val obj = getObject(setId) ?: return Result.success(emptyList())
        return runCatching {
            val r = runQuery(org.yaop.core.query.SavedSets.queryOf(obj))
            r.ids.filter { it != setId }.mapNotNull { getObject(it) }
        }
    }
    /**
     * Trash retention (spec §3.4): drops objects 30+ days in the trash whose tombstone is already
     * synced. Returns how many were purged. Runs offline too; nothing is sent (the tombstone already traveled).
     */
    suspend fun purgeExpiredTrash(now: Instant = Instant.now()): Int = withContext(Dispatchers.IO) {
        val doomed = dao.getTrashObjectsSnapshot()
            .mapNotNull(::decodeOrGetCached)
            .filter { org.yaop.core.sync.SyncPolicy.canPurgeTrashed(it.trashedAt, it.syncStatus, now) }
        for (obj in doomed) hardDelete(obj.id)
        doomed.size
    }

    override fun getTrashObjects(): Flow<List<PkmObject>> =
        cachedFlow("trash") {
            dao.getTrashObjects().map { list ->
                list.mapNotNull { entity -> deserializeObject(entity.payloadJson) }
            }.distinctUntilChanged(::isUiEquivalentList).flowOn(Dispatchers.Default)
        }

    override fun getBacklinks(targetId: String): Flow<List<PkmObject>> =
        cachedFlow("backlinks:$targetId") {
            dao.getBacklinkObjects(targetId).map { list ->
                list.mapNotNull(::decodeOrGetCached)
            }.distinctUntilChanged(::isUiEquivalentList).flowOn(Dispatchers.Default)
        }

    override fun search(query: String): Flow<List<PkmObject>> {
        val q = query.trim()
        if (q.isBlank()) return getAllActiveObjects()
        val ftsQuery = q.split(WHITESPACE_REGEX)
            .map { token -> token.filter { it.isLetterOrDigit() || it == '_' } }
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }
        if (ftsQuery.isBlank()) return getAllActiveObjects()
        return dao.search(ftsQuery).map { list ->
            deduplicateMediaObjects(list.mapNotNull(::decodeOrGetCached))
        }.distinctUntilChanged(::isUiEquivalentList).flowOn(Dispatchers.Default)
    }

    override fun getAllTypes(): Flow<List<ObjectType>> =
        cachedFlow("all_types") {
            dao.getAllTypes().map { list ->
                list.mapNotNull { entity -> deserializeType(entity.payloadJson) }
                    .distinctBy { it.id }
                    .also { if (it.isNotEmpty()) cachedTypesList = it }
            }.distinctUntilChanged().flowOn(Dispatchers.Default)
        }

    override suspend fun saveType(type: ObjectType) = withContext(Dispatchers.IO) {
        val normalizedId = type.id
        val normalizedType = type.copy(id = normalizedId)
        dirtyTypeIds[normalizedId] = normalizedType
        dao.upsertType(
            ObjectTypeEntity(
                id = normalizedType.id,
                name = normalizedType.name,
                payloadJson = json.encodeToString(normalizedType),
            ),
        )
    }

    override suspend fun syncDefaultTypes() = withContext(Dispatchers.IO) {
        if (defaultsSynced) {
            if (cachedActiveObjectsList.isEmpty()) {
                getAllActiveObjectsSnapshot()
            }
            return@withContext
        }
        val typeEntities = DefaultTypes.ALL.map { type ->
            ObjectTypeEntity(
                id = type.id,
                name = type.name,
                payloadJson = json.encodeToString(type),
            )
        }
        dao.upsertTypes(typeEntities)
        cachedTypesList = DefaultTypes.ALL

        // Populate objectCache in a single pass
        val snapshot = dao.getAllActiveObjectsSnapshot()
        val allDecoded = mutableListOf<PkmObject>()
        for (entity in snapshot) {
            val decoded = runCatching { json.decodeFromString<PkmObject>(entity.payloadJson) }.getOrNull() ?: continue
            val normalized = DefaultTypes.normalizeObject(decoded)
            if (normalized.typeId != entity.typeId || normalized != decoded) {
                persistNormalizedObject(normalized, notifyListener = false)
            } else {
                objectCache[normalized.id] = normalized
            }
            allDecoded.add(normalized)
        }

        val existingMediaAssetIds = allDecoded
            .asSequence()
            .filter { isManagedMediaType(it.typeId) }
            .mapNotNull(::resolveMediaAssetIdFromObject)
            .toHashSet()

        // 1. Ensure every Image and Audio block across all objects has its canonical Media Object
        for (owner in allDecoded) {
            for (block in owner.blocks) {
                when (block) {
                    is Block.Audio -> {
                        if (block.assetId.isNotBlank() && block.assetId !in existingMediaAssetIds) {
                            val existingAsset = getAsset(block.assetId)
                            val fallbackName = block.caption.toPlainText().trim().ifBlank {
                                existingAsset?.name?.takeIf { it.isNotBlank() } ?: "Audio (${block.durationSeconds}s)"
                            }
                            saveAsset(
                                AssetMeta(
                                    id = block.assetId,
                                    objectId = existingAsset?.objectId ?: owner.id,
                                    name = fallbackName,
                                    mimeType = existingAsset?.mimeType ?: "audio/mp4",
                                    sizeBytes = existingAsset?.sizeBytes ?: 0L,
                                    createdAt = existingAsset?.createdAt ?: owner.createdAt,
                                    isPinned = existingAsset?.isPinned ?: false,
                                    durationSeconds = block.durationSeconds.takeIf { it > 0 },
                                ),
                            )
                            existingMediaAssetIds.add(block.assetId)
                        }
                    }
                    is Block.Image -> {
                        if (block.assetId.isNotBlank() && block.assetId !in existingMediaAssetIds) {
                            val existingAsset = getAsset(block.assetId)
                            if (existingAsset != null) {
                                saveAsset(existingAsset)
                                existingMediaAssetIds.add(block.assetId)
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // 2. Deduplicate duplicate image & audio objects & purge orphan image/audio objects without media
        val mediaObjects = objectCache.values.filter { isManagedMediaType(it.typeId) }

        val byTypeAndAsset = mutableMapOf<Pair<String, String>, MutableList<PkmObject>>()
        for (obj in mediaObjects) {
            val canonicalType = obj.typeId
            val assetId = resolveMediaAssetIdFromObject(obj)

            if (assetId.isNullOrBlank()) {
                // An audio object cannot exist without audio, nor an image without an image: purge the orphan
                hardDelete(obj.id)
            } else {
                byTypeAndAsset.getOrPut(canonicalType to assetId) { mutableListOf() }.add(obj)
            }
        }

        for ((key, group) in byTypeAndAsset) {
            val (_, assetId) = key
            if (group.size > 1) {
                // The user-given name wins over the raw file id or an auto-generated title
                val best = selectBestMediaObject(assetId, group)
                val mergedProps = mutableMapOf<String, PropertyValue>()
                for (other in group) {
                    if (other.id != best.id) {
                        mergedProps.putAll(other.properties)
                    }
                }
                mergedProps.putAll(best.properties)
                if (mergedProps != best.properties) {
                    persistNormalizedObject(best.copy(properties = mergedProps), notifyListener = false)
                }
                for (other in group) {
                    if (other.id != best.id) {
                        hardDelete(other.id)
                    }
                }
            }
        }

        refreshCachedActiveObjectsList()
        defaultsSynced = true
    }

    override suspend fun getOrCreateTagObject(name: String, colorHex: String?): PkmObject {
        val cleanName = name.trim().removePrefix("#")
        val cleanId = "tag_${sanitizeTagKey(cleanName)}"
        val existing = getObject(cleanId)
            ?: dao.findObjectByTypesAndTitle(typeAliases(DefaultTypes.TAG.id), cleanName)
                ?.let { deserializeObject(it.payloadJson) }
        if (existing != null) {
            return existing
        }

        val newTag = PkmObject(
            id = cleanId,
            typeId = DefaultTypes.TAG.id,
            title = cleanName.toTextSpan(),
            icon = ObjectIcon(
                emoji = "🏷️",
                iconName = "tag",
                colorHex = colorHex ?: TagPalette.getColorHex(cleanName.hashCode()),
            ),
        )
        saveObject(newTag)
        return newTag
    }

    override fun getAssetsForObject(objectId: String): Flow<List<AssetMeta>> =
        cachedFlow("assets:$objectId") {
            dao.getAssetsForObject(objectId).map { list ->
                list.map { entity ->
                    AssetMeta(
                        id = entity.id,
                        objectId = entity.objectId,
                        name = entity.name,
                        mimeType = entity.mimeType,
                        sizeBytes = entity.sizeBytes,
                        createdAt = entity.createdAt,
                        isPinned = entity.isPinned,
                    )
                }
            }
        }

    override suspend fun getAsset(assetId: String): AssetMeta? =
        dao.getAssetById(assetId)?.let { entity ->
            AssetMeta(
                id = entity.id,
                objectId = entity.objectId,
                name = entity.name,
                mimeType = entity.mimeType,
                sizeBytes = entity.sizeBytes,
                createdAt = entity.createdAt,
                isPinned = entity.isPinned,
            )
        }

    /**
     * Persists [AssetMeta] in Room and ensures the canonical reusable media [PkmObject]
     * (`type_id = "image" | "pdf" | "audio"`) is created and linked to its owner per `SYNC.md`.
     */
    override suspend fun saveAsset(asset: AssetMeta) = withContext(Dispatchers.IO) {
        dao.upsertAsset(
            AssetEntity(
                id = asset.id,
                objectId = asset.objectId,
                name = asset.name,
                mimeType = asset.mimeType,
                sizeBytes = asset.sizeBytes,
                createdAt = asset.createdAt,
                isPinned = asset.isPinned,
            ),
        )
        SyncContractCodec.buildMediaObjectForAsset(asset)?.let { mediaObject ->
            val mediaCanonicalType = mediaObject.typeId
            val ownerObj = asset.objectId?.let { getObject(it) }
            val isOwnerSameMediaType = ownerObj != null &&
                ownerObj.typeId == mediaCanonicalType

            val targetId = if (isOwnerSameMediaType) {
                ownerObj.id
            } else {
                asset.mediaObjectId?.takeIf { it.isNotBlank() } ?: mediaObject.id
            }

            // Look for existing object by targetId or by asset_id property in objectCache (or DB snapshot if cache empty)
            val searchPool = if (objectCache.isNotEmpty()) objectCache.values else getAllActiveObjectsSnapshot()
            val existing = getObject(targetId)?.takeIf {
                it.typeId == mediaCanonicalType
            } ?: searchPool.firstOrNull {
                it.typeId == mediaCanonicalType &&
                    resolveMediaAssetIdFromObject(it) == asset.id
            }

            if (existing == null) {
                saveObject(mediaObject.copy(id = targetId))
            } else {
                // The user-given name wins:
                // If existing has a user-assigned custom title, preserve it!
                val existingFileName = (existing.property("file_name") as? PropertyValue.RichText)?.spans?.toPlainText()?.trim()
                val hasUserCustomTitle = !isAutoGeneratedMediaTitle(existing.plainTitle, asset.id, existingFileName)
                val resolvedTitle = if (hasUserCustomTitle) existing.title else mediaObject.title

                // Merge properties while keeping existing non-zero size/duration if asset meta was a lightweight sync
                val mergedProps = existing.properties.toMutableMap()
                for ((k, v) in mediaObject.properties) {
                    if (k == "size" && v is PropertyValue.Number && (v.value ?: 0.0) <= 0.0 && mergedProps.containsKey("size")) {
                        continue
                    }
                    if (k == "source_object_id" && v is PropertyValue.ObjectRelation) {
                        val prevIds = (mergedProps["source_object_id"] as? PropertyValue.ObjectRelation)?.targetObjectIds.orEmpty()
                        mergedProps["source_object_id"] = PropertyValue.ObjectRelation((prevIds + v.targetObjectIds).distinct())
                        continue
                    }
                    mergedProps[k] = v
                }

                val resolvedIcon = existing.icon ?: mediaObject.icon
                if (existing.title != resolvedTitle || existing.icon != resolvedIcon || existing.properties != mergedProps) {
                    saveObject(
                        existing.copy(
                            title = resolvedTitle,
                            icon = resolvedIcon,
                            properties = mergedProps,
                        ),
                    )
                }

                // If mediaObject had a different id than existing, remove any redundant auto-generated media object
                if (mediaObject.id != existing.id) {
                    val duplicate = getObject(mediaObject.id)
                    if (duplicate != null) {
                        hardDelete(mediaObject.id)
                    }
                }
            }
        }
        Unit
    }

    override suspend fun deleteAsset(id: String) {
        dao.deleteAsset(id)
    }

    /**
     * Serializes an object and its outgoing links to the canonical server JSON payload (`SYNC.md`).
     */
    override suspend fun exportObjectForServer(id: String): JSONObject? {
        val obj = getObject(id) ?: return null
        return SyncContractCodec.toServerJson(obj)
    }

    /**
     * Merges a canonical server JSON object payload (`SYNC.md`) into the local encrypted database.
     */
    override suspend fun importObjectFromServer(rawJson: JSONObject): PkmObject {
        val id = rawJson.optString("id")
        val isActivelyEditing = id.isNotBlank() && id == activeEditingObjectId
        val existing = if (id.isNotBlank()) getObject(id) else null
        val merged = SyncContractCodec.fromServerJson(rawJson, existing, isActivelyEditing)
        if (existing != null && existing == merged) {
            return existing
        }
        persistNormalizedObject(merged, notifyListener = false)
        return merged
    }

    // ------------------------------------------------------------------
    // Blind sync /v1 (spec/sync.md, plan ): outbox sellada, vault y merge local
    // ------------------------------------------------------------------

    suspend fun vaultSession(): VaultCryptoSession? = vaultKeyStore.session()

    /** Creates the local vault (tests and onboarding). */
    suspend fun createVaultKey(vkBytes: ByteArray? = null, keyEpoch: Long = 1L): VaultCryptoSession =
        vaultKeyStore.createVaultKey(vkBytes, keyEpoch)

    suspend fun vaultLastSeq(): Long = vaultKeyStore.lastSeq()
    suspend fun setVaultLastSeq(seq: Long) = vaultKeyStore.setLastSeq(seq)

    suspend fun outboxOps(): List<OutboxOpEntity> = withContext(Dispatchers.IO) { dao.getOutboxOps() }
    suspend fun outboxCount(): Int = withContext(Dispatchers.IO) { dao.outboxCount() }
    suspend fun upsertOutboxOp(op: OutboxOpEntity) = withContext(Dispatchers.IO) { dao.upsertOutboxOp(op) }
    suspend fun deleteOutboxOps(opIds: List<String>) = withContext(Dispatchers.IO) { dao.deleteOutboxOps(opIds) }
    suspend fun markOutboxOpFailed(opId: String, reason: String?) =
        withContext(Dispatchers.IO) { dao.markOutboxOpFailed(opId, reason) }

    /** Every real id on the device (active and trashed): maps wire_id -> real id. */
    suspend fun allRealObjectIds(): List<String> = withContext(Dispatchers.IO) { dao.getAllObjectIds() }

    /** Types this device edited that have not travelled as kind=type ops yet. */
    /**
     * The server lost its log (reset/regenerated): everything this device holds must travel again.
     * Marks every active object pending and every type dirty; the next sync re-seals and pushes.
     */
    suspend fun markEverythingForResend() = withContext(Dispatchers.IO) {
        for (obj in getAllObjectsSnapshot()) {
            if (obj.syncStatus != "synced") continue
            val pending = obj.copy(syncStatus = "pending")
            objectCache[obj.id] = pending
            val entity = dao.getObjectById(obj.id)?.copy(payloadJson = json.encodeToString(pending))
            if (entity != null) dao.upsertObject(entity)
        }
        restoreDirtyTypes(getCachedTypes())
    }

    fun takeDirtyTypes(): List<ObjectType> {
        val snapshot = dirtyTypeIds.values.toList()
        dirtyTypeIds.clear()
        return snapshot
    }

    /** Marks a type dirty again when its op could not be pushed (keeps the retry). */
    fun restoreDirtyTypes(types: List<ObjectType>) {
        for (t in types) dirtyTypeIds[t.id] = t
    }

    /**
     * Applies MergeContract.merge(local, remote) as the NEW object state: the merged state is
     * imported ONCE (never twice with different rules).
     *
     * Status: if the merged wire equals the remote one, the server already has this state ->
     * "synced". Otherwise (a pending local edit was merged in) the local syncStatus is kept
     * (a "pending" stays "pending" and the push re-sends it).
     *
     * Returns [ApplyMergedResult.convergedRemote] = true when the result IS the remote wire
     * (nothing to re-queue), or false when the merge produced a new state that must travel back
     * to the server (the merge is deterministic and symmetric: both sides converge).
     */
    suspend fun applyMergedWire(realId: String, mergedWire: JSONObject, remoteWire: JSONObject): ApplyMergedResult {
        val existing = getObject(realId)
        val isActivelyEditing = realId == activeEditingObjectId
        val applied = SyncContractCodec.fromServerJson(mergedWire, existing, isActivelyEditing)
        // CANONICAL comparison (sorted keys, like merge/pick in Go): a plain .toString() would
        // differ by insertion order and re-queue an already converged state forever.
        val matchesRemote = MergeContract.canon(mergedWire) == MergeContract.canon(remoteWire)
        val nextStatus = if (matchesRemote) "synced" else existing?.syncStatus ?: "synced"
        val next = if (applied.syncStatus == nextStatus) applied else applied.copy(syncStatus = nextStatus)
        if (existing == null || existing != next) {
            persistNormalizedObject(next, notifyListener = false)
        }
        return ApplyMergedResult(matchedRemote = matchesRemote, needsPush = !matchesRemote)
    }

    /** Result of applying a merged wire (see [applyMergedWire]). */
    data class ApplyMergedResult(val matchedRemote: Boolean, val needsPush: Boolean)

    /**
     * Applies a kind=type op (type wire): ops arrive in ascending seq (natural LWW) and are
     * applied as-is. It persists WITHOUT marking the type dirty — otherwise every pull would push
     * the same type again and two devices would oscillate forever. If the type is dirty LOCALLY
     * and not pushed yet, the local one is kept (it is pushed and converges; content equality
     * prevents loops).
     */
    suspend fun applyTypeWire(wire: JSONObject) {
        val type = SyncContractCodec.typeFromWireJson(wire) ?: return
        val normalizedId = type.id
        if (dirtyTypeIds.containsKey(normalizedId)) return
        withContext(Dispatchers.IO) {
            val normalized = type.copy(id = normalizedId)
            dao.upsertType(
                ObjectTypeEntity(
                    id = normalized.id,
                    name = normalized.name,
                    payloadJson = json.encodeToString(normalized),
                ),
            )
        }
    }

    private suspend fun extractLinks(obj: PkmObject): List<ObjectLinkEntity> {
        val links = mutableListOf<ObjectLinkEntity>()

        for ((rawKey, value) in obj.properties) {
            val key = rawKey
            when (value) {
                is PropertyValue.ObjectRelation -> {
                    value.targetObjectIds.forEach { targetId ->
                        if (targetId.isNotBlank() && (targetId != obj.id)) {
                            links += ObjectLinkEntity(obj.id, targetId, "PROPERTY_RELATION")
                        }
                    }
                }
                is PropertyValue.MultiSelect -> {
                    if (key == "tags") {
                        value.selectedOptionIds.forEach { rawTag ->
                            val cleanTag = rawTag.trim().removePrefix("#")
                            if (cleanTag.isNotBlank()) {
                                val tagId = if (cleanTag.startsWith("tag_")) cleanTag else "tag_${sanitizeTagKey(cleanTag)}"
                                if (tagId != obj.id) {
                                    links += ObjectLinkEntity(obj.id, tagId, "TAG")
                                }
                            }
                        }
                    }
                }
                is PropertyValue.RichText -> {
                    collectFromSpans(obj.id, value.spans, links, "PROPERTY_MENTION")
                }
                is PropertyValue.DateRange -> {
                    val start = value.startDate.removePrefix("@").trim().take(10)
                    if (ISO_DATE_EXACT_REGEX.matches(start)) {
                        val dailyId = "daily-$start"
                        if (dailyId != obj.id) {
                            links += ObjectLinkEntity(obj.id, dailyId, "DATE_PROPERTY")
                        }
                    }
                }
                else -> Unit
            }
        }

        for (block in obj.blocks) {
            when (block) {
                is Block.Paragraph -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.Heading -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.Todo -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.BulletList -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.NumberedList -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.AlphabeticList -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.RomanList -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.Toggle -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.Quote -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.Callout -> collectFromSpans(obj.id, block.spans, links, "BLOCK_MENTION")
                is Block.ObjectEmbed -> {
                    if (block.targetObjectId != obj.id) {
                        links += ObjectLinkEntity(obj.id, block.targetObjectId, "OBJECT_EMBED")
                    }
                }
                is Block.Bookmark -> {
                    val targetId = block.targetObjectId
                    if (!targetId.isNullOrBlank() && targetId != obj.id) {
                        links += ObjectLinkEntity(obj.id, targetId, "BOOKMARK_EMBED")
                    }
                }
                is Block.Image -> {
                    if (block.assetId.isNotBlank()) {
                        val mediaTargetId = "media_${block.assetId}"
                        if (mediaTargetId != obj.id) {
                            links += ObjectLinkEntity(obj.id, mediaTargetId, "MEDIA_ATTACHMENT")
                        }
                    }
                }
                is Block.Audio -> {
                    if (block.assetId.isNotBlank()) {
                        val mediaTargetId = "media_${block.assetId}"
                        if (mediaTargetId != obj.id) {
                            links += ObjectLinkEntity(obj.id, mediaTargetId, "MEDIA_ATTACHMENT")
                        }
                    }
                }
                else -> Unit
            }
        }

        val searchable = extractSearchableContent(obj)

        // Resolve inline @[Type/Title] and [[Title]] mentions typed as text
        if (searchable.contains('@') || searchable.contains("[[")) {
            MENTION_TOKEN_REGEX.findAll(searchable).forEach { match ->
                val rawTarget = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
                val targetTitle = rawTarget.substringAfterLast('/').trim()
                if (targetTitle.isNotBlank()) {
                    val targetEntity = dao.findObjectByTitle(targetTitle)
                    if (targetEntity != null && targetEntity.id != obj.id) {
                        links += ObjectLinkEntity(obj.id, targetEntity.id, "BLOCK_MENTION")
                    }
                }
            }
        }

        // Resolve inline date mentions typed in content (e.g. @2026-11-08)
        if (searchable.contains('@')) {
            INLINE_DATE_MENTION_REGEX.findAll(searchable).forEach { match ->
                val dateStr = match.groupValues[1]
                val dailyId = "daily-$dateStr"
                if (dailyId != obj.id) {
                    links += ObjectLinkEntity(obj.id, dailyId, "DATE_MENTION")
                }
            }
        }

        return links.distinct()
    }

    private fun collectFromSpans(
        sourceId: String,
        spans: List<InlineSpan>,
        links: MutableList<ObjectLinkEntity>,
        mentionType: String,
    ) {
        spans.forEach { span ->
            if (span is InlineSpan.Mention && (span.targetObjectId != sourceId)) {
                links += ObjectLinkEntity(sourceId, span.targetObjectId, mentionType)
            }
            if (span is InlineSpan.Tag && (span.tagId != sourceId)) {
                links += ObjectLinkEntity(sourceId, span.tagId, "BLOCK_TAG")
            }
            if (span is InlineSpan.Date) {
                val cleanDate = span.isoDate.removePrefix("@").trim().take(10)
                if (ISO_DATE_EXACT_REGEX.matches(cleanDate)) {
                    val dailyId = "daily-$cleanDate"
                    if (dailyId != sourceId) {
                        links += ObjectLinkEntity(sourceId, dailyId, "DATE_MENTION")
                    }
                }
            }
        }
    }

    private fun extractSearchableContent(obj: PkmObject): String = buildString {
        append(obj.plainSummary).append(" ")
        for ((_, value) in obj.properties) {
            if (value is PropertyValue.RichText) {
                append(value.spans.toPlainText()).append(" ")
            }
        }
        for (block in obj.blocks) {
            when (block) {
                is Block.Paragraph -> append(block.spans.toPlainText()).append(" ")
                is Block.Heading -> append(block.spans.toPlainText()).append(" ")
                is Block.Todo -> append(block.spans.toPlainText()).append(" ")
                is Block.BulletList -> append(block.spans.toPlainText()).append(" ")
                is Block.NumberedList -> append(block.spans.toPlainText()).append(" ")
                is Block.AlphabeticList -> append(block.spans.toPlainText()).append(" ")
                is Block.RomanList -> append(block.spans.toPlainText()).append(" ")
                is Block.Toggle -> append(block.spans.toPlainText()).append(" ")
                is Block.Quote -> append(block.spans.toPlainText()).append(" ")
                is Block.Callout -> append(block.spans.toPlainText()).append(" ")
                is Block.Code -> append(block.code).append(" ")
                is Block.Math -> append(block.latex).append(" ")
                is Block.Table -> append(block.rows.joinToString(" ") { it.joinToString(" ") }).append(" ")
                is Block.Bookmark -> append(block.title).append(" ").append(block.url).append(" ").append(block.description).append(" ")
                else -> Unit
            }
        }
    }


    private fun sanitizeTagKey(name: String): String =
        org.yaop.core.sync.MergeContract.tagKey(name).removePrefix("tag_")

    private fun deserializeObject(jsonStr: String): PkmObject? =
        runCatching {
            val decoded = json.decodeFromString<PkmObject>(jsonStr)
            DefaultTypes.normalizeObject(decoded)
        }.getOrNull()

    private fun deserializeType(jsonStr: String): ObjectType? =
        runCatching {
            val decoded = json.decodeFromString<ObjectType>(jsonStr)
            decoded.copy(id = decoded.id)
        }.getOrNull()

    companion object {
        private val MENTION_TOKEN_REGEX = Regex("""@\[([^\]]+)]|\[\[([^\]]+)]]""")
        private val INLINE_DATE_MENTION_REGEX = Regex("""(?<=^|[\s(\[])@(\d{4}-\d{2}-\d{2})""")
        private val ISO_DATE_EXACT_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val DIGITS_ONLY_REGEX = Regex("""^\d+$""")
        private val AUTO_MEDIA_PREFIX_REGEX = Regex(
            """^(IMG|PXL|Screenshot|VID|AUD|REC|PTT|VN|voice|audio|recording)[_-]?\d+.*""",
            RegexOption.IGNORE_CASE,
        )
        private val AUTO_MEDIA_EXT_REGEX = Regex(
            """^.*\.(jpg|jpeg|png|webp|gif|bmp|heic|heif|m4a|mp3|wav|ogg|aac|flac|opus|amr|mp4)$""",
            RegexOption.IGNORE_CASE,
        )
        private val AUTO_AUDIO_REC_REGEX = Regex(
            """^(Audio recording|Grabación de audio)\s*\(.*\)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
