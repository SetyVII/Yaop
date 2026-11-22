package org.yaop.core.pairing

import org.yaop.core.crypto.vault.Pairing
import java.security.SecureRandom

/**
 * One pairing transaction on either side. [Host] is the enrolled device that shows the QR;
 * [Guest] is the new device. Both end up with the same 6-digit [code] to compare by eye; the
 * VK only travels sealed under the X25519-derived key (spec/crypto.md).
 */
object PairingSession {

    class Host(private val relay: PairingRelay, private val serverUrl: String, private val tlsFp: String) {
        private val priv = Pairing.generatePrivateKey()
        private val pairingIdBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pairingId: String = pairingIdBytes.joinToString("") { "%02x".format(it) }
        private var shared: ByteArray? = null

        /** QR payload to render; null if the relay is unreachable. */
        fun open(): String? {
            val pub = Pairing.publicKeyOf(priv)
            if (!relay.create(pairingId, Pairing.encodePub(pub))) return null
            return Pairing.encodeQr(Pairing.Qr(Pairing.QR_VERSION, serverUrl, tlsFp, pub, pairingIdBytes))
        }

        /** Polls for the guest; returns the 6-digit code once it answered, else null. */
        fun pollCode(): String? {
            val bPub = relay.answer(pairingId)?.let { Pairing.decodePub(it) } ?: return null
            val s = Pairing.sharedSecret(priv, bPub) ?: return null
            shared = s
            return Pairing.shortCode(s)
        }

        /**
         * The human confirmed the codes match: send the VK. With this device's [ownToken] the
         * guest also gets its own server token, so nobody types one.
         */
        fun approve(vk: ByteArray, keyEpoch: Long, ownToken: String?): Boolean {
            val s = shared ?: return false
            return relay.sendTransfer(pairingId, Pairing.sealVk(s, pairingId, vk, keyEpoch), ownToken, Pairing.enrollKey(s))
        }
    }

    class Guest(private val relay: PairingRelay, private val qr: Pairing.Qr) {
        private val priv = Pairing.generatePrivateKey()
        private val pairingId = qr.pairingId.joinToString("") { "%02x".format(it) }
        private var shared: ByteArray? = null

        /** True after [join] failed because another device already used this code. */
        var codeAlreadyUsed = false
            private set

        /** Answers the QR; returns the 6-digit code to display, or null on failure. */
        fun join(): String? {
            val s = Pairing.sharedSecret(priv, qr.ephPub) ?: return null
            val status = relay.join(pairingId, Pairing.encodePub(Pairing.publicKeyOf(priv)))
            codeAlreadyUsed = status == 409
            if (status != 202) return null
            shared = s
            return Pairing.shortCode(s)
        }

        /** Polls for the sealed VK; null until the host approved. A wrong key fails closed. */
        fun pollVk(): Pairing.OpenedVk? {
            val s = shared ?: return null
            val env = relay.fetchTransfer(pairingId) ?: return null
            return Pairing.openVk(s, pairingId, env)
        }

        /** After the VK arrived: this device's own server token, or null if none was offered. */
        fun enroll(deviceId: String, name: String): String? {
            val s = shared ?: return null
            return relay.enroll(pairingId, deviceId, name, Pairing.enrollKey(s))
        }
    }
}
