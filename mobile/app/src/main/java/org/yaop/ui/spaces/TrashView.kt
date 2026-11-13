package org.yaop.ui.spaces

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.ObjectType
import org.yaop.core.repository.PkmRepository
import org.yaop.core.sync.SyncPolicy
import org.yaop.ui.common.localizedName
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.Instant

/** Filter id of the trash in the Spaces chips row; never a real type id. */
const val TRASH_FILTER_ID = "__trash__"

/** Trash chip at the end of the Spaces filter row (same shape as the type chips). */
@Composable
fun TrashFilterChip(count: Int, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                color = if (isSelected) AppColors.Danger.copy(alpha = 0.16f) else AppColors.Surface,
                shape = RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = if (isSelected) AppColors.Danger.copy(alpha = 0.6f) else AppColors.BorderSubtle,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.trash_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary,
            )
            if (count > 0) {
                Text(
                    text = "$count",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) AppColors.Danger else AppColors.TextTertiary,
                )
            }
        }
    }
}

/**
 * Trash: objects sent to the trash, newest first, with how long until the 30-day purge
 * (spec §3.4). Restoring travels to the other devices (spec/merge.md §2b). There is no
 * "delete forever": the purge removes them once their tombstone has synced, like on desktop.
 */
@Composable
fun TrashView(
    trashed: List<PkmObject>,
    allTypes: List<ObjectType>,
    searchQuery: String,
    repository: PkmRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val now = remember(trashed) { Instant.now() }
    val visible = remember(trashed, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) trashed else trashed.filter { it.plainTitle.contains(q, ignoreCase = true) }
    }
    val restoredMsg = stringResource(R.string.trash_restored)

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.trash_hint),
            fontSize = 11.sp,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(if (searchQuery.isBlank()) R.string.trash_empty else R.string.no_matches_found),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.TextSecondary,
                )
            }
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            items(visible, key = { it.id }) { obj ->
                val type = allTypes.find {
                    it.id == obj.typeId
                }
                TrashRow(
                    obj = obj,
                    type = type,
                    daysLeft = SyncPolicy.trashDaysLeft(obj.trashedAt, now),
                    onRestore = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            repository.restoreFromTrash(obj.id)
                            Toast.makeText(context, restoredMsg, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TrashRow(obj: PkmObject, type: ObjectType?, daysLeft: Int?, onRestore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = obj.icon?.emoji ?: type?.icon?.emoji ?: "📄", fontSize = 18.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = obj.plainTitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            val typeName = type?.localizedName().orEmpty()
            val left = daysLeft?.let { pluralStringResource(R.plurals.trash_days_left, it, it) }
            Text(
                text = listOfNotNull(typeName.ifBlank { null }, left).joinToString(" · ").uppercase(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (daysLeft != null && daysLeft <= 3) AppColors.Danger else AppColors.TextTertiary,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.BorderHover, RoundedCornerShape(8.dp))
                .clickable(onClick = onRestore)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.trash_restore),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Ice,
            )
        }
    }
}
