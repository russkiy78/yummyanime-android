package com.yani.client.download

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yani.client.AppContainer
import com.yani.client.BuildConfig
import com.yani.client.data.AnimeDetails
import com.yani.client.data.Video
import com.yani.client.player.LoadRequest
import com.yani.client.player.PlayerAdapters
import com.yani.client.player.PlayerCommand
import com.yani.client.player.PlayerKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "YummyCapture"

enum class CaptureStatus {
    WAITING,
    SEARCHING,
    QUEUING,
    QUEUED,
    /** No stream was found in the player. */
    FAILED,
    /** The download kept failing even with fresh links. */
    DOWNLOAD_FAILED,
    SKIPPED,
}

data class CaptureItem(val video: Video, val status: CaptureStatus = CaptureStatus.WAITING)

data class CaptureUi(
    val title: String = "",
    val loading: Boolean = true,
    val error: Throwable? = null,
    val items: List<CaptureItem> = emptyList(),
    val currentIndex: Int = -1,
    /** The episode whose player is open; null while nothing should play. */
    val request: LoadRequest? = null,
    /** Seconds spent looking for the current episode's stream. */
    val elapsed: Int = 0,
) {
    val current: CaptureItem? get() = items.getOrNull(currentIndex)
    val finished: Boolean
        get() = !loading && error == null && items.none { it.status in setOf(CaptureStatus.WAITING, CaptureStatus.SEARCHING, CaptureStatus.QUEUING) }
    val queued: Int get() = items.count { it.status == CaptureStatus.QUEUED }
    val failed: Int get() = items.count { it.status == CaptureStatus.FAILED || it.status == CaptureStatus.DOWNLOAD_FAILED }
}

sealed interface CaptureCommand {
    /** Argument for `ySend(...)` in the host page. */
    data class Send(val js: String) : CaptureCommand
    /** Tap the middle of the player, for players without a control API. */
    data object Tap : CaptureCommand
}

private data class Tagged(val seq: Int, val candidate: StreamCandidate)

/** Downloaded bytes when the link of an episode was last renewed, and how often in a row that brought nothing. */
private data class Renewal(val bytes: Long, val stalls: Int)

/**
 * Opens the chosen episodes one by one in their embedded player, muted, and waits until the player loads the episode
 * stream; that stream is then queued for download and the next episode is opened.
 */
