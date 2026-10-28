package com.yani.client.util

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Per-app UI language. The API content language (`Lang` header) follows the UI language. */
object AppLanguage {
    /** UI languages shipped with the app. English is the default resource set. */
    val supported = listOf("en", "ru")

    /** Explicitly selected language tag, or null when following the system. */
    fun selected(): String? =
        AppCompatDelegate.getApplicationLocales().get(0)?.language?.takeIf { it in supported }

    fun select(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }

    /** Language that is actually used for the UI right now. */
    fun effective(): String {
        selected()?.let { return it }
        val system = ConfigurationCompat.getLocales(Resources.getSystem().configuration).get(0) ?: Locale.getDefault()
        return if (system.language in supported) system.language else "en"
    }

    /** Value for the API `Lang` header (the API also knows "uk", but the UI does not). */
    fun apiLanguage(): String = effective()
}
