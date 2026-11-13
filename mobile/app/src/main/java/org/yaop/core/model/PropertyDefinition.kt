package org.yaop.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class PropertyKind {
    RICH_TEXT,     // Text with markdown styles, @mentions, #tags
    OBJECT_SELECT, // Relation to one or more objects
    SELECT,        // Single select from predefined options
    MULTI_SELECT,  // Multiple tags / options
    NUMBER,        // Generic number, rating (1-5 stars), currency
    DATE_RANGE,    // Date with optional end date & time
    CHECKBOX,      // Boolean toggle
    MEDIA_REF,     // Attached asset (image/pdf/audio)
    URL            // Cleaned web link
}

@Serializable
data class PropertyDefinition(
    val id: String,
    val name: String,
    val kind: PropertyKind,
    val targetTypeId: String? = null,     // For OBJECT_SELECT
    val options: List<SelectOption> = emptyList(), // For SELECT & MULTI_SELECT
    val isSystem: Boolean = false,        // If true, cannot be removed from default schema
    val icon: String? = null
)

@Serializable
data class SelectOption(
    val id: String,
    val label: String,
    val colorHex: String = "#7B91AE"
)

@Serializable
sealed interface PropertyValue {
    @Serializable
    data class RichText(val spans: List<InlineSpan>) : PropertyValue

    @Serializable
    data class ObjectRelation(val targetObjectIds: List<String>) : PropertyValue

    @Serializable
    data class Select(val selectedOptionId: String?) : PropertyValue

    @Serializable
    data class MultiSelect(val selectedOptionIds: List<String>) : PropertyValue

    @Serializable
    data class Number(val value: Double?, val isRating: Boolean = false) : PropertyValue

    @Serializable
    data class DateRange(
        val startDate: String,
        val endDate: String? = null,
        val hasTime: Boolean = false
    ) : PropertyValue

    @Serializable
    data class Checkbox(val checked: Boolean) : PropertyValue

    @Serializable
    data class Media(val assetId: String, val mimeType: String, val fileName: String) : PropertyValue

    @Serializable
    data class Url(val url: String) : PropertyValue
}
