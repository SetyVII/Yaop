package org.yaop.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.ui.theme.AppFonts
import org.yaop.ui.theme.AppColors

/**
 * Screen title: Inter Tight 34 with exactly one accent word in
 * Doto, in `ice`: the last word ("Good *evening*", "Your *tasks*"). A single-word title stays plain.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    val styled = remember(text) {
        val cut = text.lastIndexOf(' ')
        buildAnnotatedString {
            if (cut > 0) {
                append(text.substring(0, cut + 1))
                withStyle(SpanStyle(fontFamily = AppFonts.Doto, fontWeight = FontWeight.ExtraBold, color = AppColors.Ice, fontSize = 36.sp)) {
                    append(text.substring(cut + 1))
                }
            } else append(text)
        }
    }
    Text(
        text = styled,
        fontFamily = AppFonts.InterTight,
        fontSize = 34.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.7).sp,
        color = AppColors.TextPrimary,
        modifier = modifier.padding(top = 4.dp),
    )
}
