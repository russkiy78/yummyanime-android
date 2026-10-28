package com.yani.client.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.data.CollectionItem
import com.yani.client.data.YummyApi
import com.yani.client.ui.common.AnimePosterCard
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.LoadContent
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YChip
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.focusBorder
import com.yani.client.ui.common.load
import com.yani.client.ui.common.posterWidth
import com.yani.client.util.stripHtml
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Composable
fun CollectionCard(collection: CollectionItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Card(
        onClick = onClick,
        shape = shape,
        interactionSource = source,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.focusBorder(source, shape, scale = 1.03f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 7f),
        ) {
            val posters = collection.posterPreviews.take(3)
            if (posters.isEmpty()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
            posters.forEach { p ->
                AsyncImage(
                    model = p.card,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(collection.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    collection.owner?.nickname.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ThumbUp, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" ${collection.likes?.likes ?: 0}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

data class CollectionsState(
    val sort: String = "best",
    val items: List<CollectionItem> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
    val endReached: Boolean = false,
)

class CollectionsViewModel(private val api: YummyApi) : ViewModel() {
    private val _state = MutableStateFlow(CollectionsState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        loadMore()
    }

    fun setSort(sort: String) {
        job?.cancel()
        _state.value = CollectionsState(sort = sort)
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { api.collections(s.sort, s.items.size) }
                .onSuccess { page ->
                    _state.update { it.copy(items = (it.items + page).distinctBy { c -> c.id }, loading = false, endReached = page.size < 24) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e) } }
        }
    }
}

@Composable
fun CollectionsScreen() {
    val vm = appViewModel { CollectionsViewModel(it.api) }
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
        AppTopBar(stringResource(R.string.collections))
        Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            YChip(stringResource(R.string.sort_best), state.sort == "best", onClick = { vm.setSort("best") })
            YChip(stringResource(R.string.sort_new), state.sort == "new", onClick = { vm.setSort("new") })
        }
        when {
            state.items.isEmpty() && state.error != null -> ErrorBox(state.error!!, vm::loadMore)
            state.items.isEmpty() && state.endReached -> MessageBox(stringResource(R.string.nothing_found), Icons.Filled.Collections)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(260.dp),
                state = gridState,
                contentPadding = PaddingValues(ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.id }) { c -> CollectionCard(c, onClick = { nav.openCollection(c.id) }) }
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

class CollectionViewModel(private val api: YummyApi, private val id: Int) : ViewModel() {
    private val _state = MutableStateFlow<Load<CollectionItem>>(Load.Loading)
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = load { api.collection(id) }
        }
    }
}

@Composable
fun CollectionScreen(id: Int) {
    val vm = appViewModel(key = "collection-$id") { CollectionViewModel(it.api, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        AppTopBar((state as? Load.Ok)?.data?.title.orEmpty())
        LoadContent(state, vm::reload) { c ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(posterWidth()),
                contentPadding = PaddingValues(ScreenPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text(
                            stringResource(R.string.collection_by, c.owner?.nickname.orEmpty()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val description = stripHtml(c.description)
                        if (description.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
                        }
                    }
                }
                items(c.animes, key = { it.id }) { AnimePosterCard(it) }
            }
        }
    }
}
