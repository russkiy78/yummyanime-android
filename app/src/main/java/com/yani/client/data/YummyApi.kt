package com.yani.client.data

import android.os.Build
import com.yani.client.BuildConfig
import com.yani.client.util.AppLanguage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(
    val httpCode: Int,
    val apiCode: Int?,
    message: String,
) : IOException(message) {
    /** 420: the server wants an hCaptcha response (too many login attempts). */
    val needsCaptcha: Boolean get() = httpCode == 420
    val isUnauthorized: Boolean get() = httpCode == 401
}

/** Adds the headers the API expects: app token, language, image formats and the user token. */
class ApiHeadersInterceptor(private val prefs: Prefs) : Interceptor {
    private val userAgent = "YummyAndroid/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
            .header("Accept", "application/json, image/webp")
            .header("Lang", AppLanguage.apiLanguage())
            .header("User-Agent", userAgent)
        val appToken = prefs.settings.value.appToken.ifBlank { BuildConfig.APP_TOKEN }
        if (appToken.isNotBlank()) builder.header("X-Application", appToken)
        prefs.token?.let { builder.header("Authorization", "Yummy $it") }
        return chain.proceed(builder.build())
    }
}

/** Filter for GET /anime. */
data class CatalogQuery(
    val query: String = "",
    val sort: String = "top",
    val sortForward: Boolean = true,
    val types: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    val genres: Set<Int> = emptySet(),
    val translates: Set<String> = emptySet(),
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val excludeLists: Set<String> = emptySet(),
) {
    val activeFilters: Int
        get() = listOf(types.isNotEmpty(), statuses.isNotEmpty(), genres.isNotEmpty(), translates.isNotEmpty(),
            fromYear != null || toYear != null, excludeLists.isNotEmpty()).count { it }

    fun toParams(): Map<String, Any?> = mapOf(
        "q" to query.trim().takeIf { it.isNotEmpty() },
        // Sorting is ignored by the server when searching by text, relevance is used instead.
        "sort" to sort.takeIf { query.isBlank() },
        "sort_forward" to sortForward.takeIf { query.isBlank() },
        "types" to types,
        "status" to statuses,
        "genres" to genres,
        "translates" to translates,
        "from_year" to fromYear,
        "to_year" to toYear,
        "exclude_list" to excludeLists,
    )
}

