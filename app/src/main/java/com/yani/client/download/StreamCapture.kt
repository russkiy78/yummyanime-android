package com.yani.client.download

import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(UnstableApi::class)
enum class StreamType(val mimeType: String?) {
    HLS(MimeTypes.APPLICATION_M3U8),
    DASH(MimeTypes.APPLICATION_MPD),
    /** A plain video file played by a `<video>` element. */
    FILE(null),
}

/** Something the player loaded that may be the episode, with the headers it was requested with. */
data class StreamCandidate(
    val url: String,
    val type: StreamType,
    val headers: Map<String, String>,
    /** Duration of a `<video>` element that plays this file, if the page reported it. */
    val duration: Double? = null,
)

/** A stream that was checked to be the episode. */
data class CapturedStream(val url: String, val type: StreamType, val headers: Map<String, String>, val duration: Double?)

/**
 * Finds the episode stream in whatever an embedded player loads, without knowing the player:
 * every request of the page and its iframes is watched, and a script in every frame reports playlists by their content
 * and `<video>` files with their duration. Candidates are then checked: ads are short, the episode is not.
 */
object StreamCapture {
    /** Kodik names its playlists ".../720.mp4:hls:manifest.m3u8", so "m3u8" is looked for anywhere in the path. */
    fun typeOf(url: String): StreamType? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            "m3u8" in path -> StreamType.HLS
            path.endsWith(".mpd") -> StreamType.DASH
            FILE_EXTENSIONS.any { path.endsWith(it) } -> StreamType.FILE
            else -> null
        }
    }

    /**
     * A request seen by the WebView. Plain files only count when fetched like a `<video>` element does (a range from 0),
     * otherwise every fMP4 segment of a stream would be a candidate.
     */
    fun fromRequest(url: String, requestHeaders: Map<String, String>, fallbackReferer: String, userAgent: String): StreamCandidate? {
        if (!url.startsWith("http")) return null
        val type = typeOf(url) ?: return null
        if (type == StreamType.FILE && requestHeaders.header("Range")?.startsWith("bytes=0-") != true) return null
        return StreamCandidate(url, type, streamHeaders(requestHeaders, fallbackReferer, userAgent))
    }

    /** A report of [SCRIPT]: `{"type": "hls" | "dash" | "media", "url": ..., "duration": ...}`. */
    fun fromScript(raw: String, headersFor: (String) -> Map<String, String>): StreamCandidate? {
        val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: return null
        val type = when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "hls" -> StreamType.HLS
            "dash" -> StreamType.DASH
            "media" -> typeOf(url).takeIf { it != StreamType.FILE } ?: StreamType.FILE
            else -> return null
        }
        val duration = obj["duration"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }
        return StreamCandidate(url, type, headersFor(url), duration)
    }

    /**
     * The headers the request was sent with, as the CDN expects them: Referer and Origin, browser hints, and whatever
     * the player added itself (some send their own authorization headers). Left out are the ones the HTTP stack manages,
     * and X-Requested-With, which WebView adds with the app's package name.
     */
    fun streamHeaders(requestHeaders: Map<String, String>, fallbackReferer: String, userAgent: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for ((name, value) in requestHeaders) {
            val lower = name.lowercase()
            if (lower !in MANAGED_HEADERS && !lower.startsWith("access-control-request-")) result[name] = value
        }
        if (result.keys.none { it.equals("Referer", ignoreCase = true) }) result["Referer"] = fallbackReferer
        result[OfflineDownloads.USER_AGENT] = userAgent
        return result
    }

    private fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private val FILE_EXTENSIONS = listOf(".mp4", ".m4v", ".webm", ".mkv", ".mov")

    private val MANAGED_HEADERS = setOf(
        "user-agent", "accept-encoding", "range", "cookie", "host", "connection", "content-length",
        "if-range", "if-none-match", "if-modified-since", "x-requested-with",
    )

    /**
     * Runs at document start in every frame, including the players' cross-origin iframes:
     * mutes all media (the download screen shows the player, but nobody wants to hear the ads) and reports
     * playlists by their content (URLs do not always end in .m3u8) and `<video>` sources with their duration.
     */
    val SCRIPT = """
        (function () {
          if (window.__yummyCapture) return;
          window.__yummyCapture = true;
          function report(o) {
            try { var bridge = window.yummyCapture; if (bridge) bridge.postMessage(JSON.stringify(o)); } catch (e) {}
          }
          function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return null; } }
          function inspect(url, text) {
            if (!url || typeof text !== 'string') return;
            var head = text.slice(0, 1024).replace(/^﻿/, '').trim();
            if (head.indexOf('#EXTM3U') === 0) report({ type: 'hls', url: url });
            else if (head.indexOf('<MPD') >= 0) report({ type: 'dash', url: url });
          }
          var open = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            this.__yummyUrl = url;
            return open.apply(this, arguments);
          };
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            var xhr = this;
            xhr.addEventListener('load', function () {
              try {
                if (xhr.responseType === '' || xhr.responseType === 'text') inspect(xhr.responseURL || abs(xhr.__yummyUrl), xhr.responseText);
              } catch (e) {}
            });
            return send.apply(this, arguments);
          };
          if (window.fetch) {
            var originalFetch = window.fetch;
            window.fetch = function () {
              return originalFetch.apply(this, arguments).then(function (response) {
                try {
                  var type = (response.headers.get('content-type') || '').toLowerCase();
                  var size = parseInt(response.headers.get('content-length') || '0', 10);
                  var looksLikePlaylist = type.indexOf('mpegurl') >= 0 || type.indexOf('dash') >= 0 || /\.(m3u8|mpd)(\?|$)/i.test(response.url) ||
                    ((type.indexOf('text') >= 0 || type.indexOf('octet-stream') >= 0) && size > 0 && size < 2000000);
                  if (looksLikePlaylist) response.clone().text().then(function (t) { inspect(response.url, t); }, function () {});
                } catch (e) {}
                return response;
              });
            };
          }
          function mute(media) { try { media.muted = true; media.volume = 0; } catch (e) {} }
          var play = HTMLMediaElement.prototype.play;
          HTMLMediaElement.prototype.play = function () { mute(this); return play.apply(this, arguments); };
          function onMedia(e) {
            var media = e.target;
            if (!(media instanceof HTMLMediaElement)) return;
            mute(media);
            var src = media.currentSrc || media.src;
            if (e.type === 'loadedmetadata' && src && /^https?:/.test(src)) report({ type: 'media', url: src, duration: media.duration });
          }
          ['loadedmetadata', 'playing', 'volumechange'].forEach(function (name) { document.addEventListener(name, onMedia, true); });
        })();
    """.trimIndent()
}

