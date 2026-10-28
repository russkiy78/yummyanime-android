package com.yani.client.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yani.client.R
import com.yani.client.data.AnimeItem
import com.yani.client.data.CatalogQuery
import com.yani.client.data.GenreGroup
import com.yani.client.data.GenresResponse
import com.yani.client.data.Prefs
import com.yani.client.data.YummyApi
import com.yani.client.ui.common.AnimePosterCard
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.YChip
import com.yani.client.ui.common.YIconButton
import com.yani.client.ui.common.appContainer
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.load
import com.yani.client.ui.common.onAccountChange
import com.yani.client.ui.common.posterWidth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

private const val PAGE = 30

data class CatalogState(
    val query: CatalogQuery,
    val items: List<AnimeItem> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
    val endReached: Boolean = false,
)

class CatalogViewModel(private val api: YummyApi, prefs: Prefs, presetGenre: Int?) : ViewModel() {
    private val _state = MutableStateFlow(CatalogState(CatalogQuery(genres = setOfNotNull(presetGenre))))
    val state = _state.asStateFlow()

    private val _genres = MutableStateFlow<GenresResponse?>(null)
    val genres = _genres.asStateFlow()

    private var job: Job? = null

    init {
        reload()
        viewModelScope.launch { _genres.value = runCatching { api.genres() }.getOrNull() }
        // "Hide from my lists" depends on the account's lists.
        onAccountChange(prefs) { if (_state.value.query.excludeLists.isNotEmpty()) reload() }
    }

    fun setText(text: String) {
        _state.update { it.copy(query = it.query.copy(query = text)) }
        reload(debounce = true)
    }

    fun setQuery(query: CatalogQuery) {
        _state.update { it.copy(query = query) }
        reload()
    }

    fun reload(debounce: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(450)
            _state.update { it.copy(items = emptyList(), loading = true, error = null, endReached = false) }
            fetch(0)
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || s.error != null) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            fetch(s.items.size)
        }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        if (_state.value.items.isEmpty()) reload() else loadMore()
    }

    private suspend fun fetch(offset: Int) {
        val query = _state.value.query
        when (val r = load { api.catalog(query, offset, PAGE) }) {
            is Load.Ok -> _state.update {
                it.copy(items = (it.items + r.data).distinctBy { a -> a.id }, loading = false, endReached = r.data.size < PAGE)
            }
            is Load.Error -> _state.update { it.copy(loading = false, error = r.error) }
            Load.Loading -> Unit
        }
    }
}

private data class SortOption(val sort: String, val forward: Boolean, val label: Int)

private val sortOptions = listOf(
    SortOption("top", true, R.string.sort_top),
    SortOption("rating", false, R.string.sort_rating),
    SortOption("views", false, R.string.sort_views),
    SortOption("year", false, R.string.sort_year),
    SortOption("id", false, R.string.sort_added),
    SortOption("title", true, R.string.sort_title),
    SortOption("random", true, R.string.sort_random),
)

@Composable
fun CatalogScreen(presetGenre: Int?, presetTitle: String?) {
    val vm = appViewModel(key = "catalog-$presetGenre") { CatalogViewModel(it.api, it.prefs, presetGenre) }
    val prefs = appContainer().prefs
    val state by vm.state.collectAsStateWithLifecycle()
    val genres by vm.genres.collectAsStateWithLifecycle()
    val session by prefs.session.collectAsStateWithLifecycle()
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize()) {
        if (presetTitle != null) AppTopBar(presetTitle)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query.query,
                onValueChange = vm::setText,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (state.query.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setText("") }) { Icon(Icons.Filled.Clear, stringResource(R.string.clear)) }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            )
            Box {
                YIconButton(onClick = { showSort = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort))
                }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    sortOptions.forEach { opt ->
                        val selected = state.query.sort == opt.sort
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(opt.label),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                showSort = false
                                vm.setQuery(state.query.copy(sort = opt.sort, sortForward = opt.forward))
                            },
                        )
                    }
                }
            }
            YIconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = {
                    val count = state.query.activeFilters
                    if (count > 0) Badge { Text(count.toString()) }
                }) {
                    Icon(Icons.Filled.FilterList, stringResource(R.string.filters))
                }
            }
        }
        CatalogGrid(state, onLoadMore = vm::loadMore, onRetry = vm::retry)
    }

    if (showFilters) {
        FilterDialog(
            initial = state.query,
            genres = genres,
            loggedIn = session != null,
            onApply = {
                showFilters = false
                vm.setQuery(it)
            },
            onDismiss = { showFilters = false },
        )
    }
}

