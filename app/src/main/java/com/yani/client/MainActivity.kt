package com.yani.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.offline.DownloadService
import com.yani.client.download.YummyDownloadService
import com.yani.client.ui.AppRoot
import com.yani.client.ui.Routes
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.isTelevision
import com.yani.client.ui.theme.YummyTheme
import com.yani.client.util.animeAliasFromPath

class MainActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_OPEN_DOWNLOADS = "open_downloads"

        /** Opens the downloads screen, e.g. from the download notification. */
        fun downloadsIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_DOWNLOADS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    /** Route to open: an anime from a website link, or the downloads screen. */
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink = routeFrom(intent)
        // Continues downloads that were interrupted when the app was closed; the service stops itself if there are none.
        runCatching { DownloadService.start(this, YummyDownloadService::class.java) }
        val isTv = isTelevision()
        setContent {
            YummyTheme {
                CompositionLocalProvider(LocalIsTv provides isTv) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        AppRoot(deepLinkRoute = deepLink, onDeepLinkConsumed = { deepLink = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)?.let { deepLink = it }
    }

    private fun routeFrom(intent: Intent?): String? = when {
        intent == null -> null
        intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) -> Routes.DOWNLOADS
        intent.action == Intent.ACTION_VIEW -> intent.data?.path?.let(::animeAliasFromPath)?.let(Routes::anime)
        else -> null
    }
}
