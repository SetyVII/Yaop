package org.yaop

import android.app.Application
import org.yaop.core.crypto.AndroidKeyStoreVault
import org.yaop.core.crypto.EncryptedMediaStore
import org.yaop.core.database.AppDatabase
import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class YaopApp : Application() {

    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    val keyStoreVault: AndroidKeyStoreVault by lazy {
        AndroidKeyStoreVault()
    }

    val mediaStore: EncryptedMediaStore by lazy {
        EncryptedMediaStore(this, keyStoreVault)
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this, keyStoreVault, json)
    }

    val appLock: org.yaop.core.security.AppLock by lazy {
        org.yaop.core.security.AppLock(this)
    }

    val backupManager: org.yaop.core.backup.BackupManager by lazy {
        org.yaop.core.backup.BackupManager(repository, mediaStore)
    }

    val syncManager: SyncManager by lazy {
        SyncManager(this, repository, mediaStore, applicationScope)
    }

    val repository: PkmRepository by lazy {
        // keyStoreVault: the vault key is wrapped with the hardware-backed key.
        PkmRepository(database, json, keyStoreVault).apply {
            onObjectSavedListener = {
                syncManager.schedulePush(2000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerNetworkRecovery()
        org.yaop.core.model.HybridLogicalClock.setNodeFromDevice(
            org.yaop.core.sync.deviceIdFor(this)
        )
        // Eagerly pre-warm database, default types, and in-memory object cache on IO thread
        // so that all tabs and screens have instantaneous 0ms synchronous access upon entry.
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                repository.syncDefaultTypes()
                repository.getAllActiveObjectsSnapshot()
            }
            rebuildLinkIndexOnce()
            syncManager.syncNow()
        }
    }

    /**
     * Bump [LINK_INDEX_VERSION] when the local links rule changes; each device then rebuilds its
     * backlinks once. v2: typed `#word` text is no longer a tag (docs/product.md §4.5).
     */
    private suspend fun rebuildLinkIndexOnce() {
        val prefs = getSharedPreferences("maintenance", MODE_PRIVATE)
        if (prefs.getInt("link_index_version", 1) >= LINK_INDEX_VERSION) return
        runCatching { repository.rebuildLinkIndex() }
            .onSuccess { prefs.edit().putInt("link_index_version", LINK_INDEX_VERSION).apply() }
    }

    private companion object {
        const val LINK_INDEX_VERSION = 2
    }

    /**
     * Plan R7: sync as soon as the network comes back (Wi-Fi switch, leaving Doze or airplane
     * mode) instead of waiting for the live stream backoff or the next local edit.
     */
    private fun registerNetworkRecovery() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var down = false

                override fun onLost(network: android.net.Network) {
                    down = true
                }

                override fun onBlockedStatusChanged(network: android.net.Network, blocked: Boolean) {
                    if (blocked) {
                        down = true
                    } else if (down) {
                        down = false
                        syncManager.onNetworkAvailable()
                    }
                }

                override fun onAvailable(network: android.net.Network) {
                    if (down) {
                        down = false
                        syncManager.onNetworkAvailable()
                    }
                }
            })
        }
    }
}

