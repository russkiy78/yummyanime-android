package com.yani.client.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The API returns protocol-relative ("//static.yani.tv/...") and site-relative links. */
fun absUrl(url: String?): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("//") -> "https:$url"
    url.startsWith("/") -> "https://yummyani.me$url"
    else -> url
}

@Serializable
data class Poster(
    val small: String? = null,
    val medium: String? = null,
    val big: String? = null,
    val huge: String? = null,
    val fullsize: String? = null,
    val mega: String? = null,
) {
    val card: String? get() = absUrl(big ?: medium ?: fullsize ?: small)
    val large: String? get() = absUrl(fullsize ?: huge ?: mega ?: big ?: medium)
}

@Serializable
data class AnimeType(
    val name: String? = null,
    val value: Int? = null,
    val shortname: String? = null,
    val alias: String? = null,
)

@Serializable
data class AnimeStatus(
    val title: String? = null,
    val alias: String? = null,
    val value: Int? = null,
)

@Serializable
data class MinAge(
    val value: Int? = null,
    val title: String? = null,
    @SerialName("title_long") val titleLong: String? = null,
)

@Serializable
data class ListRef(
    val id: Int = -1,
    val href: String? = null,
    val title: String? = null,
)

@Serializable
data class UserListInfo(
    @SerialName("is_fav") val isFav: Boolean = false,
    val list: ListRef? = null,
)

@Serializable
data class UserAnimeInfo(
    val list: UserListInfo? = null,
    val rating: Double? = null,
)

@Serializable
data class Genre(
    val id: Int? = null,
    val value: Int? = null,
    val title: String = "",
    val alias: String? = null,
    val href: String? = null,
) {
    val genreId: Int? get() = id ?: value
}

@Serializable
data class Episodes(
    val count: Int? = null,
    val aired: Int? = null,
    @SerialName("next_date") val nextDate: Long? = null,
    @SerialName("prev_date") val prevDate: Long? = null,
)

@Serializable
data class ScreenshotSizes(val small: String? = null, val full: String? = null)

@Serializable
data class Screenshot(
    val id: Long? = null,
    val time: Double? = null,
    val episode: String? = null,
    val sizes: ScreenshotSizes? = null,
)

@Serializable
data class Top(val global: Int? = null, val category: Int? = null)

@Serializable
data class NamedRef(val id: Int? = null, val title: String = "", val url: String? = null)

@Serializable
data class ViewingOrderData(val text: String? = null, val index: Int? = null)

/** Short anime card. The same shape (with different optional fields) is used across feed, lists, history etc. */
@Serializable
data class AnimeItem(
    @SerialName("anime_id") val id: Int = 0,
    @SerialName("anime_url") val url: String = "",
    val title: String = "",
    val poster: Poster? = null,
    val description: String? = null,
    val year: Int? = null,
    val type: AnimeType? = null,
    @SerialName("anime_status") val status: AnimeStatus? = null,
    val rating: JsonElement? = null,
    val user: UserAnimeInfo? = null,
    @SerialName("min_age") val minAge: MinAge? = null,
    val season: Int? = null,
    val views: Long? = null,
    val genres: List<Genre> = emptyList(),
    val episodes: Episodes? = null,
    val date: Long? = null,
    // Watch history / continue watching
    @SerialName("ep_title") val epTitle: String? = null,
    @SerialName("end_time") val endTime: Double? = null,
    val duration: Double? = null,
    val screenshot: Screenshot? = null,
    @SerialName("video_id") val videoId: Long? = null,
    // New episodes
    @SerialName("dub_title") val dubTitle: String? = null,
    @SerialName("player_title") val playerTitle: String? = null,
    // Viewing order
    val data: ViewingOrderData? = null,
) {
    /** `rating` is a number in lists and an object everywhere else. */
    val ratingValue: Double?
        get() = when (val r = rating) {
            is JsonPrimitive -> r.doubleOrNull
            is JsonObject -> r["average"]?.jsonPrimitive?.doubleOrNull
            else -> null
        }?.takeIf { it > 0 }

    val listId: Int? get() = user?.list?.list?.id?.takeIf { it >= 0 }
    val isFavorite: Boolean get() = user?.list?.isFav == true
}

@Serializable
data class Rating(
    val average: Double? = null,
    val counters: Int? = null,
    @SerialName("kp_rating") val kinopoisk: Double? = null,
    @SerialName("myanimelist_rating") val myAnimeList: Double? = null,
    @SerialName("shikimori_rating") val shikimori: Double? = null,
    @SerialName("worldart_rating") val worldArt: Double? = null,
)

@Serializable
data class Translate(val value: Int? = null, val title: String = "", val href: String? = null)

@Serializable
data class AnimeDetails(
    @SerialName("anime_id") val id: Int = 0,
    @SerialName("anime_url") val url: String = "",
    val title: String = "",
    val poster: Poster? = null,
    val description: String? = null,
    val year: Int? = null,
    val type: AnimeType? = null,
    @SerialName("anime_status") val status: AnimeStatus? = null,
    val rating: Rating? = null,
    val user: UserAnimeInfo? = null,
    @SerialName("min_age") val minAge: MinAge? = null,
    val season: Int? = null,
    val views: Long? = null,
    val genres: List<Genre> = emptyList(),
    val episodes: Episodes? = null,
    val top: Top? = null,
    val original: String? = null,
    @SerialName("other_titles") val otherTitles: List<String> = emptyList(),
    val creators: List<NamedRef> = emptyList(),
    val studios: List<NamedRef> = emptyList(),
    val translates: List<Translate> = emptyList(),
    @SerialName("viewing_order") val viewingOrder: List<AnimeItem> = emptyList(),
    @SerialName("random_screenshots") val screenshots: List<Screenshot> = emptyList(),
    @SerialName("comments_count") val commentsCount: Int? = null,
    @SerialName("lists_count") val listsCount: Int? = null,
    @SerialName("blocked_in") val blockedIn: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
)

