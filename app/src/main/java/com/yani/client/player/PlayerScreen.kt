package com.yani.client.player

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yani.client.R
import com.yani.client.data.Video
import com.yani.client.data.isFinished
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.YChip
import com.yani.client.ui.common.focusBorder
import com.yani.client.util.formatDuration
import kotlinx.coroutines.delay

class PlayerScreenActions(
    val onBack: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onOpenPanel: () -> Unit,
    val onClosePanel: () -> Unit,
    val onImmersiveChanged: (Boolean) -> Unit,
    val onEpisodeChosen: () -> Unit,
    /** Play/pause only. */
    val onTogglePlay: () -> Unit,
    /** Remote OK on the video: skips the opening while the hint is shown, otherwise play/pause. */
    val onCenterKey: () -> Unit,
    val onSeek: (seconds: Int) -> Unit,
)

/**
 * Three layouts share one WebView slot, so switching never reloads the video:
 * fullscreen (video only, panel as overlay), landscape windowed (video | panel) and portrait (video over panel).
 */
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    webView: WebView,
    isTv: Boolean,
    fullscreen: Boolean,
    panelOpen: Boolean,
    controlsVisible: Boolean,
    customView: View?,
    actions: PlayerScreenActions,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val immersive = fullscreen || customView != null
    val videoFocus = remember { FocusRequester() }

    LaunchedEffect(immersive) { actions.onImmersiveChanged(immersive) }
    // Leaving fullscreen on TV: put the focus on the video so OK keeps controlling playback.
    LaunchedEffect(immersive, isTv) {
        if (!immersive && isTv) {
            delay(100)
            runCatching { videoFocus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (immersive) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        Layout(
            contents = listOf(
                {
                    VideoArea(
                        webView = webView,
                        ui = ui,
                        playback = playback,
                        vm = vm,
                        isTv = isTv,
                        immersive = immersive,
                        panelOpen = panelOpen,
                        controlsVisible = controlsVisible,
                        fullscreen = fullscreen,
                        actions = actions,
                        focusRequester = videoFocus,
                    )
                },
                {
                    if (!immersive) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            EpisodePanel(ui, playback, vm, isTv, inOverlay = false, fullscreen = fullscreen, actions = actions)
                        }
                    }
                },
            ),
            modifier = if (immersive) Modifier.fillMaxSize() else Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        ) { (video, panel), constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            layout(width, height) {
                when {
                    immersive -> video.forEach { it.measure(Constraints.fixed(width, height)).place(0, 0) }
                    landscape -> {
                        val panelWidth = (width * 0.38f).toInt().coerceIn(minOf(300.dp.roundToPx(), width / 2), 520.dp.roundToPx())
                        val videoWidth = width - panelWidth
                        val videoHeight = minOf(height, videoWidth * 9 / 16)
                        video.forEach { it.measure(Constraints.fixed(videoWidth, videoHeight)).place(0, (height - videoHeight) / 2) }
                        panel.forEach { it.measure(Constraints.fixed(panelWidth, height)).place(videoWidth, 0) }
                    }
                    else -> {
                        val videoHeight = minOf(height / 2, width * 9 / 16)
                        video.forEach { it.measure(Constraints.fixed(width, videoHeight)).place(0, 0) }
                        panel.forEach { it.measure(Constraints.fixed(width, height - videoHeight)).place(0, videoHeight) }
                    }
                }
            }
        }

        if (customView != null) {
            key(customView) {
                AndroidView(
                    factory = { context ->
                        FrameLayout(context).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            (customView.parent as? ViewGroup)?.removeView(customView)
                            addView(customView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (immersive && panelOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(interactionSource = null, indication = null, onClick = actions.onClosePanel),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(if (landscape) 0.42f else 0.85f)
                        .widthIn(max = 520.dp)
                        // Swallow clicks so they do not close the panel.
                        .clickable(interactionSource = null, indication = null) {},
                ) {
                    EpisodePanel(ui, playback, vm, isTv, inOverlay = true, fullscreen = fullscreen, actions = actions)
                }
            }
        }
    }
}

@Composable
private fun VideoArea(
    webView: WebView,
    ui: PlayerUi,
    playback: Playback,
    vm: PlayerViewModel,
    isTv: Boolean,
    immersive: Boolean,
    panelOpen: Boolean,
    controlsVisible: Boolean,
    fullscreen: Boolean,
    actions: PlayerScreenActions,
    focusRequester: FocusRequester,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Windowed mode: the video is a focusable item, OK toggles playback (fullscreen keys go through the activity).
            .then(
                if (immersive) {
                    Modifier
                } else {
                    Modifier
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            val center = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (center && event.type == KeyEventType.KeyDown) actions.onCenterKey()
                            center
                        }
                        .focusBorder(source, RoundedCornerShape(0.dp), scale = 1f)
                        .focusable(interactionSource = source)
                },
            ),
    ) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                // WRAP_CONTENT would make WebView size the page viewport to its content (0px tall).
                webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )
        val error = ui.error
        when {
            ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null -> ErrorBox(error, vm::load)
            ui.videos.isEmpty() -> MessageBox(stringResource(R.string.no_videos), Icons.Filled.MovieFilter)
        }
        if (playback.canSkipOpening) {
            SkipOpeningButton(
                isTv = isTv && immersive,
                onClick = vm::skipOpening,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = if (isTv && immersive) 140.dp else 56.dp),
            )
        }
        if (immersive && !isTv) {
            PhoneTopControls(
                visible = controlsVisible && !panelOpen,
                ui = ui,
                fullscreen = fullscreen,
                actions = actions,
                onNext = vm::next,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (immersive && isTv) {
            TvOsd(
                visible = !panelOpen && (controlsVisible || !playback.playing),
                ui = ui,
                playback = playback,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun SkipOpeningButton(isTv: Boolean, onClick: () -> Unit, modifier: Modifier) {
    if (isTv) {
        // On TV "OK" skips while this is shown, so it is a hint rather than a focusable button.
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, modifier = modifier) {
            Text(
                stringResource(R.string.skip_opening_tv),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    } else {
        YButton(stringResource(R.string.skip_opening), onClick = onClick, icon = Icons.Filled.SkipNext, modifier = modifier)
    }
}

@Composable
private fun PhoneTopControls(
    visible: Boolean,
    ui: PlayerUi,
    fullscreen: Boolean,
    actions: PlayerScreenActions,
    onNext: () -> Unit,
    modifier: Modifier,
) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White) }
            Text(
                episodeTitle(ui),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext, enabled = ui.hasNext) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.next), tint = if (ui.hasNext) Color.White else Color.Gray)
            }
            IconButton(onClick = actions.onOpenPanel) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.episodes), tint = Color.White) }
            IconButton(onClick = actions.onToggleFullscreen) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    stringResource(R.string.fullscreen),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun episodeTitle(ui: PlayerUi): String {
    val current = ui.current ?: return ui.title
    return listOf(ui.title, stringResource(R.string.episode_n, current.number)).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
private fun TvOsd(visible: Boolean, ui: PlayerUi, playback: Playback, modifier: Modifier) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            Text(ui.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.current?.let { v ->
                Text(
                    listOf(stringResource(R.string.episode_n, v.number), v.data.dubbing, v.data.player).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFCCCCCC),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                if (playback.duration > 0) {
                    LinearProgressIndicator(
                        progress = { (playback.position / playback.duration).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${formatDuration(playback.position)} / ${formatDuration(playback.duration)}", color = Color.White)
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            val hint = if (ui.request?.kind?.controllable == false) R.string.tv_hint_limited else R.string.tv_hint
            Text(stringResource(hint), style = MaterialTheme.typography.labelMedium, color = Color(0xFF9A9AA8))
        }
    }
}

private const val PANEL_HEADER_ITEMS = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodePanel(
    ui: PlayerUi,
    playback: Playback,
    vm: PlayerViewModel,
    isTv: Boolean,
    inOverlay: Boolean,
    fullscreen: Boolean,
    actions: PlayerScreenActions,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val currentFocus = remember { FocusRequester() }
    val current = ui.current

    // On TV, open the panel with the current episode focused.
    LaunchedEffect(inOverlay, isTv) {
        if (inOverlay && isTv) {
            gridState.scrollToItem(PANEL_HEADER_ITEMS + ui.currentIndex.coerceAtLeast(0))
            delay(80)
            runCatching { currentFocus.requestFocus() }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (isTv) 76.dp else 60.dp),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (current != null) {
                    Text(
                        listOf(stringResource(R.string.episode_n, current.number), current.data.dubbing).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                YButton(
                    stringResource(if (fullscreen) R.string.exit_fullscreen else R.string.fullscreen),
                    onClick = actions.onToggleFullscreen,
                    icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    primary = !inOverlay,
                )
                YButton(stringResource(R.string.previous), onClick = vm::previous, icon = Icons.Filled.SkipPrevious, primary = false, enabled = ui.hasPrevious)
                YButton(stringResource(R.string.next), onClick = vm::next, icon = Icons.Filled.SkipNext, primary = false, enabled = ui.hasNext)
                if (ui.request?.kind?.controllable == true) {
                    YButton("10", onClick = { actions.onSeek(-10) }, icon = Icons.Filled.Replay10, primary = false)
                    YButton(
                        stringResource(if (playback.playing) R.string.pause else R.string.play),
                        onClick = actions.onTogglePlay,
                        icon = if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        primary = false,
                    )
                    YButton("10", onClick = { actions.onSeek(10) }, icon = Icons.Filled.Forward10, primary = false)
                }
                if (inOverlay) YButton(stringResource(R.string.close), onClick = actions.onClosePanel, icon = Icons.Filled.Close, primary = false)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PanelLabel(stringResource(R.string.player))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.players.forEach { p -> YChip(p, p == current?.data?.player, onClick = { vm.selectPlayer(p) }) }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PanelLabel(stringResource(R.string.dubbing))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ui.dubbings.forEach { d -> YChip(d, d == current?.data?.dubbing, onClick = { vm.selectDubbing(d) }) }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PanelLabel(stringResource(R.string.episodes_count, ui.episodes.size))
        }
        items(ui.episodes, key = { it.id }) { ep ->
            val selected = ep.id == current?.id
            EpisodeCell(
                video = ep,
                selected = selected,
                focusRequester = if (selected) currentFocus else null,
                onClick = {
                    vm.select(ep)
                    actions.onEpisodeChosen()
                },
            )
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun EpisodeCell(video: Video, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    val finished = video.isFinished()
    Surface(
        onClick = onClick,
        shape = shape,
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            finished -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            finished -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        },
        interactionSource = source,
        modifier = Modifier
            .height(44.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBorder(source, shape, scale = 1.08f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(video.number, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            val duration = video.duration ?: 0.0
            val end = video.watched?.endTime ?: 0.0
            if (duration > 0 && end > 0 && !selected) {
                LinearProgressIndicator(
                    progress = { (end / duration).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}
