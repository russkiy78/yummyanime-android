package com.yani.client.data

import java.util.concurrent.TimeUnit

class AuthManager(
    private val api: YummyApi,
    private val prefs: Prefs,
) {
    /** Logs in, then loads the profile to learn the user id. Throws [ApiException] (420 = captcha needed). */
    suspend fun login(login: String, password: String, captcha: String? = null) {
        val token = api.login(login.trim(), password, captcha)
        prefs.setPendingToken(token)
        try {
            val profile = api.profile()
            prefs.saveSession(Session(token, profile.id, profile.nickname, absUrl(profile.avatars?.big ?: profile.avatars?.small)))
        } catch (e: Exception) {
            prefs.setPendingToken(null)
            throw e
        }
    }

    /** Tokens live two weeks; the API docs recommend refreshing every 2-3 days. */
    suspend fun refreshIfNeeded() {
        prefs.session.value ?: return
        val age = System.currentTimeMillis() - prefs.tokenSavedAt
        if (age < TimeUnit.DAYS.toMillis(2)) return
        try {
            prefs.updateToken(api.refreshToken())
            val profile = api.profile()
            prefs.updateProfile(profile.nickname, absUrl(profile.avatars?.big ?: profile.avatars?.small))
        } catch (e: ApiException) {
            if (e.isUnauthorized) prefs.clearSession()
        } catch (_: Exception) {
            // Offline: try again next time.
        }
    }

    suspend fun logout() {
        runCatching { api.logout() }
        prefs.clearSession()
    }
}
