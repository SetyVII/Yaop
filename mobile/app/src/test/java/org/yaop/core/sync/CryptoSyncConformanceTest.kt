package org.yaop.core.sync

import org.yaop.core.crypto.vault.Aead
import org.yaop.core.crypto.vault.Bip39
import org.yaop.core.crypto.vault.Bip39Assets
import org.yaop.core.crypto.vault.CryptoDomains
import org.yaop.core.crypto.vault.Envelope
import org.yaop.core.crypto.vault.Envelope.Opened
import org.yaop.core.crypto.vault.Hkdf
import org.yaop.core.crypto.vault.IdBlinder
import org.yaop.core.crypto.vault.Pairing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * E2EE contract conformance (spec/crypto.md, spec/sync.md, spec/merge.md §Merge algebra): the
 * SAME fixtures Go runs today in server/internal/simclient and server/internal/sync —
 * spec/fixtures/{crypto,syncproto,merge_assoc}. The fixtures are the shared contract: they are
 * never edited here, only executed.
 *
 * crypto: BIP39 (wordlist + official vectors), HKDF domains, XChaCha20-Poly1305 (draft
 * vectors + negatives), blind ids, envelope (byte-exact v1 + zstd roundtrip v2) and the
 * full pairing transcript. syncproto: the four protocol fixtures replayed over a local
 * `com.sun.net.httpserver` server that implements the abstract OpLog semantics with
 * the real BlindTransport HTTP client. merge_assoc: the three-device algebra — idempotency,
 * all six permutations, both groupings and the swapped fold — against MergeContract.merge
 * (block_anchor render included).
 */
class CryptoSyncConformanceTest {

    private val specDir: File = sequenceOf("../../spec/fixtures", "../spec/fixtures", "spec/fixtures")
        .map { File(it) }.firstOrNull { it.isDirectory }
        ?: error("spec/fixtures not found from ${File(".").absolutePath}")

    private fun files(sub: String): List<File> =
        File(specDir, sub).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun fx(sub: String, name: String): JSONObject =
        JSONObject(File(specDir, "$sub/$name").readText())

