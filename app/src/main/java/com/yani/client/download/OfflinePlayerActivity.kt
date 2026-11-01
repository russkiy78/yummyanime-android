package com.yani.client.download

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.yani.client.AppContainer
import com.yani.client.R
import com.yani.client.YummyApplication
import com.yani.client.data.isFinished
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.isTelevision
import com.yani.client.ui.theme.YummyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays downloaded episodes without a connection. The episodes of the same anime, player and dubbing form a playlist,
 * so "next" and auto-play work as online. Progress is kept locally and sent to the account later ([OfflineProgress]).
 */
@OptIn(UnstableApi::class)
class OfflinePlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DOWNLOAD_ID = "download_id"

        fun intent(context: Context, downloadId: String): Intent =
            Intent(context, OfflinePlayerActivity::class.java).putExtra(EXTRA_DOWNLOAD_ID, downloadId)

        private val CENTER_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    }

    private val container: AppContainer get() = (application as YummyApplication).container
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private var playlist: List<OfflineEpisode> = emptyList()
    private var isTv = false

    // State shared with Compose.
    private var title by mutableStateOf("")
    private var controlsVisible by mutableStateOf(true)
    private var canSkipOpening by mutableStateOf(false)

    /** The episode the progress below belongs to; updated every second, read when the player moves on. */
    private var tracked: OfflineEpisode? = null
    private var trackedPosition = 0.0
    private var trackedDuration = 0.0
    private val watchedSeconds = sortedSetOf<Int>()
    private var openingSkipped = false
    private var ticks = 0

    /** Episodes watched to the end in this session: removed when the setting asks for it. */
    private val finishedIds = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = isTelevision()
        val all = container.downloads.episodes.value
        val start = all.firstOrNull { it.id == intent.getStringExtra(EXTRA_DOWNLOAD_ID) && it.completed }
        if (start == null) {
            Toast.makeText(this, R.string.download_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        playlist = all
            .filter { it.completed && it.info.animeId == start.info.animeId && it.info.player == start.info.player && it.info.dubbing == start.info.dubbing }
            .sortedBy { it.info.index }
        val startIndex = playlist.indexOf(start)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        player = ExoPlayer.Builder(this).build()
        player.setMediaSources(playlist.map(container.downloads::mediaSource), startIndex, resumePositionMs(start) ?: 0L)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onEpisodeChanged(reason)
        })
        player.prepare()
        player.playWhenReady = true
        onEpisodeChanged(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)

        playerView = PlayerView(this).apply {
            player = this@OfflinePlayerActivity.player
            keepScreenOn = true
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowSubtitleButton(true)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
        }

        setContent {
            YummyTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
                    AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!isTv) {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                                }
                            }
                            Text(
                                title,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = if (isTv) 40.dp else 0.dp, top = if (isTv) 20.dp else 0.dp),
                            )
                        }
                    }
                    if (canSkipOpening) {
                        val modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 24.dp, bottom = 96.dp)
                        if (isTv) {
                            // OK skips while this is shown (see dispatchKeyEvent), so it is a hint rather than a button.
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary, modifier = modifier) {
                                Text(
                                    stringResource(R.string.skip_opening_tv),
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        } else {
                            YButton(stringResource(R.string.skip_opening), onClick = ::skipOpening, icon = Icons.Filled.SkipNext, modifier = modifier)
                        }
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(1000)
                    tick()
                }
            }
        }
    }

    /** Where the offline player stopped last time, unless the episode was finished. */
    private fun resumePositionMs(episode: OfflineEpisode): Long? {
        val position = container.offlineProgress.position(episode.info.videoId) ?: return null
        val duration = episode.info.duration ?: 0.0
        if (position < 5 || (duration > 0 && isFinished(position, duration))) return null
        return (position * 1000).toLong()
    }

    private fun onEpisodeChanged(reason: Int) {
        val current = playlist.firstOrNull { it.id == player.currentMediaItem?.mediaId } ?: return
        val previous = tracked
        if (current.id == previous?.id) return
        if (previous != null) {
            // The episode played to its end: the position is the end, not wherever the ticker saw it last.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && trackedDuration > 0) {
                trackedPosition = trackedDuration
                finishedIds += previous.id
            }
            save()
        }
        tracked = current
        trackedPosition = 0.0
        trackedDuration = current.info.duration ?: 0.0
        openingSkipped = false
        canSkipOpening = false
        title = listOf(current.info.animeTitle, getString(R.string.episode_n, current.info.number)).filter { it.isNotBlank() }.joinToString(" · ")
        if (previous != null) {
            resumePositionMs(current)?.let { player.seekTo(it) }
            removeFinished(keep = current.id)
        }
    }

    private fun tick() {
        val episode = tracked ?: return
        val position = player.currentPosition / 1000.0
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 } ?: episode.info.duration ?: 0.0
        trackedPosition = position
        trackedDuration = duration
        if (player.isPlaying) watchedSeconds += position.toInt()
        if (duration > 0 && isFinished(position, duration)) finishedIds += episode.id

        val opening = episode.info.skips?.opening?.takeIf { it.length > 0 }
        val inOpening = opening != null && position >= opening.time && position < opening.time + opening.length - 3
        canSkipOpening = inOpening && !openingSkipped
        if (canSkipOpening && container.prefs.settings.value.autoSkipOpening) skipOpening()

        if (++ticks % 10 == 0) save()
    }

    private fun skipOpening() {
        val opening = tracked?.info?.skips?.opening ?: return
        openingSkipped = true
        canSkipOpening = false
        player.seekTo(((opening.time + opening.length) * 1000).toLong())
        Toast.makeText(this, R.string.opening_skipped, Toast.LENGTH_SHORT).show()
    }

    private fun save() {
        val episode = tracked ?: return
        container.offlineProgress.record(episode.info.videoId, trackedPosition, trackedDuration, watchedSeconds)
        watchedSeconds.clear()
    }

    /** With "delete after watching" on, drops finished episodes once the player has moved past them. */
    private fun removeFinished(keep: String?, updatePlaylist: Boolean = true) {
        if (!container.prefs.settings.value.deleteWatchedDownloads) return
        for (id in finishedIds.filter { it != keep }) {
            container.downloads.remove(id)
            if (updatePlaylist) {
                (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }?.let(player::removeMediaItem)
            }
            playlist = playlist.filter { it.id != id }
            finishedIds -= id
        }
    }

    // ---- Remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (canSkipOpening && event.keyCode in CENTER_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) skipOpening()
            return true
        }
        // The controls live in PlayerView; on TV the remote goes straight to it (Back still closes the player).
        if (isTv && event.keyCode != KeyEvent.KEYCODE_BACK && ::playerView.isInitialized && playerView.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- Lifecycle ----

    override fun onStop() {
        if (::player.isInitialized) {
            player.pause()
            save()
            container.appScope.launch { container.offlineProgress.sync() }
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            save()
            player.release()
            removeFinished(keep = null, updatePlaylist = false)
        }
        super.onDestroy()
    }
}
