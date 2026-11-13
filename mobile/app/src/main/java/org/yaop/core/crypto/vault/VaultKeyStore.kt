package org.yaop.core.crypto.vault

import org.yaop.core.crypto.AndroidKeyStoreVault
import org.yaop.core.database.ObjectDao
import org.yaop.core.database.VaultMetaEntity

/**
 * Persistence of the vault key: the master key NEVER lives in clear. It is wrapped with the
 * Android Keystore AES-GCM key ([AndroidKeyStoreVault], entry `yaop_master_vault_key`) and the
 * wrapped blob is stored in `vault_meta` with its `key_epoch` and the pull cursor `last_seq`.
 *
 * Without a vault (onboarding not done yet) [session] returns null and the sync manager does
 * not sync: nothing can be sealed without the VK.
 */
class VaultKeyStore(
    private val dao: ObjectDao,
    private val keyStoreVault: AndroidKeyStoreVault?,
) {

    /** Crypto session of the loaded vault; null without a stored VK or a keystore. */
    suspend fun session(): VaultCryptoSession? {
        val vault = keyStoreVault ?: return null
        val meta = dao.getVaultMeta() ?: return null
        val wrapped = meta.wrappedVk ?: return null
        if (wrapped.isEmpty()) return null
        return runCatching {
            val vkBytes = vault.decrypt(wrapped, AAD)
            VaultCryptoSession(VaultKey(vkBytes, meta.keyEpoch))
        }.onFailure {
            android.util.Log.w("VaultKeyStore", "stored vault key cannot be unwrapped: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }

    /** Is a wrapped VK stored? (does not mean it can be unwrapped here) */
    suspend fun hasVaultKey(): Boolean =
        (dao.getVaultMeta()?.wrappedVk?.isNotEmpty() == true)

    /**
     * Creates this device's vault (or imports a given VK: tests, onboarding from a phrase or
     * pairing). Wraps the VK and stores `vault_meta`; fails if one already exists (call only
     * after [hasVaultKey] == false, or use [importReplacing] for rotation).
     */
    suspend fun createVaultKey(
        vkBytes: ByteArray? = null,
        keyEpoch: Long = 1L,
        importReplacing: Boolean = false,
    ): VaultCryptoSession {
        val vault = keyStoreVault
            ?: throw IllegalStateException("AndroidKeyStoreVault not provided: cannot store a vault key")
        if (!importReplacing && hasVaultKey()) {
            throw IllegalStateException("vault key already exists")
        }
        val key = if (vkBytes != null) VaultKey(vkBytes, keyEpoch) else VaultKey.generate(keyEpoch)
        val wrapped = vault.encrypt(key.key, AAD)
        dao.upsertVaultMeta(VaultMetaEntity(id = 1, wrappedVk = wrapped, keyEpoch = key.keyEpoch, lastSeq = 0L))
        return VaultCryptoSession(key)
    }

    /** Epoch of the stored VK (0 when there is no vault yet). */
    suspend fun keyEpoch(): Long = dao.getVaultMeta()?.keyEpoch ?: 0L

    /** Global pull cursor (persisted: a restart does not reprocess the log). */
    suspend fun lastSeq(): Long = dao.getVaultMeta()?.lastSeq ?: 0L

    suspend fun setLastSeq(seq: Long) {
        dao.updateVaultLastSeq(seq)
    }

    /** Per-domain cursor for assets (resume chunk uploads without listing again). */
    suspend fun domainSeq(domain: String): Long = dao.getDomainSeq(domain) ?: 0L

    suspend fun setDomainSeq(domain: String, seq: Long) {
        dao.upsertDomainSeq(org.yaop.core.database.VaultDomainSeqEntity(domain, seq))
    }

    companion object {
        /** Fixed AAD of the wrapped VK blob: binds it to the purpose "vault_meta vk". */
        val AAD = "yaop/vault_meta/wrapped_vk/v1".toByteArray(Charsets.UTF_8)
    }
}
