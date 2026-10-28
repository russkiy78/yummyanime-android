package com.yani.client.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A logged-in account. Several can be saved on the device, one of them is active. */
@Serializable
data class Session(
    val token: String,
    val userId: Int,
    val nickname: String,
    val avatar: String?,
    /** When [token] was received, to refresh it before it expires. */
    val tokenSavedAt: Long = System.currentTimeMillis(),
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

/** Small synchronous store: the token is needed by every request without suspending. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("yummy", Context.MODE_PRIVATE)

    private val _accounts = MutableStateFlow(readAccounts())
    /** Every account logged in on this device, in the order they were added. */
    val accounts: StateFlow<List<Session>> = _accounts.asStateFlow()

    private val _session = MutableStateFlow(_accounts.value.find { it.userId == sp.getInt(K_ACTIVE_USER, 0) })
    /** The active account, null when nobody is logged in. */
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Token of the active account, used for requests. */
    val token: String? get() = _session.value?.token

    /** Saves a freshly logged-in account, replacing an older login of the same user, and makes it active. */
    @Synchronized
    fun addAccount(account: Session) =
        saveAccounts(_accounts.value.filter { it.userId != account.userId } + account, active = account.userId)

    @Synchronized
    fun switchAccount(userId: Int) {
        if (_accounts.value.any { it.userId == userId }) saveAccounts(_accounts.value, active = userId)
    }

    /** Changes a saved account, e.g. after a token refresh. Does nothing if it was removed meanwhile. */
    @Synchronized
    fun updateAccount(userId: Int, transform: (Session) -> Session) =
        saveAccounts(_accounts.value.map { if (it.userId == userId) transform(it) else it }, active = _session.value?.userId)

    /** Forgets an account. If it was the active one, nobody is logged in afterwards. */
    @Synchronized
    fun removeAccount(userId: Int) =
        saveAccounts(_accounts.value.filter { it.userId != userId }, active = _session.value?.userId?.takeIf { it != userId })

    /** The server rejected [token]: forgets the account it belongs to. */
    @Synchronized
    fun forgetToken(token: String) {
        _accounts.value.find { it.token == token }?.let { removeAccount(it.userId) }
    }

    private fun saveAccounts(accounts: List<Session>, active: Int?) {
        sp.edit()
            .putString(K_ACCOUNTS, json.encodeToString(accountsSerializer, accounts))
            .putInt(K_ACTIVE_USER, active ?: 0)
            .apply()
        _accounts.value = accounts
        _session.value = accounts.find { it.userId == active }
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

    private fun readAccounts(): List<Session> {
        sp.getString(K_ACCOUNTS, null)?.let { stored ->
            return runCatching { json.decodeFromString(accountsSerializer, stored) }.getOrDefault(emptyList())
        }
        // Versions without multi-account support stored a single session in separate keys.
        val token = sp.getString(K_TOKEN, null) ?: return emptyList()
        val userId = sp.getInt(K_USER_ID, 0).takeIf { it > 0 } ?: return emptyList()
        val legacy = Session(token, userId, sp.getString(K_NICK, null).orEmpty(), sp.getString(K_AVATAR, null), sp.getLong(K_TOKEN_TIME, 0))
        sp.edit()
            .remove(K_TOKEN).remove(K_TOKEN_TIME).remove(K_USER_ID).remove(K_NICK).remove(K_AVATAR)
            .putString(K_ACCOUNTS, json.encodeToString(accountsSerializer, listOf(legacy)))
            .putInt(K_ACTIVE_USER, userId)
            .apply()
        return listOf(legacy)
    }

    private fun readSettings() = AppSettings(
        preferredPlayer = sp.getString(K_PLAYER, null).orEmpty(),
        preferredDubbing = sp.getString(K_DUBBING, null).orEmpty(),
        autoNext = sp.getBoolean(K_AUTO_NEXT, true),
        autoSkipOpening = sp.getBoolean(K_AUTO_SKIP, false),
        appToken = sp.getString(K_APP_TOKEN, null).orEmpty(),
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val accountsSerializer = ListSerializer(Session.serializer())

        const val K_ACCOUNTS = "accounts"
        const val K_ACTIVE_USER = "active_user_id"

        // Single-session keys of older versions, read once for migration.
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
