package org.yaop.core.backup

import org.yaop.core.backup.BackupContainer.KIND_ASSET
import org.yaop.core.backup.BackupContainer.KIND_OBJ
import org.yaop.core.backup.BackupContainer.KIND_TYPE
import org.yaop.core.crypto.vault.VaultKey
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** spec/fixtures/backup: the same bytes Go wrote must open here and be reproduced exactly. */
class BackupContainerTest {

    private val dir: File = sequenceOf("../../spec/fixtures/backup", "../spec/fixtures/backup", "spec/fixtures/backup")
        .map { File(it) }.firstOrNull { it.isDirectory } ?: error("spec/fixtures/backup not found")
    private val meta = JSONObject(File(dir, "small.json").readText())
    private val fileBytes = File(dir, "small.yaop").readBytes()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun vk() = VaultKey(hex(meta.getString("vk_hex")), meta.getLong("key_epoch"))

    private fun expected(): List<BackupContainer.Record> {
        val a = meta.getJSONArray("records")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            BackupContainer.Record(o.getInt("kind"), o.getString("id"), o.getLong("n"), o.getLong("total"), hex(o.getString("data_hex")))
        }
    }

    private fun readAll(bytes: ByteArray, key: VaultKey = vk()): List<BackupContainer.Record> {
        val got = ArrayList<BackupContainer.Record>()
        BackupContainer.read(ByteArrayInputStream(bytes), key) { got.add(it) }
        return got
    }

    @Test fun opensTheGoFixture() {
        val got = readAll(fileBytes)
        val exp = expected()
        assertEquals(exp.size, got.size)
        exp.zip(got).forEach { (e, g) ->
            assertEquals(e.kind, g.kind); assertEquals(e.id, g.id); assertEquals(e.n, g.n)
            assertEquals(e.total, g.total); assertArrayEquals(e.data, g.data)
        }
    }

    @Test fun writesTheFixtureByteForByte() {
        val out = ByteArrayOutputStream()
        val w = BackupContainer.Writer(out, vk(), compress = false) { i -> ByteArray(24) { (0x20 + i).toByte() } }
        expected().forEach(w::write)
        w.finish()
        assertArrayEquals(fileBytes, out.toByteArray())
    }

    @Test fun compressedRoundtrip() {
        val out = ByteArrayOutputStream()
        val w = BackupContainer.Writer(out, vk(), compress = true)
        expected().forEach(w::write); w.finish()
        assertEquals(expected().size, readAll(out.toByteArray()).size)
    }

    private fun assertRejected(bytes: ByteArray, key: VaultKey = vk()) {
        try { readAll(bytes, key); fail("must be rejected") } catch (_: BackupContainer.BackupException) {}
    }

    @Test fun negatives() {
        assertRejected(fileBytes, VaultKey(ByteArray(32) { 7 }, 2))
        assertRejected(fileBytes.copyOf(fileBytes.size - 5))
        assertRejected(fileBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0xff).toByte() })
        assertRejected("XXXX".toByteArray() + fileBytes.copyOfRange(4, fileBytes.size))
        val first = java.nio.ByteBuffer.wrap(fileBytes, 4, 4).int
        assertRejected(fileBytes.copyOfRange(0, 4) + fileBytes.copyOfRange(8 + first, fileBytes.size))
        assertRejected(fileBytes + byteArrayOf(0))
    }
}
