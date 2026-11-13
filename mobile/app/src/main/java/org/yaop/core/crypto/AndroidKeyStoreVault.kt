package org.yaop.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Handles hardware-backed master key protection via Android KeyStore.
 * The master key is stored securely in hardware and never exported in plaintext.
 */
class AndroidKeyStoreVault {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    init {
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     * Returns: 12-byte IV + ciphertext + 16-byte Auth Tag.
     */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv // 12 bytes for GCM

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    /**
     * Decrypts ciphertext produced by [encrypt].
     */
    fun decrypt(encryptedWithIv: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(encryptedWithIv.size > GCM_IV_LENGTH) { "Invalid encrypted data size" }
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(encryptedWithIv, 0, iv, 0, GCM_IV_LENGTH)

        val ciphertext = ByteArray(encryptedWithIv.size - GCM_IV_LENGTH)
        System.arraycopy(encryptedWithIv, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        if (associatedData != null) {
            cipher.updateAAD(associatedData)
        }
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "yaop_master_vault_key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        fun generateRandomBytes(length: Int): ByteArray {
            val bytes = ByteArray(length)
            SecureRandom().nextBytes(bytes)
            return bytes
        }
    }
}
