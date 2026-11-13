package org.yaop.ui.common

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.yaop.R
import org.yaop.core.network.LinkPreviewData
import org.yaop.core.network.LinkPreviewFetcher
import org.yaop.ui.theme.AppColors

private const val CARD_CORNER_RADIUS = 14
private const val CARD_BORDER_WIDTH = 1

private sealed interface LinkPreviewUiState {
    data object Loading : LinkPreviewUiState
    data class Ready(val data: LinkPreviewData) : LinkPreviewUiState
    data object Unavailable : LinkPreviewUiState
}

/**
 * Compact Material3 card that shows an Open Graph link preview for [url].
 *
 * While metadata is being fetched a subtle skeleton placeholder is shown; if the
 * fetch fails, a minimal card with the domain and URL is rendered instead of an
 * empty block. Tapping the card invokes [onOpenInBrowser] with the URL.
 *
 * @param url absolute http/https URL to preview.
 * @param onOpenInBrowser called with [url] when the card is tapped. By default
 * it launches an [Intent.ACTION_VIEW] in the system browser.
 */
@Composable
fun LinkPreviewCard(
    url: String,
    modifier: Modifier = Modifier,
    onOpenInBrowser: (String) -> Unit = defaultOpenInBrowser(),
) {
    val state by produceState<LinkPreviewUiState>(LinkPreviewUiState.Loading, url) {
        val fetched = LinkPreviewFetcher.fetch(url)
        value = if (fetched != null) {
            LinkPreviewUiState.Ready(fetched)
        } else {
            LinkPreviewUiState.Unavailable
        }
    }

    val currentState = state
    when (currentState) {
        LinkPreviewUiState.Loading -> LoadingPlaceholder(modifier = modifier)
        is LinkPreviewUiState.Ready -> LoadedCard(
            data = currentState.data,
            modifier = modifier,
            onOpenInBrowser = onOpenInBrowser,
        )

        LinkPreviewUiState.Unavailable -> FailedCard(
            url = url,
            modifier = modifier,
            onOpenInBrowser = onOpenInBrowser,
        )
    }
}

/** Default [onOpenInBrowser]: opens [target] in the system browser via ACTION_VIEW. */
@Composable
private fun defaultOpenInBrowser(): (String) -> Unit {
    val context = LocalContext.current
    return { target ->
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun LoadedCard(
    data: LinkPreviewData,
    modifier: Modifier = Modifier,
    onOpenInBrowser: (String) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .background(AppColors.SurfaceElevated, RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .border(
                width = CARD_BORDER_WIDTH.dp,
                color = AppColors.BorderSubtle,
                shape = RoundedCornerShape(CARD_CORNER_RADIUS.dp),
            )
            .clickable { onOpenInBrowser(data.url) }
            .padding(12.dp),
    ) {
        if (!data.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = data.imageUrl,
                contentDescription = stringResource(R.string.link_preview_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(10.dp)).privacyImage(),
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (!data.title.isNullOrBlank()) {
            Text(
                text = data.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (!data.description.isNullOrBlank()) {
            Text(
                text = data.description,
                fontSize = 13.sp,
                color = AppColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!data.faviconUrl.isNullOrBlank()) {
                AsyncImage(
                    model = data.faviconUrl,
                    contentDescription = stringResource(R.string.link_preview_favicon),
                    modifier = Modifier.size(16.dp).privacyImage(),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = data.siteName ?: displayUrl(data.url),
                fontSize = 12.sp,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = stringResource(R.string.link_preview_open),
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Minimal card shown when the preview could not be fetched: domain + link icon + url. */
@Composable
private fun FailedCard(
    url: String,
    modifier: Modifier = Modifier,
    onOpenInBrowser: (String) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .background(AppColors.Surface, RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .border(CARD_BORDER_WIDTH.dp, AppColors.BorderSubtle, RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .clickable { onOpenInBrowser(url) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(R.string.link_preview_open),
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = displayUrl(url),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = url,
                fontSize = 11.sp,
                color = AppColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact skeleton with a subtle shimmer shown while metadata is loading. */
@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    val loadingLabel = stringResource(R.string.link_preview_loading)
    val transition = rememberInfiniteTransition(label = "link_preview_shimmer")
    val shimmerAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "link_preview_shimmer_alpha",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .background(AppColors.Surface, RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .border(CARD_BORDER_WIDTH.dp, AppColors.BorderSubtle, RoundedCornerShape(CARD_CORNER_RADIUS.dp))
            .padding(12.dp)
            .semantics { contentDescription = loadingLabel },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AppColors.SurfaceElevated.copy(alpha = shimmerAlpha)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.SurfaceElevated.copy(alpha = shimmerAlpha)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppColors.SurfaceElevated.copy(alpha = shimmerAlpha)),
            )
        }
    }
}

/** Human-readable host of [url], falling back to the raw URL. */
private fun displayUrl(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
