package org.yaop.core.crypto

import java.io.File

/**
 * Storage contract for reading, writing, and deleting binary media assets (images, audio, documents).
 */
interface AssetStorage {
    /**
     * Encrypts and writes media bytes to disk, returning the created file handle.
     */
    fun saveMedia(assetId: String, data: ByteArray, mimeType: String): File

    /**
     * Reads and decrypts an encrypted media file from disk using its known MIME type.
     */
    fun readMedia(assetId: String, mimeType: String): ByteArray?

    /**
     * Reads an asset when the MIME type is not known upfront by locating the asset by ID prefix.
     */
    fun readMediaAny(assetId: String): ByteArray?

    /**
     * Deletes an encrypted media file matching the asset ID and MIME type.
     */
    fun deleteMedia(assetId: String, mimeType: String): Boolean

    /**
     * Deletes every encrypted file associated with the asset ID.
     */
    fun deleteMediaAny(assetId: String): Boolean

    /**
     * Returns true if an encrypted media file for the given asset ID exists locally.
     */
    fun hasMedia(assetId: String): Boolean
}
