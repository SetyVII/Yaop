package org.yaop.ui.onboarding

import org.yaop.ui.common.ambientGlyphField
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.crypto.vault.Bip39Runtime
import org.yaop.core.crypto.vault.Pairing
import org.yaop.core.pairing.PairingRelay
import org.yaop.core.pairing.PairingSession
import org.yaop.core.pairing.SetupQr
import org.yaop.core.pairing.TlsFingerprint
import org.yaop.core.sync.SyncManager
import org.yaop.ui.onboarding.OnboardingMachine.RestoreError
import org.yaop.ui.onboarding.OnboardingMachine.Step
import org.yaop.ui.theme.AppColors
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-run flow (spec §21.1). Calls [onDone] once the vault key is stored. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as YaopApp
    val scope = rememberCoroutineScope()
    val machine = remember { OnboardingMachine(Bip39Runtime.fromAssets(ctx)) }
    var step by remember { mutableStateOf<Step>(machine.state) }
    var epoch by remember { mutableStateOf(1L) }
    var qrServer by remember { mutableStateOf("") }
    fun sync() { step = machine.state }

    Column(
        Modifier.fillMaxSize().background(org.yaop.ui.theme.AppBackgroundBrush.Background).ambientGlyphField().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (val s = step) {
            is Step.Welcome -> {
                org.yaop.ui.common.ScreenTitle(stringResource(R.string.onb_title))
                Text(stringResource(R.string.onb_subtitle), color = AppColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Button({ machine.create(); sync() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_create)) }
                OutlinedButton({ machine.restore(); sync() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_restore)) }
                OutlinedButton({ machine.join(); sync() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_join)) }
            }

            is Step.ShowPhrase -> {
                org.yaop.ui.common.ScreenTitle(stringResource(R.string.onb_phrase_title))
                Text(stringResource(R.string.onb_phrase_warning), color = AppColors.TextSecondary)
                PhraseGrid(s.phrase)
                Button({ machine.phraseSaved(); sync() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_phrase_saved)) }
                TextButton({ machine.back(); sync() }) { Text(stringResource(R.string.onb_back)) }
            }

            is Step.ConfirmPhrase -> {
                val answers = remember(s.indices) { mutableStateOf(List(s.indices.size) { "" }) }
                org.yaop.ui.common.ScreenTitle(stringResource(R.string.onb_confirm_title))
                Text(stringResource(R.string.onb_confirm_hint), color = AppColors.TextSecondary)
                s.indices.forEachIndexed { i, idx ->
                    OutlinedTextField(
                        value = answers.value[i],
                        onValueChange = { v -> answers.value = answers.value.toMutableList().also { it[i] = v } },
                        label = { Text(stringResource(R.string.onb_confirm_word_n, idx + 1)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (s.wrong) Text(stringResource(R.string.onb_confirm_wrong), color = AppColors.Danger)
                Button({ machine.confirm(answers.value); sync() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_continue)) }
                TextButton({ machine.showPhraseAgain(); sync() }) { Text(stringResource(R.string.onb_confirm_show_again)) }
            }

            is Step.Restore -> {
                var phrase by remember { mutableStateOf("") }
                org.yaop.ui.common.ScreenTitle(stringResource(R.string.onb_restore_title))
                Text(stringResource(R.string.onb_restore_hint), color = AppColors.TextSecondary)
                OutlinedTextField(
                    value = phrase, onValueChange = { phrase = it },
                    label = { Text(stringResource(R.string.onb_restore_phrase)) },
                    minLines = 4, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                s.error?.let {
                    Text(
                        stringResource(
                            when (it) {
                                RestoreError.WORD_COUNT -> R.string.onb_err_word_count
                                RestoreError.UNKNOWN_WORD -> R.string.onb_err_unknown_word
                                RestoreError.CHECKSUM -> R.string.onb_err_checksum
                            }
                        ),
                        color = AppColors.Danger,
                    )
                }
                Button({ machine.submitRestore(phrase); sync() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_continue)) }
                TextButton({ machine.back(); sync() }) { Text(stringResource(R.string.onb_back)) }
            }

            is Step.Join -> JoinStep(
                onVk = { vk, ep, url, token ->
                    epoch = ep; qrServer = url; machine.joined(vk)
                    // The pairing gave this device its own token: nothing to type, go straight in.
                    if (token != null) scope.launch { commit(app, vk, ep, url to token); machine.finish(); onDone() } else sync()
                },
                onBack = { machine.back(); sync() },
            )

            is Step.Server -> ServerStep(
                sync = app.syncManager,
                initialUrl = qrServer,
                onSkip = { scope.launch { commit(app, s.vk, epoch, null); machine.finish(); onDone() } },
                onConnect = { url, token -> scope.launch { commit(app, s.vk, epoch, url to token); machine.finish(); onDone() } },
            )

            Step.Done -> Unit
        }
    }
}

private suspend fun commit(app: YaopApp, vk: ByteArray, epoch: Long, server: Pair<String, String>?) {
    withContext(Dispatchers.IO) {
        app.repository.createVaultKey(vk, epoch)
        if (server != null) app.syncManager.updateConfig(server.first, server.second)
    }
    app.syncManager.syncNow()
}

