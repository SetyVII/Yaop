package org.yaop.ui.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.PkmObject
import org.yaop.core.model.HeadingLevel
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectType
import org.yaop.core.model.toPlainText
import org.yaop.ui.common.localizedName
import org.yaop.ui.theme.TypePalette
import org.yaop.ui.theme.AppColors
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Tablet Inspector Panel (Right Drawer).
 * Displays document properties, hierarchical outline, and backlinks.
 */
@Composable
fun TabletInspectorPanel(
    activeObject: PkmObject?,
    allObjects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onCloseInspector: () -> Unit,
    onNavigateToObject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val currentLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.getDefault()
    }

    val dateFormatter = remember(currentLocale) {
        runCatching {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(currentLocale)
        }.getOrNull()
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(AppColors.Surface)
            .border(width = 1.dp, color = AppColors.BorderSubtle)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = AppColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.tablet_inspector),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppColors.TextPrimary,
                )
            }
            IconButton(
                onClick = onCloseInspector,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.tablet_close_pane),
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        HorizontalDivider(color = AppColors.BorderSubtle)

        if (activeObject != null) {
            val objectType = allTypes.find { it.id == activeObject.typeId }

            // Section 1: Properties
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.tablet_properties_title),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextSecondary,
                )

                // Type Badge
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.SurfaceElevated)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = activeObject.icon?.emoji ?: objectType?.icon?.emoji ?: "📄", fontSize = 14.sp)
                    Text(
                        text = objectType?.localizedName() ?: activeObject.typeId,
                        fontSize = 13.sp,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Dates
                val createdFormatted = remember(activeObject.createdAt, dateFormatter) {
                    runCatching {
                        val instant = Instant.parse(activeObject.createdAt)
                        val zone = java.time.ZoneId.systemDefault()
                        dateFormatter?.format(instant.atZone(zone)) ?: activeObject.createdAt
                    }.getOrDefault(activeObject.createdAt)
                }
                Text(
                    text = "${stringResource(R.string.tab_created)}: $createdFormatted",
                    fontSize = 12.sp,
                    color = AppColors.TextSecondary,
                )
            }

            HorizontalDivider(color = AppColors.BorderSubtle)

            // Section 2: Document Outline (Headings H1-H4)
            val headings = remember(activeObject.blocks) {
                activeObject.blocks.filterIsInstance<Block.Heading>()
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.FormatListBulleted,
                        contentDescription = null,
                        tint = TypePalette.ColorIce,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.tablet_outline_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextSecondary,
                    )
                }

                if (headings.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tablet_outline_empty),
                        fontSize = 12.sp,
                        color = AppColors.TextTertiary,
                    )
                } else {
                    headings.forEach { heading ->
                        val levelIndent = when (heading.level) {
                            HeadingLevel.H1 -> 0.dp
                            HeadingLevel.H2 -> 12.dp
                            HeadingLevel.H3 -> 24.dp
                            HeadingLevel.H4 -> 36.dp
                        }
                        val headingText = heading.spans.toPlainText().ifBlank {
                            stringResource(R.string.placeholder_heading, heading.level.name)
                        }

                        Text(
                            text = headingText,
                            fontSize = 13.sp,
                            fontWeight = if (heading.level == HeadingLevel.H1) FontWeight.Bold else FontWeight.Normal,
                            color = AppColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = levelIndent)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { /* Jump to heading anchor */ }
                                .padding(vertical = 4.dp, horizontal = 6.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = AppColors.BorderSubtle)

            // Section 3: Backlinks (Incoming references)
            val backlinks = remember(activeObject.id, allObjects) {
                allObjects.filter { candidate ->
                    candidate.id != activeObject.id &&
                        candidate.blocks.any { block ->
                            when (block) {
                                is Block.ObjectEmbed -> block.targetObjectId == activeObject.id
                                is Block.Bookmark -> block.targetObjectId == activeObject.id
                                else -> {
                                    val spans = (block as? org.yaop.core.model.SpannedBlock)?.spans ?: emptyList()
                                    spans.any { it is InlineSpan.Mention && it.targetObjectId == activeObject.id }
                                }
                            }
                        }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = TypePalette.ColorTeal,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.tablet_backlinks_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextSecondary,
                    )
                }

                if (backlinks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tablet_backlinks_empty),
                        fontSize = 12.sp,
                        color = AppColors.TextTertiary,
                    )
                } else {
                    backlinks.forEach { linkObj ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.SurfaceElevated)
                                .clickable { onNavigateToObject(linkObj.id) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(text = linkObj.icon?.emoji ?: "📄", fontSize = 14.sp)
                            Text(
                                text = linkObj.plainTitle,
                                fontSize = 12.sp,
                                color = AppColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}
