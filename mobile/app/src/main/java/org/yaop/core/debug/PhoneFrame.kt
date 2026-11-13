package org.yaop.core.debug

import android.content.res.Configuration
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Dev aid (debug builds, `--ez phone_frame true`): shows the PHONE layout inside a 412x892 dp frame
 * on a tablet by lying to the layout code about the window size. Never active in release builds.
 */
@Composable
fun PhoneFrameIf(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val base = LocalConfiguration.current
    val phone = Configuration(base).apply { screenWidthDp = 412; screenHeightDp = 892 }
    Box(Modifier.fillMaxSize().then(Modifier), contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalConfiguration provides phone) {
            Box(
                Modifier.width(412.dp).height(892.dp).clip(RoundedCornerShape(28.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp)),
            ) { content() }
        }
    }
}
