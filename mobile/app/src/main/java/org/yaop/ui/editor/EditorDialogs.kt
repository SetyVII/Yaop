package org.yaop.ui.editor

import org.yaop.ui.common.privacyImage
import org.yaop.ui.common.DesignModalBottomSheet
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.Block
import org.yaop.core.model.BlockTypeKeys
import org.yaop.core.model.DefaultTypes
import org.yaop.core.model.PkmObject
import org.yaop.core.model.InlineSpan
import org.yaop.core.model.ObjectType
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toPlainText
import org.yaop.ui.common.TypeBadge
import org.yaop.ui.common.extractAudioDurationSeconds
import org.yaop.ui.common.extractImageMime
import org.yaop.ui.common.extractImageSize
import org.yaop.ui.common.extractImageTags
import org.yaop.ui.common.formatFileSize
import org.yaop.ui.common.formatMimeShort
import org.yaop.ui.common.rememberDecodedThumbnail
import org.yaop.ui.common.resolveAudioAssetId
import org.yaop.ui.common.resolveImageAssetId
import org.yaop.ui.common.resolvePdfAssetId
import org.yaop.ui.spaces.CreateObjectDialog
import org.yaop.ui.theme.AppColors
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

data class SlashMenuItem(
    val id: String,
    val titleRes: Int,
    val icon: ImageVector,
)

private val SLASH_MENU_ITEMS = listOf(
    SlashMenuItem(BlockTypeKeys.PARAGRAPH, R.string.block_type_paragraph, Icons.AutoMirrored.Filled.Notes),
    SlashMenuItem(BlockTypeKeys.H1, R.string.block_type_h1, Icons.Default.Title),
    SlashMenuItem(BlockTypeKeys.H2, R.string.block_type_h2, Icons.Default.Title),
    SlashMenuItem(BlockTypeKeys.H3, R.string.block_type_h3, Icons.Default.Title),
    SlashMenuItem(BlockTypeKeys.H4, R.string.block_type_h4, Icons.Default.Title),
    SlashMenuItem(BlockTypeKeys.TODO, R.string.block_type_todo, Icons.Default.CheckCircle),
    SlashMenuItem(BlockTypeKeys.BULLET, R.string.block_type_bullet, Icons.AutoMirrored.Filled.FormatListBulleted),
    SlashMenuItem(BlockTypeKeys.NUMBERED, R.string.block_type_numbered, Icons.Default.FormatListNumbered),
    SlashMenuItem(BlockTypeKeys.ALPHABETIC, R.string.block_type_alphabetic, Icons.Default.FormatListNumbered),
    SlashMenuItem(BlockTypeKeys.ROMAN, R.string.block_type_roman, Icons.Default.FormatListNumbered),
    SlashMenuItem(BlockTypeKeys.QUOTE, R.string.block_type_quote, Icons.Default.FormatQuote),
    SlashMenuItem(BlockTypeKeys.CALLOUT, R.string.block_type_callout, Icons.Default.Lightbulb),
    SlashMenuItem(BlockTypeKeys.GROUP, R.string.block_type_group, Icons.AutoMirrored.Filled.Notes),
    SlashMenuItem(BlockTypeKeys.CODE, R.string.block_type_code, Icons.Default.Code),
    SlashMenuItem(BlockTypeKeys.MATH, R.string.block_type_math, Icons.Default.Functions),
    SlashMenuItem(BlockTypeKeys.TABLE, R.string.block_type_table, Icons.Default.TableChart),
    SlashMenuItem(BlockTypeKeys.DIVIDER, R.string.block_type_divider, Icons.Default.HorizontalRule),
    SlashMenuItem(BlockTypeKeys.TOGGLE, R.string.block_type_toggle, Icons.AutoMirrored.Filled.KeyboardArrowRight),
    SlashMenuItem(BlockTypeKeys.BOOKMARK, R.string.block_type_bookmark, Icons.Default.BookmarkBorder),
    SlashMenuItem(BlockTypeKeys.IMAGE, R.string.block_type_image, Icons.Default.Image),
    SlashMenuItem(BlockTypeKeys.AUDIO, R.string.block_type_audio, Icons.Default.Mic),
    SlashMenuItem(BlockTypeKeys.PDF, R.string.block_type_pdf, Icons.Default.PictureAsPdf),
    SlashMenuItem(BlockTypeKeys.CANVAS, R.string.block_type_canvas, Icons.Default.Palette),
)

/**
 * Single universal ModalBottomSheet container for editor menus and pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalEditorBottomSheet(
    onDismiss: () -> Unit,
    searchQuery: String? = null,
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    headerTitle: String? = null,
    headerActionLabel: String? = null,
    onHeaderActionClick: (() -> Unit)? = null,
    badgeLabel: String? = null,
    badgeIcon: ImageVector? = null,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)

    DesignModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(AppColors.TextTertiary, RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(bottom = 24.dp),
        ) {
            if (searchQuery != null && onSearchQueryChange != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.Surface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(AppColors.Accent),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty() && searchPlaceholder.isNotEmpty()) {
                                Text(
                                    text = searchPlaceholder,
                                    color = AppColors.TextTertiary,
                                    fontSize = 15.sp,
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (headerTitle != null || headerActionLabel != null || badgeLabel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        headerActionLabel != null && onHeaderActionClick != null -> {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onHeaderActionClick)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = AppColors.TextPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = headerActionLabel,
                                    color = AppColors.TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        headerTitle != null -> {
                            Text(
                                text = headerTitle,
                                color = AppColors.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        else -> Spacer(Modifier.width(1.dp))
                    }

                    if (badgeLabel != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AppColors.Surface, RoundedCornerShape(8.dp))
                                .border(BorderStroke(1.dp, AppColors.BorderHover), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (badgeIcon != null) {
                                Icon(
                                    badgeIcon,
                                    contentDescription = null,
                                    tint = AppColors.TextSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = badgeLabel,
                                color = AppColors.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            content()
        }
    }
}

/**
 * Parsed representation of a `@tipo/nombre` or `@nombre` mention query.
 */
internal data class ParsedMentionQuery(
    val rawQuery: String,
    val hasTypeSlash: Boolean,
    val resolvedType: ObjectType?,
    val typeToken: String,
    val nameQuery: String,
)

internal fun String.stripMentionAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase(Locale.ROOT)

private fun parseCsvAliases(raw: String): List<String> =
    raw.split(',')
        .map { it.trim().stripMentionAccents() }
        .filter { it.isNotEmpty() }

/**
 * Loads localized `@tipo/` aliases from `R.string.mention_aliases_*` reactively according to the
 * active device locale. Adding a new language only requires translating `mention_aliases_*` in `strings.xml`.
 */
