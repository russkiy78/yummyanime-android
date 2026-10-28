package com.yani.client.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yani.client.BuildConfig
import com.yani.client.R
import com.yani.client.YummyApplication
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.isTelevision
import com.yani.client.ui.theme.YummyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ANIME_ID = "anime_id"
        private const val EXTRA_VIDEO_ID = "video_id"

        fun intent(context: Context, animeId: Int, videoId: Long?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ANIME_ID, animeId)
                .apply { if (videoId != null) putExtra(EXTRA_VIDEO_ID, videoId) }
    }

    private val vm: PlayerViewModel by viewModels {
        viewModelFactory {
            initializer {
                PlayerViewModel(
                    container = (application as YummyApplication).container,
                    animeId = intent.getIntExtra(EXTRA_ANIME_ID, 0),
                    requestedVideoId = intent.getLongExtra(EXTRA_VIDEO_ID, -1L).takeIf { it > 0 },
                )
            }
        }
    }

    private lateinit var webView: WebView
    private var isTv = false
    private var loadedSeq = -1

    // State shared with Compose.
    /** Video fills the screen. Toggled by the user on every device; TV starts in fullscreen. */
    private var fullscreen by mutableStateOf(false)
    /** Episode/dubbing panel shown over the video in fullscreen. */
    private var panelOpen by mutableStateOf(false)
    /** On-screen info / phone controls, shown for a few seconds after input. */
    private var controlsVisible by mutableStateOf(true)
    private var customView by mutableStateOf<View?>(null)
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var hideControlsJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = isTelevision()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        webView = createWebView()
        fullscreen = isTv

        setContent {
            YummyTheme {
                CompositionLocalProvider(LocalIsTv provides isTv) {
                    PlayerScreen(
                        vm = vm,
                        webView = webView,
                        isTv = isTv,
                        fullscreen = fullscreen,
                        panelOpen = panelOpen,
                        controlsVisible = controlsVisible,
                        customView = customView,
                        actions = PlayerScreenActions(
                            onBack = ::handleBack,
                            onToggleFullscreen = { changeFullscreen(!fullscreen) },
                            onOpenPanel = { panelOpen = true },
                            onClosePanel = { panelOpen = false; showControls() },
                            onImmersiveChanged = ::applySystemBars,
                            onEpisodeChosen = { panelOpen = false },
                            onTogglePlay = {
                                showControls()
                                if (!vm.togglePlay()) tapCenter()
                            },
                            onCenterKey = ::onCenter,
                            onSeek = { seconds -> showControls(); vm.seekBy(seconds) },
                        ),
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.ui.map { it.request }.distinctUntilChanged().collect { request ->
                        if (request != null && request.seq != loadedSeq) {
                            loadedSeq = request.seq
                            webView.loadDataWithBaseURL(
                                PlayerAdapters.HOST_URL,
                                PlayerAdapters.hostHtml(request.src),
                                "text/html",
                                "utf-8",
                                null,
                            )
                            showControls()
                        }
                    }
                }
                launch {
                    vm.commands.collect { js -> webView.evaluateJavascript("window.ySend && ySend($js)", null) }
                }
                launch {
                    vm.notices.collect { notice ->
                        val text = when (notice) {
                            PlayerNotice.NextEpisode -> R.string.next_episode_starting
                            PlayerNotice.Unsupported -> R.string.player_control_unsupported
                            PlayerNotice.OpeningSkipped -> R.string.opening_skipped
                        }
                        Toast.makeText(this@PlayerActivity, text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        if (isTv) {
            // Remote keys are handled by the activity; focus must stay in the Compose overlay.
            isFocusable = false
            isFocusableInTouchMode = false
        }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Popups (ads) are dropped: onCreateWindow is not implemented.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            // Some players serve a degraded page to WebView user agents.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webViewClient = object : WebViewClient() {
            // Never let anything navigate the host page away; iframes navigate freely.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame

            override fun onPageFinished(view: WebView, url: String?) {
                vm.onHostPageLoaded()
            }

            // Without this a crashed or killed renderer (low memory on TV boxes) takes the whole app down.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                vm.flush(final = true)
                Toast.makeText(this@PlayerActivity, R.string.player_crashed, Toast.LENGTH_SHORT).show()
                // A new activity instance creates a new WebView; the ViewModel keeps the selection.
                recreate()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customViewCallback = callback
                if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                customView = null
                customViewCallback = null
                if (!isTv && !fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // Messages from the host page: prefer the origin-restricted listener when available.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(this, "yummyBridge", setOf(PlayerAdapters.HOST_ORIGIN)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) message.data?.let(vm::onMessage)
            }
        } else {
            addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(data: String) {
                    runOnUiThread { vm.onMessage(data) }
                }
            }, "YummyAndroid")
        }

        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) showControls()
            false
        }
    }

    private fun immersive() = fullscreen || customView != null

    private fun changeFullscreen(on: Boolean) {
        fullscreen = on
        panelOpen = false
        if (!isTv) {
            requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        showControls()
    }

    private fun applySystemBars(immersive: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (immersive) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showControls() {
        controlsVisible = true
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(3500)
            controlsVisible = false
        }
    }

    private fun handleBack() {
        when {
            customView != null && !isTv -> {
                customViewCallback?.onCustomViewHidden()
                customView = null
            }
            panelOpen -> panelOpen = false
            fullscreen && !isTv -> changeFullscreen(false)
            else -> finish()
        }
    }

    // ---- Remote control ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handlePlayerKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handlePlayerKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        // Media keys of remotes and headsets work in every mode.
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (first) onCenter()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (first) vm.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (first) vm.pause()
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) seek(-1, event.repeatCount)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seek(1, event.repeatCount)
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (first) vm.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (first) vm.previous()
            else -> {
                // In windowed mode the D-pad moves focus between the video and the panel.
                if (!immersive() || panelOpen) return false
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A,
                    -> if (first) onCenter()

                    KeyEvent.KEYCODE_DPAD_LEFT -> if (down) seek(-1, event.repeatCount)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) seek(1, event.repeatCount)
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
                    KeyEvent.KEYCODE_GUIDE,
                    -> if (first) panelOpen = true

                    KeyEvent.KEYCODE_CHANNEL_UP -> if (first) vm.next()
                    KeyEvent.KEYCODE_CHANNEL_DOWN -> if (first) vm.previous()
                    else -> return false
                }
            }
        }
        return true
    }

    private fun onCenter() {
        showControls()
        if (vm.playback.value.canSkipOpening) {
            vm.skipOpening()
            return
        }
        if (!vm.togglePlay()) tapCenter()
    }

    private fun seek(direction: Int, repeat: Int) {
        showControls()
        val step = when {
            repeat > 20 -> 60
            repeat > 6 -> 30
            else -> 10
        }
        vm.seekBy(direction * step)
    }

    /** For players without a postMessage API: a tap in the middle toggles playback in most of them. */
    private fun tapCenter() {
        val x = webView.width / 2f
        val y = webView.height / 2f
        val time = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 60).forEach { (action, at) ->
            val ev = MotionEvent.obtain(time, at, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    // ---- Lifecycle ----

    override fun onStart() {
        super.onStart()
        webView.onResume()
    }

    override fun onStop() {
        vm.pauseScript()?.let { webView.evaluateJavascript("window.ySend && ySend($it)", null) }
        vm.flush(final = true)
        webView.onPause()
        super.onStop()
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
