package org.yaop.core.crypto.vault

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * HKDF-SHA256 (RFC 5869) exactly as spec/crypto.md fixes it:
 *
 * `subkey(dom) = HKDF-SHA256(ikm = VK_epoch, salt = "yaop-vault/1", info = "<dominio>")` → 32 bytes.
 *
 * Byte-exact against `spec/fixtures/crypto/hkdf_domains.json` and the Go reference
 * (`server/internal/simclient/hkdf.go`); a change in salt/info/len breaks every platform.
 */
object Hkdf {

    const val SUBKEY_BYTES = 32

    /** Domain subkey derivation (spec/crypto.md §Subkeys). */
    fun subkey(vk: ByteArray, domain: String): ByteArray =
        derive(ikm = vk, salt = VaultKey.HKDF_SALT.toByteArray(Charsets.UTF_8), info = domain.toByteArray(Charsets.UTF_8), outLen = SUBKEY_BYTES)

    /** Raw HKDF-SHA256 (extract + expand); also the pairing KDF spine (salt "yaop-pairing/1"). */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(outLen)
        gen.generateBytes(out, 0, outLen)
        return out
    }
}
