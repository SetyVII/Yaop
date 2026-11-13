package org.yaop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import org.yaop.ui.theme.AppLabelStyle
import org.yaop.ui.theme.AppColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.yaop.YaopApp
import org.yaop.R
import org.yaop.core.security.AppLockPolicy
import org.yaop.ui.lock.canAuthenticate

/** App lock settings (spec §15.5): enable, timeout and screenshot protection. */
@Composable
fun LockPane() {
    val context = LocalContext.current
    val lock = (context.applicationContext as YaopApp).appLock
    val canLock = (context as? FragmentActivity)?.let(::canAuthenticate) ?: false
    var enabled by remember { mutableStateOf(lock.enabled) }
    var minutes by remember { mutableStateOf(lock.timeoutMinutes) }
    var secure by remember { mutableStateOf(lock.secureScreen) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!canLock) Text(stringResource(R.string.lock_unavailable))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.lock_enable))
            Switch(colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Ice, checkedThumbColor = AppColors.AccentInk), checked = enabled, enabled = canLock, onCheckedChange = { enabled = it; lock.enabled = it })
        }
        if (enabled) {
            Text(stringResource(R.string.lock_timeout).uppercase(), style = AppLabelStyle, color = AppColors.TextTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLockPolicy.TIMEOUT_MINUTES.forEach { m ->
                    FilterChip(
                        selected = minutes == m,
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = AppColors.Surface, labelColor = AppColors.TextSecondary,
                            selectedContainerColor = AppColors.Surface, selectedLabelColor = AppColors.Ice,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = minutes == m,
                            borderColor = AppColors.BorderSubtle, selectedBorderColor = AppColors.Ice,
                        ),
                        onClick = { minutes = m; lock.timeoutMinutes = m },
                        label = {
                            Text(
                                if (m == 0) stringResource(R.string.lock_timeout_now)
                                else stringResource(R.string.lock_timeout_minutes, m),
                            )
                        },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.lock_secure_screen))
                Switch(colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Ice, checkedThumbColor = AppColors.AccentInk), checked = secure, onCheckedChange = { secure = it; lock.secureScreen = it })
            }
        }
    }
}
