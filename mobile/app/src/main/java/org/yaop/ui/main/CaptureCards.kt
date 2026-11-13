package org.yaop.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.yaop.R
import org.yaop.ui.theme.AppLabelStyle
import org.yaop.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.yaop.core.model.toTextSpan

/**
 * Quick-capture panel: a translucent sheet that springs up from the dock's (+) with a grid of cards
 * (emoji + mono label), the default capture style. The radial fan stays available as a setting.
 * Cards enter staggered (35 ms apart) so the panel feels alive without any continuous animation.
 */
@Composable
fun CaptureCardsPanel(
    items: List<RadialItem>,
    visible: Boolean,
    onPick: (RadialItem) -> Unit,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            if (visible) 1f else 0f,
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        )
    }
    if (progress.value <= 0.001f && !visible) return
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .padding(horizontal = 18.dp)
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value.coerceIn(0f, 1f)
                translationY = (1f - progress.value) * 36.dp.toPx()
                val s = 0.94f + 0.06f * progress.value
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFA0B1A4A), RoundedCornerShape(22.dp))
            .border(1.dp, AppColors.BorderSubtle, RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.capture_title).uppercase(),
            style = AppLabelStyle,
            color = AppColors.TextTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        CaptureTextField(onClose)
        Spacer(Modifier.height(12.dp))
        items.chunked(3).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { col, item ->
                    CaptureCard(
                        item = item,
                        order = rowIndex * 3 + col,
                        visible = visible,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onPick(item)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CaptureCard(
    item: RadialItem,
    order: Int,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(order * 35L)
            pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
        } else {
            pop.snapTo(0f)
        }
    }
    val press = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .aspectRatio(1.05f)
            .graphicsLayer {
                alpha = pop.value.coerceIn(0f, 1f)
                val s = 0.8f + 0.2f * pop.value
                scaleX = s; scaleY = s
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
            .clickable(interactionSource = press, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = item.emoji, fontSize = 26.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.label.uppercase(),
            style = AppLabelStyle,
            color = AppColors.TextPrimary,
            maxLines = 1,
        )
    }
}

/** Text-first capture: write, then send to today's journal or keep as a note. */
@Composable
private fun CaptureTextField(onDone: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as org.yaop.YaopApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    fun send(toDaily: Boolean) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch { captureText(app.repository, t, toDaily) }
        text = ""
        onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = AppColors.TextPrimary, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppColors.Ice),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AppColors.Surface, RoundedCornerShape(12.dp))
                .padding(12.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text(stringResource(R.string.capture_text_hint), color = AppColors.TextTertiary, fontSize = 16.sp)
                inner()
            },
        )
        if (text.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, toDaily) in listOf(R.string.capture_to_daily to true, R.string.capture_as_note to false)) {
                Text(
                    text = stringResource(label),
                    fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (toDaily) AppColors.AccentInk else AppColors.TextPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (toDaily) AppColors.Accent else AppColors.Surface, RoundedCornerShape(50))
                        .clickable { send(toDaily) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private suspend fun captureText(repo: org.yaop.core.repository.PkmRepository, text: String, toDaily: Boolean) {
    val now = java.time.Instant.now().toString()
    if (toDaily) {
        val key = java.time.LocalDate.now().toString()
        val id = "daily-$key"
        val para = org.yaop.core.model.Block.Paragraph(
            id = java.util.UUID.randomUUID().toString(),
            spans = text.toTextSpan(),
        )
        val base = repo.getObject(id) ?: org.yaop.core.model.PkmObject(
            id = id,
            typeId = org.yaop.core.model.DefaultTypes.DAILY.id,
            title = key.toTextSpan(),
            icon = org.yaop.core.model.ObjectIcon(emoji = "📅"),
            properties = mapOf("date" to org.yaop.core.model.PropertyValue.DateRange(key)),
        )
        repo.saveObject(base.copy(blocks = base.blocks + para, updatedAt = now))
    } else {
        val title = text.lineSequence().first().take(80)
        val rest = text.lineSequence().drop(1).joinToString("\n").trim()
        repo.saveObject(
            org.yaop.core.model.PkmObject(
                id = java.util.UUID.randomUUID().toString(),
                typeId = org.yaop.core.model.DefaultTypes.NOTE.id,
                title = title.toTextSpan(),
                createdAt = now,
                updatedAt = now,
                properties = mapOf("reviewed" to org.yaop.core.model.PropertyValue.Checkbox(false)),
                blocks = if (rest.isEmpty()) emptyList() else listOf(
                    org.yaop.core.model.Block.Paragraph(java.util.UUID.randomUUID().toString(), rest.toTextSpan()),
                ),
            ),
        )
    }
}
