package com.yani.client.player

import android.util.Log
import androidx.lifecycle.ViewModel
import com.yani.client.BuildConfig
import androidx.lifecycle.viewModelScope
import com.yani.client.AppContainer
import com.yani.client.data.Video
import com.yani.client.data.Watched
import com.yani.client.data.continueTarget
import com.yani.client.data.dubbingStudio
import com.yani.client.data.dubbingsFor
import com.yani.client.data.episodesFor
import com.yani.client.data.orderedPlayers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "YummyPlayer"

/** A request to (re)load the WebView; [seq] changes on every selection. */
data class LoadRequest(val video: Video, val src: String, val kind: PlayerKind, val seq: Int)

data class PlayerUi(
    val title: String = "",
    val loading: Boolean = true,
    val error: Throwable? = null,
    val videos: List<Video> = emptyList(),
    val players: List<String> = emptyList(),
    val dubbings: List<String> = emptyList(),
    val episodes: List<Video> = emptyList(),
    val current: Video? = null,
    val request: LoadRequest? = null,
) {
    val currentIndex: Int get() = episodes.indexOfFirst { it.id == current?.id }
    val hasNext: Boolean get() = currentIndex in 0 until episodes.lastIndex
    val hasPrevious: Boolean get() = currentIndex > 0
}

data class Playback(
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val playing: Boolean = false,
    /** The player reported anything at all, i.e. it is loaded and talking to us. */
    val ready: Boolean = false,
    val canSkipOpening: Boolean = false,
)

sealed interface PlayerNotice {
    data object NextEpisode : PlayerNotice
    data object Unsupported : PlayerNotice
    data object OpeningSkipped : PlayerNotice
}

