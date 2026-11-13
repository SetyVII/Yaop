package org.yaop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.yaop.R
import org.yaop.ui.theme.AppColors

/** Settings › Inicio: which optional sections Home shows (default: only what you need today). */
@Composable
fun HomePane() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row2(R.string.home_habits, HomePrefs.showHabits, HomePrefs::setHabits)
        Row2(R.string.home_section_continue, HomePrefs.showContinue, HomePrefs::setContinue)
        Row2(R.string.home_section_pinned, HomePrefs.showPinned, HomePrefs::setPinned)
        Row2(R.string.home_section_images, HomePrefs.showImages, HomePrefs::setImages)
        Row2(R.string.home_section_recent_created, HomePrefs.showRecent, HomePrefs::setRecent)
    }
}

@Composable
private fun Row2(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f), color = AppColors.TextPrimary)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
