package org.yaop.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.ObjectType
import org.yaop.ui.theme.AppColors

/**
 * Returns the localized display name of an [ObjectType] based on the active device locale.
 * Custom user-renamed types preserve their custom [ObjectType.name].
 */
@Composable
fun ObjectType.localizedName(): String {
    val canonical = id
    val defaultType = DefaultTypes.ALL.find { it.id == canonical }
    if (defaultType != null && !name.equals(defaultType.name, ignoreCase = true)) {
        return name
    }
    return when (canonical) {
        DefaultTypes.NOTE.id -> stringResource(R.string.object_type_note)
        DefaultTypes.DAILY.id -> stringResource(R.string.object_type_journal)
        DefaultTypes.BOOK.id -> stringResource(R.string.object_type_book)
        DefaultTypes.TASK.id -> stringResource(R.string.object_type_task)
        DefaultTypes.PERSON.id -> stringResource(R.string.object_type_person)
        DefaultTypes.PROJECT.id -> stringResource(R.string.object_type_project)
        DefaultTypes.WEBLINK.id -> stringResource(R.string.object_type_web)
        DefaultTypes.IMAGE.id -> stringResource(R.string.object_type_image)
        DefaultTypes.PDF.id -> stringResource(R.string.object_type_pdf)
        DefaultTypes.AUDIO.id -> stringResource(R.string.object_type_audio)
        DefaultTypes.COLLECTION.id -> stringResource(R.string.object_type_collection)
        DefaultTypes.TAG.id -> stringResource(R.string.object_type_tag)
        DefaultTypes.WHITEBOARD.id -> stringResource(R.string.object_type_whiteboard)
        else -> name
    }
}

/** Type tag pill: ghost pill, Mono caps, a dot in the type colour. */
@Composable
fun TypeBadge(
    type: ObjectType,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val dot = org.yaop.ui.theme.AppTypeColors.of(type.id)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x24FFFFFF), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(6.dp).background(dot, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = type.localizedName().uppercase(),
            style = org.yaop.ui.theme.AppLabelStyle,
            color = AppColors.TextPrimary.copy(alpha = 0.9f),
        )
    }
}

/** Mention pill: ice text 500 preceded by a 6 dp dot of the target type colour; no visible "@". */
@Composable
fun MentionChip(
    targetTitle: String,
    typeColorHex: String? = null,
    typeEmoji: String? = null,
    onClick: () -> Unit,
) {
    val dot = runCatching {
        if (typeColorHex != null) Color(typeColorHex.toColorInt()) else AppColors.Ice
    }.getOrDefault(AppColors.Ice)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(6.dp).background(dot, androidx.compose.foundation.shape.CircleShape),
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = targetTitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.Ice,
        )
    }
}

@Composable
fun TagChip(
    tagName: String,
    onClick: (() -> Unit)? = null,
) {
    val cleanName = tagName.removePrefix("#").trim()
    val tagBg = org.yaop.ui.editor.TagPalette.getPillBackground(cleanName)
    val tagBorder = org.yaop.ui.editor.TagPalette.getPillBorder(cleanName)
    val tagText = org.yaop.ui.editor.TagPalette.getTextColor(cleanName)
    val tagHash = org.yaop.ui.editor.TagPalette.getHashColor(cleanName)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(5.5.dp))
            .background(AppColors.Surface, RoundedCornerShape(5.5.dp))
            .background(tagBg, RoundedCornerShape(5.5.dp))
            .border(1.dp, tagBorder, RoundedCornerShape(5.5.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#",
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Normal,
            color = tagHash,
        )
        Spacer(modifier = Modifier.width(1.dp))
        Text(
            text = cleanName,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = tagText,
        )
    }
}
