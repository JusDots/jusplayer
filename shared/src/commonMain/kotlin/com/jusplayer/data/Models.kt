package com.jusplayer.data

import kotlinx.serialization.Serializable

/** An artist credit attached to a song/album row. */
@Serializable
data class Credit(
    val name: String,
    val browseId: String = "",
)

/**
 * A single card/row in the UI. The Node gateway normalises YouTube Music's
 * renderer soup into this shape so the client stays tiny and stable.
 */
@Serializable
data class MediaItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
    /** song | album | playlist | artist */
    val kind: String = "song",
    val videoId: String? = null,
    val browseId: String? = null,
    val duration: String? = null,
    val artists: List<Credit> = emptyList(),
) {
    val isSong: Boolean get() = kind == "song" && videoId != null
    val isCollection: Boolean get() = kind == "album" || kind == "playlist"
    val isArtist: Boolean get() = kind == "artist" && !browseId.isNullOrBlank()

    /** "4:21" -> 261. Used for lyrics matching. */
    val durationSeconds: Int
        get() = duration?.split(':')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, part -> acc * 60 + part }
            ?: 0

    /** First artist credit, falling back to the leading part of the subtitle. */
    val primaryArtist: String
        get() = artists.firstOrNull()?.name
            ?: subtitle.split(" • ", " · ").firstOrNull()?.trim().orEmpty()
}

@Serializable
data class Section(
    val title: String = "",
    val items: List<MediaItem> = emptyList(),
)

@Serializable
data class Feed(
    val sections: List<Section> = emptyList(),
) {
    val isEmpty: Boolean get() = sections.all { it.items.isEmpty() }
    fun allItems(): List<MediaItem> = sections.flatMap { it.items }
    fun songs(): List<MediaItem> = allItems().filter { it.videoId != null }
}

@Serializable
data class CollectionResponse(
    val title: String = "",
    val sections: List<Section> = emptyList(),
)

/** Album / playlist / artist detail page. */
@Serializable
data class PageResponse(
    val title: String = "",
    val subtitle: String = "",
    val description: String = "",
    val thumbnail: String? = null,
    val artists: List<Credit> = emptyList(),
    val meta: String = "",
    val kind: String = "",
    val sections: List<Section> = emptyList(),
) {
    fun songs(): List<MediaItem> = sections.flatMap { it.items }.filter { it.videoId != null }
}

/** Charts / new releases landing pages. */
@Serializable
data class HubResponse(
    val title: String = "",
    val sections: List<Section> = emptyList(),
)

@Serializable
data class MoodEntry(
    val title: String = "",
    val params: String = "",
    val color: Long? = null,
)

@Serializable
data class MoodGroup(
    val title: String = "",
    val entries: List<MoodEntry> = emptyList(),
)

@Serializable
data class MoodsResponse(
    val groups: List<MoodGroup> = emptyList(),
)

@Serializable
data class MoodResponse(
    val title: String = "",
    val sections: List<Section> = emptyList(),
)

@Serializable
data class SuggestResponse(
    val suggestions: List<String> = emptyList(),
)

@Serializable
data class LyricsResponse(
    val lyrics: String? = null,
    /** LRC text (time-coded) when LRCLIB has a match. */
    val syncedLyrics: String? = null,
    val source: String? = null,
)

/**
 * The signed-in YouTube Music account, as reported by the gateway.
 *
 * `signedIn` is false for anonymous browsing, which is the default: the app
 * works fine without an account, it just cannot show personalised feeds.
 */
@Serializable
data class Account(
    val signedIn: Boolean = false,
    val name: String = "",
    val email: String = "",
    val avatar: String = "",
    val channelId: String = "",
    /** "Google", "YouTube", … or null while signed out. */
    val source: String? = null,
    val method: String? = null,
    /** True when the deployment has Google OAuth configured. */
    val oauthAvailable: Boolean = false,
)
