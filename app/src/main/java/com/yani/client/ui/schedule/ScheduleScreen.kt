package com.yani.client.ui.schedule

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.yani.client.data.AnimeItem
import com.yani.client.data.YummyApi
import com.yani.client.ui.common.Load
import com.yani.client.ui.common.LoadContent
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.MessageBox
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.SectionTitle
import com.yani.client.ui.common.appViewModel
import com.yani.client.ui.common.focusBorder
import com.yani.client.ui.common.load
import com.yani.client.util.currentLocale
import com.yani.client.util.formatClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

class ScheduleViewModel(private val api: YummyApi) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<AnimeItem>>>(Load.Loading)
    val state = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = load { api.schedule() }
        }
    }
}

private data class Day(val start: Long, val items: List<AnimeItem>)

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun groupByDay(items: List<AnimeItem>): List<Day> {
    val today = startOfDay(System.currentTimeMillis())
    return items
        .filter { (it.episodes?.nextDate ?: 0) * 1000 >= today }
        .sortedBy { it.episodes?.nextDate }
        .groupBy { startOfDay(it.episodes!!.nextDate!! * 1000) }
        .map { (day, list) -> Day(day, list) }
        .take(14)
}

@Composable
fun ScheduleScreen() {
    val vm = appViewModel { ScheduleViewModel(it.api) }
    val state by vm.state.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val today = stringResource(R.string.today)
    val tomorrow = stringResource(R.string.tomorrow)

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.nav_schedule),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
        )
        LoadContent(state, vm::reload) { items ->
            val days = remember(items) { groupByDay(items) }
            if (days.isEmpty()) {
                MessageBox(stringResource(R.string.schedule_empty), Icons.Filled.EventBusy)
                return@LoadContent
            }
            val dayFormat = remember(locale) { SimpleDateFormat("EEEE, d MMMM", locale) }
            val todayStart = startOfDay(System.currentTimeMillis())
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                days.forEach { day ->
                    item("h${day.start}") {
                        val label = when ((day.start - todayStart) / 86_400_000L) {
                            0L -> "$today, ${dayFormat.format(Date(day.start))}"
                            1L -> "$tomorrow, ${dayFormat.format(Date(day.start))}"
                            else -> dayFormat.format(Date(day.start)).replaceFirstChar { it.titlecase(locale) }
                        }
                        SectionTitle(label)
                    }
                    day.items.forEach { anime ->
                        item("${day.start}-${anime.id}") { ScheduleRow(anime, formatClock(anime.episodes?.nextDate ?: 0, locale)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(anime: AnimeItem, time: String) {
    val nav = LocalNav.current
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = { nav.openAnime(anime.id.toString()) },
        shape = shape,
        interactionSource = source,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 4.dp)
            .focusBorder(source, shape, scale = 1.02f),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = anime.poster?.card,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(48.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(anime.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val ep = anime.episodes
                val next = (ep?.aired ?: 0) + 1
                val total = ep?.count?.takeIf { it > 0 }?.toString() ?: "?"
                Text(
                    stringResource(R.string.episode_of, next, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(time, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
