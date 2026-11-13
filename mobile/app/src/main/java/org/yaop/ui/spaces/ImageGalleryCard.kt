package org.yaop.ui.spaces

import org.yaop.ui.common.privacyImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.ui.common.extractImageMime
import org.yaop.ui.common.extractImageSize
import org.yaop.ui.common.extractImageTags
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.formatMimeShort
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors

/**
 * Modern, asymmetric card for the staggered masonry Image Gallery view.
 * Adapts its height to the original image's aspect ratio (clamped between 0.55 and 2.0).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageGalleryCard(
    obj: PkmObject,
    type: ObjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assetId = resolveImageAssetId(obj)
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 384) else null
    val rawRatio = thumbnail?.aspectRatio ?: 1f
    // Clamping keeps extreme screenshots and panoramas nicely proportioned
    val clampedRatio = rawRatio.coerceIn(0.55f, 2.0f)

    val tags = extractImageTags(obj)
    val fileSizeFormatted = formatFileSize(extractImageSize(obj))
    val mimeShort = formatMimeShort(extractImageMime(obj))
    val is4K = (thumbnail?.originalWidth ?: 0) >= 3800 || (thumbnail?.originalHeight ?: 0) >= 2100

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.Surface, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Column {
            // Visual Image Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(clampedRatio)
                    .background(AppColors.SurfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.bitmap.asImageBitmap(),
                        contentDescription = obj.plainTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = stringResource(R.string.block_type_image),
                        tint = AppColors.TextTertiary.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp),
                    )
                }

                // Top floating badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (is4K) {
                        Text(
                            text = stringResource(R.string.image_format_resolution_4k),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TypePalette.ColorYellow,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    } else if (!mimeShort.isNullOrBlank()) {
                        Text(
                            text = mimeShort,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    if (obj.isPinned) {
                        Text(
                            text = "📌",
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // Card Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = obj.plainTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        tags.take(2).forEach { tag ->
                            Text(
                                text = "#$tag",
                                fontSize = 10.sp,
                                color = AppColors.Accent,
                                maxLines = 1,
                            )
                        }
                    }
                }

                if (!fileSizeFormatted.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = fileSizeFormatted,
                        fontSize = 10.sp,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }
    }
}

/**
 * Compact horizontal list item for the Image List view.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageListItem(
    obj: PkmObject,
    type: ObjectType?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assetId = resolveImageAssetId(obj)
    val thumbnail = if (!assetId.isNullOrBlank()) rememberDecodedThumbnail(assetId, targetMaxSidePx = 384) else null
    val tags = extractImageTags(obj)
    val fileSize = formatFileSize(extractImageSize(obj))
    val mimeShort = formatMimeShort(extractImageMime(obj))
    val is4K = (thumbnail?.originalWidth ?: 0) >= 3800 || (thumbnail?.originalHeight ?: 0) >= 2100

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail Box
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.SurfaceElevated, RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.bitmap.asImageBitmap(),
                    contentDescription = obj.plainTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().privacyImage(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(24.dp),
                )
            }

            if (is4K) {
                Text(
                    text = stringResource(R.string.image_format_resolution_4k),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = TypePalette.ColorYellow,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(vertical = 1.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Content Column
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = obj.plainTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (obj.isPinned) {
                    Text(text = "📌", fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }

            // Dimensions / Format / Size info
            val metaParts = buildList {
                if (thumbnail != null) {
                    add("${thumbnail.originalWidth}×${thumbnail.originalHeight}")
                }
                if (!mimeShort.isNullOrBlank()) add(mimeShort)
                if (!fileSize.isNullOrBlank()) add(fileSize)
            }
            if (metaParts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = metaParts.joinToString(" • "),
                    fontSize = 11.sp,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tags.take(3).forEach { tag ->
                        Text(
                            text = "#$tag",
                            fontSize = 11.sp,
                            color = AppColors.Accent,
                        )
                    }
                }
            }
        }
    }
}
