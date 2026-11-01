package com.yani.client.ui.downloads

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yani.client.R
import com.yani.client.data.Prefs
import com.yani.client.data.Video
import com.yani.client.data.YummyApi
import com.yani.client.data.defaultVideo
import com.yani.client.data.dubbingsFor
import com.yani.client.data.episodesFor
import com.yani.client.data.isFinished
import com.yani.client.data.orderedPlayers
import com.yani.client.data.pickDubbing
import com.yani.client.download.CaptureActivity
import com.yani.client.download.OfflineDownloads
import com.yani.client.ui.Routes
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.LoadContent
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.YChip
import com.yani.client.ui.common.appContainer
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.focusBorder
import com.yani.client.ui.common.load
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadChoice(
    val title: String,
    val videos: List<Video>,
    val player: String,
    val dubbing: String,
    val selected: Set<Long>,
    val preferredPlayer: String,
) {
    val players: List<String> get() = orderedPlayers(videos, preferredPlayer)
    val dubbings: List<String> get() = dubbingsFor(videos, player)
    val episodes: List<Video> get() = episodesFor(videos, player, dubbing)
}

class DownloadSelectViewModel(
    private val api: YummyApi,
    private val prefs: Prefs,
    private val downloads: OfflineDownloads,
    private val animeId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow<Load<DownloadChoice>>(Load.Loading)
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = load {
                val title = runCatching { api.anime(animeId.toString()).title }.getOrDefault("")
                val videos = api.videos(animeId)
                val settings = prefs.settings.value
                val start = defaultVideo(videos, settings)
                val choice = DownloadChoice(title, videos, start?.data?.player.orEmpty(), start?.data?.dubbing.orEmpty(), emptySet(), settings.preferredPlayer)
                choice.copy(selected = defaultSelection(choice.episodes))
            }
        }
    }

    /** What is still to watch and not downloaded yet: the usual "for the flight" choice. */
    private fun defaultSelection(episodes: List<Video>): Set<Long> =
        episodes.filter { !it.isFinished() && !isDownloaded(it) }.map { it.id }.toSet()

    fun isDownloaded(video: Video): Boolean = downloads.forVideo(video.id)?.failed == false

    fun selectPlayer(player: String) = change { c ->
        val dubbing = pickDubbing(dubbingsFor(c.videos, player), c.dubbing)
        c.copy(player = player, dubbing = dubbing).let { it.copy(selected = defaultSelection(it.episodes)) }
    }

    fun selectDubbing(dubbing: String) = change { c ->
        c.copy(dubbing = dubbing).let { it.copy(selected = defaultSelection(it.episodes)) }
    }

    fun toggle(video: Video) = change { c ->
        if (isDownloaded(video)) c else c.copy(selected = if (video.id in c.selected) c.selected - video.id else c.selected + video.id)
    }

    fun selectAll() = change { c -> c.copy(selected = c.episodes.filterNot(::isDownloaded).map { it.id }.toSet()) }

    fun selectNone() = change { it.copy(selected = emptySet()) }

    private fun change(transform: (DownloadChoice) -> DownloadChoice) =
        _state.update { s -> if (s is Load.Ok) Load.Ok(transform(s.data)) else s }
}

@Composable
fun DownloadSelectScreen(animeId: Int) {
    val vm = appViewModel(key = "download-$animeId") { DownloadSelectViewModel(it.api, it.prefs, it.downloads, animeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.download_episodes)) {
            TextButton(onClick = { nav.openRoute(Routes.DOWNLOADS) }) { Text(stringResource(R.string.downloads)) }
        }
        LoadContent(state, vm::reload) { choice -> DownloadSelectContent(animeId, choice, vm) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadSelectContent(animeId: Int, choice: DownloadChoice, vm: DownloadSelectViewModel) {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    // Recomposes when a download is added or removed, so the marks stay current.
    val downloaded by appContainer().downloads.episodes.collectAsStateWithLifecycle()
    val downloadedIds = remember(downloaded) { downloaded.filterNot { it.failed }.map { it.info.videoId }.toSet() }
    val episodes = choice.episodes
    val selected = episodes.filter { it.id in choice.selected && it.id !in downloadedIds }

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (isTv) 76.dp else 60.dp),
            contentPadding = PaddingValues(ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(choice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.download_select_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Label(stringResource(R.string.player))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        choice.players.forEach { p -> YChip(p, p == choice.player, onClick = { vm.selectPlayer(p) }) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Label(stringResource(R.string.dubbing))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        choice.dubbings.forEach { d -> YChip(d, d == choice.dubbing, onClick = { vm.selectDubbing(d) }) }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Label(stringResource(R.string.episodes_count, episodes.size)) }
                    TextButton(onClick = vm::selectAll) { Text(stringResource(R.string.select_all)) }
                    TextButton(onClick = vm::selectNone) { Text(stringResource(R.string.select_none)) }
                }
            }
            items(episodes, key = { it.id }) { video ->
                SelectCell(
                    video = video,
                    selected = video.id in choice.selected,
                    downloaded = video.id in downloadedIds,
                    onClick = { vm.toggle(video) },
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
                YButton(
                    stringResource(R.string.download_selected, selected.size),
                    onClick = {
                        context.startActivity(CaptureActivity.intent(context, animeId, selected.map { it.id }))
                        vm.selectNone()
                    },
                    icon = Icons.Filled.Download,
                    enabled = selected.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SelectCell(video: Video, selected: Boolean, downloaded: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        enabled = !downloaded,
        shape = shape,
        color = when {
            selected -> MaterialTheme.colorScheme.primary
            downloaded -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        interactionSource = source,
        modifier = Modifier
            .height(44.dp)
            .focusBorder(source, shape, scale = 1.08f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(video.number, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            if (downloaded) {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(12.dp),
                )
            }
        }
    }
}