@Serializable
data class VideoData(
    val dubbing: String = "",
    val player: String = "",
    @SerialName("player_id") val playerId: Int? = null,
)

@Serializable
data class SkipRange(val time: Double = 0.0, val length: Double = 0.0)

@Serializable
data class Skips(val opening: SkipRange? = null, val ending: SkipRange? = null)

@Serializable
data class Watched(@SerialName("end_time") val endTime: Double = 0.0, val date: Long = 0)

@Serializable
data class Video(
    @SerialName("video_id") val id: Long = 0,
    @SerialName("iframe_url") val iframeUrl: String = "",
    val data: VideoData = VideoData(),
    val number: String = "",
    val date: Long? = null,
    val index: Int = 0,
    val skips: Skips? = null,
    val watched: Watched? = null,
    val views: Long? = null,
    val duration: Double? = null,
)

@Serializable
data class Avatars(val small: String? = null, val big: String? = null, val full: String? = null)

@Serializable
data class Banner(val cropped: String? = null, val full: String? = null)

@Serializable
data class NotificationsInfo(val count: Int = 0)

@Serializable
data class MessagesInfo(@SerialName("unread_count") val unread: Int = 0)

@Serializable
data class Profile(
    val id: Int = 0,
    val nickname: String = "",
    val email: String? = null,
    val avatars: Avatars? = null,
    val banner: Banner? = null,
    val about: String? = null,
    @SerialName("register_date") val registerDate: Long? = null,
    @SerialName("last_online") val lastOnline: Long? = null,
    val notifications: NotificationsInfo? = null,
    val messages: MessagesInfo? = null,
) {
    val avatarUrl: String? get() = absUrl(avatars?.big ?: avatars?.small)
}

@Serializable
data class TopCarousel(val season: Int? = null, val year: Int? = null, val items: List<AnimeItem> = emptyList())

@Serializable
data class Likes(val likes: Int = 0, val dislikes: Int = 0, val vote: Int? = null)

@Serializable
data class UserShort(val id: Int = 0, val nickname: String = "", val avatars: Avatars? = null)

@Serializable
data class CollectionItem(
    val id: Int = 0,
    val title: String = "",
    val description: String? = null,
    val owner: UserShort? = null,
    val likes: Likes? = null,
    val views: Long? = null,
    @SerialName("create_date") val createDate: Long? = null,
    @SerialName("poster_previews") val posterPreviews: List<Poster> = emptyList(),
    val animes: List<AnimeItem> = emptyList(),
)

@Serializable
data class Feed(
    val announcements: List<AnimeItem> = emptyList(),
    val recommends: List<AnimeItem> = emptyList(),
    @SerialName("last_watches") val lastWatches: List<AnimeItem> = emptyList(),
    @SerialName("top_carousel") val topCarousel: TopCarousel? = null,
    val new: List<AnimeItem> = emptyList(),
    @SerialName("new_videos") val newVideos: List<AnimeItem> = emptyList(),
    val collections: List<CollectionItem> = emptyList(),
)

@Serializable
data class GenreDef(
    val title: String = "",
    val href: String? = null,
    val value: Int = 0,
    @SerialName("group_id") val groupId: Int = 0,
)

@Serializable
data class GenreGroup(val id: Int = 0, val title: String = "")

@Serializable
data class GenresResponse(val genres: List<GenreDef> = emptyList(), val groups: List<GenreGroup> = emptyList())

@Serializable
data class Comment(
    val id: Long = 0,
    val text: String = "",
    val name: String = "",
    @SerialName("user_id") val userId: Int = 0,
    val avatars: Avatars? = null,
    val likes: Int = 0,
    val dislikes: Int = 0,
    val time: Long = 0,
    @SerialName("children_count") val childrenCount: Int = 0,
    @SerialName("parent_id") val parentId: Long = 0,
    @SerialName("deleted_at") val deletedAt: Long = 0,
)

@Serializable
data class CommentsPage(val comments: List<Comment> = emptyList())

@Serializable
data class NotificationItem(
    val id: Long = 0,
    val date: Long = 0,
    val type: String? = null,
    @SerialName("sub_type") val subType: String? = null,
    @SerialName("click_uri") val clickUri: String? = null,
    @SerialName("text_html") val textHtml: String? = null,
    @SerialName("title_html") val titleHtml: String? = null,
    val viewed: Boolean = false,
    val data: JsonObject? = null,
)

/** User list ids as used by the API. Favorites are a flag, not a list. */
enum class UserList(val id: Int) {
    WATCHING(0),
    PLANNED(1),
    COMPLETED(2),
    DROPPED(3),
    ON_HOLD(5);

    companion object {
        fun byId(id: Int?): UserList? = entries.firstOrNull { it.id == id }
    }
}
