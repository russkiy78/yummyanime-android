package com.yani.client.ui.lists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yani.client.R
import com.yani.client.data.AnimeItem
import com.yani.client.data.Prefs
import com.yani.client.data.UserList
import com.yani.client.data.YummyApi
import com.yani.client.ui.Routes
import com.yani.client.ui.anime.label
import com.yani.client.ui.common.AnimePosterCard
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.LoadContent
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.WatchCard
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.dataOrNull
import com.yani.client.ui.common.load
import com.yani.client.ui.common.onAccountChange
import com.yani.client.ui.common.posterWidth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ListsViewModel(private val api: YummyApi, private val prefs: Prefs) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<AnimeItem>>>(Load.Loading)
    val state = _state.asStateFlow()
    val session = prefs.session
    private var loadedFor: Int? = null

    fun ensureLoaded(quiet: Boolean) {
        val userId = prefs.session.value?.userId ?: return
        if (loadedFor != userId || _state.value is Load.Error) {
            loadedFor = userId
            reload()
        } else if (quiet && _state.value is Load.Ok) {
            viewModelScope.launch { load { api.userLists(userId) }.dataOrNull?.let { _state.value = Load.Ok(it) } }
        }
    }

    fun reload() {
        val userId = prefs.session.value?.userId ?: return
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = load { api.userLists(userId) }
        }
    }
}

/** Tabs: the five user lists plus favorites (index 5). */
private val tabs: List<UserList?> = listOf(
    UserList.WATCHING, UserList.PLANNED, UserList.COMPLETED, UserList.ON_HOLD, UserList.DROPPED, null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen() {
    val vm = appViewModel { ListsViewModel(it.api, it.prefs) }
    val session by vm.session.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.ensureLoaded(quiet = true) }
    LaunchedEffect(session?.userId) { vm.ensureLoaded(quiet = false) }

    if (session == null) {
        MessageBox(stringResource(R.string.login_for_lists), Icons.Filled.Bookmarks) {
            YButton(stringResource(R.string.login), onClick = { nav.openRoute(Routes.LOGIN) })
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        val all = state.dataOrNull.orEmpty()
        PrimaryScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = ScreenPadding,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            tabs.forEachIndexed { index, list ->
                val count = all.count { if (list == null) it.isFavorite else it.listId == list.id }
                val title = if (list == null) stringResource(R.string.favorites) else stringResource(list.label)
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(if (state is Load.Ok) "$title ($count)" else title) },
                )
            }
        }
        LoadContent(state, vm::reload) { items ->
            val list = tabs[tab]
            val filtered = remember(items, tab) {
                items.filter { if (list == null) it.isFavorite else it.listId == list.id }
                    .sortedByDescending { it.date ?: 0 }
            }
            if (filtered.isEmpty()) {
                MessageBox(stringResource(R.string.list_empty), Icons.Filled.Inbox)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(posterWidth()),
                    contentPadding = PaddingValues(ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filtered, key = { it.id }) { AnimePosterCard(it) }
                }
            }
        }
    }
}

data class HistoryState(
    val items: List<AnimeItem> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
    val endReached: Boolean = false,
)

class HistoryViewModel(private val api: YummyApi, prefs: Prefs) : ViewModel() {
    private val _state = MutableStateFlow(HistoryState())
    val state = _state.asStateFlow()

    init {
        loadMore()
        onAccountChange(prefs, ::reload)
    }

    fun reload() {
        _state.value = HistoryState()
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { api.watchHistory(s.items.size, 30) }
                .onSuccess { page -> _state.update { it.copy(items = it.items + page, loading = false, endReached = page.size < 30) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e) } }
        }
    }
}

@Composable
fun HistoryScreen() {
    val vm = appViewModel { HistoryViewModel(it.api, it.prefs) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, state.items.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.history))
        when {
            state.items.isEmpty() && state.error != null -> ErrorBox(state.error!!, vm::reload)
            state.items.isEmpty() && state.endReached -> MessageBox(stringResource(R.string.history_empty), Icons.Filled.History)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                state = gridState,
                contentPadding = PaddingValues(ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items.size) { i ->
                    val item = state.items[i]
                    WatchCard(item, onClick = { nav.openPlayer(item.id, item.videoId) })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.loading) CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