class PlayerViewModel(
    private val container: AppContainer,
    private val animeId: Int,
    private val requestedVideoId: Long?,
) : ViewModel() {
    private val api = container.api
    private val prefs = container.prefs

    private val _ui = MutableStateFlow(PlayerUi())
    val ui = _ui.asStateFlow()

    private val _playback = MutableStateFlow(Playback())
    val playback = _playback.asStateFlow()

    /** JavaScript arguments for `ySend(...)`, consumed by the activity. */
    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val commands = _commands.asSharedFlow()

    private val _notices = MutableSharedFlow<PlayerNotice>(extraBufferCapacity = 4)
    val notices = _notices.asSharedFlow()

    private var seq = 0
    private val pendingTimes = sortedSetOf<Int>()
    private var lastSync = 0L
    private var lastDurationRequest = 0L
    private var openingSkipped = false
    private var ended = false
    private var autoplayPending = false
    private var autoplayJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val anime = runCatching { api.anime(animeId.toString()) }.getOrNull()
                val videos = api.videos(animeId)
                _ui.update { it.copy(title = anime?.title.orEmpty(), videos = videos, loading = false) }
                initialVideo(videos)?.let { select(it) }
            } catch (e: Exception) {
                _ui.update { it.copy(loading = false, error = e) }
            }
        }
    }

    private fun initialVideo(videos: List<Video>): Video? {
        if (videos.isEmpty()) return null
        requestedVideoId?.let { id -> videos.firstOrNull { it.id == id }?.let { return it } }
        continueTarget(videos)?.let { return it.video }
        val settings = prefs.settings.value
        val player = orderedPlayers(videos, settings.preferredPlayer).first()
        val dubbing = pickDubbing(dubbingsFor(videos, player), settings.preferredDubbing)
        return episodesFor(videos, player, dubbing).firstOrNull()
    }

    private fun pickDubbing(dubbings: List<String>, wanted: String?): String {
        if (wanted.isNullOrBlank()) return dubbings.first()
        return dubbings.firstOrNull { it == wanted }
            ?: dubbings.firstOrNull { dubbingStudio(it) == dubbingStudio(wanted) }
            ?: dubbings.first()
    }

    // ---- Selection ----

    fun select(video: Video) {
        flush(final = true)
        val videos = _ui.value.videos
        val player = video.data.player
        val duration = video.duration ?: 0.0
        val resumeAt = video.watched?.endTime
            ?.takeIf { it > 5 && (duration <= 0 || it < duration - 20) }
            ?.toInt()
        val kind = PlayerKind.of(PlayerAdapters.normalize(video.iframeUrl))
        seq++
        _ui.update {
            it.copy(
                current = video,
                players = orderedPlayers(videos, prefs.settings.value.preferredPlayer),
                dubbings = dubbingsFor(videos, player),
                episodes = episodesFor(videos, player, video.data.dubbing),
                request = LoadRequest(video, PlayerAdapters.buildSrc(video.iframeUrl, resumeAt), kind, seq),
            )
        }
        _playback.value = Playback(position = resumeAt?.toDouble() ?: 0.0, duration = duration)
        pendingTimes.clear()
        openingSkipped = false
        ended = false
        autoplayPending = true
        prefs.updateSettings { it.copy(preferredDubbing = video.data.dubbing) }
    }

    fun selectPlayer(player: String) {
        val state = _ui.value
        if (player == state.current?.data?.player) return
        val dubbing = pickDubbing(dubbingsFor(state.videos, player), state.current?.data?.dubbing)
        selectEpisodeIn(player, dubbing)
    }

    fun selectDubbing(dubbing: String) {
        val current = _ui.value.current ?: return
        if (dubbing == current.data.dubbing) return
        selectEpisodeIn(current.data.player, dubbing)
    }

    /** Keeps the current episode number when switching player or dubbing, if it exists there. */
    private fun selectEpisodeIn(player: String, dubbing: String) {
        val episodes = episodesFor(_ui.value.videos, player, dubbing)
        val number = _ui.value.current?.number
        val target = episodes.firstOrNull { it.number == number } ?: episodes.firstOrNull() ?: return
        select(target)
    }

    fun next() {
        val s = _ui.value
        if (s.hasNext) select(s.episodes[s.currentIndex + 1])
    }

    fun previous() {
        val s = _ui.value
        if (s.hasPrevious) select(s.episodes[s.currentIndex - 1])
    }

    // ---- Player events ----

    fun onHostPageLoaded() {
        val request = _ui.value.request ?: return
        // Not every player reports readiness, so also try to start playback after a delay.
        autoplayJob?.cancel()
        autoplayJob = viewModelScope.launch {
            delay(if (request.kind == PlayerKind.ALLOHA) 3000 else 10_000)
            autoplay()
        }
    }

    /** "play" is idempotent, so it is repeated until the player reports playback (which clears the flag). */
    private fun autoplay() {
        if (autoplayPending && !_playback.value.playing) send(PlayerCommand.Play)
    }

    fun onMessage(raw: String) {
        if (BuildConfig.DEBUG && "time_update" !in raw) Log.d(TAG, "message: ${raw.take(160)}")
        val request = _ui.value.request ?: return
        when (val event = PlayerAdapters.parse(raw) ?: return) {
            is PlayerEvent.Time -> onTime(event.seconds, request)
            is PlayerEvent.Duration -> _playback.update { it.copy(duration = event.seconds, ready = true) }
            PlayerEvent.Ready -> {
                _playback.update { it.copy(ready = true) }
                viewModelScope.launch {
                    delay(700)
                    autoplay()
                }
            }
            is PlayerEvent.Playing -> {
                if (event.playing) autoplayPending = false
                _playback.update { it.copy(playing = event.playing, ready = true) }
                if (!event.playing) flush()
            }
            PlayerEvent.Ended -> onEnded()
        }
    }

    private fun onTime(seconds: Double, request: LoadRequest) {
        val previous = _playback.value
        val moving = seconds > 0 && seconds != previous.position
        if (moving) {
            autoplayPending = false
            pendingTimes += seconds.toInt()
        }
        val opening = request.video.skips?.opening?.takeIf { it.length > 0 }
        val inOpening = opening != null && seconds >= opening.time && seconds < opening.time + opening.length - 3
        _playback.update { it.copy(position = seconds, ready = true, playing = it.playing || moving, canSkipOpening = inOpening && !openingSkipped) }

        if (inOpening && !openingSkipped && prefs.settings.value.autoSkipOpening) skipOpening()

        val now = System.currentTimeMillis()
        if (request.kind == PlayerKind.ALLOHA) {
            // Alloha only reports the duration on request.
            if (now - lastDurationRequest > 15_000 || previous.duration <= 0) {
                lastDurationRequest = now
                send(PlayerCommand.RequestDuration)
            }
            val duration = _playback.value.duration
            if (duration > 0 && seconds + 0.5 >= duration) onEnded()
        }
        if (now - lastSync > 20_000) flush()
    }

    private fun onEnded() {
        if (ended) return
        ended = true
        val duration = _playback.value.duration
        if (duration > 0) _playback.update { it.copy(position = duration, playing = false) }
        flush(final = true)
        if (prefs.settings.value.autoNext && _ui.value.hasNext) {
            viewModelScope.launch {
                _notices.emit(PlayerNotice.NextEpisode)
                delay(1500)
                next()
            }
        }
    }

    // ---- Controls (used by the TV remote and the overlay) ----

    /** Returns false when the player cannot be controlled (caller may emulate a tap). */
    fun togglePlay(): Boolean {
        val kind = _ui.value.request?.kind ?: return false
        if (!kind.controllable) return false
        val playing = _playback.value.playing
        send(if (playing) PlayerCommand.Pause else PlayerCommand.Play)
        _playback.update { it.copy(playing = !playing) }
        return true
    }

    fun play() = send(PlayerCommand.Play)

    fun pause() {
        send(PlayerCommand.Pause)
        flush()
    }

    /** Pause command for direct use when the screen stops (command collectors are inactive then). */
    fun pauseScript(): String? {
        val kind = _ui.value.request?.kind ?: return null
        _playback.update { it.copy(playing = false) }
        return PlayerAdapters.command(kind, PlayerCommand.Pause)
    }

    fun seekBy(deltaSeconds: Int): Boolean {
        val kind = _ui.value.request?.kind ?: return false
        if (!kind.controllable) {
            _notices.tryEmit(PlayerNotice.Unsupported)
            return false
        }
        val p = _playback.value
        val max = if (p.duration > 0) p.duration - 1 else Double.MAX_VALUE
        val target = (p.position + deltaSeconds).coerceIn(0.0, max)
        send(PlayerCommand.Seek(target))
        _playback.update { it.copy(position = target) }
        return true
    }

    fun skipOpening() {
        val opening = _ui.value.request?.video?.skips?.opening ?: return
        openingSkipped = true
        send(PlayerCommand.Seek(opening.time + opening.length))
        _playback.update { it.copy(canSkipOpening = false, position = opening.time + opening.length) }
        _notices.tryEmit(PlayerNotice.OpeningSkipped)
    }

    private fun send(command: PlayerCommand) {
        val kind = _ui.value.request?.kind ?: return
        if (BuildConfig.DEBUG) Log.d(TAG, "send $command to $kind")
        PlayerAdapters.command(kind, command)?.let { _commands.tryEmit(it) }
    }

    // ---- Progress sync (same payload as the website) ----

    /** Sends watched seconds to the server. [final] uses the app scope so it survives this screen. */
    fun flush(final: Boolean = false) {
        val video = _ui.value.current ?: return
        if (prefs.session.value == null || pendingTimes.isEmpty()) return
        val times = pendingTimes.toList()
        pendingTimes.clear()
        lastSync = System.currentTimeMillis()
        val playback = _playback.value
        val position = playback.position.toInt()
        val duration = (playback.duration.takeIf { it > 0 } ?: video.duration ?: 0.0).toInt()
        markLocally(video.id, playback.position)
        val scope = if (final) container.appScope else viewModelScope
        scope.launch {
            runCatching { api.markWatched(video.id, position, duration, times) }
                .onSuccess { if (BuildConfig.DEBUG) Log.d(TAG, "synced video ${video.id}: $position/$duration, ${times.size} seconds") }
                .onFailure {
                    Log.w(TAG, "sync failed", it)
                    if (!final) pendingTimes += times
                }
        }
    }

    /** Updates episode progress marks without reloading the list. */
    private fun markLocally(videoId: Long, position: Double) {
        val watched = Watched(position, System.currentTimeMillis() / 1000)
        _ui.update { s ->
            fun List<Video>.patch() = map { if (it.id == videoId) it.copy(watched = watched) else it }
            s.copy(
                videos = s.videos.patch(),
                episodes = s.episodes.patch(),
                current = s.current?.let { if (it.id == videoId) it.copy(watched = watched) else it },
            )
        }
    }

    override fun onCleared() {
        flush(final = true)
    }
}
