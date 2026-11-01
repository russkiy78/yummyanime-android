package com.yani.client.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.offline.Download
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.download.CaptureActivity
import com.yani.client.download.EpisodeInfo
import com.yani.client.download.OfflineEpisode
import com.yani.client.download.OfflinePlayerActivity
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YIconButton
import com.yani.client.ui.common.appContainer
import com.yani.client.ui.common.focusBorder

@Composable
fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Downloaded episodes by anime. Works without a connection: everything shown is stored with the downloads. */
@Composable
fun DownloadsScreen() {
    val downloads = appContainer().downloads
    val episodes by downloads.episodes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val nav = LocalNav.current
    var confirmDelete by remember { mutableStateOf<EpisodeInfo?>(null) }
    val groups = remember(episodes) { episodes.groupBy { it.info.animeId }.values.toList() }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.downloads))
        if (episodes.isEmpty()) {
            MessageBox(stringResource(R.string.downloads_empty), Icons.Filled.DownloadForOffline)
            return@Column
        }
        LazyColumn(
            Modifier.widthIn(max = 840.dp),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("storage") {
                val free = remember(episodes.size) { downloads.freeBytes() }
                Text(
                    stringResource(R.string.downloads_storage, formatBytes(episodes.sumOf { it.bytes }), formatBytes(free)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groups.forEach { group ->
                val info = group.first().info
                item("anime-${info.animeId}") {
                    AnimeHeader(info, group, onOpen = { nav.openAnime(info.animeId.toString()) }, onDeleteAll = { confirmDelete = info })
                }
                items(group, key = { it.id }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onPlay = { context.startActivity(OfflinePlayerActivity.intent(context, episode.id)) },
                        // A failed download usually means the stream link expired: a fresh one continues it where it stopped.
                        onRetry = { context.startActivity(CaptureActivity.intent(context, episode.info.animeId, listOf(episode.info.videoId))) },
                        onDelete = { downloads.remove(episode.id) },
                    )
                }
            }
        }
    }

    confirmDelete?.let { info ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.download_delete_all)) },
            text = { Text(stringResource(R.string.download_delete_all_confirm, info.animeTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    downloads.removeAnime(info.animeId)
                }) { Text(stringResource(R.string.download_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AnimeHeader(info: EpisodeInfo, episodes: List<OfflineEpisode>, onOpen: () -> Unit, onDeleteAll: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Card(
            onClick = onOpen,
            shape = shape,
            interactionSource = source,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            modifier = Modifier
                .weight(1f)
                .focusBorder(source, shape, scale = 1.02f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = info.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(48.dp)
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(info.animeTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.downloads_anime_summary, episodes.size, formatBytes(episodes.sumOf { it.bytes })),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        YIconButton(onClick = onDeleteAll) { Icon(Icons.Filled.DeleteSweep, stringResource(R.string.download_delete_all)) }
    }
}

@Composable
private fun EpisodeRow(episode: OfflineEpisode, onPlay: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.episode_n, episode.info.number), style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(episode.info.dubbing, episode.info.player).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    downloadStateText(episode),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (episode.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (episode.state == Download.STATE_DOWNLOADING) {
                    Spacer(Modifier.height(6.dp))
                    val progress = episode.progress
                    val modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 12.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = modifier) else LinearProgressIndicator(modifier)
                }
            }
            if (episode.completed) {
                YIconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), tint = MaterialTheme.colorScheme.primary) }
            }
            if (episode.failed) {
                YIconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, stringResource(if (episode.bytes > 0) R.string.download_continue else R.string.retry))
                }
            }
            YIconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.download_delete)) }
        }
    }
}

@Composable
fun downloadStateText(episode: OfflineEpisode): String {
    val size = formatBytes(episode.bytes)
    return when (episode.state) {
        Download.STATE_COMPLETED -> stringResource(R.string.download_state_done, size)
        Download.STATE_DOWNLOADING -> episode.progress
            ?.let { stringResource(R.string.download_state_downloading_percent, (it * 100).toInt(), size) }
            ?: stringResource(R.string.download_state_downloading, size)
        Download.STATE_FAILED -> stringResource(R.string.download_state_failed)
        Download.STATE_STOPPED -> stringResource(R.string.download_state_stopped)
        Download.STATE_REMOVING -> stringResource(R.string.download_state_removing)
        else -> stringResource(R.string.download_state_queued)
    }
}