@Composable
private fun CatalogGrid(state: CatalogState, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, state.items.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 10
        }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }
    when {
        state.items.isEmpty() && state.error != null -> ErrorBox(state.error, onRetry)
        state.items.isEmpty() && !state.loading -> MessageBox(stringResource(R.string.nothing_found), Icons.Filled.SearchOff)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(posterWidth()),
            state = gridState,
            contentPadding = PaddingValues(ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.id }) { AnimePosterCard(it) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.error != null -> TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
            }
        }
    }
}

private val typeOptions = listOf(
    "tv" to R.string.type_tv,
    "movie" to R.string.type_movie,
    "ova" to R.string.type_ova,
    "ona" to R.string.type_ona,
    "special" to R.string.type_special,
    "shorttv" to R.string.type_short_tv,
    "shortfilm" to R.string.type_short_film,
)

private val statusOptions = listOf(
    "ongoing" to R.string.status_ongoing,
    "released" to R.string.status_released,
    "announcement" to R.string.status_announcement,
)

private val translateOptions = listOf(
    "dubbing" to R.string.translate_dubbing,
    "multivoice" to R.string.translate_multivoice,
    "duet" to R.string.translate_duet,
    "single" to R.string.translate_single,
    "subtitles" to R.string.translate_subtitles,
)

private val excludeOptions = listOf(
    "watched" to R.string.exclude_completed,
    "lost" to R.string.exclude_dropped,
    "will" to R.string.exclude_planned,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterDialog(
    initial: CatalogQuery,
    genres: GenresResponse?,
    loggedIn: Boolean,
    onApply: (CatalogQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(0.94f)
                .fillMaxSize(0.92f),
        ) {
            Column {
                Text(
                    stringResource(R.string.filters),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { ChipSection(R.string.filter_type, typeOptions, q.types) { q = q.copy(types = it) } }
                    item { ChipSection(R.string.filter_status, statusOptions, q.statuses) { q = q.copy(statuses = it) } }
                    item { ChipSection(R.string.filter_translate, translateOptions, q.translates) { q = q.copy(translates = it) } }
                    item {
                        FilterLabel(R.string.filter_years)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            YearStepper(stringResource(R.string.year_from), q.fromYear, default = 2015) { q = q.copy(fromYear = it) }
                            Spacer(Modifier.width(24.dp))
                            YearStepper(stringResource(R.string.year_to), q.toYear, default = Calendar.getInstance().get(Calendar.YEAR)) {
                                q = q.copy(toYear = it)
                            }
                        }
                    }
                    if (loggedIn) {
                        item { ChipSection(R.string.filter_exclude, excludeOptions, q.excludeLists) { q = q.copy(excludeLists = it) } }
                    }
                    if (genres != null) {
                        val groups = genres.groups.ifEmpty { listOf(GenreGroup(0, "")) }
                        groups.forEach { group ->
                            val inGroup = genres.genres.filter { genres.groups.isEmpty() || it.groupId == group.id }
                            if (inGroup.isNotEmpty()) {
                                item("genres-${group.id}") {
                                    Column {
                                        Text(
                                            group.title.ifBlank { stringResource(R.string.filter_genres) },
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            inGroup.forEach { g ->
                                                YChip(g.title, g.value in q.genres, onClick = {
                                                    q = q.copy(genres = if (g.value in q.genres) q.genres - g.value else q.genres + g.value)
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { q = CatalogQuery(query = initial.query, sort = initial.sort, sortForward = initial.sortForward) }) {
                        Text(stringResource(R.string.reset))
                    }
                    Spacer(Modifier.width(8.dp))
                    YButton(stringResource(R.string.apply), onClick = { onApply(q) })
                }
            }
        }
    }
}

@Composable
private fun FilterLabel(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(label: Int, options: List<Pair<String, Int>>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
    Column {
        FilterLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, text) ->
                YChip(stringResource(text), value in selected, onClick = {
                    onChange(if (value in selected) selected - value else selected + value)
                })
            }
        }
    }
}

@Composable
private fun YearStepper(label: String, value: Int?, default: Int, onChange: (Int?) -> Unit) {
    val maxYear = Calendar.getInstance().get(Calendar.YEAR) + 2
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(((value ?: default) - 1).coerceAtLeast(1960)) }) { Icon(Icons.Filled.Remove, "-") }
            TextButton(onClick = { onChange(null) }) {
                Text(value?.toString() ?: stringResource(R.string.any), modifier = Modifier.widthIn(min = 48.dp))
            }
            IconButton(onClick = { onChange(((value ?: default) + 1).coerceAtMost(maxYear)) }) { Icon(Icons.Filled.Add, "+") }
        }
        Spacer(Modifier.height(4.dp))
    }
}
