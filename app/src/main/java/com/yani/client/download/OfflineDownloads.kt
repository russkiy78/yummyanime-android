package com.yani.client.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.yani.client.data.Skips
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What is known about a downloaded episode. Kept inside the download itself, so the list works offline. */
@Serializable
data class EpisodeInfo(
    val animeId: Int,
    val animeTitle: String,
    val poster: String? = null,
    val videoId: Long,
    val number: String,
    val index: Int = 0,
    val player: String = "",
    val dubbing: String = "",
    val duration: Double? = null,
    val skips: Skips? = null,
    /** Headers the player sent for the stream (Referer, Origin, User-Agent, cookies): video CDNs often check them. */
    val headers: Map<String, String> = emptyMap(),
)

data class OfflineEpisode(val download: Download, val info: EpisodeInfo) {
    val id: String get() = download.request.id
    val state: Int get() = download.state
    val bytes: Long get() = download.bytesDownloaded

    /** 0..1, or null while unknown. */
    val progress: Float? get() = download.percentDownloaded.takeIf { it >= 0 }?.let { it / 100f }
    val completed: Boolean get() = state == Download.STATE_COMPLETED
    val failed: Boolean get() = state == Download.STATE_FAILED
}

/**
 * Downloaded episodes: Media3 downloads the stream the embedded player was found to play (see [CaptureActivity]),
 * with the same headers, into a cache that the offline player reads from.
 */
@OptIn(UnstableApi::class)
class OfflineDownloads(context: Context, private val json: Json, private val scope: CoroutineScope) {
    private val app = context.applicationContext

    /** Not backed up: episodes are large and the backup would fail (see the backup rules for the index). */
    private val directory = File(app.noBackupFilesDir, "downloads")
    private val databaseProvider = StandaloneDatabaseProvider(app)
    val cache = SimpleCache(directory, NoOpCacheEvictor(), databaseProvider)

    /** Segments of one episode are loaded in parallel. */
    private val executor = Executors.newFixedThreadPool(4)

    val manager = DownloadManager(app, DefaultDownloadIndex(databaseProvider), HeadersDownloaderFactory()).apply {
        maxParallelDownloads = 2
    }

    private val _episodes = MutableStateFlow<List<OfflineEpisode>>(emptyList())
    /** All downloads, sorted by anime and episode. */
    val episodes: StateFlow<List<OfflineEpisode>> = _episodes.asStateFlow()

