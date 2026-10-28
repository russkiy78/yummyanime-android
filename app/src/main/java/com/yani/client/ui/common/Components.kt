package com.yani.client.ui.common

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.data.AnimeItem
import com.yani.client.data.ApiException
import com.yani.client.data.absUrl
import com.yani.client.ui.theme.Gold
import com.yani.client.util.formatDuration
import com.yani.client.util.formatRating
import java.io.IOException

val LocalIsTv = staticCompositionLocalOf { false }

fun Context.isTelevision(): Boolean {
    val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

val ScreenPadding = 16.dp

@Composable
fun posterWidth(): Dp = if (LocalIsTv.current) 150.dp else 116.dp

/** Visible focus for D-pad navigation: accent border and a slight zoom. */
@Composable
fun Modifier.focusBorder(
    interactionSource: InteractionSource,
    shape: Shape = RoundedCornerShape(10.dp),
    scale: Float = 1.05f,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val animated by animateFloatAsState(if (focused) scale else 1f, label = "focusScale")
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = animated
            scaleY = animated
        }
        .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
}

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    rating: Double? = null,
    focusRequester: FocusRequester? = null,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Card(
            onClick = onClick,
            shape = shape,
            interactionSource = source,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusBorder(source, shape),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                formatRating(rating)?.let { RatingBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun AnimePosterCard(
    item: AnimeItem,
    modifier: Modifier = Modifier,
    subtitle: String? = listOfNotNull(item.year?.takeIf { it > 0 }?.toString(), item.type?.shortname).joinToString(" · "),
    badge: String? = null,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
) {
    val nav = LocalNav.current
    PosterCard(
        title = item.title,
        imageUrl = item.poster?.card,
        onClick = onClick ?: { nav.openAnime(item.id.toString()) },
        modifier = modifier,
        subtitle = subtitle,
        badge = badge,
        rating = item.ratingValue,
        focusRequester = focusRequester,
    )
}

/** Wide card for "continue watching" and history: screenshot, progress and episode. */
@Composable
fun WatchCard(item: AnimeItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Card(
            onClick = onClick,
            shape = shape,
            interactionSource = source,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .focusBorder(source, shape),
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = absUrl(item.screenshot?.sizes?.small) ?: item.poster?.large,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    val episode = item.epTitle?.let { stringResource(R.string.episode_n, it) }
                    val time = item.endTime?.let { end ->
                        item.duration?.takeIf { it > 0 }?.let { "${formatDuration(end)} / ${formatDuration(it)}" }
                    }
                    Text(
                        listOfNotNull(episode, time).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    val duration = item.duration ?: 0.0
                    if (duration > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { ((item.endTime ?: 0.0) / duration).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RatingBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(2.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun AnimeRow(
    title: String,
    items: List<AnimeItem>,
    modifier: Modifier = Modifier,
    subtitle: (AnimeItem) -> String? = { listOfNotNull(it.year?.takeIf { y -> y > 0 }?.toString(), it.type?.shortname).joinToString(" · ") },
    badge: (AnimeItem) -> String? = { null },
    firstFocus: FocusRequester? = null,
) {
    if (items.isEmpty()) return
    Column(modifier) {
        SectionTitle(title)
        val width = posterWidth()
        LazyRow(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items.size, key = { "${items[it].id}-$it" }) { index ->
                val item = items[index]
                AnimePosterCard(
                    item = item,
                    subtitle = subtitle(item),
                    badge = badge(item),
                    focusRequester = if (index == 0) firstFocus else null,
                    modifier = Modifier.width(width),
                )
            }
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun errorMessage(error: Throwable): String = when (error) {
    is ApiException -> when {
        error.needsCaptcha -> stringResource(R.string.error_captcha)
        error.isUnauthorized -> stringResource(R.string.error_unauthorized)
        error.httpCode == 404 -> stringResource(R.string.error_not_found)
        else -> error.message ?: stringResource(R.string.error_unknown)
    }
    is IOException -> stringResource(R.string.error_network)
    else -> error.message ?: stringResource(R.string.error_unknown)
}

@Composable
fun ErrorBox(error: Throwable, onRetry: (() -> Unit)?, modifier: Modifier = Modifier.fillMaxSize()) {
    MessageBox(errorMessage(error), Icons.Filled.CloudOff, modifier) {
        if (onRetry != null) YButton(stringResource(R.string.retry), onRetry)
    }
}

@Composable
fun MessageBox(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier.fillMaxSize(),
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
fun <T> LoadContent(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> LoadingBox()
        is Load.Error -> ErrorBox(state.error, onRetry)
        is Load.Ok -> content(state.data)
    }
}

/** Button with a clear D-pad focus border. */
@Composable
fun YButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val m = modifier.focusBorder(source, shape, scale = 1.04f)
    if (primary) {
        Button(onClick, m, enabled = enabled, shape = shape, interactionSource = source, content = content)
    } else {
        FilledTonalButton(onClick, m, enabled = enabled, shape = shape, interactionSource = source, content = content)
    }
}

@Composable
fun YChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        shape = shape,
        interactionSource = source,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBorder(source, shape, scale = 1.04f),
    )
}

/** Icon button with a clear D-pad focus border. */
@Composable
fun YIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.focusBorder(source, RoundedCornerShape(50), scale = 1.1f),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = LocalNav.current.back,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                YIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            }
        },
        actions = actions,
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