@Composable
private fun PhraseGrid(phrase: String) {
    val words = phrase.split(' ')
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        words.chunked(3).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth()) {
                chunk.forEachIndexed { col, w ->
                    Text(
                        "${row * 3 + col + 1}. $w",
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                        color = AppColors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun JoinStep(onVk: (ByteArray, Long, String, String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var guest by remember { mutableStateOf<PairingSession.Guest?>(null) }
    var serverUrl by remember { mutableStateOf("") }

    fun start(raw: String) {
        val qr = Pairing.decodeQr(raw.trim())
        if (qr == null) { errorRes = R.string.onb_join_bad_code; return }
        scope.launch {
            val g = PairingSession.Guest(PairingRelay(qr.serverUrl), qr)
            // Tell a certificate that does not match apart from a server that cannot be reached.
            val fp = withContext(Dispatchers.IO) { TlsFingerprint.of(qr.serverUrl) }
            if (fp == null) { errorRes = R.string.onb_join_unreachable; return@launch }
            if (qr.serverUrl.startsWith("https://") && fp != qr.tlsFp) {
                android.util.Log.w("Pairing", "tls_fp mismatch: server $fp, code ${qr.tlsFp}")
                errorRes = R.string.onb_server_bad_cert; return@launch
            }
            val c = withContext(Dispatchers.IO) { g.join() }
            if (c == null) {
                errorRes = if (g.codeAlreadyUsed) R.string.onb_join_used else R.string.onb_join_unreachable
                return@launch
            }
            errorRes = null; serverUrl = qr.serverUrl; guest = g; code = c
        }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { start(it) } }

    // After joining, poll until the host approves (relay rows expire after 10 min).
    LaunchedEffect(guest) {
        val g = guest ?: return@LaunchedEffect
        repeat(300) {
            val opened = withContext(Dispatchers.IO) { g.pollVk() }
            if (opened != null) {
                val token = withContext(Dispatchers.IO) {
                    g.enroll(org.yaop.core.sync.deviceIdFor(context), android.os.Build.MODEL ?: "Android")
                }
                onVk(opened.vk, opened.keyEpoch, serverUrl, token)
                return@LaunchedEffect
            }
            delay(2000)
        }
        errorRes = R.string.onb_join_unreachable
        guest = null; code = null
    }

    org.yaop.ui.common.ScreenTitle(stringResource(R.string.onb_join_title))
    if (code == null) {
        Text(stringResource(R.string.onb_join_hint), color = AppColors.TextSecondary)
        Button({
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false))
        }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_join_scan)) }
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it },
            label = { Text(stringResource(R.string.onb_join_paste)) },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        errorRes?.let { Text(stringResource(it), color = AppColors.Danger) }
        OutlinedButton({ start(pasted) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_join_go)) }
    } else {
        Text(stringResource(R.string.onb_join_compare), color = AppColors.TextSecondary)
        Text(code!!.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace, color = AppColors.Accent)
        Text(stringResource(R.string.onb_join_waiting), color = AppColors.TextSecondary)
    }
    TextButton(onBack) { Text(stringResource(R.string.onb_back)) }
}

@Composable
private fun ServerStep(
    sync: SyncManager,
    initialUrl: String,
    onSkip: () -> Unit,
    onConnect: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<Int?>(null) }

    fun connect(u: String, t: String) {
        busy = true
        scope.launch {
            val ok = sync.testConnection(u, t) == SyncManager.AuthCheck.OK
            busy = false
            if (ok) onConnect(u, t) else failed = true
        }
    }

    // First device: the server's setup QR carries URL, pinned certificate and token.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val raw = r.contents ?: return@rememberLauncherForActivityResult
        val setup = SetupQr.decode(raw)
        if (setup == null) { scanError = R.string.onb_server_bad_setup; return@rememberLauncherForActivityResult }
        scope.launch {
            val pinned = withContext(Dispatchers.IO) { TlsFingerprint.matches(setup.serverUrl, setup.tlsFp) }
            if (!pinned) { scanError = R.string.onb_server_bad_cert; return@launch }
            scanError = null; failed = false
            url = setup.serverUrl; token = setup.token
            connect(setup.serverUrl, setup.token)
        }
    }

    org.yaop.ui.common.ScreenTitle(stringResource(R.string.onb_server_title))
    Text(stringResource(R.string.onb_server_hint), color = AppColors.TextSecondary)
    Button(enabled = !busy, onClick = {
        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setOrientationLocked(false))
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onb_server_scan)) }
    Text(stringResource(R.string.onb_server_scan_hint), color = AppColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    scanError?.let { Text(stringResource(it), color = AppColors.Danger) }
    OutlinedTextField(
        value = url, onValueChange = { url = it; failed = false },
        label = { Text(stringResource(R.string.sync_settings_url)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token, onValueChange = { token = it; failed = false },
        label = { Text(stringResource(R.string.sync_settings_token)) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (failed) Text(stringResource(R.string.onb_server_failed), color = AppColors.Danger)
    OutlinedButton(enabled = !busy && url.isNotBlank() && token.isNotBlank(), onClick = { connect(url, token) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_server_connect))
    }
    TextButton(onSkip) { Text(stringResource(R.string.onb_server_skip)) }
}
