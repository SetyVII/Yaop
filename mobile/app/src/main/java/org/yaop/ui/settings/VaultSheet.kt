package org.yaop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.crypto.vault.Bip39Runtime
import org.yaop.core.pairing.PairingRelay
import org.yaop.core.pairing.PairingSession
import org.yaop.core.pairing.TlsFingerprint
import org.yaop.ui.onboarding.QrImage
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private enum class VaultPane { MENU, PHRASE, ADD, DEVICES, LOCK, BACKUP, CAPTURE, IMAGES, HOME }

/** Settings entry for the vault: recovery phrase, add device (QR), device list/revoke (spec §15.2–15.3). */
@Composable
fun VaultSheet(onDismiss: () -> Unit) {
    var pane by remember { mutableStateOf(VaultPane.MENU) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_title)) },
        text = {
            when (pane) {
                VaultPane.MENU -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pane = VaultPane.PHRASE }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_show_phrase)) }
                    OutlinedButton({ pane = VaultPane.ADD }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_add_device)) }
                    OutlinedButton({ pane = VaultPane.DEVICES }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vault_devices)) }
                    OutlinedButton({ pane = VaultPane.HOME }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_settings_title)) }
                    OutlinedButton({ pane = VaultPane.CAPTURE }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_style_title)) }
                    OutlinedButton({ pane = VaultPane.IMAGES }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.thumb_settings_title)) }
                    OutlinedButton({ pane = VaultPane.BACKUP }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_section)) }
                    OutlinedButton({ pane = VaultPane.LOCK }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock_section)) }
                }
                VaultPane.PHRASE -> PhrasePane()
                VaultPane.ADD -> AddDevicePane()
                VaultPane.DEVICES -> DevicesPane()
                VaultPane.LOCK -> LockPane()
                VaultPane.BACKUP -> BackupPane()
                VaultPane.CAPTURE -> CaptureStylePane()
                VaultPane.IMAGES -> ImagePrivacyPane()
                VaultPane.HOME -> HomePane()
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.common_close)) } },
        dismissButton = {
            if (pane != VaultPane.MENU) TextButton({ pane = VaultPane.MENU }) { Text(stringResource(R.string.onb_back)) }
        },
    )
}

@Composable
private fun PhrasePane() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as YaopApp
    var phrase by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val vk = withContext(Dispatchers.IO) { app.repository.vaultSession()?.vaultKey?.key }
        if (vk == null) missing = true else phrase = Bip39Runtime.fromAssets(ctx).entropyToMnemonic(vk)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onb_phrase_warning), fontSize = 12.sp, color = AppColors.TextSecondary)
        if (missing) Text(stringResource(R.string.vault_no_vault))
        phrase?.split(' ')?.chunked(3)?.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEachIndexed { c, w ->
                    Text("${r * 3 + c + 1}. $w", Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

private sealed interface AddState {
    data object Starting : AddState
    data class Qr(val payload: String) : AddState
    data class Compare(val code: String) : AddState
    data object Done : AddState
    data object Failed : AddState
    data object NeedServer : AddState
}

@Composable
private fun AddDevicePane() {
    val app = LocalContext.current.applicationContext as YaopApp
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AddState>(AddState.Starting) }
    var host by remember { mutableStateOf<PairingSession.Host?>(null) }

    LaunchedEffect(Unit) {
        val url = app.syncManager.serverUrl
        if (app.syncManager.bearerToken.isBlank() || url.isBlank()) { state = AddState.NeedServer; return@LaunchedEffect }
        val h = withContext(Dispatchers.IO) {
            val fp = TlsFingerprint.of(url) ?: return@withContext null
            PairingSession.Host(PairingRelay(url), url, fp)
        }
        val payload = h?.let { withContext(Dispatchers.IO) { it.open() } }
        if (h == null || payload == null) { state = AddState.Failed; return@LaunchedEffect }
        host = h
        state = AddState.Qr(payload)
        repeat(300) {
            val code = withContext(Dispatchers.IO) { h.pollCode() }
            if (code != null) { state = AddState.Compare(code); return@LaunchedEffect }
            delay(2000)
        }
        state = AddState.Failed
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (val s = state) {
            AddState.Starting -> Text("…")
            AddState.NeedServer -> Text(stringResource(R.string.vault_add_need_server))
            AddState.Failed -> Text(stringResource(R.string.vault_add_failed), color = AppColors.Danger)
            AddState.Done -> Text(stringResource(R.string.vault_add_done), color = AppColors.Success)
            is AddState.Qr -> {
                Text(stringResource(R.string.vault_add_waiting), fontSize = 13.sp)
                QrImage(s.payload, sizeDp = 220)
                // The raw code is also offered as selectable text for the paste path of the new device.
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(s.payload, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
                }
            }
            is AddState.Compare -> {
                Text(stringResource(R.string.vault_add_compare), fontSize = 13.sp)
                Text(s.code.chunked(3).joinToString(" "), fontSize = 36.sp, fontFamily = FontFamily.Monospace, color = AppColors.Accent)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        scope.launch {
                            val session = withContext(Dispatchers.IO) { app.repository.vaultSession() }
                            val ok = session != null && withContext(Dispatchers.IO) {
                                host?.approve(session.vaultKey.key, session.vaultKey.keyEpoch) == true
                            }
                            state = if (ok) AddState.Done else AddState.Failed
                        }
                    }) { Text(stringResource(R.string.vault_add_match)) }
                    TextButton({ state = AddState.Failed }) { Text(stringResource(R.string.vault_add_cancel)) }
                }
            }
        }
    }
}

