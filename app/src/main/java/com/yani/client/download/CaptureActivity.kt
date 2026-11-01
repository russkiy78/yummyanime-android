package com.yani.client.download

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yani.client.MainActivity
import com.yani.client.R
import com.yani.client.YummyApplication
import com.yani.client.player.PlayerAdapters
import com.yani.client.player.applyPlayerSettings
import com.yani.client.player.tapCenter
import com.yani.client.ui.common.AppTopBar
import com.yani.client.ui.common.ErrorBox
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.ScreenPadding
import com.yani.client.ui.common.YButton
import com.yani.client.ui.common.appContainer
import com.yani.client.ui.common.isTelevision
import com.yani.client.ui.downloads.downloadStateText
import com.yani.client.ui.theme.YummyTheme
import com.yani.client.util.formatDuration
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Prepares downloads: plays each chosen episode in its own embedded player, muted, until the episode stream is found
 * (see [CaptureViewModel]). The player stays visible, so a player that needs a click can be helped by hand.
 * Downloads themselves continue in [YummyDownloadService] after this screen is closed.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ANIME_ID = "anime_id"
        private const val EXTRA_VIDEO_IDS = "video_ids"

        /** Full HD layout: players choose their best quality for it (see [PlayerAdapters.hostHtml]). */
        private const val PLAYER_LAYOUT_WIDTH = 1920

        /**
         * Like "desktop site" in a browser, with this WebView's engine version: players give phones a low quality
         * by default (Kodik: 360p), desktops the best one.
         */
        private fun desktopUserAgent(context: Context): String {
            val version = Regex("""Chrome/([\d.]+)""").find(WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1) ?: "133.0.0.0"
            return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        }

        fun intent(context: Context, animeId: Int, videoIds: List<Long>): Intent =
            Intent(context, CaptureActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .putExtra(EXTRA_VIDEO_IDS, videoIds.toLongArray())
    }

    private val vm: CaptureViewModel by viewModels {
        viewModelFactory {
            initializer {
                CaptureViewModel(
                    container = (application as YummyApplication).container,
                    animeId = intent.getIntExtra(EXTRA_ANIME_ID, 0),
                    videoIds = intent.getLongArrayExtra(EXTRA_VIDEO_IDS)?.toList().orEmpty(),
                    userAgent = desktopUserAgent(this@CaptureActivity),
                )
            }
        }
    }

    private lateinit var webView: WebView
    private var loadedSeq = -1

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Looking through a whole season takes a while; the screen must not lock meanwhile.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val isTv = isTelevision()
        webView = createWebView(isTv)
        if (savedInstanceState == null) askForNotifications()

        setContent {
            YummyTheme {
                CompositionLocalProvider(LocalIsTv provides isTv) {
                    CaptureScreen(
                        vm = vm,
                        webView = webView,
                        onClose = ::finish,
                        onOpenDownloads = {
                            startActivity(MainActivity.downloadsIntent(this))
                            finish()
                        },
                    )
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.ui.map { it.request }.distinctUntilChanged().collect { request ->
                        when {
                            request == null -> if (loadedSeq != -1) {
                                loadedSeq = -1
                                webView.loadUrl("about:blank")
                            }
                            request.seq != loadedSeq -> {
                                loadedSeq = request.seq
                                val html = PlayerAdapters.hostHtml(request.src, viewport = "width=$PLAYER_LAYOUT_WIDTH")
                                webView.loadDataWithBaseURL(PlayerAdapters.HOST_URL, html, "text/html", "utf-8", null)
                            }
                        }
                    }
                }
                launch {
                    vm.commands.collect { command ->
                        when (command) {
                            is CaptureCommand.Send -> webView.evaluateJavascript("window.ySend && ySend(${command.js})", null)
                            CaptureCommand.Tap -> webView.tapCenter()
                        }
                    }
                }
            }
        }
    }

    /** The download notification shows the progress; without the permission it is hidden on Android 13+. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun createWebView(isTv: Boolean): WebView = WebView(this).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        if (isTv) {
            // The remote controls the buttons below; the player is started by the app.
            isFocusable = false
            isFocusableInTouchMode = false
        }
        applyPlayerSettings()
        settings.userAgentString = desktopUserAgent(this@CaptureActivity)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                vm.onRequest(request.url.toString(), request.method, request.requestHeaders.orEmpty())
                return null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // A new activity creates a new WebView; the ViewModel looks for the same episode again.
                recreate()
                return true
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(this, StreamCapture.SCRIPT, setOf("*"))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Any origin: the reports come from the players' own iframes. They are only hints, every one is checked.
            WebViewCompat.addWebMessageListener(this, "yummyCapture", setOf("*")) { _, message, _, _, _ ->
                message.data?.let(vm::onScriptReport)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        webView.onResume()
        vm.resume()
    }

    override fun onStop() {
        vm.pause()
        webView.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}

@Composable
private fun CaptureScreen(vm: CaptureViewModel, webView: WebView, onClose: () -> Unit, onOpenDownloads: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            AppTopBar(stringResource(R.string.download_preparing), onBack = onClose)
            val error = ui.error
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> ErrorBox(error, vm::load)
                landscape -> Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = ScreenPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.weight(1.2f)) { PlayerPreview(webView, ui) }
                    Column(Modifier.weight(1f)) { CapturePanel(ui, vm, onClose, onOpenDownloads) }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(horizontal = ScreenPadding)) { PlayerPreview(webView, ui) }
                    CapturePanel(ui, vm, onClose, onOpenDownloads)
                }
            }
        }
    }
}

@Composable
private fun PlayerPreview(webView: WebView, ui: CaptureUi) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                webView
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (ui.request == null) {
            Icon(
                if (ui.finished) Icons.Filled.CheckCircle else Icons.Filled.DownloadForOffline,
                null,
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapturePanel(ui: CaptureUi, vm: CaptureViewModel, onClose: () -> Unit, onOpenDownloads: () -> Unit) {
    val downloads by appContainer().downloads.episodes.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("status") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val current = ui.current
                val status = when {
                    ui.finished -> if (ui.failed > 0) {
                        stringResource(R.string.download_prepared_with_failures, ui.queued, ui.failed)
                    } else {
                        stringResource(R.string.download_prepared, ui.queued)
                    }
                    current?.status == CaptureStatus.QUEUING -> stringResource(R.string.download_queuing, current.video.number)
                    current != null -> stringResource(R.string.download_searching, current.video.number, formatDuration(ui.elapsed.toDouble()))
                    else -> stringResource(R.string.download_paused)
                }
                Text(status, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(if (ui.finished) R.string.download_prepared_hint else R.string.download_searching_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.finished) {
                        YButton(stringResource(R.string.open_downloads), onClick = onOpenDownloads, icon = Icons.Filled.DownloadForOffline)
                        if (ui.failed > 0) YButton(stringResource(R.string.retry_failed), onClick = vm::retryFailed, icon = Icons.Filled.Refresh, primary = false)
                        YButton(stringResource(R.string.close), onClick = onClose, icon = Icons.Filled.Close, primary = false)
                    } else {
                        YButton(
                            stringResource(R.string.download_skip_episode),
                            onClick = vm::skip,
                            icon = Icons.Filled.SkipNext,
                            enabled = current?.status == CaptureStatus.SEARCHING,
                        )
                        YButton(stringResource(R.string.download_stop), onClick = onClose, icon = Icons.Filled.Close, primary = false)
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        items(ui.items, key = { it.video.id }) { item -> CaptureRow(item, downloads.firstOrNull { it.info.videoId == item.video.id }) }
    }
}

@Composable
private fun CaptureRow(item: CaptureItem, download: OfflineEpisode?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (item.status) {
                CaptureStatus.SEARCHING, CaptureStatus.QUEUING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                CaptureStatus.QUEUED -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                CaptureStatus.FAILED, CaptureStatus.DOWNLOAD_FAILED -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                CaptureStatus.SKIPPED -> Icon(Icons.Filled.SkipNext, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                CaptureStatus.WAITING -> Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.episode_n, item.video.number), style = MaterialTheme.typography.bodyLarge)
            val failed = item.status == CaptureStatus.FAILED || item.status == CaptureStatus.DOWNLOAD_FAILED
            Text(
                if (item.status == CaptureStatus.QUEUED && download != null) {
                    downloadStateText(download)
                } else {
                    stringResource(
                        when (item.status) {
                            CaptureStatus.WAITING -> R.string.capture_waiting
                            CaptureStatus.SEARCHING -> R.string.capture_searching
                            CaptureStatus.QUEUING -> R.string.capture_queuing
                            CaptureStatus.QUEUED -> R.string.capture_queued
                            CaptureStatus.FAILED -> R.string.capture_failed
                            CaptureStatus.DOWNLOAD_FAILED -> R.string.capture_download_failed
                            CaptureStatus.SKIPPED -> R.string.capture_skipped
                        },
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
