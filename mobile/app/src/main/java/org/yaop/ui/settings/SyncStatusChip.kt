package org.yaop.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.sync.SyncManager
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Always-visible sync state (spec §22.6); tap to configure server URL + token. */
@Composable
fun SyncStatusChip(modifier: Modifier = Modifier, onlyProblems: Boolean = false) {
    val app = LocalContext.current.applicationContext as YaopApp
    val sync = app.syncManager
    val state by sync.syncState.collectAsState()
    var open by remember { mutableStateOf(false) }

    // "No vault" must reflect the key actually stored, not whether a sync happened to run yet.
    var storedVault by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state) { storedVault = app.repository.vaultKeyStore.hasVaultKey() }

    val noToken = sync.bearerToken.isBlank()
    val (label, dot) = when {
        noToken -> stringResource(R.string.sync_status_no_token) to AppColors.Warning
        // Without a VK nothing travels (onboarding not done): visible state, never a false error.
        storedVault == false -> stringResource(R.string.sync_status_no_vault) to AppColors.Warning
        state.isSyncing -> stringResource(R.string.sync_status_syncing) to AppColors.Ice
        // protocol_mismatch: the server does not speak yaop-sync/1 (spec §16.4): never blindly.
        state.lastErrorCode == "protocol_mismatch" ->
            stringResource(R.string.sync_status_protocol_mismatch) to AppColors.Danger
        state.lastError != null -> stringResource(R.string.sync_status_error) to AppColors.Danger
        state.isConnected -> {
            val withPending = if (state.pendingUploads > 0) {
                stringResource(R.string.sync_status_connected) + " · " +
                    stringResource(R.string.sync_status_pending_n, state.pendingUploads)
            } else {
                stringResource(R.string.sync_status_connected)
            }
            withPending to AppColors.Success
        }
        else -> stringResource(R.string.sync_status_offline) to AppColors.TextSecondary
    }
    val healthy = !noToken && storedVault != false && state.lastError == null &&
        (state.isConnected || state.isSyncing)
    if (onlyProblems && healthy) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable { open = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = AppColors.TextSecondary)
    }
    if (open) SyncSettingsDialog(sync = sync, onDismiss = { open = false })
}

@Composable
private fun SyncSettingsDialog(sync: SyncManager, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by sync.syncState.collectAsState()
    var url by remember { mutableStateOf(sync.serverUrl) }
    var token by remember { mutableStateOf(sync.bearerToken) }
    var vaultOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SyncManager.AuthCheck?>(null) }

    if (vaultOpen) VaultSheet(onDismiss = { vaultOpen = false })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; result = null },
                    label = { Text(stringResource(R.string.sync_settings_url)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it; result = null },
                    label = { Text(stringResource(R.string.sync_settings_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                result?.let {
                    Text(
                        stringResource(
                            when (it) {
                                SyncManager.AuthCheck.OK -> R.string.sync_settings_ok
                                SyncManager.AuthCheck.BAD_TOKEN -> R.string.sync_settings_bad_token
                                SyncManager.AuthCheck.UNREACHABLE -> R.string.sync_settings_unreachable
                            }
                        ),
                        fontSize = 13.sp,
                        color = if (it == SyncManager.AuthCheck.OK) AppColors.Success else AppColors.Danger,
                    )
                }
                val last = if (state.lastSyncTime > 0) {
                    DateUtils.getRelativeTimeSpanString(state.lastSyncTime).toString()
                } else {
                    stringResource(R.string.sync_settings_never)
                }
                Text(stringResource(R.string.sync_settings_last_sync, last), fontSize = 12.sp, color = AppColors.TextSecondary)
                state.lastError?.let {
                    Text(stringResource(R.string.sync_settings_last_error, it), fontSize = 12.sp, color = AppColors.TextSecondary)
                }
                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val check = sync.testConnection(url, token)
                    result = check
                    if (check == SyncManager.AuthCheck.OK) sync.updateConfig(url, token)
                    busy = false
                }
            }) { Text(stringResource(R.string.sync_settings_save)) }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy && !state.isSyncing, onClick = { scope.launch { sync.syncNow() } }) {
                    Text(stringResource(R.string.sync_settings_sync_now))
                }
                TextButton(onClick = { vaultOpen = true }) { Text(stringResource(R.string.vault_title)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.sync_settings_close)) }
            }
        },
    )
}
