package org.yaop.core.crypto.vault

import java.security.SecureRandom

/**
 * Vault Key and crypto domains (spec/crypto.md): the VK is 32 bytes of random entropy
 * (the 24-word BIP39 phrase **is** that entropy) and each payload type is encrypted with
 * its own domain subkey derived with HKDF-SHA256.
 *
 * Rotation (revoking a device, spec §15.3) increments [keyEpoch] and yields a new VK;
 * every envelope declares the epoch it was sealed with.
 */
data class VaultKey(
    /** 32 bytes never exposed outside the vault services. */
    val key: ByteArray,
    /** 1-based era of this key; every envelope writes it in the clear. */
    val keyEpoch: Long,
) {
    init {
        require(key.size == KEY_BYTES) { "vault key must be $KEY_BYTES bytes, has ${key.size}" }
        require(keyEpoch >= 1) { "key_epoch starts at 1" }
    }

    /** Domain subkey (spec/crypto.md §Subkeys): HKDF-SHA256(ikm=VK, salt, info=domain). */
    fun subkey(domain: String): ByteArray = Hkdf.subkey(key, domain)

    override fun equals(other: Any?): Boolean = other is VaultKey && other.keyEpoch == keyEpoch && key.contentEquals(other.key)
    override fun hashCode(): Int = 31 * keyEpoch.toInt() + key.contentHashCode()

    companion object {
        const val KEY_BYTES = 32
        const val HKDF_SALT = "yaop-vault/1"

        /** Fresh random VK for a new vault (or a rotation to [keyEpoch]). */
        fun generate(keyEpoch: Long = 1L, random: SecureRandom = SecureRandom()): VaultKey {
            val key = ByteArray(KEY_BYTES)
            random.nextBytes(key)
            return VaultKey(key, keyEpoch)
        }
    }
}

/**
 * Domain names of spec/crypto.md §Subkeys: their ASCII forms are the HKDF `info`, so a
 * domain string here is the contract — never rename.
 */
object CryptoDomains {
    const val OBJECTS = "objects"
    const val OPS = "ops"
    const val SNAPSHOTS = "snapshots"
    const val ASSETS = "assets"
    const val THUMBS = "thumbs"
    const val DERIVED = "derived"
    const val IDS = "ids"

    val ALL = listOf(OBJECTS, OPS, SNAPSHOTS, ASSETS, THUMBS, DERIVED, IDS)
}
