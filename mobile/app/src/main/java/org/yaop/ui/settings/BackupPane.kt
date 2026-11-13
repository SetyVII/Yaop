package org.yaop.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.ui.theme.AppLabelStyle
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch

/** Export / restore of the encrypted `.yaop` backup (spec §15.6). */
@Composable
fun BackupPane() {
    val context = LocalContext.current
    val app = context.applicationContext as YaopApp
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun vk() = app.repository.vaultKeyStore.session()?.vaultKey

    val create = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = try {
                val key = vk() ?: error("no vault key")
                val s = context.contentResolver.openOutputStream(uri)!!.use { app.backupManager.export(it, key) }
                context.getString(R.string.backup_done_export, s.objects, s.assets)
            } catch (e: Exception) {
                context.getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName)
            }
            busy = false
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = try {
                val key = vk() ?: error("no vault key")
                val s = context.contentResolver.openInputStream(uri)!!.use { app.backupManager.restore(it, key) }
                context.getString(R.string.backup_done_restore, s.objects, s.assets)
            } catch (e: Exception) {
                context.getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName)
            }
            busy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.backup_hint), color = AppColors.TextSecondary)
        OutlinedButton({ create.launch("yaop-backup.yaop") }, Modifier.fillMaxWidth(), enabled = !busy) {
            Text(stringResource(R.string.backup_export).uppercase(), style = AppLabelStyle)
        }
        OutlinedButton({ open.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy) {
            Text(stringResource(R.string.backup_restore).uppercase(), style = AppLabelStyle)
        }
        if (busy) Text(stringResource(R.string.backup_working), color = AppColors.Ice)
        status?.let { Text(it, color = AppColors.TextPrimary) }
    }
}
