package com.yani.client.player

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Embedded players differ in how they are controlled through postMessage.
 * The rules mirror the adapters of the yummyani.me web player.
 */
enum class PlayerKind {
    KODIK,
    ALLOHA,
    CVH,
    OTHER;

    /** Whether play/pause/seek can be sent to the iframe (needed for TV remotes). */
    val controllable: Boolean get() = this != OTHER

    companion object {
        fun of(url: String): PlayerKind = when {
            "iframeCVH" in url -> CVH
            "kodik" in url -> KODIK
            "alloha" in url -> ALLOHA
            else -> OTHER
        }
    }
}

sealed interface PlayerCommand {
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data class Seek(val seconds: Double) : PlayerCommand
    data object RequestDuration : PlayerCommand
}

sealed interface PlayerEvent {
    data class Time(val seconds: Double) : PlayerEvent
    data class Duration(val seconds: Double) : PlayerEvent
    data class Playing(val playing: Boolean) : PlayerEvent
    data object Ended : PlayerEvent

    /** The player has initialised and accepts commands. */
    data object Ready : PlayerEvent
}

object PlayerAdapters {
    /** Host page origin. Some players (Alloha) only work when embedded from yummyani.me. */
    const val HOST_URL = "https://yummyani.me/"
    const val HOST_ORIGIN = "https://yummyani.me"

    // The site hides Kodik's own skip buttons and shows its own "skip opening" overlay; so do we.
    private const val KODIK_SKIP_OFF = "[opening]999999-1000000,[ending]999999-1000000,[intro]999999-1000000,[credits]999999-1000000"

    fun normalize(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://ru.yummyani.me$url"
        else -> url
    }

    /** Adds the start position (resume) and player-specific options to the iframe URL. */
    fun buildSrc(iframeUrl: String, start: Int?): String {
        val url = normalize(iframeUrl)
        val parsed = url.toHttpUrlOrNull() ?: return url
        val builder = parsed.newBuilder()
        return when (PlayerKind.of(url)) {
            PlayerKind.KODIK -> {
                start?.let { builder.setQueryParameter("start_from", it.toString()) }
                builder.setQueryParameter("hide_resume_button", "true")
                builder.removeAllQueryParameters("skip_button")
                builder.build().toString() + "&skip_button=" + KODIK_SKIP_OFF
            }
            PlayerKind.ALLOHA -> {
                start?.let { builder.setQueryParameter("start", it.toString()) }
                builder.build().toString()
            }
            PlayerKind.CVH -> {
                start?.let { builder.setQueryParameter("start_from", it.toString()) }
                builder.build().toString()
            }
            PlayerKind.OTHER -> url
        }
    }

    /** JavaScript expression to pass to `ySend(...)` in the host page, or null if unsupported. */
    fun command(kind: PlayerKind, command: PlayerCommand): String? {
        fun num(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v)
        return when (kind) {
            PlayerKind.KODIK, PlayerKind.CVH -> {
                val value = when (command) {
                    PlayerCommand.Play -> """{"method":"play"}"""
                    PlayerCommand.Pause -> """{"method":"pause"}"""
                    is PlayerCommand.Seek -> """{"method":"seek","seconds":${num(command.seconds)}}"""
                    PlayerCommand.RequestDuration -> return null
                }
                if (kind == PlayerKind.KODIK) """{"key":"kodik_player_api","value":$value}""" else value
            }
            PlayerKind.ALLOHA -> {
                val message = when (command) {
                    PlayerCommand.Play -> """{"api":"play"}"""
                    PlayerCommand.Pause -> """{"api":"pause"}"""
                    is PlayerCommand.Seek -> """{"api":"seek","value":${num(command.seconds.coerceAtLeast(0.1))}}"""
                    PlayerCommand.RequestDuration -> """{"api":"duration"}"""
                }
                // Alloha expects a string message, so pass a quoted JS string literal.
                JsonPrimitive(message).toString()
            }
            PlayerKind.OTHER -> null
        }
    }

    /** Parses a message posted by the iframe (objects arrive JSON-stringified by the host page). */
    fun parse(raw: String): PlayerEvent? {
        val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null

        // Kodik ("kodik_player_*") and CVH ("player_*"): {key, value}
        obj["key"]?.let { keyElement ->
            val key = (keyElement as? JsonPrimitive)?.contentOrNull?.removePrefix("kodik_") ?: return null
            if (key == "player_current_episode" || key == "player-rendered") return PlayerEvent.Ready
            val value = (obj["value"] as? JsonPrimitive)?.doubleOrNull
            return when (key) {
                "player_time_update" -> value?.let { PlayerEvent.Time(it) }
                "player_duration_update" -> value?.let { PlayerEvent.Duration(it) }
                "player_play", "player_video_started" -> PlayerEvent.Playing(true)
                "player_pause" -> PlayerEvent.Playing(false)
                "player_video_ended" -> PlayerEvent.Ended
                else -> null
            }
        }

        // Alloha: {"event": "...", "time": ...}
        val event = obj["event"]?.jsonPrimitive?.contentOrNull ?: return null
        val time = (obj["time"] as? JsonPrimitive)?.doubleOrNull
        return when (event) {
            "timeupdate" -> time?.let { PlayerEvent.Time(it) }
            "duration" -> time?.let { PlayerEvent.Duration(it) }
            "play" -> PlayerEvent.Playing(true)
            "pause", "paused" -> PlayerEvent.Playing(false)
            "ended" -> PlayerEvent.Ended
            "inited" -> PlayerEvent.Ready
            else -> null
        }
    }

    /** Host page: a full-size iframe plus a relay for postMessage in both directions. */
    fun hostHtml(src: String): String {
        val escaped = src.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return """
            <!DOCTYPE html>
            <html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
              iframe{position:fixed;top:0;left:0;width:100vw;height:100vh;border:0}
            </style>
            </head><body>
            <iframe id="player" src="$escaped" referrerpolicy="origin"
              allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen></iframe>
            <script>
            (function () {
              var bridge = window.yummyBridge || window.YummyAndroid;
              window.addEventListener('message', function (e) {
                if (!bridge || e.source === window) return;
                var d = e.data, s;
                try { s = (typeof d === 'string') ? d : JSON.stringify(d); } catch (err) { return; }
                if (s && s.length < 8192) bridge.postMessage(s);
              });
              window.ySend = function (m) {
                var f = document.getElementById('player');
                if (f && f.contentWindow) f.contentWindow.postMessage(m, '*');
              };
            })();
            </script>
            </body></html>
        """.trimIndent()
    }
}
