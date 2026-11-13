package org.yaop.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val YaopDarkColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = AppColors.AccentInk,
    primaryContainer = AppColors.AccentSubtle,
    onPrimaryContainer = AppColors.Accent,
    background = Color.Transparent, // the sky gradient is painted once behind everything
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceElevated,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.BorderSubtle,
    outlineVariant = AppColors.BorderHover,
    surfaceContainer = AppColors.SurfaceElevated,
    surfaceContainerHigh = AppColors.SurfaceElevated,
    surfaceContainerHighest = AppColors.SurfaceElevated,
    surfaceContainerLow = AppColors.Surface,
    surfaceTint = Color.Transparent,
    secondary = AppColors.Ice,
    error = AppColors.Danger
)

/** Design radii (§4): 14 on controls and menus, 22 on cards, sheets and dialogs. */
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
)

@Composable
fun YaopTheme(
    darkTheme: Boolean = true, // Default to true for precision dark aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = YaopDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = AppColors.Background.toArgb()
            window.navigationBarColor = AppColors.NavigationBar.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
    ) {
        Box(Modifier.fillMaxSize().background(AppBackgroundBrush.Background)) { content() }
    }
}