class CaptureViewModel(
    container: AppContainer,
    private val animeId: Int,
    private val videoIds: List<Long>,
    private val userAgent: String,
) : ViewModel() {
    private val api = container.api
    private val downloads = container.downloads
    private val probe = StreamProbe(container.mediaHttp)

    private val _ui = MutableStateFlow(CaptureUi())
    val ui = _ui.asStateFlow()

    private val _commands = MutableSharedFlow<CaptureCommand>(extraBufferCapacity = 8)
    val commands = _commands.asSharedFlow()

    private var anime: AnimeDetails? = null

    /** Requests arrive on WebView threads; candidates are handled on the main thread. */
    private val candidates = Channel<Tagged>(Channel.UNLIMITED)

    /** Headers of the current episode's requests by URL, for streams that the page script reports. */
    private val requestHeaders = ConcurrentHashMap<String, Map<String, String>>()
    private val seen = mutableSetOf<String>()

    @Volatile
    private var seq = 0
    private var searchJob: Job? = null
    /** The screen is in the background: players do not play there, so nothing is looked for. */
    private var paused = false
    private val renewals = mutableMapOf<Long, Renewal>()

    init {
        viewModelScope.launch {
            for (tagged in candidates) onCandidate(tagged)
        }
        viewModelScope.launch {
            downloads.episodes.collect(::renewFailed)
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                anime = runCatching { api.anime(animeId.toString()) }.getOrNull()
                val videos = api.videos(animeId)
                val items = videoIds.mapNotNull { id -> videos.firstOrNull { it.id == id } }.sortedBy { it.index }.map(::CaptureItem)
                _ui.update { it.copy(title = anime?.title.orEmpty(), items = items, loading = false) }
                next()
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun next() {
        if (paused) {
            _ui.update { it.copy(currentIndex = -1, request = null) }
            return
        }
        val index = _ui.value.items.indexOfFirst { it.status == CaptureStatus.WAITING }
        if (index < 0) {
            _ui.update { it.copy(currentIndex = -1, request = null) }
            return
        }
        start(index)
    }

    private fun start(index: Int) {
        val video = _ui.value.items[index].video
        val existing = downloads.forVideo(video.id)
        // Already downloaded or queued (e.g. chosen again): nothing to look for.
        if (existing?.failed == false) {
            setStatus(index, CaptureStatus.QUEUED)
            return next()
        }
        seq++
        seen.clear()
        requestHeaders.clear()
        // A download that was cut off: the player starts where it stopped, as some CDNs sign links for the part being played.
        val resumeAt = existing?.progress?.let { progress -> video.duration?.let { (progress * it).toInt() - 30 } }?.takeIf { it > 0 }
        val src = PlayerAdapters.buildSrc(video.iframeUrl, resumeAt)
        val kind = PlayerKind.of(src)
        _ui.update { it.copy(currentIndex = index, elapsed = 0, request = LoadRequest(video, src, kind, seq)) }
        setStatus(index, CaptureStatus.SEARCHING)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            for (second in 1..TIMEOUT_SECONDS) {
                delay(1000)
                _ui.update { it.copy(elapsed = second) }
                nudge(kind, second)
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "no stream for video ${video.id}")
            finish(index, CaptureStatus.FAILED)
        }
    }

    /**
     * Most players load the stream only when playback starts. "play" is repeated because players accept it only once ready;
     * players without a control API get a tap, but only twice: a tap on a playing ad opens the ad.
     */
    private fun nudge(kind: PlayerKind, second: Int) {
        if (kind.controllable) {
            if (second % 4 == 0) PlayerAdapters.command(kind, PlayerCommand.Play)?.let { _commands.tryEmit(CaptureCommand.Send(it)) }
        } else if (second == 8 || second == 20) {
            _commands.tryEmit(CaptureCommand.Tap)
        }
    }

    /** Every request of the page and its iframes; called on a WebView thread. CORS preflights carry no player headers. */
    fun onRequest(url: String, method: String, headers: Map<String, String>) {
        if (method != "GET") return
        val request = _ui.value.request ?: return
        val tag = seq
        if (requestHeaders.size < MAX_REMEMBERED_REQUESTS) {
            requestHeaders[url] = StreamCapture.streamHeaders(headers, request.src, userAgent)
        }
        StreamCapture.fromRequest(url, headers, request.src, userAgent)?.let { candidates.trySend(Tagged(tag, it)) }
    }

    /** A playlist or `<video>` reported by the script injected in the page's frames. */
    fun onScriptReport(raw: String) {
        val request = _ui.value.request ?: return
        val candidate = StreamCapture.fromScript(raw) { url ->
            requestHeaders[url] ?: StreamCapture.streamHeaders(emptyMap(), request.src, userAgent)
        } ?: return
        candidates.trySend(Tagged(seq, candidate))
    }

    private fun onCandidate(tagged: Tagged) {
        val candidate = tagged.candidate
        if (tagged.seq != seq || !seen.add(candidate.url)) return
        if (BuildConfig.DEBUG) Log.d(TAG, "candidate ${candidate.type}: ${candidate.url.takeLast(120)}")
        viewModelScope.launch {
            val stream = probe.probe(candidate) ?: return@launch
            val state = _ui.value
            val item = state.current ?: return@launch
            if (tagged.seq != seq || item.status != CaptureStatus.SEARCHING) return@launch
            if (BuildConfig.DEBUG) Log.d(TAG, "probed ${stream.type}, ${stream.duration} s (episode ${item.video.duration} s)")
            if (probe.isEpisode(stream, item.video.duration)) accept(state.currentIndex, item.video, stream)
        }
    }

    private suspend fun accept(index: Int, video: Video, stream: CapturedStream) {
        searchJob?.cancel()
        setStatus(index, CaptureStatus.QUEUING)
        // Stop the player: the stream is known, and it would only compete with the download.
        _ui.update { it.copy(request = null) }
        val result = runCatching { downloads.enqueue(episodeInfo(video), stream) }
        result.exceptionOrNull()?.let { Log.w(TAG, "could not queue video ${video.id}", it) }
        finish(index, if (result.isSuccess) CaptureStatus.QUEUED else CaptureStatus.FAILED)
    }

    private fun finish(index: Int, status: CaptureStatus) {
        searchJob?.cancel()
        setStatus(index, status)
        next()
    }

    private fun episodeInfo(video: Video) = EpisodeInfo(
        animeId = animeId,
        animeTitle = anime?.title.orEmpty(),
        poster = anime?.poster?.card,
        videoId = video.id,
        number = video.number,
        index = video.index,
        player = video.data.player,
        dubbing = video.data.dubbing,
        duration = video.duration,
        skips = video.skips,
    )

    fun skip() {
        val state = _ui.value
        if (state.current?.status == CaptureStatus.SEARCHING) finish(state.currentIndex, CaptureStatus.SKIPPED)
    }

    fun retryFailed() {
        _ui.update { s -> s.copy(items = s.items.map { if (it.status == CaptureStatus.FAILED) it.copy(status = CaptureStatus.WAITING) else it }) }
        next()
    }

    /** The screen went to the background: players pause there, so this episode is looked for again on return. */
    fun pause() {
        paused = true
        val state = _ui.value
        if (state.current?.status != CaptureStatus.SEARCHING) return
        searchJob?.cancel()
        seq++
        setStatus(state.currentIndex, CaptureStatus.WAITING)
        _ui.update { it.copy(request = null, currentIndex = -1) }
    }

    fun resume() {
        paused = false
        val state = _ui.value
        if (!state.loading && state.error == null && state.current == null) next()
    }

    /**
     * Some players hand out links that stop working partway through an episode (e.g. Alloha's CDN). While this screen
     * is open, such a download gets a fresh link from the player and continues where it stopped. An episode is given up
     * after a few renewals that did not move it forward.
     */
    private fun renewFailed(episodes: List<OfflineEpisode>) {
        val state = _ui.value
        var renewed = false
        val items = state.items.map { item ->
            if (item.status != CaptureStatus.QUEUED) return@map item
            val download = episodes.firstOrNull { it.info.videoId == item.video.id }?.takeIf { it.failed } ?: return@map item
            val previous = renewals[item.video.id]
            val stalls = if (previous == null || download.bytes > previous.bytes) 0 else previous.stalls + 1
            if (stalls >= MAX_STALLED_RENEWALS) return@map item.copy(status = CaptureStatus.DOWNLOAD_FAILED)
            renewals[item.video.id] = Renewal(download.bytes, stalls)
            renewed = true
            if (BuildConfig.DEBUG) Log.d(TAG, "renewing the link of video ${item.video.id} at ${download.bytes} bytes")
            item.copy(status = CaptureStatus.WAITING)
        }
        if (items == state.items) return
        _ui.update { it.copy(items = items) }
        if (renewed && _ui.value.current == null && !paused) next()
    }

    private fun setStatus(index: Int, status: CaptureStatus) {
        _ui.update { s -> s.copy(items = s.items.mapIndexed { i, item -> if (i == index) item.copy(status = status) else item }) }
    }

    private companion object {
        /** Long enough for a pre-roll ad to play to the end. */
        const val TIMEOUT_SECONDS = 120
        const val MAX_REMEMBERED_REQUESTS = 2000
        const val MAX_STALLED_RENEWALS = 3
    }
}
