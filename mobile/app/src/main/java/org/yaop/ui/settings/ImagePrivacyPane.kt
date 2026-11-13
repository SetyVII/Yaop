package org.yaop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.ui.common.ImagePrivacy
import org.yaop.ui.common.PrivacyScope
import org.yaop.ui.common.ThumbnailMode
import org.yaop.ui.common.labelRes
import org.yaop.ui.theme.AppColors

/** Settings -> Images: mode, discreet mode, scope, reset on exit. */
@Composable
fun ImagePrivacyPane() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(R.string.thumb_settings_mode)
        ChipRow(ThumbnailMode.entries, ImagePrivacy.mode, { stringResource(it.labelRes()) }) { ImagePrivacy.updateMode(it) }
        Label(R.string.thumb_settings_discrete)
        ChipRow(listOf(ThumbnailMode.BLUR, ThumbnailMode.HIDE), ImagePrivacy.discreteMode, { stringResource(it.labelRes()) }) {
            ImagePrivacy.updateDiscreteMode(it)
        }
        Label(R.string.thumb_settings_scope)
        ChipRow(PrivacyScope.entries, ImagePrivacy.scope, {
            stringResource(if (it == PrivacyScope.ALL) R.string.thumb_scope_all else R.string.thumb_scope_lists)
        }) { ImagePrivacy.updateScope(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.thumb_settings_reset), Modifier.weight(1f), color = AppColors.TextPrimary)
            Switch(checked = ImagePrivacy.resetOnExit, onCheckedChange = ImagePrivacy::updateResetOnExit)
        }
        Text(stringResource(R.string.thumb_settings_hint), fontSize = 12.sp, color = AppColors.TextSecondary)
    }
}

@Composable
private fun Label(res: Int) = Text(stringResource(res), fontSize = 12.sp, color = AppColors.TextTertiary)

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            FilterChip(
                selected = selected == opt,
                onClick = { onSelect(opt) },
                label = { Text(label(opt)) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AppColors.Surface, labelColor = AppColors.TextSecondary,
                    selectedContainerColor = AppColors.Surface, selectedLabelColor = AppColors.Ice,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = selected == opt,
                    borderColor = AppColors.BorderSubtle, selectedBorderColor = AppColors.Ice,
                ),
            )
        }
    }
}
