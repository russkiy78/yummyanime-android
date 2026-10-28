package com.yani.client.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yani.client.R
import com.yani.client.data.Feed
import com.yani.client.data.Prefs
import com.yani.client.data.YummyApi
import com.yani.client.ui.Routes
import com.yani.client.ui.collections.CollectionCard
import com.yani.client.ui.common.AnimeRow
import com.yani.client.ui.common.LoadContent
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.SectionTitle
import com.yani.client.ui.common.WatchCard
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.dataOrNull
import com.yani.client.ui.common.load
import com.yani.client.ui.common.onAccountChange
import com.yani.client.ui.theme.Accent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val api: YummyApi, prefs: Prefs) : ViewModel() {
    private val _state = MutableStateFlow<Load<Feed>>(Load.Loading)
    val state = _state.asStateFlow()

    init {
        reload()
        // "Continue watching" and recommendations belong to the account.
        onAccountChange(prefs, ::reload)
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = load { api.feed() }
        }
    }

    /** Refresh "continue watching" etc. after returning from the player, without a spinner. Retries after errors. */
    fun refreshQuietly() {
        if (_state.value is Load.Error) return reload()
        if (_state.value !is Load.Ok) return
        viewModelScope.launch { load { api.feed() }.dataOrNull?.let { _state.value = Load.Ok(it) } }
    }
}

@Composable
fun HomeScreen() {
    val vm = appViewModel { HomeViewModel(it.api, it.prefs) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshQuietly() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Yummy", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = Accent)
            Text("Anime", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }
        LoadContent(state, vm::reload) { feed -> HomeContent(feed) }
    }
}

@Composable
private fun HomeContent(feed: Feed) {
    val nav = LocalNav.current
    val isTv = LocalIsTv.current
    val firstFocus = remember { FocusRequester() }
    val hasContinue = feed.lastWatches.isNotEmpty()

    if (isTv) {
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (hasContinue) {
            item("continue") {
                Column {
                    SectionTitle(stringResource(R.string.home_continue)) {
                        TextButton(onClick = { nav.openRoute(Routes.HISTORY) }) { Text(stringResource(R.string.see_all)) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(feed.lastWatches.size) { i ->
                            val item = feed.lastWatches[i]
                            WatchCard(
                                item = item,
                                onClick = { nav.openPlayer(item.id, item.videoId) },
                                modifier = Modifier
                                    .width(if (isTv) 300.dp else 250.dp)
                                    .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                            )
                        }
                    }
                }
            }
        }
        item("top") {
            val top = feed.topCarousel?.items.orEmpty()
            AnimeRow(
                title = stringResource(R.string.home_top_season),
                items = top,
                badge = { item -> "#${top.indexOf(item) + 1}" },
                firstFocus = if (hasContinue) null else firstFocus,
            )
        }
        item("new_videos") {
            val episodeFormat = stringResource(R.string.ep_short)
            AnimeRow(
                title = stringResource(R.string.home_new_episodes),
                items = feed.newVideos,
                subtitle = { it.dubTitle },
                badge = { item -> item.epTitle?.let { episodeFormat.format(it) } },
            )
        }
        item("recommends") { AnimeRow(stringResource(R.string.home_recommended), feed.recommends) }
        item("announcements") { AnimeRow(stringResource(R.string.home_announcements), feed.announcements) }
        item("new") { AnimeRow(stringResource(R.string.home_new), feed.new) }
        if (feed.collections.isNotEmpty()) {
            item("collections") {
                Column {
                    SectionTitle(stringResource(R.string.collections)) {
                        TextButton(onClick = { nav.openRoute(Routes.COLLECTIONS) }) { Text(stringResource(R.string.see_all)) }
                    }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(feed.collections, key = { it.id }) { c ->
                            CollectionCard(c, onClick = { nav.openCollection(c.id) }, modifier = Modifier.width(if (isTv) 300.dp else 250.dp))
                        }
                    }
                }
            }
        }
    }
}
