package org.yaop.ui.tablet

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window information holder for responsive tablet adaptation.
 */
data class TabletWindowInfo(
    val isTablet: Boolean,
    val isLandscape: Boolean,
    val screenWidthDp: Dp,
    val screenHeightDp: Dp,
    val useDualPane: Boolean,
)

/**
 * Computes window size characteristics to adapt layout between Phone and Tablet modes.
 * - Phone: width < 600dp (standard portrait phones).
 * - Tablet Portrait: width >= 600dp (large vertical canvas, single wide pane or navigation rail).
 * - Tablet Landscape: width >= 800dp in landscape (dual-pane master-detail view).
 */
@Composable
fun rememberTabletWindowInfo(): TabletWindowInfo {
    val configuration = LocalConfiguration.current
    return remember(configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation) {
        val widthDp = configuration.screenWidthDp.dp
        val heightDp = configuration.screenHeightDp.dp
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val isTablet = (configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 500) ||
            configuration.screenWidthDp >= 840
        val useDualPane = isTablet && isLandscape && configuration.screenWidthDp >= 800

        TabletWindowInfo(
            isTablet = isTablet,
            isLandscape = isLandscape,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            useDualPane = useDualPane,
        )
    }
}