    private val refreshes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var ticker: Job? = null

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = refresh()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
        })
        scope.launch { refreshes.collectLatest { reload() } }
        refresh()
    }

    private fun refresh() {
        refreshes.tryEmit(Unit)
    }

    /** Persisted downloads plus the live progress of the active ones (the index is only updated now and then). */
    private suspend fun reload() {
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                manager.downloadIndex.getDownloads().use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.download) }
                }
            }.getOrDefault(emptyList())
        }
        val byId = stored.associateByTo(LinkedHashMap()) { it.request.id }
        manager.currentDownloads.forEach { byId[it.request.id] = it }
        _episodes.value = byId.values
            .mapNotNull { download -> decode(download.request)?.let { OfflineEpisode(download, it) } }
            .sortedWith(compareBy({ it.info.animeTitle.lowercase() }, { it.info.animeId }, { it.info.index }))
        // Media3 reports no progress events, so poll while something is loading.
        if (_episodes.value.any { it.state == Download.STATE_DOWNLOADING } && ticker == null) {
            ticker = scope.launch {
                delay(1000)
                ticker = null
                refresh()
            }
        }
    }

    fun forVideo(videoId: Long): OfflineEpisode? = _episodes.value.firstOrNull { it.info.videoId == videoId }

    /**
     * Resolves which renditions to keep and queues the download. An episode keeps its download id, so a download that
     * was cut off (an expired link) continues with the new link where it stopped instead of starting over.
     */
    suspend fun enqueue(info: EpisodeInfo, stream: CapturedStream) {
        val withHeaders = info.copy(headers = stream.headers)
        val data = json.encodeToString(EpisodeInfo.serializer(), withHeaders).toByteArray()
        val id = downloadId(info.videoId)
        val request = if (stream.type == StreamType.FILE) {
            DownloadRequest.Builder(id, Uri.parse(stream.url)).setData(data).build()
        } else {
            selectRenditions(id, stream, data)
        }
        DownloadService.sendAddDownload(app, YummyDownloadService::class.java, request, /* foreground = */ true)
    }

    /** The best video quality and every audio track: a stream may carry several dubbings, and audio is small. */
    private suspend fun selectRenditions(id: String, stream: CapturedStream, data: ByteArray): DownloadRequest {
        val mediaItem = MediaItem.Builder().setUri(stream.url).setMimeType(stream.type.mimeType).build()
        val helper = DownloadHelper.Factory()
            .setDataSourceFactory(httpFactory(stream.headers))
            .setRenderersFactory(DefaultRenderersFactory(app))
            .create(mediaItem)
        try {
            val tracksAvailable = suspendCancellableCoroutine { cont ->
                helper.prepare(object : DownloadHelper.Callback {
                    override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) = cont.resume(tracksInfoAvailable)
                    override fun onPrepareError(helper: DownloadHelper, e: IOException) = cont.resumeWithException(e)
                })
            }
            if (tracksAvailable) {
                val languages = (0 until helper.periodCount)
                    .flatMap { period -> helper.getTracks(period).groups }
                    .filter { it.type == C.TRACK_TYPE_AUDIO }
                    .flatMap { group -> (0 until group.length).mapNotNull { group.getTrackFormat(it).language } }
                    .distinct()
                if (languages.size > 1) helper.addAudioLanguagesToSelection(*languages.toTypedArray())
            }
            return helper.getDownloadRequest(id, data)
        } finally {
            helper.release()
        }
    }

    fun remove(id: String) {
        DownloadService.sendRemoveDownload(app, YummyDownloadService::class.java, id, /* foreground = */ false)
    }

    fun removeAnime(animeId: Int) {
        _episodes.value.filter { it.info.animeId == animeId }.forEach { remove(it.id) }
    }

    /** Space left on the storage that holds the downloads. */
    fun freeBytes(): Long = runCatching { StatFs(app.noBackupFilesDir.path).availableBytes }.getOrDefault(0L)

    /** Plays a downloaded episode from the cache; the network is used only for parts that are missing. */
    fun mediaSource(episode: OfflineEpisode): MediaSource {
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(cacheKeys(episode.info.videoId))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true))
            .setCacheWriteDataSinkFactory(null)
        return DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(episode.download.request.toMediaItem())
    }

    private fun cacheKeys(videoId: Long) = CacheKeyFactory { spec -> spec.key ?: stableKey(videoId, spec.uri) ?: spec.uri.toString() }

    fun decode(request: DownloadRequest): EpisodeInfo? =
        runCatching { json.decodeFromString(EpisodeInfo.serializer(), String(request.data)) }.getOrNull()

    private fun httpFactory(headers: Map<String, String>): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(headers[USER_AGENT])
            .setDefaultRequestProperties(headers - USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

    /** Each download is loaded with the headers its player used. */
    private inner class HeadersDownloaderFactory : DownloaderFactory {
        override fun createDownloader(request: DownloadRequest): Downloader {
            val info = decode(request)
            val cacheFactory = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpFactory(info?.headers.orEmpty()))
                .apply { if (info != null) setCacheKeyFactory(cacheKeys(info.videoId)) }
            return DefaultDownloaderFactory(cacheFactory, executor).createDownloader(request)
        }
    }

    companion object {
        const val USER_AGENT = "User-Agent"

        private val MEDIA_EXTENSIONS = setOf("ts", "m4s", "mp4", "m4v", "m4a", "aac", "webm", "mkv", "mov", "vtt")

        fun downloadId(videoId: Long) = "video-$videoId"

        /**
         * Cache key of a media segment without what changes with every link: the CDN host, tokens in the path
         * (long parts without an extension) and long query values (signatures, expiry times). A new link to the same
         * episode then finds the segments already downloaded. Playlists keep their full URL: they are small and are
         * loaded again with every link anyway.
         *
         * `https://edge-7.cdn/0/<token>/seg-12-v1-a1.m4s?sig=...` -> `<videoId>/0/seg-12-v1-a1.m4s`
         */
        fun stableKey(videoId: Long, uri: Uri): String? {
            val name = uri.lastPathSegment ?: return null
            if (name.substringAfterLast('.', "").lowercase() !in MEDIA_EXTENSIONS) return null
            val path = uri.pathSegments.filter { it.length < 20 || '.' in it }.joinToString("/")
            val query = uri.queryParameterNames.sorted().mapNotNull { key ->
                uri.getQueryParameter(key)
                    ?.takeIf { it.length < 16 && !(it.length >= 9 && it.all(Char::isDigit)) }
                    ?.let { "$key=$it" }
            }
            return "$videoId/$path" + if (query.isEmpty()) "" else "?" + query.joinToString("&")
        }
    }
}