class YummyApi(
    private val http: OkHttpClient,
    private val json: Json,
    private val prefs: Prefs,
) {
    // ---- Auth / profile ----

    suspend fun login(login: String, password: String, captcha: String?): String {
        val body = buildJsonObject {
            put("login", login)
            put("password", password)
            put("need_json", true)
            if (captcha != null) put("recaptcha_response", captcha)
        }
        val response = call("POST", "profile/login", body = body)
        return (response as? JsonObject)?.get("token")?.jsonPrimitive?.contentOrNull
            ?: throw ApiException(200, null, "No token in response")
    }

    suspend fun refreshToken(): String =
        call("GET", "profile/token").jsonObject["token"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException(200, null, "No token in response")

    suspend fun logout() {
        call("POST", "profile/logout")
    }

    suspend fun profile(): Profile = get("profile")

    suspend fun notifications(offset: Int, limit: Int = 30): List<NotificationItem> =
        get("profile/notifications", mapOf("offset" to offset, "limit" to limit))

    suspend fun readAllNotifications() {
        call("POST", "profile/notifications/read")
    }

    // ---- Anime ----

    suspend fun feed(): Feed = get("feed")

    suspend fun anime(idOrAlias: String): AnimeDetails = get("anime/${idOrAlias.trim('/')}")

    suspend fun videos(animeId: Int): List<Video> = get("anime/$animeId/videos")

    suspend fun recommendations(animeId: Int): List<AnimeItem> = get("anime/$animeId/recommendations")

    suspend fun catalog(query: CatalogQuery, offset: Int, limit: Int = 30): List<AnimeItem> =
        get("anime", query.toParams() + mapOf("offset" to offset, "limit" to limit))

    suspend fun genres(): GenresResponse = get("anime/genres")

    suspend fun schedule(): List<AnimeItem> = get("anime/schedule")

    // ---- User lists / rating ----

    suspend fun userLists(userId: Int): List<AnimeItem> = get("users/$userId/lists")

    suspend fun setList(animeId: Int, list: UserList) {
        call("PUT", "anime/$animeId/list", body = buildJsonObject {
            put("list", list.id)
            put("date", System.currentTimeMillis() / 1000)
        })
    }

    suspend fun removeFromList(animeId: Int) {
        call("DELETE", "anime/$animeId/list")
    }

    suspend fun setFavorite(animeId: Int, favorite: Boolean) {
        if (favorite) {
            call("PUT", "anime/$animeId/list/fav", body = buildJsonObject { put("date", System.currentTimeMillis() / 1000) })
        } else {
            call("DELETE", "anime/$animeId/list/fav")
        }
    }

    suspend fun rate(animeId: Int, rate: Int) {
        call("PUT", "anime/$animeId/rate", body = buildJsonObject { put("rate", rate) })
    }

    suspend fun deleteRating(animeId: Int) {
        call("DELETE", "anime/$animeId/rate")
    }

    // ---- Watching ----

    suspend fun watchHistory(offset: Int, limit: Int = 20): List<AnimeItem> =
        get("video/watch-history", mapOf("offset" to offset, "limit" to limit))

    /** Same payload as the website: current position, duration and the seconds seen since the last sync. */
    suspend fun markWatched(videoId: Long, time: Int, duration: Int, times: List<Int>) {
        call("PUT", "video/$videoId", body = buildJsonObject {
            put("time", time)
            put("duration", duration)
            put("date", System.currentTimeMillis() / 1000)
            putJsonArray("times") { times.forEach { add(it) } }
        })
    }

    suspend fun removeWatched(videoId: Long) {
        call("DELETE", "video/$videoId")
    }

    // ---- Comments ----

    suspend fun comments(animeId: Int, sort: String, skip: Int, limit: Int = 20): List<Comment> =
        get<CommentsPage>("comments/anime/$animeId", mapOf("sort" to sort, "skip" to skip, "limit" to limit)).comments

    suspend fun commentChildren(commentId: Long): List<Comment> =
        get<CommentsPage>("comments/$commentId/children").comments

    suspend fun postComment(animeId: Int, text: String, parentId: Long?): Comment =
        decode(call("POST", "comments/anime/$animeId", body = buildJsonObject {
            put("text", text)
            if (parentId != null) {
                put("parent_comment", parentId)
                put("reply_to_comment", parentId)
            }
        }))

    // ---- Collections ----

    suspend fun collections(sort: String, offset: Int, limit: Int = 24): List<CollectionItem> =
        get("collection", mapOf("sort" to sort, "offset" to offset, "limit" to limit))

    suspend fun collection(id: Int): CollectionItem = get("collection/$id")

    // ---- Plumbing ----

    private suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        decode(call("GET", path, query))

    private inline fun <reified T> decode(element: JsonElement): T = json.decodeFromJsonElement(element)

    private suspend fun call(
        method: String,
        path: String,
        query: Map<String, Any?> = emptyMap(),
        body: JsonObject? = null,
    ): JsonElement {
        val url = BASE_URL.toHttpUrl().newBuilder().addPathSegments(path).apply {
            for ((key, value) in query) {
                when (value) {
                    null -> Unit
                    is Collection<*> -> if (value.isNotEmpty()) addQueryParameter(key, value.joinToString(","))
                    else -> addQueryParameter(key, value.toString())
                }
            }
        }.build()
        val requestBody = when {
            body != null -> body.toString().toRequestBody(JSON_MEDIA)
            method == "GET" || method == "DELETE" -> null
            else -> "{}".toRequestBody(JSON_MEDIA)
        }
        val hadToken = prefs.token != null
        val (code, text) = http.newCall(Request.Builder().url(url).method(method, requestBody).build()).fetch()

        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val obj = root as? JsonObject
        if (code !in 200..299 || obj?.containsKey("error") == true) {
            val apiCode = obj?.get("error_code")?.jsonPrimitive?.intOrNull
            val message = obj?.get("error_title")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("error")?.jsonPrimitive?.contentOrNull
                ?: "HTTP $code"
            // error_code 1 = NotAuthorizedError: the token is invalid or expired.
            if (code == 401 && apiCode == 1 && hadToken) prefs.clearSession()
            throw ApiException(code, apiCode, message)
        }
        return obj?.get("response") ?: JsonNull
    }

    companion object {
        const val BASE_URL = "https://api.yani.tv"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

private suspend fun Call.fetch(): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = try {
                response.use { it.code to it.body.string() }
            } catch (e: IOException) {
                cont.resumeWithException(e)
                return
            }
            cont.resume(result)
        }
    })
}
