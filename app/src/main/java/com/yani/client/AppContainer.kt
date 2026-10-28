package com.yani.client

import android.app.Application
import com.yani.client.data.ApiHeadersInterceptor
import com.yani.client.data.AuthManager
import com.yani.client.data.Prefs
import com.yani.client.data.YummyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container, created once in [YummyApplication]. */
class AppContainer(app: Application) {
    val prefs = Prefs(app)

    /** For work that must outlive a screen, e.g. the final progress sync when the player closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val apiHttp: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor(ApiHeadersInterceptor(prefs))
        .build()

    val imageHttp: OkHttpClient = baseHttp.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Referer", "https://yummyani.me/")
                    .header("Accept", "image/webp,image/*;q=0.8")
                    .build(),
            )
        }
        .build()

    val api = YummyApi(apiHttp, json, prefs)
    val auth = AuthManager(api, prefs)
}
