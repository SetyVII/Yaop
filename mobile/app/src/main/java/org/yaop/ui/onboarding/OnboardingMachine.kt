package org.yaop.ui.onboarding

import org.yaop.core.crypto.vault.Bip39
import org.yaop.core.crypto.vault.Bip39Exception
import java.security.SecureRandom

/**
 * Onboarding state machine (spec §15.2, §21.1): create or restore the vault, show the 24-word
 * phrase once, confirm 3 random words, then (optionally) connect a server. Pure Kotlin with no
 * Android types so it is unit-testable; the Compose screen only renders [state] and forwards
 * events. The VK is NOT persisted here: the caller commits [Step.Server]'s `vk` through the
 * repository once the confirmation passes.
 */
class OnboardingMachine(
    private val bip39: Bip39,
    private val random: SecureRandom = SecureRandom(),
) {

    sealed interface Step {
        data object Welcome : Step

        /** The phrase is shown once; [vk] is the entropy it encodes. */
        data class ShowPhrase(val phrase: String, val vk: ByteArray) : Step

        /** The user types the words at [indices] (0-based, ascending, 3 distinct). */
        data class ConfirmPhrase(
            val phrase: String,
            val vk: ByteArray,
            val indices: List<Int>,
            val wrong: Boolean = false,
        ) : Step

        data class Restore(val error: RestoreError? = null) : Step

        /** Join an enrolled device (QR scan or pasted code); handled by the pairing flow. */
        data object Join : Step

        /** Vault key ready to persist; server connection is optional. */
        data class Server(val vk: ByteArray) : Step

        data object Done : Step
    }

    enum class RestoreError { WORD_COUNT, UNKNOWN_WORD, CHECKSUM }

    var state: Step = Step.Welcome
        private set

    fun create() {
        check(state is Step.Welcome)
        val vk = ByteArray(Bip39.ENTROPY_BYTES).also { random.nextBytes(it) }
        state = Step.ShowPhrase(bip39.entropyToMnemonic(vk), vk)
    }

    fun restore() {
        check(state is Step.Welcome)
        state = Step.Restore()
    }

    fun join() {
        check(state is Step.Welcome)
        state = Step.Join
    }

    /** The phrase was read; pick the 3 words to confirm. */
    fun phraseSaved() {
        val s = state as? Step.ShowPhrase ?: error("not showing the phrase")
        state = Step.ConfirmPhrase(s.phrase, s.vk, pickIndices())
    }

    fun confirm(answers: List<String>) {
        val s = state as? Step.ConfirmPhrase ?: error("not confirming")
        val words = s.phrase.split(' ')
        val ok = answers.size == s.indices.size &&
            s.indices.indices.all { answers[it].trim().lowercase() == words[s.indices[it]] }
        state = if (ok) Step.Server(s.vk) else s.copy(wrong = true)
    }

    /** Back from confirmation to the phrase (the same one — never regenerated). */
    fun showPhraseAgain() {
        val s = state as? Step.ConfirmPhrase ?: error("not confirming")
        state = Step.ShowPhrase(s.phrase, s.vk)
    }

    fun submitRestore(phrase: String) {
        check(state is Step.Restore)
        state = try {
            Step.Server(bip39.mnemonicToEntropy(normalize(phrase)))
        } catch (e: Bip39Exception) {
            Step.Restore(classify(e))
        }
    }

    /** VK obtained by pairing (already enrolled through the QR flow). */
    fun joined(vk: ByteArray) {
        check(state is Step.Join)
        state = Step.Server(vk)
    }

    fun finish() {
        check(state is Step.Server)
        state = Step.Done
    }

    fun back() {
        state = when (state) {
            is Step.Restore, Step.Join -> Step.Welcome
            is Step.ConfirmPhrase -> (state as Step.ConfirmPhrase).let { Step.ShowPhrase(it.phrase, it.vk) }
            is Step.ShowPhrase -> Step.Welcome
            else -> state
        }
    }

    private fun pickIndices(): List<Int> {
        val set = sortedSetOf<Int>()
        while (set.size < CONFIRM_WORDS) set.add(random.nextInt(Bip39.WORDS))
        return set.toList()
    }

    private fun normalize(phrase: String): String =
        phrase.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun classify(e: Bip39Exception): RestoreError {
        val m = e.message.orEmpty()
        return when {
            "checksum" in m -> RestoreError.CHECKSUM
            "not in the list" in m -> RestoreError.UNKNOWN_WORD
            else -> RestoreError.WORD_COUNT
        }
    }

    companion object {
        const val CONFIRM_WORDS = 3
    }
}