@Composable
internal fun rememberLocalizedTypeAliases(): Map<String, List<String>> {
    val img = stringResource(R.string.mention_aliases_image)
    val aud = stringResource(R.string.mention_aliases_audio)
    val pdf = stringResource(R.string.mention_aliases_pdf)
    val note = stringResource(R.string.mention_aliases_note)
    val journal = stringResource(R.string.mention_aliases_journal)
    val book = stringResource(R.string.mention_aliases_book)
    val task = stringResource(R.string.mention_aliases_task)
    val person = stringResource(R.string.mention_aliases_person)
    val project = stringResource(R.string.mention_aliases_project)
    val web = stringResource(R.string.mention_aliases_web)
    val collection = stringResource(R.string.mention_aliases_collection)
    val tag = stringResource(R.string.mention_aliases_tag)

    return remember(img, aud, pdf, note, journal, book, task, person, project, web, collection, tag) {
        mapOf(
            "image" to parseCsvAliases(img),
            "audio" to parseCsvAliases(aud),
            "pdf" to parseCsvAliases(pdf),
            "note" to parseCsvAliases(note),
            "journal" to parseCsvAliases(journal),
            "book" to parseCsvAliases(book),
            "task" to parseCsvAliases(task),
            "person" to parseCsvAliases(person),
            "project" to parseCsvAliases(project),
            "web" to parseCsvAliases(web),
            "collection" to parseCsvAliases(collection),
            "tag" to parseCsvAliases(tag),
        )
    }
}

/**
 * How a type is written in `@tipo/nombre`: lowercase, no accents, spaces and `_` become `-`.
 * "Web Links" → `web-links`, "Modelos de IA" → `modelos-de-ia`.
 */
internal fun mentionTypeSlug(name: String): String =
    name.stripMentionAccents().trim().replace(Regex("[\\s_-]+"), "-").trim('-')

/**
 * Key used to compare a typed type token with a type: the slug without separators, so
 * `web-links`, `weblinks`, `web links` and `web_links` all match "Web Links".
 */
internal fun mentionTypeKey(name: String): String = mentionTypeSlug(name).replace("-", "")

/**
 * Returns the primary localized keyword for `@tipo/` autocomplete chips (first alias in `strings.xml`
 * for the active locale, falling back to the [mentionTypeSlug] of `type.name`).
 */
internal fun preferredTypeMentionKeyword(
    type: ObjectType,
    localizedAliases: Map<String, List<String>>,
): String {
    val canon = type.id
    return localizedAliases[canon]?.firstOrNull()
        ?: mentionTypeSlug(type.name).ifEmpty { type.id }
}

/**
 * Parses a raw `@...` mention query, resolving `@tipo/nombre` prefixes using the active locale's
 * `mention_aliases_*` resources plus each [ObjectType]'s canonical `id` and `name`.
 */
internal fun parseMentionQuery(
    rawQuery: String,
    allTypes: List<ObjectType>,
    localizedAliases: Map<String, List<String>> = emptyMap(),
): ParsedMentionQuery {
    val clean = rawQuery.trim().removePrefix("@")
    if (!clean.contains('/')) {
        return ParsedMentionQuery(
            rawQuery = clean,
            hasTypeSlash = false,
            resolvedType = null,
            typeToken = "",
            nameQuery = clean,
        )
    }
    val typePart = clean.substringBefore('/').trim()
    val namePart = clean.substringAfter('/').trim()
    val normType = mentionTypeKey(typePart)

    val resolved = if (normType.isBlank()) {
        null
    } else {
        // 1. Exact match by id, name, or localized alias from strings.xml
        allTypes.firstOrNull { t ->
            mentionTypeKey(t.id) == normType ||
                mentionTypeKey(t.name) == normType ||
                localizedAliases[t.id]?.any { mentionTypeKey(it) == normType } == true
        } ?: if (normType.length >= 2) {
            // 2. Prefix match (e.g. @im/ or @aud/ or @lib/)
            allTypes.firstOrNull { t ->
                mentionTypeKey(t.id).startsWith(normType) ||
                    mentionTypeKey(t.name).startsWith(normType) ||
                    localizedAliases[t.id]?.any { mentionTypeKey(it).startsWith(normType) } == true
            }
        } else {
            null
        }
    }

    return ParsedMentionQuery(
        rawQuery = clean,
        hasTypeSlash = true,
        resolvedType = resolved,
        typeToken = typePart,
        nameQuery = namePart,
    )
}

/**
 * Returns object types (built-in and user-created) whose name, id, localized alias, or any word of
 * the name starts with [prefix] (before typing `/`). "mode" matches "Modelos de IA" and
 * "Modelos de referencia"; "refe" matches the second one too.
 * Types used most recently come first ([recentTypeIds], most recent first), then the built-in
 * priority order.
 */
internal fun matchingTypesForMentionPrefix(
    prefix: String,
    allTypes: List<ObjectType>,
    localizedAliases: Map<String, List<String>> = emptyMap(),
    recentTypeIds: List<String> = emptyList(),
): List<ObjectType> {
    val key = mentionTypeKey(prefix.trim().removePrefix("@"))
    val priorityIds = listOf("image", "audio", "pdf", "note", "task", "book", "person", "project", "web")
    fun rank(ids: List<String>, id: String) = ids.indexOf(id).let { if (it >= 0) it else Int.MAX_VALUE }
    val ordered = allTypes.sortedWith(
        compareBy<ObjectType>({ rank(recentTypeIds, it.id) }, { rank(priorityIds, it.id) }),
    )
    if (key.isEmpty()) return ordered
    return ordered.filter { t ->
        mentionTypeKey(t.id).startsWith(key) ||
            mentionTypeKey(t.name).startsWith(key) ||
            mentionTypeSlug(t.name).split('-').any { word -> key.length >= 2 && word.startsWith(key) } ||
            localizedAliases[t.id]?.any { mentionTypeKey(it).startsWith(key) } == true
    }
}

/**
 * Resolves human-readable tag names attached to [obj] (both direct `tags` property and inline block tags).
 */
internal fun resolveObjectTagNames(
    obj: PkmObject,
    allTags: List<PkmObject>,
): List<String> {
    val rawIds = extractImageTags(obj)
    val direct = rawIds.mapNotNull { idOrName ->
        val tagObj = allTags.find {
            it.id == idOrName || it.plainTitle.equals(idOrName, ignoreCase = true)
        }
        (tagObj?.plainTitle ?: idOrName).trim().removePrefix("#").takeIf { it.isNotBlank() }
    }
    val inlineTags = obj.blocks.flatMap { block ->
        BlockOperations.extractSpans(block)
            .filterIsInstance<InlineSpan.Tag>()
            .mapNotNull { it.tagName.trim().removePrefix("#").takeIf { n -> n.isNotBlank() } }
    }
    return (direct + inlineTags).distinctBy { it.lowercase(Locale.ROOT) }
}