/** Checks candidates with the player's headers: the episode is the long stream, ads and previews are short. */
class StreamProbe(private val http: OkHttpClient) {

    suspend fun probe(candidate: StreamCandidate): CapturedStream? = withContext(Dispatchers.IO) {
        val headers = withCookies(candidate.url, candidate.headers)
        val duration = runCatching {
            when (candidate.type) {
                StreamType.HLS -> hlsDuration(candidate.url, headers)
                StreamType.DASH -> dashDuration(candidate.url, headers)
                StreamType.FILE -> candidate.duration ?: fileDuration(candidate.url, headers)
            }
        }.getOrNull() ?: return@withContext null
        CapturedStream(candidate.url, candidate.type, headers, duration.takeIf { it > 0 })
    }

    /**
     * Whether [stream] is the episode. With a known episode length the stream must cover most of it;
     * without one it must be longer than any ad.
     */
    fun isEpisode(stream: CapturedStream, expected: Double?): Boolean {
        val duration = stream.duration ?: return false
        return if (expected != null && expected > 0) duration >= maxOf(30.0, expected * 0.6) else duration >= MIN_UNKNOWN_EPISODE
    }

    /** Sums the segment durations; for a master playlist, those of its best variant. */
    private fun hlsDuration(url: String, headers: Map<String, String>): Double? {
        var text = fetchText(url, headers) ?: return null
        if (!text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U") || text.isProtected()) return null
        if ("#EXT-X-STREAM-INF" in text) {
            val variant = bestVariant(text, url) ?: return null
            text = fetchText(variant, headers) ?: return null
            if (text.isProtected()) return null
        }
        return EXTINF.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
    }

    private fun bestVariant(master: String, baseUrl: String): String? {
        val lines = master.lines().map { it.trim() }
        val variants = lines.mapIndexedNotNull { i, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapIndexedNotNull null
            (BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L) to uri
        }
        val uri = variants.maxByOrNull { it.first }?.second ?: return null
        return baseUrl.toHttpUrlOrNull()?.resolve(uri)?.toString()
    }

    /** DRM-protected streams cannot be played back offline; AES-128 (plain key file) can. */
    private fun String.isProtected(): Boolean = "METHOD=SAMPLE-AES" in this || "com.widevine" in this || "skd://" in this

    private fun dashDuration(url: String, headers: Map<String, String>): Double? {
        val text = fetchText(url, headers) ?: return null
        if ("<MPD" !in text || "<ContentProtection" in text) return null
        val iso = MPD_DURATION.find(text)?.groupValues?.get(1) ?: return null
        val match = ISO_DURATION.matchEntire(iso) ?: return null
        val (h, m, s) = match.destructured
        return (h.toDoubleOrNull() ?: 0.0) * 3600 + (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
    }

    /** No `<video>` reported the length: judge by size instead (an ad is a few MB, an episode is hundreds). */
    private fun fileDuration(url: String, headers: Map<String, String>): Double? {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.header("Range", "bytes=0-0").build()
        val size = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: response.body.contentLength()
        }
        return if (size >= MIN_FILE_BYTES) MIN_UNKNOWN_EPISODE else null
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.peekBody(MAX_PLAYLIST_BYTES).string()
        }
    }

    private fun withCookies(url: String, headers: Map<String, String>): Map<String, String> {
        val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        return if (cookies.isNullOrBlank()) headers else headers + ("Cookie" to cookies)
    }

    private companion object {
        val EXTINF = Regex("""#EXTINF:\s*([0-9.]+)""")
        val BANDWIDTH = Regex("""[:,]BANDWIDTH=(\d+)""")
        val MPD_DURATION = Regex("""mediaPresentationDuration="([^"]+)"""")
        val ISO_DURATION = Regex("""PT(?:([0-9.]+)H)?(?:([0-9.]+)M)?(?:([0-9.]+)S)?""")
        const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
        const val MIN_FILE_BYTES = 40L * 1024 * 1024
        /** Ads last up to a couple of minutes; episodes of unknown length must be longer. */
        const val MIN_UNKNOWN_EPISODE = 150.0
    }
}
