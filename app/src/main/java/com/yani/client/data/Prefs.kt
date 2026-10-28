package com.yani.client.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Session(
    val token: String,
    val userId: Int,
    val nickname: String,
    val avatar: String?,
)

data class AppSettings(
    /** Player key ("kodik", "alloha", "cvh", "aksor"), empty = automatic. */
    val preferredPlayer: String = "",
    /** Last chosen dubbing, used as default for anime the user has not watched yet. */
    val preferredDubbing: String = "",
    val autoNext: Boolean = true,
    val autoSkipOpening: Boolean = false,
    /** X-Application token; empty = use the one from BuildConfig. */
    val appToken: String = "",
)

/** Small synchronous store: the token is needed by the OkHttp interceptor without suspending. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("yummy", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(readSession())
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Token used for requests. Can be set before the session is complete (during login). */
    @Volatile
    var token: String? = _session.value?.token
        private set

    val tokenSavedAt: Long get() = sp.getLong(K_TOKEN_TIME, 0)

    fun setPendingToken(token: String?) {
        this.token = token
    }

    fun saveSession(session: Session) {
        sp.edit()
            .putString(K_TOKEN, session.token)
            .putLong(K_TOKEN_TIME, System.currentTimeMillis())
            .putInt(K_USER_ID, session.userId)
            .putString(K_NICK, session.nickname)
            .putString(K_AVATAR, session.avatar)
            .apply()
        token = session.token
        _session.value = session
    }

    fun updateToken(newToken: String) {
        val current = _session.value ?: return
        sp.edit().putString(K_TOKEN, newToken).putLong(K_TOKEN_TIME, System.currentTimeMillis()).apply()
        token = newToken
        _session.value = current.copy(token = newToken)
    }

    fun updateProfile(nickname: String, avatar: String?) {
        val current = _session.value ?: return
        sp.edit().putString(K_NICK, nickname).putString(K_AVATAR, avatar).apply()
        _session.value = current.copy(nickname = nickname, avatar = avatar)
    }

    fun clearSession() {
        sp.edit().remove(K_TOKEN).remove(K_TOKEN_TIME).remove(K_USER_ID).remove(K_NICK).remove(K_AVATAR).apply()
        token = null
        _session.value = null
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        val s = _settings.value
        sp.edit()
            .putString(K_PLAYER, s.preferredPlayer)
            .putString(K_DUBBING, s.preferredDubbing)
            .putBoolean(K_AUTO_NEXT, s.autoNext)
            .putBoolean(K_AUTO_SKIP, s.autoSkipOpening)
            .putString(K_APP_TOKEN, s.appToken)
            .apply()
    }

    private fun readSession(): Session? {
        val token = sp.getString(K_TOKEN, null) ?: return null
        val userId = sp.getInt(K_USER_ID, 0).takeIf { it > 0 } ?: return null
        return Session(token, userId, sp.getString(K_NICK, null).orEmpty(), sp.getString(K_AVATAR, null))
    }

    private fun readSettings() = AppSettings(
        preferredPlayer = sp.getString(K_PLAYER, null).orEmpty(),
        preferredDubbing = sp.getString(K_DUBBING, null).orEmpty(),
        autoNext = sp.getBoolean(K_AUTO_NEXT, true),
        autoSkipOpening = sp.getBoolean(K_AUTO_SKIP, false),
        appToken = sp.getString(K_APP_TOKEN, null).orEmpty(),
    )

    private companion object {
        const val K_TOKEN = "token"
        const val K_TOKEN_TIME = "token_time"
        const val K_USER_ID = "user_id"
        const val K_NICK = "nickname"
        const val K_AVATAR = "avatar"
        const val K_PLAYER = "preferred_player"
        const val K_DUBBING = "preferred_dubbing"
        const val K_AUTO_NEXT = "auto_next"
        const val K_AUTO_SKIP = "auto_skip_opening"
        const val K_APP_TOKEN = "app_token"
    }
}