/**
 * Matches [obj] against a mention search query across:
 * 1. Object title & aliases
 * 2. Underlying file name (`file_name` property or PDF block filename)
 * 3. Object tags (`#tags`) — essential when searching thousands of untitled images/audios
 */
internal fun matchesObjectMentionQuery(
    obj: PkmObject,
    query: String,
    allTags: List<PkmObject>,
): Boolean {
    val cleanQ = query.trim().removePrefix("#").stripMentionAccents()
    if (cleanQ.isBlank()) return true

    if (obj.plainTitle.stripMentionAccents().contains(cleanQ)) return true
    if (obj.aliases.any { it.stripMentionAccents().contains(cleanQ) }) return true

    val propFileName = (obj.property("file_name") as? PropertyValue.RichText)?.spans?.toPlainText().orEmpty()
    if (propFileName.isNotBlank() && propFileName.stripMentionAccents().contains(cleanQ)) return true

    val pdfFileName = obj.blocks.filterIsInstance<Block.Pdf>().firstOrNull()?.fileName.orEmpty()
    if (pdfFileName.isNotBlank() && pdfFileName.stripMentionAccents().contains(cleanQ)) return true

    val tagNames = resolveObjectTagNames(obj, allTags)
    if (tagNames.any { it.stripMentionAccents().contains(cleanQ) }) return true

    return false
}

/**
 * Pattern E: Half-height Universal Object & Link Selector Sheet (`skipPartiallyExpanded = false`).
 * Supports `@tipo/nombre` syntax, multi-field search (title, filename, `#tags`),
 * a 2-column visual gallery grid with full-screen zoom for Images, inline Play/Pause preview for Audios,
 * and first-page thumbnails for PDFs.
 */
