package org.yaop.core.model

/**
 * A [InlineSpan.Mention] keeps its target's title in `fallbackText` as it was when the mention was
 * inserted. Navigation uses `targetObjectId`, but the label would stay stale after a rename
 * ("@hydroge" after renaming the object to "hydrogen").
 *
 * Rewrites every mention label to the current title of its target. [titleOf] returns the title for
 * an object id, or null when the target is unknown or untitled (the label is then kept).
 * Returns [blocks] itself when nothing changed, so callers can compare by identity.
 */
fun refreshMentionLabels(
    blocks: List<Block>,
    titleOf: (String) -> String?,
): List<Block> {
    var changed = false
    val out = blocks.map { block ->
        if (block !is SpannedBlock || block.spans.none { it is InlineSpan.Mention }) return@map block
        var blockChanged = false
        val spans = block.spans.map { span ->
            if (span !is InlineSpan.Mention) return@map span
            val title = titleOf(span.targetObjectId)?.trim()?.takeIf { it.isNotEmpty() }
            if (title == null || title == span.fallbackText) {
                span
            } else {
                blockChanged = true
                span.copy(fallbackText = title)
            }
        }
        if (blockChanged) {
            changed = true
            block.withSpans(spans)
        } else {
            block
        }
    }
    return if (changed) out else blocks
}
