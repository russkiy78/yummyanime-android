package com.yani.client.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.yani.client.data.ApiException
import com.yani.client.data.Prefs
import com.yani.client.data.YummyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Progress of one episode watched offline that has not reached the server yet. */
@Serializable
data class PendingProgress(
    val videoId: Long,
    /** The account that was active while watching. */
    val userId: Int,
    val position: Int,
    val duration: Int,
    val times: List<Int>,
)

/**
 * Watch progress of downloaded episodes: lets the offline player resume, and is sent to the account
 * (the same request the online player makes) as soon as there is a connection.
 */
class OfflineProgress(
    context: Context,
    private val json: Json,
    private val api: YummyApi,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private val sp = context.getSharedPreferences("offline_progress", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(PendingProgress.serializer())
    private val syncLock = Mutex()

    init {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch { sync() }
                }
            })
        }
    }

    /** Last position in the offline player, seconds. */
    fun position(videoId: Long): Double? = sp.getFloat(positionKey(videoId), -1f).takeIf { it >= 0 }?.toDouble()

    /** Saves the position and queues [times] (seconds seen) for the account. */
    @Synchronized
    fun record(videoId: Long, position: Double, duration: Double, times: Collection<Int>) {
        val editor = sp.edit().putFloat(positionKey(videoId), position.toFloat())
        val userId = prefs.session.value?.userId
        if (userId != null && times.isNotEmpty()) {
            val pending = readPending()
            val previous = pending.find { it.videoId == videoId && it.userId == userId }
            val merged = PendingProgress(
                videoId = videoId,
                userId = userId,
                position = position.toInt(),
                duration = duration.toInt(),
                times = ((previous?.times ?: emptyList()) + times).distinct().sorted(),
            )
            editor.putString(K_PENDING, json.encodeToString(serializer, pending.filter { it !== previous } + merged))
        }
        editor.apply()
    }

    /** Sends queued progress. Entries of accounts that were logged out are dropped; network errors keep them. */
    suspend fun sync() = syncLock.withLock {
        for (item in synchronized(this) { readPending() }) {
            val account = prefs.accounts.value.find { it.userId == item.userId }
            val sent = account == null || try {
                api.markWatched(item.videoId, item.position, item.duration, item.times, token = account.token)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // Refused by the server (e.g. the video is gone): retrying would not help, unless the anti-bot layer answered.
                Log.w(TAG, "offline progress of ${item.videoId} rejected", e)
                !e.isRejectedAsBot
            } catch (_: Exception) {
                false
            }
            if (sent) removePending(item)
        }
    }

    @Synchronized
    private fun removePending(item: PendingProgress) {
        // Compared by value: the entry may have been replaced by newer progress meanwhile.
        val rest = readPending().filter { it != item }
        sp.edit().putString(K_PENDING, json.encodeToString(serializer, rest)).apply()
    }

    private fun readPending(): List<PendingProgress> =
        sp.getString(K_PENDING, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private fun positionKey(videoId: Long) = "pos_$videoId"

    private companion object {
        const val TAG = "OfflineProgress"
        const val K_PENDING = "pending"
    }
}
