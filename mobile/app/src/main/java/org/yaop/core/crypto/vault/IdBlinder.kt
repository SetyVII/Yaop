package org.yaop.core.crypto.vault

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Blind ids (spec/crypto.md §Ids ciegos): real object ids carry meaning (daily-2026-11-09,
 * tag_mi_tema, media_<asset>) and spec §15.1 forbids the server from seeing them. On the
 * wire each real id travels as
 *
 * `wire_id = hex(HMAC-SHA256(key = subkey "ids", msg = id_utf8))[0:32]`
 *
 * — the first 16 bytes of the HMAC in lowercase hex (32 ASCII chars). Deterministic: two
 * offline devices creating the same daily note produce the same wire_id. Derived artifacts
 * blind the COMPOSITE id `derived/<objectId>/<kind>` so neither piece leaks.
 */
object IdBlinder {

    /** Blinds a real object id. Pass the "ids" domain subkey OF THE SAME key epoch. */
    fun wireId(idsKey: ByteArray, realId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(idsKey, "HmacSHA256"))
        return hexNoPad(mac.doFinal(realId.toByteArray(Charsets.UTF_8))).substring(0, 32)
    }

    /** HMAC input of a derived artifact: the composite `derived/<objectId>/<kind>` key. */
    fun derivedInput(objectId: String, kind: String): String = "derived/$objectId/$kind"

    /** Blinds the pair (objectId, kind) of a derived/ artifact (spec §23.3). */
    fun derivedWireId(idsKey: ByteArray, objectId: String, kind: String): String =
        wireId(idsKey, derivedInput(objectId, kind))

    private fun hexNoPad(bytes: ByteArray): String = buildString {
        for (b in bytes) {
            append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
            append("0123456789abcdef"[b.toInt() and 0xF])
        }
    }.toString()
}