private data class DeviceRow(val id: String, val revoked: Boolean)

@Composable
private fun DevicesPane() {
    val app = LocalContext.current.applicationContext as YaopApp
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceRow>>(emptyList()) }
    var me by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        val (list, you) = withContext(Dispatchers.IO) { fetchDevices(app.syncManager.serverUrl, app.syncManager.bearerToken) }
        devices = list; me = you
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (devices.isEmpty()) Text(stringResource(R.string.vault_devices_none), color = AppColors.TextSecondary)
        devices.forEach { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val tag = when {
                    d.id == me -> " · " + stringResource(R.string.vault_device_this)
                    d.revoked -> " · " + stringResource(R.string.vault_device_revoked)
                    else -> ""
                }
                Text(d.id.take(12) + tag, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (!d.revoked && d.id != me) {
                    TextButton({
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { revokeDevice(app.syncManager.serverUrl, app.syncManager.bearerToken, d.id) }
                            error = !ok
                            reload++
                        }
                    }) { Text(stringResource(R.string.vault_device_revoke)) }
                }
            }
        }
        if (error) Text(stringResource(R.string.vault_device_revoke_failed), color = AppColors.Danger, fontSize = 12.sp)
    }
}

private fun fetchDevices(base: String, token: String): Pair<List<DeviceRow>, String> = try {
    val c = URL(base.trimEnd('/') + "/v1/devices").openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Bearer $token")
    c.connectTimeout = 8000; c.readTimeout = 8000
    if (c.responseCode != 200) emptyList<DeviceRow>() to "" else {
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val arr = j.optJSONArray("devices")
        val rows = (0 until (arr?.length() ?: 0)).map { i ->
            val o = arr!!.getJSONObject(i)
            DeviceRow(o.getString("device_id"), o.optBoolean("revoked"))
        }
        rows to j.optString("you")
    }
} catch (e: Exception) { emptyList<DeviceRow>() to "" }

private fun revokeDevice(base: String, token: String, id: String): Boolean = try {
    val c = URL(base.trimEnd('/') + "/v1/devices/$id").openConnection() as HttpURLConnection
    c.requestMethod = "DELETE"
    c.setRequestProperty("Authorization", "Bearer $token")
    c.connectTimeout = 8000; c.readTimeout = 8000
    c.responseCode == 200
} catch (e: Exception) { false }
