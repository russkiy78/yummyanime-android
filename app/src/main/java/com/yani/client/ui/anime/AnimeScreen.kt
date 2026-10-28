package com.yani.client.ui.anime

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.data.AnimeDetails
import com.yani.client.data.AnimeItem
import com.yani.client.data.continueTarget
import com.yani.client.data.Prefs
import com.yani.client.data.UserList
import com.yani.client.data.Video
import com.yani.client.data.YummyApi
import com.yani.client.data.absUrl
import com.yani.client.ui.Routes
import com.yani.client.ui.common.AnimeRow
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.LoadContent
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.SectionTitle
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.YChip
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.load
import com.yani.client.ui.common.dataOrNull
import com.yani.client.ui.theme.Gold
import com.yani.client.util.currentLocale
import com.yani.client.util.formatDate
import com.yani.client.util.formatDuration
import com.yani.client.util.formatRating
import com.yani.client.util.stripHtml
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UserState(val listId: Int?, val favorite: Boolean, val rating: Int)

class AnimeViewModel(
    private val api: YummyApi,
    private val prefs: Prefs,
    private val idOrAlias: String,
) : ViewModel() {
    private val _details = MutableStateFlow<Load<AnimeDetails>>(Load.Loading)
    val details = _details.asStateFlow()

    private val _videos = MutableStateFlow<List<Video>?>(null)
    val videos = _videos.asStateFlow()

    private val _similar = MutableStateFlow<List<AnimeItem>>(emptyList())
    val similar = _similar.asStateFlow()

    private val _user = MutableStateFlow(UserState(null, false, 0))
    val user = _user.asStateFlow()

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()

    val session = prefs.session

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _details.value = Load.Loading
            val result = load { api.anime(idOrAlias) }
            _details.value = result
            val anime = result.dataOrNull ?: return@launch
            _user.value = UserState(
                listId = anime.user?.list?.list?.id?.takeIf { it >= 0 },
                favorite = anime.user?.list?.isFav == true,
                rating = anime.user?.rating?.toInt() ?: 0,
            )
            launch { _similar.value = runCatching { api.recommendations(anime.id) }.getOrDefault(emptyList()) }
            refreshVideos()
        }
    }

    /** Videos carry per-episode watch progress, refresh them when coming back from the player. */
    fun refreshVideos() {
        if (_details.value is Load.Error) return reload()
        val id = _details.value.dataOrNull?.id ?: return
        viewModelScope.launch { runCatching { api.videos(id) }.onSuccess { _videos.value = it } }
    }

    fun setList(list: UserList?) = act {
        if (list == null) api.removeFromList(it) else api.setList(it, list)
        _user.update { u -> u.copy(listId = list?.id) }
    }

    fun toggleFavorite() = act {
        val target = !_user.value.favorite
        api.setFavorite(it, target)
        _user.update { u -> u.copy(favorite = target) }
    }

    fun rate(value: Int) = act {
        if (value <= 0) api.deleteRating(it) else api.rate(it, value)
        _user.update { u -> u.copy(rating = value) }
    }

    private fun act(block: suspend (animeId: Int) -> Unit) {
        val id = _details.value.dataOrNull?.id ?: return
        viewModelScope.launch {
            runCatching { block(id) }.onFailure { _errors.tryEmit(it) }
        }
    }
}

val UserList.label: Int
    get() = when (this) {
        UserList.WATCHING -> R.string.list_watching
        UserList.PLANNED -> R.string.list_planned
        UserList.COMPLETED -> R.string.list_completed
        UserList.DROPPED -> R.string.list_dropped
        UserList.ON_HOLD -> R.string.list_on_hold
    }