    private fun unhex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun sha256OfFile(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------
    // spec/crypto.md — spec/fixtures/crypto
    // ---------------------------------------------------------------------

    @Test
    fun bip39EntropyPhraseRoundTrip() {
        val b = fx("crypto", "bip39.json")
        val wordlistFile = File(specDir, "crypto/bip39_english.txt")
        assertEquals(2048, b.getJSONObject("wordlist").getInt("words"))
        assertEquals(b.getJSONObject("wordlist").getString("sha256"), sha256OfFile(wordlistFile))
        val bip39 = Bip39Assets.fromBytes(wordlistFile.readBytes())

        for (valid in JSONArrayIterable(b.getJSONArray("valid"))) {
            val entropy = unhex(valid.getString("entropy_hex"))
            val mnemonic = valid.getString("mnemonic")
            assertEquals(valid.getString("name"), mnemonic, bip39.entropyToMnemonic(entropy))
            assertArrayEquals(valid.getString("name"), entropy, bip39.mnemonicToEntropy(mnemonic))
            // The standard seed exists for compatibility; the vault NEVER uses it as keys.
            assertArrayEquals(valid.getString("name"), unhex(valid.getString("seed_no_passphrase_hex")), Bip39.seed(mnemonic))
        }
        for (invalid in JSONArrayIterable(b.getJSONArray("invalid"))) {
            val error = runCatching { bip39.mnemonicToEntropy(invalid.getString("mnemonic")) }
            assertTrue(invalid.getString("name"), error.isFailure)
            val got = error.exceptionOrNull().toString()
            // The fixture classifies the rejection; the exact wording is free per platform.
            val want = invalid.getString("error")
            val words = setOf("checksum mismatch", "not in the list", "24 words")
            val expectedWord = words.firstOrNull { want.contains(it) } ?: want
            assertTrue("${invalid.getString("name")}: $got", got.contains(expectedWord))
        }
    }

    @Test
    fun hkdfSubkeysByDomain() {
        val fx = fx("crypto", "hkdf_domains.json")
        assertEquals("yaop-vault/1", fx.getString("salt"))
        assertEquals(32, fx.getInt("len"))
        val vk = unhex(fx.getString("vk"))
        val subkeys = fx.getJSONObject("subkeys")
        for (domain in CryptoDomains.ALL) {
            assertEquals(domain, subkeys.getString(domain), hex(Hkdf.subkey(vk, domain)))
        }
    }

    @Test
    fun aeadXChaChaOfficialVectors() {
        val fx = fx("crypto", "aead_xchacha20poly1305.json")
        // Draft §A.1 official vector: byte-exact ciphertext+tag.
        for (case in JSONArrayIterable(fx.getJSONArray("cases"))) {
            val key = unhex(case.getString("key_hex"))
            val nonce = unhex(case.getString("nonce_hex"))
            val aad = unhex(case.getString("aad_hex"))
            val plain = case.getString("plaintext_ascii").toByteArray(Charsets.UTF_8)
            val ct = Aead.sealWithNonce(key, nonce, plain, aad)
            assertEquals(case.getString("name"), case.getString("ciphertext_plus_tag_hex"), hex(ct))
            assertArrayEquals(case.getString("name"), plain, Aead.open(key, nonce, ct, aad))
        }
        // HChaCha20 §2.2.1: the draft's own subkey vector pins the KDF half.
        val h = fx.getJSONObject("hchacha20")
        assertArrayEquals(
            h.getString("name"),
            unhex(h.getString("subkey_hex")),
            Aead.hChaCha20(unhex(h.getString("key_hex")), unhex(h.getString("nonce_hex"))),
        )
        // Negatives: one flipped byte in ct/tag/AAD/key must fail authentication.
        val case0 = fx.getJSONArray("cases").getJSONObject(0)
        val key = unhex(case0.getString("key_hex"))
        val nonce = unhex(case0.getString("nonce_hex"))
        val aad = unhex(case0.getString("aad_hex"))
        val plain = case0.getString("plaintext_ascii").toByteArray(Charsets.UTF_8)
        val ct = Aead.sealWithNonce(key, nonce, plain, aad)
        for (flip in listOf(
            ct.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() },
            ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )) {
            assertNull(Aead.open(key, nonce, flip, aad))
        }
        assertNull(Aead.open(key, nonce, ct, aad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertNull(Aead.open(key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, nonce, ct, aad))
    }

    @Test
    fun wireIdDeterministicAndDerived() {
        val fx = fx("crypto", "wire_id.json")
        val idsKey = unhex(fx.getString("ids_subkey"))
        for (case in JSONArrayIterable(fx.getJSONArray("cases"))) {
            assertEquals(case.getString("name"), case.getString("wire_id"), IdBlinder.wireId(idsKey, case.getString("id")))
        }
        for (derived in JSONArrayIterable(fx.getJSONArray("derived_cases"))) {
            assertEquals(derived.getString("input"), IdBlinder.derivedInput(derived.getString("object_id"), derived.getString("kind")))
            assertEquals(
                derived.getString("name"),
                derived.getString("wire_id"),
                IdBlinder.derivedWireId(idsKey, derived.getString("object_id"), derived.getString("kind")),
            )
        }
    }

    @Test
    fun envelopeSealOpen() {
        // Byte-exact v1 vectors (fixed nonces): the runner only fixes the nonce; every
        // other byte must match the fixture.
        val fx = fx("crypto", "envelope.json")
        for (case in JSONArrayIterable(fx.getJSONArray("cases"))) {
            val domain = case.getString("domain")
            val wireId = case.getString("wire_id")
            val keyEpoch = case.getLong("key_epoch")
            val nonce = unhex(case.getString("nonce"))
            val payload = if (case.has("payload_hex")) unhex(case.getString("payload_hex")) else case.getString("payload").toByteArray(Charsets.UTF_8)
            val env = Envelope.seal(unhex(case.getString("key")), keyEpoch, domain, wireId, payload, compress = false, nonce = nonce)
            assertEquals(case.getString("name"), case.getString("envelope_hex"), hex(env))
            if (case.has("chunk_index")) {
                assertEquals(
                    case.getString("name") + " (chunk AAD)",
                    case.getString("aad"),
                    String(Envelope.chunkAad(wireId, case.getInt("chunk_index")), Charsets.UTF_8),
                )
            } else {
                assertEquals(case.getString("name") + " (domain AAD)", case.getString("aad"), String(Envelope.aad(domain, wireId), Charsets.UTF_8))
            }
            val opened = Envelope.open(unhex(case.getString("key")), env, domain, wireId)
            assertNotNull(case.getString("name"), opened)
            assertArrayEquals(case.getString("name"), payload, opened!!.payload)
            assertEquals(case.getLong("key_epoch"), opened.keyEpoch)
            assertEquals(Envelope.VERSION_RAW, opened.version)
        }
        // Tampered envelopes fail closed: short, wrong version, flipped byte, wrong epoch key.
        val case0 = fx.getJSONArray("cases").getJSONObject(0)
        val key = unhex(case0.getString("key"))
        val domain = case0.getString("domain")
        val wireId = case0.getString("wire_id")
        val env = unhex(case0.getString("envelope_hex"))
        assertNull(Envelope.open(key, env.copyOf(env.size - 10), domain, wireId))
        assertNull(Envelope.open(key, env.copyOf().also { it[0] = 0x7F }, domain, wireId))
        assertNull(Envelope.open(key, env.copyOf().also { it[env.size - 1] = (it[env.size - 1].toInt() xor 1).toByte() }, domain, wireId))
        assertNull(Envelope.open(ByteArray(32), env, domain, wireId))
        assertNull(Envelope.open(key, env, "snapshots", wireId))
        // v2 (zstd): roundtrip only — zstd bytes are never compared between libraries.
        val z = fx("crypto", "envelope_zstd_roundtrip.json")
        val vk = unhex(z.getString("vk_hex"))
        for (case in JSONArrayIterable(z.getJSONArray("cases"))) {
            val domainKey = Hkdf.subkey(vk, case.getString("key_subkey"))
            val payload = case.getString("payload").toByteArray(Charsets.UTF_8)
            val sealed = Envelope.seal(
                domainKey, case.getLong("key_epoch"), case.getString("domain"), case.getString("wire_id"),
                payload, compress = true, nonce = unhex(case.getString("nonce_hex")),
            )
            assertEquals(Envelope.VERSION_ZSTD, sealed[0])
            val opened = Envelope.open(domainKey, sealed, case.getString("domain"), case.getString("wire_id"))
            assertNotNull(case.getString("name"), opened)
            assertArrayEquals(case.getString("name"), payload, opened!!.payload)
            assertEquals(case.getLong("key_epoch"), opened.keyEpoch)
        }
    }

    @Test
    fun pairingTranscript() {
        val fx = fx("crypto", "pairing_transcript.json")
        val steps = fx.getJSONArray("steps")
        val s1 = steps.getJSONObject(0)
        val s2 = steps.getJSONObject(1)
        val s3 = steps.getJSONObject(2)
        val s4 = steps.getJSONObject(3)
        val s5 = steps.getJSONObject(4)
        val s6 = steps.getJSONObject(5)

        // 1. A's ephemeral X25519 pair: the private scalar derives the fixed public key.
        val privA = unhex(s1.getString("priv_a"))
        assertArrayEquals(s1.getString("desc"), unhex(s1.getString("pub_a")), Pairing.publicKeyOf(privA))
        assertEquals(s1.getString("pub_a_b64url"), Pairing.encodePub(unhex(s1.getString("pub_a"))))

        // 2. The QR payload: exact compact JSON, key order as declared.
        val pubA = unhex(s1.getString("pub_a"))
        val qr = Pairing.Qr(
            version = 1,
            serverUrl = "https://yaop.vps.example.net",
            tlsFp = "abababababababababababababababababababababababababababababababab",
            ephPub = pubA,
            pairingId = unhex("8f14e45fceea167a5a36dedd4bea2543"),
        )
        assertEquals(s2.getString("desc"), s2.getString("qr_json"), Pairing.encodeQr(qr))
        val decodedQr = Pairing.decodeQr(s2.getString("qr_json"))
        assertNotNull("qr decode", decodedQr)
        assertArrayEquals(unhex(s1.getString("pub_a")), decodedQr!!.ephPub)
        assertNull("unknown keys/versions are rejected", Pairing.decodeQr("""{"v":2,"server_url":"x","tls_fp":"","eph_pub":"","pairing_id":""}"""))
        assertNull(Pairing.decodePub("c2hvcnQ"))

        // 3. B's ephemeral pair.
        val privB = unhex(s3.getString("priv_b"))
        assertArrayEquals(unhex(s3.getString("pub_b")), Pairing.publicKeyOf(privB))
        assertEquals(s3.getString("pub_b_b64url"), Pairing.encodePub(unhex(s3.getString("pub_b"))))

        // 4. Both derive the same X25519 shared secret.
        val sharedA = Pairing.sharedSecret(privA, unhex(s3.getString("pub_b")))
        val sharedB = Pairing.sharedSecret(privB, unhex(s1.getString("pub_a")))
        assertArrayEquals(s4.getString("desc"), unhex(s4.getString("shared_hex")), sharedA)
        assertArrayEquals("B derives the same secret", unhex(s4.getString("shared_hex")), sharedB)

        // 5. The 6-digit short code both devices show.
        assertEquals(s5.getString("desc"), s5.getString("short_code"), Pairing.shortCode(sharedA!!))
        assertEquals("code is symmetric", s5.getString("short_code"), Pairing.shortCode(sharedB!!))

        // 7. The enroll key B redeems for its own server token (same on both sides).
        val s7 = steps.getJSONObject(6)
        assertEquals(s7.getString("desc"), s7.getString("enroll_key"), Pairing.enrollKey(sharedA))
        assertEquals("enroll key is symmetric", s7.getString("enroll_key"), Pairing.enrollKey(sharedB))

        // 6. A wraps the VK under the vk-transfer key; the envelope is byte-exact.
        val vk = unhex(s6.getString("vk"))
        assertArrayEquals(s6.getString("desc"), unhex(s6.getString("vk_transfer_key")), Pairing.vkTransferKey(sharedA))
        val sealed = Pairing.sealVk(sharedA, s6.getString("pairing_id"), vk, s6.getLong("key_epoch"), unhex(s6.getString("nonce")))
        assertEquals(s6.getString("envelope_v1_hex"), hex(sealed))
        val opened = Pairing.openVk(sharedB, s6.getString("pairing_id"), sealed)
        assertNotNull("B can open", opened)
        assertArrayEquals(vk, opened!!.vk)
        assertEquals(s6.getLong("key_epoch"), opened.keyEpoch)
        assertNull("wrong pairing id fails", Pairing.openVk(sharedB, "00000000000000000000000000000000", sealed))
        assertNull("a degenerate shared secret fails", Pairing.openVk(ByteArray(32), s6.getString("pairing_id"), sealed))
    }

    // ---------------------------------------------------------------------
    // spec/sync.md — spec/fixtures/sync (local JDK HTTP server + BlindTransport)
    // ---------------------------------------------------------------------

    @Test
    fun syncprotoProtocolFixtureSteps() {
        SyncProtoFixtureServerRunner.runFixtureSteps(specDir)
    }

    // ---------------------------------------------------------------------
    // spec/merge.md §Merge algebra — spec/fixtures/merge_assoc
    // ---------------------------------------------------------------------

    /** The canonical projection the algebra is verified over (Go fuzzObjCanon). */
    private fun objCanon(o: JSONObject): String {
        val proj = JSONObject()
        proj.put("id", o.optString("id"))
        proj.put("title", o.opt("title") ?: JSONObject.NULL)
        proj.put("title_runs", o.optJSONArray("title_runs") ?: JSONArray())
        proj.put("type_id", o.optString("type_id"))
        proj.put("properties", o.optJSONObject("properties") ?: JSONObject())
        proj.put("hlc", o.optString("hlc"))
        proj.put("field_hlc", o.optJSONObject("field_hlc") ?: JSONObject())
        proj.put("block_hlc", o.optJSONObject("block_hlc") ?: JSONObject())
        proj.put("block_anchor", o.optJSONObject("block_anchor") ?: JSONObject())
        proj.put("deleted_blocks", o.optJSONObject("deleted_blocks") ?: JSONObject())
        proj.put("links", o.optJSONArray("links") ?: JSONArray())
        proj.put("property_links", o.optJSONArray("property_links") ?: JSONArray())
        proj.put("created_at", o.optString("created_at"))
        return MergeContract.canon(proj)
    }

    @Test
    fun merge_assocAlgebraAllPermutations() {
        assertTrue(files("merge_assoc").isNotEmpty())
        for (f in files("merge_assoc")) {
            val fx = JSONObject(f.readText())
            val states = mapOf(
                "a" to fx.getJSONObject("a"),
                "b" to fx.getJSONObject("b"),
                "c" to fx.getJSONObject("c"),
            )
            val expect = fx.getJSONObject("expect")
            val label = f.name

            // Idempotency: merge(x,x) == x for each device state (normalized CRDT canon).
            for ((dev, x) in states) {
                assertEquals("$label merge($dev,$dev)", objCanon(x), objCanon(MergeContract.merge(x, x)))
            }
            // All permutations, both groupings, plus the swapped fold: every final state
            // must be exactly the same convergent result (associativity + symmetry).
            val a = states.getValue("a")
            val b = states.getValue("b")
            val c = states.getValue("c")
            val perms = listOf(
                Triple(a, b, c), Triple(a, c, b), Triple(b, a, c),
                Triple(b, c, a), Triple(c, a, b), Triple(c, b, a),
            )
            for ((i, pm) in perms.withIndex()) {
                val (x, y, z) = pm
                val g1 = MergeContract.merge(MergeContract.merge(x, y), z)
                val g2 = MergeContract.merge(x, MergeContract.merge(y, z))
                val g3 = MergeContract.merge(z, MergeContract.merge(y, x))
                assertEquals("$label/$i not associative", objCanon(g2), objCanon(g1))
                assertEquals("$label/$i not convergent (swapped fold)", objCanon(g3), objCanon(g1))
                // Every expect key must match (missing keys on the got side read as {}).
                for (key in expect.keys()) {
                    assertEquals(
                        "$label/$i expect.$key",
                        MergeContract.canon(expect.get(key)),
                        MergeContract.canon(g1.opt(key) ?: JSONObject()),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------

    private class JSONArrayIterable(private val arr: JSONArray) : Iterable<JSONObject> {
        override fun iterator(): Iterator<JSONObject> = object : Iterator<JSONObject> {
            var i = 0
            override fun hasNext() = i < arr.length()
            override fun next() = arr.getJSONObject(i++)
        }
    }

    @Test
    fun setupQr() {
        val fx = fx("crypto", "setup_qr.json")
        val enc = fx.getJSONObject("encode")
        val decoded = org.yaop.core.pairing.SetupQr.decode(enc.getString("qr_json"))
        assertNotNull("encode case decodes", decoded)
        assertEquals(enc.getString("server_url"), decoded!!.serverUrl)
        assertEquals(enc.getString("tls_fp"), decoded.tlsFp)
        assertEquals(enc.getString("token"), decoded.token)
        val valid = fx.getJSONArray("valid")
        for (k in 0 until valid.length()) {
            val c = valid.getJSONObject(k)
            assertNotNull(c.getString("desc"), org.yaop.core.pairing.SetupQr.decode(c.getString("qr_json")))
        }
        val invalid = fx.getJSONArray("invalid")
        for (k in 0 until invalid.length()) {
            val c = invalid.getJSONObject(k)
            assertNull(c.getString("desc"), org.yaop.core.pairing.SetupQr.decode(c.getString("qr_json")))
        }
    }
}
