package com.yani.client.data

import java.util.concurrent.TimeUnit

class AuthManager(
    private val api: YummyApi,
    private val prefs: Prefs,
) {
    /**
     * Logs in, then loads the profile to learn the user id. The account is added to the saved ones and becomes active.
     * Throws [ApiException] (420 = captcha needed).
     */
    suspend fun login(login: String, password: String, captcha: String? = null) {
        val token = api.login(login.trim(), password, captcha)
        val profile = api.profile(token)
        prefs.addAccount(Session(token, profile.id, profile.nickname, profile.avatarUrl))
    }

    /**
     * Tokens live two weeks; the API docs recommend refreshing every 2-3 days. All saved accounts are refreshed,
     * so switching to one that was not used for a while still works. Rejected tokens are forgotten by [YummyApi].
     */
    suspend fun refreshIfNeeded() {
        for (account in prefs.accounts.value) {
            val age = System.currentTimeMillis() - account.tokenSavedAt
            if (age < TimeUnit.DAYS.toMillis(2)) continue
            try {
                val token = api.refreshToken(account.token)
                val profile = api.profile(token)
                prefs.updateAccount(account.userId) {
                    it.copy(token = token, tokenSavedAt = System.currentTimeMillis(), nickname = profile.nickname, avatar = profile.avatarUrl)
                }
            } catch (_: Exception) {
                // Offline: try again next time.
            }
        }
    }

    /** Logs the active account out and removes it from the device; other saved accounts stay. */
    suspend fun logout() {
        val session = prefs.session.value ?: return
        prefs.removeAccount(session.userId)
        runCatching { api.logout(session.token) }
    }
}
