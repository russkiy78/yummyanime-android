package com.yani.client.data

/** What "watch" should open: resume an episode or start the next one. */
data class ContinueTarget(val video: Video, val resume: Boolean)

fun Video.isFinished(): Boolean {
    val duration = duration ?: return false
    val end = watched?.endTime ?: return false
    return duration > 0 && (end >= duration * 0.9 || duration - end < 120)
}

fun continueTarget(videos: List<Video>): ContinueTarget? {
    val last = videos.filter { it.watched != null }.maxByOrNull { it.watched!!.date } ?: return null
    if (!last.isFinished()) return ContinueTarget(last, resume = true)
    val next = videos
        .filter { it.data.player == last.data.player && it.data.dubbing == last.data.dubbing && it.index > last.index }
        .minByOrNull { it.index }
    return next?.let { ContinueTarget(it, resume = false) } ?: ContinueTarget(last, resume = false)
}

/** Known players, in the default order of preference. */
val knownPlayers = listOf("kodik", "cvh", "alloha", "aksor")

/** Player key used in settings for a player name from the API; the names are localized but contain "Kodik", "Alloha", ... */
fun playerKey(playerName: String): String = playerName.lowercase().let { name -> knownPlayers.firstOrNull { it in name } ?: name }

/** Players of an anime ordered by the user's preference, then by reliability. */
fun orderedPlayers(videos: List<Video>, preferredKey: String): List<String> {
    val names = videos.map { it.data.player }.distinct()
    return names.sortedWith(compareBy({ playerKey(it) != preferredKey }, { knownPlayers.indexOf(playerKey(it)).let { i -> if (i < 0) 99 else i } }))
}

/** Dubbings of a player: the most complete ones first. */
fun dubbingsFor(videos: List<Video>, player: String): List<String> =
    videos.filter { it.data.player == player }
        .groupBy { it.data.dubbing }
        .entries
        .sortedWith(compareBy({ -it.value.size }, { it.key }))
        .map { it.key }

fun episodesFor(videos: List<Video>, player: String, dubbing: String): List<Video> =
    videos.filter { it.data.player == player && it.data.dubbing == dubbing }
        .sortedBy { it.index }
        .distinctBy { it.number }

/** Drops the localized "Dubbing"/"Subtitles" word: "<Dubbing> AniLibria" -> "anilibria", to match a dubbing across players and UI languages. */
fun dubbingStudio(dubbing: String): String = dubbing.substringAfter(' ', dubbing).lowercase().trim()