@Composable
fun AnimeScreen(idOrAlias: String) {
    val vm = appViewModel(key = "anime-$idOrAlias") { AnimeViewModel(it.api, it.prefs, idOrAlias) }
    val details by vm.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val errorText = stringResource(R.string.action_failed)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshVideos() }
    LaunchedEffect(vm) {
        vm.errors.collect { Toast.makeText(context, "$errorText: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(details.dataOrNull?.title.orEmpty())
        LoadContent(details, vm::reload) { anime -> AnimeContent(anime, vm) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeContent(anime: AnimeDetails, vm: AnimeViewModel) {
    val nav = LocalNav.current
    val isTv = LocalIsTv.current
    val locale = currentLocale()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val similar by vm.similar.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    var showListDialog by remember { mutableStateOf(false) }
    var showRateDialog by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val watchFocus = remember { FocusRequester() }

    if (isTv) LaunchedEffect(Unit) { runCatching { watchFocus.requestFocus() } }

    val requireLogin: (() -> Unit) -> Unit = { action ->
        if (session == null) nav.openRoute(Routes.LOGIN) else action()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item("header") {
            Box(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = anime.poster?.large,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.25f,
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.background),
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
                )
                Row(Modifier.padding(ScreenPadding)) {
                    AsyncImage(
                        model = anime.poster?.large,
                        contentDescription = anime.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(if (isTv) 200.dp else 130.dp)
                            .aspectRatio(0.7f)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(anime.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        anime.otherTitles.firstOrNull()?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            listOfNotNull(anime.type?.name, anime.year?.takeIf { it > 0 }?.toString(), anime.status?.title, anime.minAge?.title)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        RatingLine(anime)
                        if (isTv) {
                            Spacer(Modifier.height(16.dp))
                            ActionButtons(anime, videos, user, watchFocus, requireLogin, { showListDialog = true }, { showRateDialog = true }, vm)
                        }
                    }
                }
            }
        }
        if (!isTv) {
            item("actions") {
                Box(Modifier.padding(horizontal = ScreenPadding)) {
                    ActionButtons(anime, videos, user, watchFocus, requireLogin, { showListDialog = true }, { showRateDialog = true }, vm)
                }
            }
        }
        item("facts") {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                val episodes = anime.episodes
                if (episodes != null && ((episodes.count ?: 0) > 0 || (episodes.aired ?: 0) > 0)) {
                    Fact(stringResource(R.string.episodes), "${episodes.aired ?: 0} / ${episodes.count?.takeIf { it > 0 } ?: "?"}")
                }
                val next = episodes?.nextDate ?: 0
                if (next * 1000 > System.currentTimeMillis()) Fact(stringResource(R.string.next_episode), formatDate(next, locale))
                if (anime.studios.isNotEmpty()) Fact(stringResource(R.string.studio), anime.studios.joinToString { it.title })
                if (anime.creators.isNotEmpty()) Fact(stringResource(R.string.director), anime.creators.joinToString { it.title })
                anime.original?.takeIf { it.isNotBlank() }?.let { Fact(stringResource(R.string.source), it) }
                if (anime.translates.isNotEmpty()) Fact(stringResource(R.string.translations), anime.translates.joinToString { it.title })
                anime.top?.global?.takeIf { it > 0 }?.let { Fact(stringResource(R.string.top_place), "#$it") }
            }
        }
        if (anime.genres.isNotEmpty()) {
            item("genres") {
                FlowRow(
                    Modifier.padding(horizontal = ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    anime.genres.forEach { g -> YChip(g.title, false, onClick = { nav.openCatalog(g.genreId, g.title) }) }
                }
            }
        }
        val description = stripHtml(anime.description)
        if (description.isNotEmpty()) {
            item("description") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = ScreenPadding, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.description), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item("comments") {
            Box(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)) {
                YButton(
                    stringResource(R.string.comments_count, anime.commentsCount ?: 0),
                    onClick = { nav.openComments(anime.id, anime.title) },
                    icon = Icons.AutoMirrored.Filled.Comment,
                    primary = false,
                )
            }
        }
        if (anime.viewingOrder.size > 1) {
            item("order") {
                AnimeRow(
                    title = stringResource(R.string.viewing_order),
                    items = anime.viewingOrder,
                    subtitle = { listOfNotNull(it.data?.text, it.year?.toString()).joinToString(" · ") },
                    badge = { if (it.id == anime.id) "▶" else null },
                )
            }
        }
        if (anime.screenshots.isNotEmpty()) {
            item("screens") {
                Column {
                    SectionTitle(stringResource(R.string.screenshots))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(anime.screenshots) { s ->
                            AsyncImage(
                                model = absUrl(s.sizes?.small),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(if (isTv) 260.dp else 220.dp)
                                    .aspectRatio(16f / 9f)
                                    .clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }
        }
        item("similar") { AnimeRow(stringResource(R.string.similar), similar) }
    }

    if (showListDialog) {
        ListDialog(
            current = UserList.byId(user.listId),
            onSelect = {
                showListDialog = false
                vm.setList(it)
            },
            onDismiss = { showListDialog = false },
        )
    }
    if (showRateDialog) {
        RateDialog(
            current = user.rating,
            onRate = {
                showRateDialog = false
                vm.rate(it)
            },
            onDismiss = { showRateDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(
    anime: AnimeDetails,
    videos: List<Video>?,
    user: UserState,
    watchFocus: FocusRequester,
    requireLogin: (() -> Unit) -> Unit,
    onList: () -> Unit,
    onRate: () -> Unit,
    vm: AnimeViewModel,
) {
    val nav = LocalNav.current
    val target = remember(videos) { videos?.let(::continueTarget) }
    val noVideos = videos != null && videos.isEmpty()
    val watchLabel = when {
        noVideos -> stringResource(R.string.no_videos)
        target == null -> stringResource(R.string.watch)
        target.resume -> stringResource(
            R.string.continue_episode,
            target.video.number,
            formatDuration(target.video.watched?.endTime),
        )
        else -> stringResource(R.string.watch_episode, target.video.number)
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        YButton(
            watchLabel,
            onClick = { nav.openPlayer(anime.id, target?.video?.id) },
            icon = Icons.Filled.PlayArrow,
            enabled = !noVideos,
            modifier = Modifier.focusRequester(watchFocus),
        )
        val list = UserList.byId(user.listId)
        YButton(
            if (list != null) stringResource(list.label) else stringResource(R.string.add_to_list),
            onClick = { requireLogin(onList) },
            icon = if (list != null) Icons.Filled.BookmarkAdded else Icons.Filled.BookmarkAdd,
            primary = false,
        )
        YButton(
            stringResource(if (user.favorite) R.string.in_favorites else R.string.favorite),
            onClick = { requireLogin { vm.toggleFavorite() } },
            icon = if (user.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            primary = false,
        )
        YButton(
            if (user.rating > 0) stringResource(R.string.my_rating, user.rating) else stringResource(R.string.rate),
            onClick = { requireLogin(onRate) },
            icon = if (user.rating > 0) Icons.Filled.Star else Icons.Filled.StarBorder,
            primary = false,
        )
    }
}

@Composable
private fun RatingLine(anime: AnimeDetails) {
    val r = anime.rating ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        formatRating(r.average)?.let {
            Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            r.counters?.let { c ->
                Text(" ($c)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val external = listOfNotNull(
        formatRating(r.myAnimeList)?.let { "MAL $it" },
        formatRating(r.shikimori)?.let { "Shiki $it" },
        formatRating(r.kinopoisk)?.let { "KP $it" },
    )
    if (external.isNotEmpty()) {
        Text(external.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ListDialog(current: UserList?, onSelect: (UserList?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_list)) },
        text = {
            Column {
                UserList.entries.forEach { list ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = list == current, role = Role.RadioButton, onClick = { onSelect(list) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = list == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(list.label))
                    }
                }
            }
        },
        confirmButton = {
            if (current != null) TextButton(onClick = { onSelect(null) }) { Text(stringResource(R.string.remove_from_list)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RateDialog(current: Int, onRate: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rate)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..10).forEach { n -> YChip(n.toString(), n == current, onClick = { onRate(n) }) }
            }
        },
        confirmButton = {
            if (current > 0) TextButton(onClick = { onRate(0) }) { Text(stringResource(R.string.remove_rating)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
