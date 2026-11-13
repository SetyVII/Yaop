package org.yaop.ui.onboarding

import org.yaop.core.crypto.vault.Bip39
import org.yaop.core.crypto.vault.Bip39Assets
import org.yaop.ui.onboarding.OnboardingMachine.RestoreError
import org.yaop.ui.onboarding.OnboardingMachine.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingMachineTest {

    private val bip39: Bip39 = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "spec/fixtures/crypto/bip39_english.txt").exists()) dir = dir.parentFile
        Bip39Assets.fromBytes(File(dir!!, "spec/fixtures/crypto/bip39_english.txt").readBytes())
    }

    private fun machine() = OnboardingMachine(bip39)

    @Test
    fun createFlowConfirmsThreeWordsThenServer() {
        val m = machine()
        m.create()
        val show = m.state as Step.ShowPhrase
        assertEquals(24, show.phrase.split(' ').size)
        assertArrayEquals(show.vk, bip39.mnemonicToEntropy(show.phrase))
        m.phraseSaved()
        val c = m.state as Step.ConfirmPhrase
        assertEquals(3, c.indices.size)
        assertEquals(c.indices.sorted(), c.indices)
        assertEquals(3, c.indices.toSet().size)
        val words = c.phrase.split(' ')
        m.confirm(c.indices.map { words[it] })
        val server = m.state as Step.Server
        assertArrayEquals(show.vk, server.vk)
        m.finish()
        assertTrue(m.state is Step.Done)
    }

    @Test
    fun wrongWordStaysInConfirmationAndKeepsThePhrase() {
        val m = machine()
        m.create()
        val phrase = (m.state as Step.ShowPhrase).phrase
        m.phraseSaved()
        m.confirm(listOf("zzz", "zzz", "zzz"))
        val c = m.state as Step.ConfirmPhrase
        assertTrue(c.wrong)
        assertEquals(phrase, c.phrase)
        m.back()
        assertEquals(phrase, (m.state as Step.ShowPhrase).phrase) // never regenerated
    }

    @Test
    fun confirmationIsCaseAndSpaceTolerant() {
        val m = machine()
        m.create()
        m.phraseSaved()
        val c = m.state as Step.ConfirmPhrase
        val words = c.phrase.split(' ')
        m.confirm(c.indices.map { " ${words[it].uppercase()} " })
        assertTrue(m.state is Step.Server)
    }

    @Test
    fun restoreAcceptsAValidPhraseAndClassifiesErrors() {
        val entropy = ByteArray(32) { it.toByte() }
        val phrase = bip39.entropyToMnemonic(entropy)
        val m = machine()
        m.restore()
        m.submitRestore("short phrase")
        assertEquals(RestoreError.WORD_COUNT, (m.state as Step.Restore).error)
        m.submitRestore((List(24) { "notaword" }).joinToString(" "))
        assertEquals(RestoreError.UNKNOWN_WORD, (m.state as Step.Restore).error)
        val bad = phrase.split(' ').toMutableList().also { it[23] = if (it[23] == "abandon") "ability" else "abandon" }
        m.submitRestore(bad.joinToString(" "))
        assertEquals(RestoreError.CHECKSUM, (m.state as Step.Restore).error)
        m.submitRestore("  " + phrase.uppercase().replace(' ', '\n') + " ")
        assertArrayEquals(entropy, (m.state as Step.Server).vk)
    }

    @Test
    fun joinedFromPairingGoesToServer() {
        val m = machine()
        m.join()
        val vk = ByteArray(32) { 7 }
        m.joined(vk)
        assertArrayEquals(vk, (m.state as Step.Server).vk)
    }

    @Test
    fun backReturnsToWelcome() {
        val m = machine()
        m.restore(); m.back()
        assertTrue(m.state is Step.Welcome)
        m.join(); m.back()
        assertTrue(m.state is Step.Welcome)
    }
}
