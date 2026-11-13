package org.yaop.ui.onboarding

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** Renders [text] as a QR (black on white with a quiet zone, readable in dark theme). */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, sizeDp: Int = 260) {
    val bmp = remember(text) { renderQr(text) }
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        filterQuality = FilterQuality.None,
        modifier = modifier.size(sizeDp.dp).background(Color.White).padding(8.dp),
    )
}

fun renderQr(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) AColor.BLACK else AColor.WHITE }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}
