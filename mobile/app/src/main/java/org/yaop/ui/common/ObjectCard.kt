package org.yaop.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.ui.editor.AudioPlayerRow
import org.yaop.ui.editor.formatDurationSeconds
import org.yaop.ui.theme.AppColors
import org.yaop.ui.theme.bouncyClickable

@Composable
fun ObjectCard(
    obj: PkmObject,
    type: ObjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isImage = obj.typeId == "image" || type?.id == "image" || obj.typeId == "type_image"
    val isAudio = obj.typeId == "audio" || type?.id == "audio" || obj.typeId == "type_audio"
    val isPdf = obj.typeId == "pdf" || type?.id == "pdf" || obj.typeId == "type_pdf"
    val assetId = if (isImage) resolveImageAssetId(obj) else obj.cover?.assetId?.takeIf { !it.startsWith("cover_") }
    val audioAssetId = if (isAudio) resolveAudioAssetId(obj) else null
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 384) else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClickable(onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (type != null) {
                        TypeBadge(type = type)
                    }
                    if (obj.isBookmark) {
                        Text(text = "🔖", fontSize = 12.sp)
                    }
                    if (obj.isReviewed) {
                        Text(text = "✓", fontSize = 12.sp, color = AppColors.Accent)
                    }
                }

                if (obj.isPinned) {
                    Text(text = "📌", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (thumbnail != null) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp)),
                    ) {
                        Image(
                            bitmap = thumbnail.bitmap.asImageBitmap(),
                            contentDescription = obj.plainTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().privacyImage(),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (thumbnail == null && !obj.icon?.emoji.isNullOrBlank()) {
                            Text(text = obj.icon.emoji, fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = obj.plainTitle,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (obj.plainSummary.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = obj.plainSummary,
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (isAudio && !audioAssetId.isNullOrBlank()) {
                        val dur = extractAudioDurationSeconds(obj)
                        val mimeShort = formatAudioMimeShort(extractImageMime(obj)) ?: "M4A"
                        val sizeText = formatFileSize(extractImageSize(obj))
                        val meta = listOfNotNull(
                            formatDurationSeconds(dur),
                            mimeShort,
                            sizeText,
                        ).joinToString(" • ")

                        Spacer(modifier = Modifier.height(8.dp))
                        AudioPlayerRow(
                            assetId = audioAssetId,
                            fallbackDurationSeconds = dur,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = meta,
                            fontSize = 11.sp,
                            color = AppColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (obj.aliases.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    obj.aliases.take(2).forEach { alias ->
                        Text(
                            text = "~$alias",
                            fontSize = 11.sp,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}
