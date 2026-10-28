package com.yani.client.ui.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.data.Comment
import com.yani.client.data.Prefs
import com.yani.client.data.YummyApi
import com.yani.client.data.absUrl
import com.yani.client.ui.Routes
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.YChip
import com.yani.client.ui.common.appViewModel
import com.yani.client.util.currentLocale
import com.yani.client.util.formatDateTime
import com.yani.client.util.stripHtml
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE = 20

data class CommentsState(
    val sort: String = "new",
    val items: List<Comment> = emptyList(),
    val replies: Map<Long, List<Comment>> = emptyMap(),
    val loading: Boolean = false,
    val error: Throwable? = null,
    val endReached: Boolean = false,
    val posting: Boolean = false,
    val postError: Throwable? = null,
)

class CommentsViewModel(private val api: YummyApi, prefs: Prefs, private val animeId: Int) : ViewModel() {
    private val _state = MutableStateFlow(CommentsState())
    val state = _state.asStateFlow()
    val session = prefs.session
    private var job: Job? = null

    init {
        reload()
    }

    fun setSort(sort: String) {
        if (sort == _state.value.sort) return
        _state.update { CommentsState(sort = sort) }
        reload()
    }

    fun reload() {
        job?.cancel()
        _state.update { it.copy(items = emptyList(), endReached = false, error = null) }
        loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { api.comments(animeId, s.sort, s.items.size, PAGE) }
                .onSuccess { page ->
                    _state.update { it.copy(items = (it.items + page).distinctBy { c -> c.id }, loading = false, endReached = page.size < PAGE) }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e) } }
        }
    }

    fun loadReplies(id: Long) {
        viewModelScope.launch {
            runCatching { api.commentChildren(id) }.onSuccess { list ->
                _state.update { it.copy(replies = it.replies + (id to list)) }
            }
        }
    }

    fun post(text: String, onDone: () -> Unit) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(posting = true, postError = null) }
            runCatching { api.postComment(animeId, text.trim(), null) }
                .onSuccess { c ->
                    _state.update { it.copy(posting = false, items = listOf(c) + it.items) }
                    onDone()
                }
                .onFailure { e -> _state.update { it.copy(posting = false, postError = e) } }
        }
    }
}

private val sorts = listOf("new" to R.string.sort_new, "nice" to R.string.sort_best, "old" to R.string.sort_old)

@Composable
fun CommentsScreen(animeId: Int, title: String) {
    val vm = appViewModel(key = "comments-$animeId") { CommentsViewModel(it.api, it.prefs, animeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(listState, state.items.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.comments) + if (title.isNotBlank()) " · $title" else "")
        Row(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sorts.forEach { (key, label) -> YChip(stringResource(label), state.sort == key, onClick = { vm.setSort(key) }) }
        }
        if (session != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.write_comment)) },
                    maxLines = 4,
                    isError = state.postError != null,
                )
                if (state.posting) {
                    CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                } else {
                    IconButton(onClick = { vm.post(text) { text = "" } }, enabled = text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                    }
                }
            }
        } else {
            TextButton(onClick = { nav.openRoute(Routes.LOGIN) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.login_to_comment))
            }
        }
        HorizontalDivider()
        when {
            state.items.isEmpty() && state.error != null -> ErrorBox(state.error!!, vm::reload)
            state.items.isEmpty() && !state.loading && state.endReached ->
                MessageBox(stringResource(R.string.no_comments), Icons.Filled.ChatBubbleOutline)
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                state.items.forEach { c ->
                    item(key = c.id) {
                        CommentItem(c)
                        if (c.childrenCount > 0) {
                            val replies = state.replies[c.id]
                            if (replies == null) {
                                TextButton(onClick = { vm.loadReplies(c.id) }, modifier = Modifier.padding(start = 56.dp)) {
                                    Text(stringResource(R.string.show_replies, c.childrenCount))
                                }
                            } else {
                                replies.forEach { r -> CommentItem(r, Modifier.padding(start = 40.dp)) }
                            }
                        }
                    }
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            state.loading -> CircularProgressIndicator()
                            state.error != null -> YButton(stringResource(R.string.retry), vm::loadMore, primary = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(c: Comment, modifier: Modifier = Modifier) {
    val locale = currentLocale()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
    ) {
        AsyncImage(
            model = absUrl(c.avatars?.small),
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(formatDateTime(c.time, locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            if (c.deletedAt > 0) {
                Text(stringResource(R.string.comment_deleted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stripHtml(c.text), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ThumbUp, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" ${c.likes}   ", style = MaterialTheme.typography.labelSmall)
                Icon(Icons.Filled.ThumbDown, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" ${c.dislikes}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
