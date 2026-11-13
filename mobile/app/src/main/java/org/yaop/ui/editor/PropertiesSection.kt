package org.yaop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import org.yaop.R
import org.yaop.core.model.PropertyDefinition
import org.yaop.core.model.PropertyKind
import org.yaop.core.model.PropertyValue
import org.yaop.core.model.toPlainText
import org.yaop.core.model.toTextSpan
import org.yaop.ui.theme.AppColors

@Composable
fun PropertiesSection(
    propertyDefs: List<PropertyDefinition>,
    properties: Map<String, PropertyValue>,
    onPropertyChange: (String, PropertyValue) -> Unit,
    onAddCustomProperty: (PropertyDefinition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(value = false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.Surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.properties_section_title),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
            )

            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.padding(0.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_property),
                    tint = AppColors.Accent,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        propertyDefs.forEach { def ->
            val value = properties[def.id] ?: properties["prop_${def.id}"]
            PropertyRow(
                def = def,
                value = value,
                onChange = { newValue -> onPropertyChange(def.id, newValue) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (showAddDialog) {
            AddCustomPropertyDialog(
                onDismiss = { showAddDialog = false },
            ) { newDef ->
                onAddCustomProperty(newDef)
                showAddDialog = false
            }
        }
    }
}

@Composable
private fun PropertyRow(
    def: PropertyDefinition,
    value: PropertyValue?,
    onChange: (PropertyValue) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Property Label
        Box(modifier = Modifier.width(110.dp)) {
            Text(
                text = def.name,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
            )
        }

        // Property Dynamic Editor Value
        Box(modifier = Modifier.weight(1f)) {
            when (def.kind) {
                PropertyKind.RICH_TEXT -> {
                    val currentText = (value as? PropertyValue.RichText)?.spans?.toPlainText().orEmpty()
                    var text by remember(currentText) { mutableStateOf(currentText) }

                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onChange(PropertyValue.RichText(it.toTextSpan()))
                        },
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = AppColors.TextPrimary,
                        ),
                        cursorBrush = SolidColor(AppColors.Accent),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.property_empty),
                                    fontSize = 13.sp,
                                    color = AppColors.TextTertiary,
                                )
                            }
                            innerTextField()
                        },
                    )
                }

                PropertyKind.SELECT -> {
                    var expanded by remember { mutableStateOf(value = false) }
                    val currentOptionId = (value as? PropertyValue.Select)?.selectedOptionId
                    val currentOption = def.options.find { opt -> opt.id == currentOptionId }

                    Box {
                        Text(
                            text = currentOption?.label ?: stringResource(R.string.property_select_option),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (currentOption != null) {
                                runCatching { Color(currentOption.colorHex.toColorInt()) }
                                    .getOrDefault(AppColors.Accent)
                            } else {
                                AppColors.TextTertiary
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AppColors.SurfaceElevated, RoundedCornerShape(4.dp))
                                .clickable { expanded = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(AppColors.SurfaceElevated),
                        ) {
                            def.options.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label, color = AppColors.TextPrimary) },
                                    onClick = {
                                        onChange(PropertyValue.Select(opt.id))
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                PropertyKind.NUMBER -> {
                    val isRating = def.id == "rating" || def.id == "prop_rating" || (value as? PropertyValue.Number)?.isRating == true
                    if (isRating) {
                        val ratingValue = (value as? PropertyValue.Number)?.value?.toInt() ?: 0
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (star in 1..5) {
                                val isFilled = star <= ratingValue
                                Icon(
                                    imageVector = if (isFilled) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = stringResource(R.string.property_rating_stars, star),
                                    tint = if (isFilled) AppColors.Warning else AppColors.TextTertiary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            onChange(PropertyValue.Number(star.toDouble(), isRating = true))
                                        }
                                        .padding(2.dp),
                                )
                            }
                        }
                    } else {
                        val numVal = (value as? PropertyValue.Number)?.value?.toString() ?: ""
                        var text by remember(numVal) { mutableStateOf(numVal) }
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                val d = it.toDoubleOrNull()
                                onChange(PropertyValue.Number(d))
                            },
                            textStyle = TextStyle(
                                fontSize = 14.sp,
                                color = AppColors.TextPrimary,
                            ),
                            cursorBrush = SolidColor(AppColors.Accent),
                            decorationBox = { innerTextField ->
                                if (text.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.property_number_zero),
                                        fontSize = 13.sp,
                                        color = AppColors.TextTertiary,
                                    )
                                }
                                innerTextField()
                            },
                        )
                    }
                }

                PropertyKind.CHECKBOX -> {
                    val checked = (value as? PropertyValue.Checkbox)?.checked ?: false
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onChange(PropertyValue.Checkbox(it)) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AppColors.Accent,
                            uncheckedColor = AppColors.BorderHover,
                            checkmarkColor = AppColors.Background,
                        ),
                    )
                }

                PropertyKind.DATE_RANGE -> {
                    val dateVal = (value as? PropertyValue.DateRange)?.startDate.orEmpty()
                    var text by remember(dateVal) { mutableStateOf(dateVal) }
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onChange(PropertyValue.DateRange(it))
                        },
                        textStyle = TextStyle(fontSize = 13.sp, color = AppColors.TextPrimary),
                        cursorBrush = SolidColor(AppColors.Accent),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.property_date_placeholder),
                                    fontSize = 13.sp,
                                    color = AppColors.TextTertiary,
                                )
                            }
                            inner()
                        },
                    )
                }

                PropertyKind.URL -> {
                    val url = (value as? PropertyValue.Url)?.url.orEmpty()
                    var text by remember(url) { mutableStateOf(url) }
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onChange(PropertyValue.Url(it))
                        },
                        textStyle = TextStyle(fontSize = 13.sp, color = AppColors.Accent),
                        cursorBrush = SolidColor(AppColors.Accent),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.property_url_placeholder),
                                    fontSize = 13.sp,
                                    color = AppColors.TextTertiary,
                                )
                            }
                            inner()
                        },
                    )
                }

                else -> {
                    Text(
                        text = stringResource(R.string.property_custom),
                        fontSize = 13.sp,
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
fun AddCustomPropertyDialog(
    onDismiss: () -> Unit,
    onConfirm: (PropertyDefinition) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf(PropertyKind.RICH_TEXT) }

    val selectableKinds = remember {
        listOf(
            PropertyKind.RICH_TEXT to R.string.property_kind_rich_text,
            PropertyKind.SELECT to R.string.property_kind_select,
            PropertyKind.NUMBER to R.string.property_kind_number,
            PropertyKind.CHECKBOX to R.string.property_kind_checkbox,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceElevated,
        title = {
            Text(
                text = stringResource(R.string.dialog_add_property_title),
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.label_property_name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                    ),
                )

                Text(
                    text = stringResource(R.string.label_property_type),
                    fontSize = 13.sp,
                    color = AppColors.TextSecondary,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    selectableKinds.forEach { (kind, labelRes) ->
                        val isSelected = selectedKind == kind
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    color = if (isSelected) AppColors.AccentSubtle else AppColors.Surface,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .clickable { selectedKind = kind }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                fontSize = 11.sp,
                                color = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val propId = name.trim().lowercase().replace(Regex("\\s+"), "_")
                        onConfirm(PropertyDefinition(propId, name.trim(), selectedKind))
                    }
                },
            ) {
                Text(stringResource(R.string.action_add), color = AppColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = AppColors.TextSecondary)
            }
        },
    )
}
