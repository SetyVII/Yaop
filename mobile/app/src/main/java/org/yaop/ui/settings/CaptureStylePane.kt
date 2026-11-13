package org.yaop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import org.yaop.R
import org.yaop.ui.main.CaptureStyle
import org.yaop.ui.theme.AppColors

/** Choose how the dock's (+) opens quick capture: card grid (default) or the radial fan. */
@Composable
fun CaptureStylePane() {
    val prefs = CaptureStyle.prefs(LocalContext.current)
    var cards by remember { mutableStateOf(CaptureStyle.isCards(prefs)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(true to R.string.capture_style_cards, false to R.string.capture_style_radial).forEach { (isCards, label) ->
            FilterChip(
                selected = cards == isCards,
                onClick = { cards = isCards; CaptureStyle.set(prefs, isCards) },
                label = { Text(stringResource(label)) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AppColors.Surface, labelColor = AppColors.TextSecondary,
                    selectedContainerColor = AppColors.Surface, selectedLabelColor = AppColors.Ice,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = cards == isCards,
                    borderColor = AppColors.BorderSubtle, selectedBorderColor = AppColors.Ice,
                ),
            )
        }
    }
}