@Composable
fun UniversalObjectLinkSelectorSheet(
    objects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onDismiss: () -> Unit,
    onSelectObject: (PkmObject) -> Unit,
    onSelectUrl: ((String) -> Unit)? = null,
    initialQuery: String = "",
    initialTypeId: String? = null,
    isLinkMode: Boolean = false,
    onCreateNew: ((typeId: String, title: String, emoji: String) -> Unit)? = null,
) {
    val localizedAliases = rememberLocalizedTypeAliases()
    val initialParsed = remember(initialQuery, allTypes, localizedAliases) {
        parseMentionQuery(initialQuery, allTypes, localizedAliases)
    }
    var query by remember {
        mutableStateOf(
            if (initialParsed.hasTypeSlash && initialParsed.resolvedType != null) {
                initialParsed.nameQuery
            } else {
                initialQuery
            },
        )
    }
    var selectedFilterId by remember {
        mutableStateOf<String?>(initialTypeId ?: initialParsed.resolvedType?.id)
    } // null = All, "__links__" = Web links, else typeId
    var showCreateDialog by remember { mutableStateOf(false) }
    var zoomPreviewImageObj by remember { mutableStateOf<PkmObject?>(null) }

    val untitledLabel = stringResource(R.string.untitled_object)
    val filterAllLabel = stringResource(R.string.selector_filter_all)
    val filterLinksLabel = stringResource(R.string.selector_filter_links)

    val allTags = remember(objects) {
        objects.filter { it.typeId == DefaultTypes.TAG.id }
    }

    val parsedLive = remember(query, allTypes, localizedAliases) {
        parseMentionQuery(query, allTypes, localizedAliases)
    }
    val effectiveFilterId = if (parsedLive.hasTypeSlash && parsedLive.resolvedType != null) {
        parsedLive.resolvedType.id
    } else {
        selectedFilterId
    }
    val effectiveNameQuery = if (parsedLive.hasTypeSlash && parsedLive.resolvedType != null) {
        parsedLive.nameQuery
    } else {
        parsedLive.rawQuery
    }

    // Full-screen zoom dialog when inspecting an image from the selector before inserting
    zoomPreviewImageObj?.let { imgObj ->
        val assetId = resolveImageAssetId(imgObj)
        if (!assetId.isNullOrBlank()) {
            FullScreenImagePreviewDialog(
                assetId = assetId,
                caption = imgObj.plainTitle.ifBlank { untitledLabel },
                onDismiss = { zoomPreviewImageObj = null },
            )
        } else {
            zoomPreviewImageObj = null
        }
    }

    if (showCreateDialog) {
        CreateObjectDialog(
            types = allTypes,
            initialTypeId = effectiveFilterId?.takeIf { it != "__links__" } ?: DefaultTypes.NOTE.id,
            initialTitle = effectiveNameQuery,
            onDismiss = { showCreateDialog = false },
            onCreate = { typeId, title, emoji ->
                showCreateDialog = false
                onCreateNew?.invoke(typeId, title, emoji)
            },
        )
    }

    val normalizedUrlCandidate = remember(effectiveNameQuery) {
        val candidate = effectiveNameQuery.trim()
        when {
            candidate.startsWith("https://", ignoreCase = true) ||
                candidate.startsWith("http://", ignoreCase = true) -> candidate
            candidate.contains('.') && !candidate.contains(' ') && candidate.length >= 4 -> "https://$candidate"
            else -> null
        }
    }

    // Collect web bookmark entries from objects (either Bookmark objects or objects containing Block.Bookmark)
    val linkEntries = remember(objects) {
        buildList {
            objects.forEach { obj ->
                val urlProp = (obj.properties["url"] ?: obj.properties["prop_url"])
                val propUrlText = when (urlProp) {
                    is PropertyValue.RichText -> urlProp.spans.toPlainText()
                    is PropertyValue.Url -> urlProp.url
                    else -> null
                }
                if (!propUrlText.isNullOrBlank()) {
                    add(Triple(obj.plainTitle.ifBlank { propUrlText }, propUrlText, obj))
                }
                obj.blocks.filterIsInstance<Block.Bookmark>().forEach { bm ->
                    if (bm.url.isNotBlank()) {
                        add(Triple(bm.title.ifBlank { bm.url }, bm.url, obj))
                    }
                }
            }
        }.distinctBy { it.second }
    }

    val filteredObjects = remember(effectiveNameQuery, objects, effectiveFilterId, allTags) {
        if (effectiveFilterId == "__links__") {
            emptyList()
        } else {
            val targetCanon = effectiveFilterId?.let { it }
            objects
                .distinctBy { it.id }
                .filter { obj ->
                    val objCanon = obj.typeId
                    (targetCanon == null || objCanon == targetCanon || obj.typeId == effectiveFilterId) &&
                        matchesObjectMentionQuery(obj, effectiveNameQuery, allTags)
                }
                .sortedByDescending { it.updatedAt }
                .take(60)
        }
    }

    val filteredLinks = remember(effectiveNameQuery, linkEntries, effectiveFilterId) {
        if (effectiveFilterId != null && effectiveFilterId != "__links__") {
            emptyList()
        } else {
            linkEntries.filter { (title, url, _) ->
                effectiveNameQuery.isBlank() ||
                    title.contains(effectiveNameQuery, ignoreCase = true) ||
                    url.contains(effectiveNameQuery, ignoreCase = true)
            }.take(25)
        }
    }

    val activeTypeObj = remember(effectiveFilterId, allTypes) {
        allTypes.find { it.id == effectiveFilterId || it.id == effectiveFilterId }
    }
    val isImageGalleryMode = remember(effectiveFilterId) {
        effectiveFilterId != null && effectiveFilterId == DefaultTypes.IMAGE.id
    }

    UniversalEditorBottomSheet(
        onDismiss = onDismiss,
        searchQuery = query,
        onSearchQueryChange = { next ->
            val parsedNext = parseMentionQuery(next, allTypes, localizedAliases)
            if (parsedNext.hasTypeSlash && parsedNext.resolvedType != null) {
                selectedFilterId = parsedNext.resolvedType.id
                query = parsedNext.nameQuery
            } else {
                query = next
            }
        },
        searchPlaceholder = when {
            isLinkMode -> stringResource(R.string.placeholder_url_input)
            activeTypeObj != null -> stringResource(R.string.selector_search_by_type_placeholder, activeTypeObj.name)
            else -> stringResource(R.string.search_objects_placeholder)
        },
        headerTitle = stringResource(R.string.selector_links_and_objects_title),
        headerActionLabel = if (onCreateNew != null) stringResource(R.string.new_object_action) else null,
        onHeaderActionClick = if (onCreateNew != null) ({ showCreateDialog = true }) else null,
        badgeLabel = when {
            isLinkMode -> stringResource(R.string.action_link)
            activeTypeObj != null -> activeTypeObj.name
            else -> stringResource(R.string.objects_badge)
        },
        badgeIcon = if (isLinkMode) Icons.Default.Link else Icons.Default.AlternateEmail,
        skipPartiallyExpanded = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Filter chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolbarChip(
                    label = filterAllLabel,
                    isActive = effectiveFilterId == null,
                    onClick = {
                        selectedFilterId = null
                        if (parsedLive.hasTypeSlash) query = parsedLive.nameQuery
                    },
                )
                if (onSelectUrl != null || linkEntries.isNotEmpty()) {
                    ToolbarChip(
                        label = filterLinksLabel,
                        leadingEmoji = "🌐",
                        isActive = effectiveFilterId == "__links__",
                        onClick = {
                            selectedFilterId = "__links__"
                            if (parsedLive.hasTypeSlash) query = parsedLive.nameQuery
                        },
                    )
                }
                allTypes.forEach { type ->
                    val isCurrentType = effectiveFilterId == type.id ||
                        (effectiveFilterId != null && effectiveFilterId == type.id)
                    ToolbarChip(
                        label = type.name,
                        leadingEmoji = type.icon.emoji ?: "📄",
                        isActive = isCurrentType,
                        onClick = {
                            selectedFilterId = if (isCurrentType) null else type.id
                            if (parsedLive.hasTypeSlash) query = parsedLive.nameQuery
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Direct URL action row when query looks like a URL
                if (normalizedUrlCandidate != null && onSelectUrl != null) {
                    item(key = "direct-url-option") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AppColors.Accent.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                                .border(1.dp, AppColors.Accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                .clickable { onSelectUrl(normalizedUrlCandidate) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = AppColors.Accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.action_use_url, normalizedUrlCandidate),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.Accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Saved Web Links section
                if (filteredLinks.isNotEmpty() && onSelectUrl != null) {
                    items(filteredLinks, key = { "link-" + it.second }) { (title, url, sourceObj) ->
                        val domain = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { url }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSelectUrl(url) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AppColors.Surface,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = AppColors.Accent,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = AppColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$domain · ${sourceObj.plainTitle.ifBlank { untitledLabel }}",
                                    fontSize = 11.sp,
                                    color = AppColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        HorizontalDivider(color = AppColors.BorderSubtle)
                    }
                }

                // Objects section
                if (filteredObjects.isEmpty() && filteredLinks.isEmpty() && normalizedUrlCandidate == null) {
                    item {
                        Text(
                            text = stringResource(R.string.no_objects_found),
                            color = AppColors.TextTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                } else if (isImageGalleryMode) {
                    // 2-column Visual Thumbnail Grid when filtering by Image
                    val rows = filteredObjects.chunked(2)
                    items(rows, key = { pair -> "img-row-" + pair.first().id }) { pair ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            pair.forEach { imgObj ->
                                SelectorImageGridCard(
                                    obj = imgObj,
                                    allTags = allTags,
                                    untitledLabel = untitledLabel,
                                    onSelect = { onSelectObject(imgObj) },
                                    onZoom = { zoomPreviewImageObj = imgObj },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    items(filteredObjects, key = { "obj-" + it.id }) { obj ->
                        RichSelectorObjectRow(
                            obj = obj,
                            allTypes = allTypes,
                            allTags = allTags,
                            untitledLabel = untitledLabel,
                            onSelect = onSelectObject,
                            onZoomImage = { zoomPreviewImageObj = it },
                        )
                        HorizontalDivider(color = AppColors.BorderSubtle)
                    }
                }
            }
        }
    }
}

/**
 * Visual grid card for selecting an Image object in [UniversalObjectLinkSelectorSheet].
 * Displays the decoded image thumbnail, format/size badge, tags, and a quick zoom button.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SelectorImageGridCard(
    obj: PkmObject,
    allTags: List<PkmObject>,
    untitledLabel: String,
    onSelect: () -> Unit,
    onZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val assetId = resolveImageAssetId(obj)
    val thumbnail = if (!assetId.isNullOrBlank()) {
        rememberDecodedThumbnail(assetId, targetMaxSidePx = 384)
    } else {
        null
    }
    val tagNames = remember(obj, allTags) { resolveObjectTagNames(obj, allTags) }
    val fileSize = formatFileSize(extractImageSize(obj))
    val mimeShort = formatMimeShort(extractImageMime(obj))
    val is4K = (thumbnail?.originalWidth ?: 0) >= 3800 || (thumbnail?.originalHeight ?: 0) >= 2100

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AppColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onZoom,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(AppColors.SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.bitmap.asImageBitmap(),
                    contentDescription = obj.plainTitle.ifBlank { untitledLabel },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().privacyImage(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(30.dp),
                )
            }

            // Top badges + Zoom button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val badgeText = when {
                    is4K -> stringResource(R.string.image_format_resolution_4k)
                    !mimeShort.isNullOrBlank() -> mimeShort
                    else -> null
                }
                if (badgeText != null) {
                    Text(
                        text = badgeText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (is4K) AppColors.Warning else Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.68f))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (thumbnail != null) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            .clickable(onClick = onZoom),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = stringResource(R.string.selector_zoom_image),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            Text(
                text = obj.plainTitle.ifBlank { untitledLabel },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val metaText = buildList {
                if (thumbnail != null) add("${thumbnail.originalWidth}×${thumbnail.originalHeight}")
                if (!fileSize.isNullOrBlank()) add(fileSize)
            }.joinToString(" · ")
            if (metaText.isNotEmpty()) {
                Text(
                    text = metaText,
                    fontSize = 10.sp,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tagNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(3.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    tagNames.take(2).forEach { tag ->
                        Text(
                            text = "#$tag",
                            fontSize = 10.sp,
                            color = AppColors.Accent,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun RichSelectorObjectRow(
    obj: PkmObject,
    allTypes: List<ObjectType>,
    allTags: List<PkmObject>,
    untitledLabel: String,
    onSelect: (PkmObject) -> Unit,
    onZoomImage: ((PkmObject) -> Unit)? = null,
) {
    val canonicalType = obj.typeId
    val objType = allTypes.find { it.id == obj.typeId || it.id == canonicalType }
    val tagNames = remember(obj, allTags) { resolveObjectTagNames(obj, allTags) }

    // 1. Specialized Audio Row with Inline Play/Pause Preview Player
    if (canonicalType == DefaultTypes.AUDIO.id) {
        val audioAssetId = resolveAudioAssetId(obj)
        val durationSec = extractAudioDurationSeconds(obj).coerceAtLeast(1)
        val fileSize = formatFileSize(extractImageSize(obj))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(12.dp))
                .clickable { onSelect(obj) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AppColors.SurfaceElevated,
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(text = "🎙️", fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = obj.plainTitle.ifBlank { untitledLabel },
                        color = AppColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaParts = buildList {
                        add(formatDurationSeconds(durationSec))
                        if (!fileSize.isNullOrBlank()) add(fileSize)
                        if (tagNames.isNotEmpty()) add(tagNames.take(2).joinToString(" ") { "#$it" })
                    }
                    Text(
                        text = metaParts.joinToString(" · "),
                        color = AppColors.TextTertiary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (objType != null) {
                    Spacer(Modifier.width(8.dp))
                    TypeBadge(type = objType)
                }
            }
            if (!audioAssetId.isNullOrBlank()) {
                AudioPlayerRow(
                    assetId = audioAssetId,
                    fallbackDurationSeconds = durationSec,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }

    // 2. Specialized Image Row with Decoded Thumbnail + Zoom Action
    if (canonicalType == DefaultTypes.IMAGE.id) {
        val imageAssetId = resolveImageAssetId(obj)
        val thumbnail = if (!imageAssetId.isNullOrBlank()) {
            rememberDecodedThumbnail(imageAssetId, targetMaxSidePx = 192)
        } else {
            null
        }
        val fileSize = formatFileSize(extractImageSize(obj))
        val mimeShort = formatMimeShort(extractImageMime(obj))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .combinedClickable(
                    onClick = { onSelect(obj) },
                    onLongClick = { onZoomImage?.invoke(obj) },
                )
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.Surface, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(8.dp))
                    .then(
                        if (thumbnail != null && onZoomImage != null) {
                            Modifier.clickable { onZoomImage(obj) }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.bitmap.asImageBitmap(),
                        contentDescription = obj.plainTitle.ifBlank { untitledLabel },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    Text(text = "🖼️", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.plainTitle.ifBlank { untitledLabel },
                    color = AppColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val metaParts = buildList {
                    if (thumbnail != null) add("${thumbnail.originalWidth}×${thumbnail.originalHeight}")
                    if (!mimeShort.isNullOrBlank()) add(mimeShort)
                    if (!fileSize.isNullOrBlank()) add(fileSize)
                    if (tagNames.isNotEmpty()) add(tagNames.take(3).joinToString(" ") { "#$it" })
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        color = AppColors.TextTertiary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (objType != null) {
                Spacer(Modifier.width(8.dp))
                TypeBadge(type = objType)
            }
        }
        return
    }

    // 3. Specialized PDF Row with First-Page Thumbnail + Page Count
    if (canonicalType == DefaultTypes.PDF.id) {
        val pdfAssetId = resolvePdfAssetId(obj)
        val pdfPreview = if (!pdfAssetId.isNullOrBlank()) rememberPdfFirstPage(pdfAssetId) else null
        val fileSize = formatFileSize(extractImageSize(obj))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onSelect(obj) }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 52.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(AppColors.Surface, RoundedCornerShape(7.dp))
                    .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (pdfPreview != null) {
                    Image(
                        bitmap = pdfPreview.first.asImageBitmap(),
                        contentDescription = obj.plainTitle.ifBlank { untitledLabel },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    Text(text = "📄", fontSize = 18.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.plainTitle.ifBlank { untitledLabel },
                    color = AppColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pagesLabel = pdfPreview?.second?.let { stringResource(R.string.media_pdf_pages, it) }
                val metaParts = buildList {
                    if (!pagesLabel.isNullOrBlank()) add(pagesLabel)
                    if (!fileSize.isNullOrBlank()) add(fileSize)
                    if (tagNames.isNotEmpty()) add(tagNames.take(2).joinToString(" ") { "#$it" })
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        color = AppColors.TextTertiary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (objType != null) {
                Spacer(Modifier.width(8.dp))
                TypeBadge(type = objType)
            }
        }
        return
    }

    // 4. General / Text / WebLink / Book / Task Objects (with cover thumbnail if available)
    val coverAssetId = obj.cover?.assetId
    val coverThumb = if (!coverAssetId.isNullOrBlank()) {
        rememberDecodedThumbnail(coverAssetId, targetMaxSidePx = 128)
    } else {
        null
    }
    val snippet = remember(obj, tagNames) {
        val textSnippet = obj.plainSummary.takeIf { it.isNotBlank() }
            ?: obj.blocks.firstNotNullOfOrNull { b ->
                BlockOperations.extractPlainText(b).trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        val tagsSuffix = if (tagNames.isNotEmpty()) {
            tagNames.take(2).joinToString(" ") { "#$it" }
        } else {
            ""
        }
        listOf(textSnippet, tagsSuffix).filter { it.isNotBlank() }.joinToString(" · ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onSelect(obj) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.Surface,
            modifier = Modifier.size(38.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (coverThumb != null) {
                    Image(
                        bitmap = coverThumb.bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().privacyImage(),
                    )
                } else {
                    val emoji = obj.icon?.emoji ?: objType?.icon?.emoji
                    if (!emoji.isNullOrBlank()) {
                        Text(text = emoji, fontSize = 18.sp)
                    } else {
                        Icon(
                            Icons.Default.AlternateEmail,
                            contentDescription = null,
                            tint = AppColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = obj.plainTitle.ifBlank { untitledLabel },
                color = AppColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snippet.isNotBlank()) {
                Text(
                    text = snippet,
                    color = AppColors.TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (objType != null) {
                Text(
                    text = objType.name,
                    color = AppColors.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        }
        if (objType != null) {
            Spacer(Modifier.width(8.dp))
            TypeBadge(type = objType)
        }
    }
}

/**
 * Pattern F: Data model for the compact floating preview popup when tapping an inline element
 * (`@mention`, `#tag`, `linkUrl`, or `Block.Bookmark`).
 */
sealed interface InlineElementPreviewData {
    data class ObjectMention(
        val obj: PkmObject?,
        val type: ObjectType?,
        val fallbackLabel: String,
        val onOpen: () -> Unit,
        val onRemove: () -> Unit,
    ) : InlineElementPreviewData

    data class TagPill(
        val tagName: String,
        val tagObj: PkmObject?,
        val onOpen: () -> Unit,
        val onRemove: () -> Unit,
    ) : InlineElementPreviewData

    data class WebLink(
        val url: String,
        val title: String,
        val description: String = "",
        val linkedObject: PkmObject? = null,
        val onOpenBrowser: () -> Unit,
        val onOpenInApp: (() -> Unit)? = null,
        val onEditLink: (() -> Unit)? = null,
        val onRemoveLink: () -> Unit,
    ) : InlineElementPreviewData

    data class DatePill(
        val isoDate: String,
        val dailyObject: PkmObject? = null,
        val mentionCount: Int = 0,
        val onOpen: () -> Unit,
        val onRemove: () -> Unit,
    ) : InlineElementPreviewData
}

/**
 * Pattern F: Compact floating preview card on tap.
 * Shows icon, title, type badge, 2–3 properties, 2-line content snippet, and action buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineElementPreviewPopup(
    preview: InlineElementPreviewData,
    onDismiss: () -> Unit,
) {
    val untitledLabel = stringResource(R.string.untitled_object)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppColors.SurfaceElevated,
            border = BorderStroke(1.dp, AppColors.BorderHover),
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (preview) {
                    is InlineElementPreviewData.ObjectMention -> {
                        val obj = preview.obj
                        val type = preview.type
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.Surface,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = obj?.icon?.emoji ?: type?.icon?.emoji ?: "📄",
                                        fontSize = 20.sp,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = obj?.plainTitle?.ifBlank { untitledLabel } ?: preview.fallbackLabel,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (type != null) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    TypeBadge(type = type)
                                }
                            }
                        }

                        val audioAssetId = obj?.let { resolveAudioAssetId(it) }
                        if (obj != null && !audioAssetId.isNullOrBlank()) {
                            AudioPlayerRow(
                                assetId = audioAssetId,
                                fallbackDurationSeconds = extractAudioDurationSeconds(obj).coerceAtLeast(1),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        // Up to 3 key properties
                        val propPairs = remember(obj) {
                            obj?.properties?.entries
                                ?.filter { (k, _) ->
                                    k != "blocks" && k != "asset_id" && k != "file_name" && k != "source_object_id"
                                }
                                ?.mapNotNull { (k, v) ->
                                    val formatted = when (v) {
                                        is PropertyValue.Select -> v.selectedOptionId
                                        is PropertyValue.MultiSelect -> v.selectedOptionIds.joinToString(", ")
                                        is PropertyValue.DateRange -> v.startDate
                                        is PropertyValue.Number -> v.value.toString()
                                        is PropertyValue.Checkbox -> if (v.checked) "✓" else "✗"
                                        is PropertyValue.RichText -> v.spans.toPlainText()
                                        else -> null
                                    }
                                    if (!formatted.isNullOrBlank()) k to formatted else null
                                }
                                ?.take(3)
                                .orEmpty()
                        }

                        if (propPairs.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                propPairs.forEach { (k, v) ->
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AppColors.Surface, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "$k: $v",
                                            fontSize = 11.sp,
                                            color = AppColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }

                        // 2-line content snippet
                        val snippet = remember(obj) {
                            obj?.plainSummary?.takeIf { it.isNotBlank() }
                                ?: obj?.blocks?.mapNotNull { b ->
                                    BlockOperations.extractPlainText(b).trim().takeIf { it.isNotBlank() }
                                }?.joinToString(" ")
                                .orEmpty()
                        }
                        if (snippet.isNotBlank()) {
                            Text(
                                text = snippet,
                                fontSize = 13.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        HorizontalDivider(color = AppColors.BorderSubtle)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolbarChip(
                                label = stringResource(R.string.action_remove_mention_btn),
                                leadingIcon = Icons.Default.Delete,
                                chipColor = AppColors.Danger,
                                onClick = {
                                    preview.onRemove()
                                    onDismiss()
                                },
                            )
                            if (obj != null) {
                                ToolbarChip(
                                    label = stringResource(R.string.action_open_object),
                                    leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                    isAccent = true,
                                    onClick = {
                                        preview.onOpen()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }

                    is InlineElementPreviewData.TagPill -> {
                        val customHex = preview.tagObj?.icon?.colorHex
                        val tagBg = TagPalette.getPillBackground(preview.tagName, customHex)
                        val tagBorder = TagPalette.getPillBorder(preview.tagName, customHex)
                        val tagText = TagPalette.getTextColor(preview.tagName, customHex)
                        val tagHash = TagPalette.getHashColor(preview.tagName, customHex)
                        val tagAccent = TagPalette.getAccentColor(preview.tagName, customHex)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tagBg, RoundedCornerShape(10.dp))
                                    .border(1.dp, tagBorder, RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Label,
                                    contentDescription = null,
                                    tint = tagAccent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "#",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = tagHash,
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = preview.tagName,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = tagText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.tags_badge),
                                    fontSize = 12.sp,
                                    color = AppColors.TextSecondary,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        HorizontalDivider(color = AppColors.BorderSubtle)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolbarChip(
                                label = stringResource(R.string.action_remove_mention_btn),
                                leadingIcon = Icons.Default.Delete,
                                chipColor = AppColors.Danger,
                                onClick = {
                                    preview.onRemove()
                                    onDismiss()
                                },
                            )
                            ToolbarChip(
                                label = stringResource(R.string.action_open_object),
                                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                isAccent = true,
                                onClick = {
                                    preview.onOpen()
                                    onDismiss()
                                },
                            )
                        }
                    }

                    is InlineElementPreviewData.WebLink -> {
                        val domain = remember(preview.url) {
                            runCatching { Uri.parse(preview.url).host }.getOrNull().orEmpty().ifBlank { preview.url }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AppColors.Surface,
                                modifier = Modifier.size(40.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = AppColors.Accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preview.title.ifBlank { domain },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = domain,
                                    fontSize = 12.sp,
                                    color = AppColors.Accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (preview.description.isNotBlank()) {
                            Text(
                                text = preview.description,
                                fontSize = 13.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text(
                                text = preview.url,
                                fontSize = 12.sp,
                                color = AppColors.TextTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        HorizontalDivider(color = AppColors.BorderSubtle)

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ToolbarChip(
                                label = stringResource(R.string.action_open_browser),
                                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                isAccent = true,
                                onClick = {
                                    preview.onOpenBrowser()
                                    onDismiss()
                                },
                            )
                            if (preview.onOpenInApp != null) {
                                ToolbarChip(
                                    label = stringResource(R.string.action_open_in_app),
                                    leadingEmoji = "📄",
                                    onClick = {
                                        preview.onOpenInApp.invoke()
                                        onDismiss()
                                    },
                                )
                            }
                            if (preview.onEditLink != null) {
                                ToolbarChip(
                                    label = stringResource(R.string.action_edit_link),
                                    leadingIcon = Icons.Default.Edit,
                                    onClick = {
                                        preview.onEditLink.invoke()
                                        onDismiss()
                                    },
                                )
                            }
                            ToolbarChip(
                                label = stringResource(R.string.action_remove_link),
                                leadingIcon = Icons.Default.Delete,
                                chipColor = AppColors.Danger,
                                onClick = {
                                    preview.onRemoveLink()
                                    onDismiss()
                                },
                            )
                        }
                    }

                    is InlineElementPreviewData.DatePill -> {
                        val configuration = LocalConfiguration.current
                        val currentLocale = remember(configuration) {
                            configuration.locales[0] ?: Locale.getDefault()
                        }
                        val parsedDate = remember(preview.isoDate) {
                            runCatching { LocalDate.parse(preview.isoDate) }.getOrNull()
                        }
                        val formattedDateLong = remember(parsedDate, currentLocale) {
                            parsedDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(currentLocale))
                                ?: preview.isoDate
                        }
                        val relativeLabel = remember(parsedDate) {
                            if (parsedDate == null) null
                            else {
                                val today = LocalDate.now()
                                val diff = ChronoUnit.DAYS.between(today, parsedDate)
                                when (diff) {
                                    0L -> R.string.today
                                    1L -> R.string.date_tomorrow
                                    -1L -> R.string.date_yesterday
                                    else -> null
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AppColors.AccentSubtle, RoundedCornerShape(12.dp))
                                    .border(1.dp, AppColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Event,
                                    contentDescription = null,
                                    tint = AppColors.Accent,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formattedDateLong,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.date_object_label),
                                        fontSize = 12.sp,
                                        color = AppColors.Accent,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (relativeLabel != null) {
                                        Text(
                                            text = "•",
                                            fontSize = 11.sp,
                                            color = AppColors.TextTertiary,
                                        )
                                        Text(
                                            text = stringResource(relativeLabel),
                                            fontSize = 12.sp,
                                            color = AppColors.TextSecondary,
                                        )
                                    }
                                    if (preview.mentionCount > 0) {
                                        Text(
                                            text = "•",
                                            fontSize = 11.sp,
                                            color = AppColors.TextTertiary,
                                        )
                                        Text(
                                            text = stringResource(R.string.date_mentions_count, preview.mentionCount),
                                            fontSize = 12.sp,
                                            color = AppColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }

                        val dailySummary = preview.dailyObject?.plainSummary?.takeIf { it.isNotBlank() }
                            ?: preview.dailyObject?.blocks?.firstOrNull()?.let { b ->
                                BlockOperations.extractSpans(b).toPlainText().trim().takeIf { it.isNotBlank() }
                            }

                        if (!dailySummary.isNullOrBlank()) {
                            Text(
                                text = dailySummary,
                                fontSize = 13.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        HorizontalDivider(color = AppColors.BorderSubtle)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ToolbarChip(
                                label = stringResource(R.string.action_remove_date),
                                leadingIcon = Icons.Default.Delete,
                                chipColor = AppColors.Danger,
                                onClick = {
                                    preview.onRemove()
                                    onDismiss()
                                },
                            )
                            ToolbarChip(
                                label = stringResource(R.string.action_open_date),
                                leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                                isAccent = true,
                                onClick = {
                                    preview.onOpen()
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen pinch-to-zoom image preview dialog when tapping a [Block.Image].
 */
@Composable
fun FullScreenImagePreviewDialog(
    assetId: String,
    caption: String,
    refreshKey: Boolean = false,
    onDismiss: () -> Unit,
) {
    val bitmap = rememberDecodedImageForPreview(assetId, refreshKey)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale > 1f) {
                            Offset(
                                x = offset.x + pan.x,
                                y = offset.y + pan.y,
                            )
                        } else {
                            Offset.Zero
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = caption.ifBlank { stringResource(R.string.block_type_image) },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ).privacyImage(),
                )
            } else {
                Text(
                    text = stringResource(R.string.media_loading),
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }

            // Close button at top-end
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AppColors.SurfaceElevated.copy(alpha = 0.85f), CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (caption.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = caption,
                        color = Color.White,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
fun SlashMenuBottomSheet(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }

    val localizedItems = SLASH_MENU_ITEMS.map { item ->
        item to stringResource(item.titleRes)
    }
    val filteredItems = remember(query, localizedItems) {
        val q = query.trim()
        if (q.isBlank()) {
            localizedItems.map { it.first }
        } else {
            localizedItems
                .filter { (item, label) ->
                    item.id.contains(q, ignoreCase = true) ||
                        label.contains(q, ignoreCase = true)
                }
                .map { it.first }
        }
    }

    UniversalEditorBottomSheet(
        onDismiss = onDismiss,
        searchQuery = query,
        onSearchQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.search_block_type_placeholder),
        headerTitle = stringResource(R.string.dialog_insert_block_title),
        badgeLabel = stringResource(R.string.badge_block_types),
        badgeIcon = Icons.AutoMirrored.Filled.Notes,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredItems, key = { it.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(item.id) }
                        .padding(vertical = 9.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = AppColors.Surface,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = AppColors.Accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(item.titleRes),
                        color = AppColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                HorizontalDivider(color = AppColors.BorderSubtle)
            }
        }
    }
}

@Composable
fun SlashMenuDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    SlashMenuBottomSheet(onDismiss = onDismiss, onSelect = onSelect)
}

@Composable
fun ObjectMentionBottomSheet(
    objects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onDismiss: () -> Unit,
    onSelect: (PkmObject) -> Unit,
    initialQuery: String = "",
    onCreateNew: ((typeId: String, title: String, emoji: String) -> Unit)? = null,
) {
    UniversalObjectLinkSelectorSheet(
        objects = objects,
        allTypes = allTypes,
        onDismiss = onDismiss,
        onSelectObject = onSelect,
        initialQuery = initialQuery,
        isLinkMode = false,
        onCreateNew = onCreateNew,
    )
}

@Composable
fun ObjectMentionDialog(
    objects: List<PkmObject>,
    allTypes: List<ObjectType>,
    onDismiss: () -> Unit,
    onSelect: (PkmObject) -> Unit,
    initialQuery: String = "",
    onCreateNew: ((typeId: String, title: String, emoji: String) -> Unit)? = null,
) {
    ObjectMentionBottomSheet(
        objects = objects,
        allTypes = allTypes,
        onDismiss = onDismiss,
        onSelect = onSelect,
        initialQuery = initialQuery,
        onCreateNew = onCreateNew,
    )
}

object TagPalette {
    fun parseHexColor(hex: String?): Color? {
        if (hex.isNullOrBlank()) return null
        val cleaned = hex.trim().removePrefix("#")
        val value = cleaned.toLongOrNull(16) ?: return null
        return when (cleaned.length) {
            6 -> Color(0xFF000000L or value)
            8 -> Color(value)
            else -> null
        }
    }

    fun getColor(name: String, customHex: String? = null): Color {
        parseHexColor(customHex)?.let { return it }
        if (!customHex.isNullOrBlank()) {
            val parsed = runCatching { Color(customHex.toColorInt()) }.getOrNull()
            if (parsed != null) return parsed
        }
        val hex = org.yaop.core.model.TagPalette.getColorHex(name.trim().lowercase().hashCode())
        return parseHexColor(hex)
            ?: runCatching { Color(hex.toColorInt()) }.getOrDefault(Color(0xFF10B981))
    }

    /**
     * Formal, high-legibility foreground text tint for dark editorial surfaces.
     * Blends the base tag color with soft white so darker palette tones (like crimson or forest green)
     * remain crisp, balanced, and readable inside blocks.
     */
    fun getTextColor(name: String, customHex: String? = null): Color {
        val base = getColor(name, customHex)
        return lerp(base, Color(0xFFF8FAFC), 0.42f)
    }

    /**
     * Muted tint for the leading `#` prefix to create formal typographic hierarchy
     * between the symbol and the tag name.
     */
    fun getHashColor(name: String, customHex: String? = null): Color {
        return getTextColor(name, customHex).copy(alpha = 0.62f)
    }

    /**
     * Subtle translucent pill surface for inline block tags.
     */
    fun getPillBackground(name: String, customHex: String? = null): Color {
        val base = getColor(name, customHex)
        return lerp(base, Color(0xFF1E1E24), 0.22f).copy(alpha = 0.20f)
    }

    /**
     * Crisp 1dp hairline border for inline block tag pills.
     */
    fun getPillBorder(name: String, customHex: String? = null): Color {
        val base = getColor(name, customHex)
        return lerp(base, Color.White, 0.20f).copy(alpha = 0.44f)
    }

    /**
     * Refined accent dot/indicator color.
     */
    fun getAccentColor(name: String, customHex: String? = null): Color {
        val base = getColor(name, customHex)
        return lerp(base, Color.White, 0.22f)
    }

    fun getColorHex(index: Int): String = org.yaop.core.model.TagPalette.getColorHex(index)
}


data class TagItemUi(
    val id: String?,
    val name: String,
    val colorHex: String,
)

@Composable
fun TagSelectionBottomSheet(
    allTags: List<PkmObject>,
    activeBlockTags: List<String>,
    onDismiss: () -> Unit,
    onToggleTag: (name: String, colorHex: String) -> Unit,
    onCreateTag: (name: String, colorHex: String) -> Unit,
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }

    val tagItems = remember(allTags) {
        allTags.map { tag ->
            TagItemUi(
                id = tag.id,
                name = tag.plainTitle,
                colorHex = tag.icon?.colorHex ?: TagPalette.getColorHex(abs(tag.plainTitle.hashCode())),
            )
        }
    }

    val filtered = remember(query, tagItems) {
        if (query.isBlank()) {
            tagItems
        } else {
            tagItems.filter { it.name.contains(query, ignoreCase = true) }
        }
    }

    UniversalEditorBottomSheet(
        onDismiss = onDismiss,
        searchQuery = query,
        onSearchQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.search_or_create_placeholder),
        headerActionLabel = stringResource(R.string.new_tag_action),
        onHeaderActionClick = {
            val trimmed = query.trim()
            if (trimmed.isNotBlank()) {
                val colorHex = TagPalette.getColorHex(tagItems.size)
                onCreateTag(trimmed, colorHex)
            }
        },
        badgeLabel = stringResource(R.string.tags_badge),
        badgeIcon = Icons.AutoMirrored.Filled.Label,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_tags_found),
                        color = AppColors.TextTertiary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(filtered, key = { it.name }) { tag ->
                    val isSelected = activeBlockTags.any { it.equals(tag.name, ignoreCase = true) }
                    val tagBg = TagPalette.getPillBackground(tag.name, tag.colorHex)
                    val tagBorder = TagPalette.getPillBorder(tag.name, tag.colorHex)
                    val tagText = TagPalette.getTextColor(tag.name, tag.colorHex)
                    val tagHash = TagPalette.getHashColor(tag.name, tag.colorHex)
                    val tagAccent = TagPalette.getAccentColor(tag.name, tag.colorHex)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleTag(tag.name, tag.colorHex) }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .background(AppColors.Surface, RoundedCornerShape(7.dp))
                                .background(tagBg, RoundedCornerShape(7.dp))
                                .border(1.dp, tagBorder, RoundedCornerShape(7.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "#",
                                color = tagHash,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = tag.name,
                                color = tagText,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Spacer(Modifier.weight(1f))

                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    color = if (isSelected) tagAccent else Color.Transparent,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .border(
                                    border = BorderStroke(1.5.dp, if (isSelected) tagAccent else AppColors.BorderHover),
                                    shape = RoundedCornerShape(6.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = AppColors.Background,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
