package org.yaop.ui.common

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import org.yaop.ui.theme.AppColors

/**
 * Translucent modal bottom sheet over a blurred backdrop. On API 31+
 * the dialog window blurs what is behind it (a real backdrop blur, no per-frame cost in our
 * content); below that it falls back to a 90% solid. One place for every sheet in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle(color = AppColors.TextTertiary) },
    content: @Composable ColumnScope.() -> Unit,
) {
    // Translucent only when the system really blurs behind windows; otherwise nearly solid, so the
    // text behind never bleeds through.
    val context = LocalView.current.context
    val blurs = remember(context) { crossWindowBlurEnabled(context) }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = if (blurs) Color(0xD90B1A4A) else Color(0xF50B1A4A),
        contentColor = AppColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = dragHandle,
    ) {
        WindowBackdropBlur()
        content()
    }
}

private fun crossWindowBlurEnabled(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    var c = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c.windowManager.isCrossWindowBlurEnabled
        c = c.baseContext
    }
    return false
}

/** Blurs the content behind the current dialog window (API 31+); no-op below or outside a dialog. */
@Composable
fun WindowBackdropBlur(radiusPx: Int = 40) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
    }
}
