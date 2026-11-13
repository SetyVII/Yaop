package org.yaop.core.crypto

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the encrypted local storage for assets (images, PDFs, audio recordings).
 * Files are encrypted and decrypted in memory or streaming mode, so unencrypted data
 * is never stored on disk.
 */
class EncryptedMediaStore(
    private val context: Context,
    private val keyStoreVault: AndroidKeyStoreVault
) : AssetStorage {
    private val mediaDir: File by lazy {
        File(context.filesDir, "vault_media").apply { if (!exists()) mkdirs() }
    }

    /**
     * Encrypts and writes media bytes to disk, returning the relative file path.
     */
    override fun saveMedia(assetId: String, data: ByteArray, mimeType: String): File {
        val extension = extensionForMime(mimeType)
        val targetFile = File(mediaDir, "$assetId.$extension.enc")

        val aad = "asset/$assetId".toByteArray(Charsets.UTF_8)
        val encryptedBytes = keyStoreVault.encrypt(data, aad)
        targetFile.writeBytes(encryptedBytes)
        return targetFile
    }

    /**
     * Reads and decrypts an encrypted media file from disk into RAM.
     */
    override fun readMedia(assetId: String, mimeType: String): ByteArray? {
        val extension = extensionForMime(mimeType)
        val file = File(mediaDir, "$assetId.$extension.enc")
        if (!file.exists()) return null

        val aad = "asset/$assetId".toByteArray(Charsets.UTF_8)
        return runCatching {
            keyStoreVault.decrypt(file.readBytes(), aad)
        }.getOrNull()
    }

    companion object {
        private val COMMON_EXTENSIONS = listOf("webp", "jpg", "png", "m4a", "pdf", "gif", "bin")
    }

    /**
     * Reads an asset without knowing its mime type upfront: locates the
     * encrypted file by checking common extensions first in O(1) time,
     * falling back to directory scan only if non-standard.
     */
    override fun readMediaAny(assetId: String): ByteArray? {
        val file = findMediaFile(assetId) ?: return null
        val aad = "asset/$assetId".toByteArray(Charsets.UTF_8)
        return runCatching {
            keyStoreVault.decrypt(file.readBytes(), aad)
        }.getOrNull()
    }

    private fun findMediaFile(assetId: String): File? {
        for (ext in COMMON_EXTENSIONS) {
            val candidate = File(mediaDir, "$assetId.$ext.enc")
            if (candidate.exists()) return candidate
        }
        return mediaDir.listFiles()?.firstOrNull { it.name.startsWith("$assetId.") }
    }

    override fun hasMedia(assetId: String): Boolean = findMediaFile(assetId) != null

    /**
     * Deletes every encrypted file belonging to an asset id.
     */
    override fun deleteMediaAny(assetId: String): Boolean =
        mediaDir.listFiles()?.filter { it.name.startsWith("$assetId.") }?.all { it.delete() } ?: true

    /**
     * Deletes an encrypted media file.
     */
    override fun deleteMedia(assetId: String, mimeType: String): Boolean {
        val extension = extensionForMime(mimeType)
        val file = File(mediaDir, "$assetId.$extension.enc")
        return if (file.exists()) file.delete() else true
    }

    private fun extensionForMime(mime: String): String = when {
        mime.contains("webp") -> "webp"
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("gif") -> "gif"
        mime == "application/pdf" -> "pdf"
        mime.startsWith("audio/") -> "m4a"
        else -> "bin"
    }
}
