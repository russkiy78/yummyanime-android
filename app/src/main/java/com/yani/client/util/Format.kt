package com.yani.client.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.text.HtmlCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = LocalConfiguration.current.locales.get(0)

fun formatDate(epochSeconds: Long, locale: Locale): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(epochSeconds * 1000))

fun formatDateTime(epochSeconds: Long, locale: Locale): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(epochSeconds * 1000))

fun formatClock(epochSeconds: Long, locale: Locale): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(epochSeconds * 1000))

/** 83 -> "1:23", 3723 -> "1:02:03" */
fun formatDuration(seconds: Double?): String {
    val total = (seconds ?: 0.0).toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatRating(value: Double?): String? = value?.takeIf { it > 0 }?.let { "%.1f".format(Locale.US, it) }

fun stripHtml(html: String?): String =
    if (html.isNullOrBlank()) "" else HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()

/** Extracts the anime alias from links like "/catalog/item/some-alias#comment1". */
fun animeAliasFromPath(path: String?): String? {
    if (path == null) return null
    val marker = "/catalog/item/"
    val start = path.indexOf(marker).takeIf { it >= 0 } ?: return null
    return path.substring(start + marker.length).substringBefore('#').substringBefore('?').trim('/').takeIf { it.isNotEmpty() }
}
