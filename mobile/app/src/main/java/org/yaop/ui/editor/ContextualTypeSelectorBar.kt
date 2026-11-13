package org.yaop.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.CanonicalTypeIds
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.ObjectType
import org.yaop.ui.common.localizedName
import org.yaop.ui.theme.AppColors

/**
 * Contextual type selector bar displayed directly above the software keyboard & editing toolbar.
 * Implements the design specified in `design/Editor_Contextual_Type_Bar.svg`:
 * - Collapsible header: "Ocultar tipos ▾" / "Mostrar tipos ▴".
 * - Horizontal scroll of type pills (Note, Tasks, Bookmark, Project, Whiteboard, etc.).
 * - Allows instant conversion of the current object's type while editing.
 */
@Composable
fun ContextualTypeSelectorBar(
    currentTypeId: String,
    allTypes: List<ObjectType>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectObjectType: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val normalizedCurrent = currentTypeId

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.KeyboardBar)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (isExpanded) stringResource(R.string.hide_types) else stringResource(R.string.show_types),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(140)) + expandVertically(tween(140)),
            exit = fadeOut(tween(110)) + shrinkVertically(tween(110)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                allTypes.forEach { type ->
                    val canonicalId = type.id
                    val isSelected = type.id == currentTypeId || canonicalId == normalizedCurrent
                    val shape = RoundedCornerShape(10.dp)
                    val bg = if (isSelected) AppColors.AccentSubtle else AppColors.SurfaceElevated
                    val border = if (isSelected) AppColors.Accent else AppColors.BorderSubtle
                    val emoji = when (canonicalId) {
                        DefaultTypes.NOTE.id -> "📝"
                        DefaultTypes.TASK.id -> "✅"
                        DefaultTypes.DAILY.id -> "📅"
                        DefaultTypes.BOOK.id -> "📚"
                        DefaultTypes.PERSON.id -> "👤"
                        DefaultTypes.PROJECT.id -> "🔨"
                        DefaultTypes.WEBLINK.id -> "🔖"
                        CanonicalTypeIds.WHITEBOARD -> "🎨"
                        "image" -> "🖼️"
                        "audio" -> "🎙️"
                        "pdf" -> "📄"
                        else -> "📄"
                    }

                    Row(
                        modifier = Modifier
                            .clip(shape)
                            .background(bg, shape)
                            .border(1.dp, border, shape)
                            .clickable { onSelectObjectType(type.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = emoji, fontSize = 12.sp)
                        Text(
                            text = type.localizedName(),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) AppColors.Accent else AppColors.TextPrimary,
                        )
                    }
                }
            }
        }
    }
}
