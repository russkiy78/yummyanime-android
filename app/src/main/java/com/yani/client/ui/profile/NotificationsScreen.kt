package com.yani.client.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.yani.client.R
import com.yani.client.data.NotificationItem
import com.yani.client.data.YummyApi
import com.yani.client.data.absUrl
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.focusBorder
import com.yani.client.util.animeAliasFromPath
import com.yani.client.util.currentLocale
import com.yani.client.util.formatDateTime
import com.yani.client.util.stripHtml
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class NotificationsState(
    val items: List<NotificationItem> = emptyList(),
    val loading: Boolean = false,
    val error: Throwable? = null,
    val endReached: Boolean = false,
)

class NotificationsViewModel(private val api: YummyApi) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsState())
    val state = _state.asStateFlow()

    init {
        loadMore()
        viewModelScope.launch { runCatching { api.readAllNotifications() } }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { api.notifications(s.items.size) }
                .onSuccess { page -> _state.update { it.copy(items = it.items + page, loading = false, endReached = page.size < 30) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e) } }
        }
    }
}

/** Notifications about anime carry the anime object with a poster; others (comments, users) may not. */
private fun NotificationItem.imageUrl(): String? {
    val anime = data?.get("anime") as? JsonObject
    val poster = anime?.get("poster") as? JsonObject
    return absUrl(poster?.get("medium")?.jsonPrimitive?.contentOrNull ?: poster?.get("small")?.jsonPrimitive?.contentOrNull)
}

@Composable
fun NotificationsScreen() {
    val vm = appViewModel { NotificationsViewModel(it.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val locale = currentLocale()
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.items.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
        }.distinctUntilChanged().filter { it }.collect { vm.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(stringResource(R.string.notifications))
        when {
            state.items.isEmpty() && state.error != null -> ErrorBox(state.error!!, vm::loadMore)
            state.items.isEmpty() && state.endReached -> MessageBox(stringResource(R.string.no_notifications), Icons.Filled.NotificationsNone)
            else -> LazyColumn(state = listState, contentPadding = PaddingValues(ScreenPadding)) {
                items(state.items, key = { it.id }) { n ->
                    val source = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(12.dp)
                    val alias = animeAliasFromPath(n.clickUri)
                    Card(
                        onClick = { if (alias != null) nav.openAnime(alias) },
                        shape = shape,
                        interactionSource = source,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .focusBorder(source, shape, scale = 1.02f),
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            val image = n.imageUrl()
                            if (image != null) {
                                AsyncImage(
                                    model = image,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(62.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!n.viewed) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Text(stripHtml(n.titleHtml), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(stripHtml(n.textHtml), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatDateTime(n.date, locale),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                        if (state.loading) CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
