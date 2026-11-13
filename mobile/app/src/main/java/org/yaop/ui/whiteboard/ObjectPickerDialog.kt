package org.yaop.ui.whiteboard

import org.yaop.ui.common.privacyImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyKeys
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toPlainText
import org.yaop.ui.common.TypeBadge
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors

/**
 * Picker dialog to search and place any existing object from the vault onto the whiteboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectPickerDialog(
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onDismiss: () -> Unit,
    onObjectSelected: (String) -> Unit,
    onCreateNewObject: ((String, String) -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf("all") }

    val filteredObjects = remember(allObjects, searchQuery, selectedTypeFilter) {
        val q = searchQuery.trim().lowercase()
        allObjects.filter { obj ->
            if (obj.trashedAt != null) return@filter false
            val canonicalType = obj.typeId
            val matchesType = selectedTypeFilter == "all" || canonicalType == selectedTypeFilter
            val matchesQuery = q.isEmpty() ||
                obj.plainTitle.lowercase().contains(q) ||
                obj.plainSummary.lowercase().contains(q)
            matchesType && matchesQuery
        }
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(TypePalette.ColorBgSecondary)
                .border(1.dp, TypePalette.ColorShapeHighlightMedium, RoundedCornerShape(24.dp)),
            color = Color.Transparent,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.whiteboard_add_card_dialog_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TypePalette.ColorTextPrimary,
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close),
                            tint = TypePalette.ColorTextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(TypePalette.ColorShapeHighlightLight)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = TypePalette.ColorTextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(TypePalette.ColorTextPrimary),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.search_objects_placeholder),
                                    color = AppColors.TextSecondary,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                // Type Filter Chips (All, Notes, Images, Links, Tasks)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(
                        "all" to stringResource(R.string.whiteboard_filter_all),
                        CanonicalTypeIds.NOTE to stringResource(R.string.whiteboard_filter_notes),
                        CanonicalTypeIds.IMAGE to stringResource(R.string.whiteboard_filter_images),
                        CanonicalTypeIds.WEB to stringResource(R.string.whiteboard_filter_links),
                        CanonicalTypeIds.TASK to stringResource(R.string.whiteboard_filter_tasks),
                    ).forEach { (typeKey, label) ->
                        val isChosen = selectedTypeFilter == typeKey
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isChosen) TypePalette.ColorCobalt else TypePalette.ColorShapeHighlightLight)
                                .clickable { selectedTypeFilter = typeKey }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isChosen) TypePalette.ColorTextPrimary else TypePalette.ColorTextSecondary,
                            )
                        }
                    }
                }

                // Object List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Quick creation option when typing or when list is empty
                    if (searchQuery.isNotBlank() && onCreateNewObject != null) {
                        item {
                            val targetType = when (selectedTypeFilter) {
                                CanonicalTypeIds.TASK -> CanonicalTypeIds.TASK
                                CanonicalTypeIds.WEB -> CanonicalTypeIds.WEB
                                CanonicalTypeIds.IMAGE -> CanonicalTypeIds.IMAGE
                                else -> CanonicalTypeIds.NOTE
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(TypePalette.ColorCobalt.copy(alpha = 0.16f))
                                    .border(1.dp, TypePalette.ColorCobalt.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        onCreateNewObject(searchQuery.trim(), targetType)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = TypePalette.ColorIce,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = stringResource(R.string.create_object_with_name, searchQuery.trim()),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TypePalette.ColorTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (filteredObjects.isEmpty() && (searchQuery.isBlank() || onCreateNewObject == null)) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_objects_found),
                                    color = AppColors.TextSecondary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    } else {
                        items(filteredObjects, key = { it.id }) { obj ->
                            val objType = allTypes.find { it.id == obj.typeId }
                            val canonicalType = obj.typeId
                            val isImage = canonicalType == CanonicalTypeIds.IMAGE
                            val imageAssetId = if (isImage) resolveImageAssetId(obj) else obj.cover?.assetId?.takeIf { !it.startsWith("cover_") }
                            val thumbnail = if (!imageAssetId.isNullOrBlank()) rememberDecodedThumbnail(imageAssetId, targetMaxSidePx = 256) else null
                            val isWeb = canonicalType == CanonicalTypeIds.WEB
                            val webUrl = if (isWeb) obj.weblinkUrl.orEmpty() else ""

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(TypePalette.ColorShapeHighlightLight.copy(alpha = 0.5f))
                                    .clickable { onObjectSelected(obj.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                if (thumbnail != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(TypePalette.ColorBgPrimary),
                                    ) {
                                        Image(
                                            bitmap = thumbnail.bitmap.asImageBitmap(),
                                            contentDescription = obj.plainTitle,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize().privacyImage(),
                                        )
                                    }
                                } else if (obj.icon?.emoji != null) {
                                    Text(text = obj.icon.emoji, fontSize = 20.sp)
                                } else if (objType != null) {
                                    TypeBadge(type = objType)
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = obj.plainTitle.ifBlank { stringResource(R.string.untitled_object) },
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TypePalette.ColorTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val subText = if (isWeb && webUrl.isNotBlank()) {
                                        webUrl.removePrefix("https://").removePrefix("http://")
                                    } else obj.plainSummary

                                    if (subText.isNotBlank()) {
                                        Text(
                                            text = subText,
                                            fontSize = 11.sp,
                                            color = if (isWeb) TypePalette.ColorIce else AppColors.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
