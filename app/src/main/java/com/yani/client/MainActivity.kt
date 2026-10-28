package com.yani.client

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
import com.yani.client.ui.AppRoot
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.isTelevision
import com.yani.client.ui.theme.YummyTheme
import com.yani.client.util.animeAliasFromPath

class MainActivity : AppCompatActivity() {
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink = aliasFrom(intent)
        val isTv = isTelevision()
        setContent {
            YummyTheme {
                CompositionLocalProvider(LocalIsTv provides isTv) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        AppRoot(deepLinkAlias = deepLink, onDeepLinkConsumed = { deepLink = null })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        aliasFrom(intent)?.let { deepLink = it }
    }

    private fun aliasFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.path?.let(::animeAliasFromPath)
}
