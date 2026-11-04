package com.jusplayer.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.jusplayer.platform.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

private val persistJson = Json { ignoreUnknownKeys = true; isLenient = true }

// ------------------------------------------------------------ local playlists ---

@Serializable
data class LocalPlaylist(
    val id: String,
    val name: String,
    val items: List<MediaItem> = emptyList(),
)

object LocalPlaylists {

    private const val KEY = "jusplayer.playlists.v1"
    val playlists = mutableStateListOf<LocalPlaylist>()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { Storage.get(KEY)?.let { persistJson.decodeFromString<List<LocalPlaylist>>(it) } }
            .getOrNull()
            ?.let { playlists.addAll(it) }
    }

    fun byId(id: String): LocalPlaylist? = playlists.firstOrNull { it.id == id }

    fun create(name: String, items: List<MediaItem> = emptyList()): LocalPlaylist {
        val clean = name.trim().ifBlank { "New playlist" }
        val playlist = LocalPlaylist(
            id = "local-" + Random.nextLong().toString(16),
            name = clean,
            items = items.filter { it.videoId != null },
        )
        playlists.add(0, playlist)
        persist()
        return playlist
    }

    fun rename(id: String, name: String) {
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return
        playlists[index] = playlists[index].copy(name = name.trim().ifBlank { "New playlist" })
        persist()
    }

    fun remove(id: String) {
        playlists.removeAll { it.id == id }
        persist()
    }

    fun addTrack(id: String, item: MediaItem) {
        if (item.videoId == null) return
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return
        val playlist = playlists[index]
        if (playlist.items.any { it.videoId == item.videoId }) return
        playlists[index] = playlist.copy(items = playlist.items + item)
        persist()
    }

    fun removeTrack(id: String, videoId: String) {
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return
        val playlist = playlists[index]
        playlists[index] = playlist.copy(items = playlist.items.filterNot { it.videoId == videoId })
        persist()
    }

    /** Reorder tracks inside a local playlist. */
    fun moveTrack(id: String, from: Int, to: Int) {
        val index = playlists.indexOfFirst { it.id == id }
        if (index < 0) return
        val items = playlists[index].items.toMutableList()
        if (from !in items.indices || to !in items.indices || from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        playlists[index] = playlists[index].copy(items = items)
        persist()
    }

    private fun persist() {
        runCatching { Storage.put(KEY, persistJson.encodeToString(playlists.toList())) }
    }
}

// --------------------------------------------------------- recently played ---

object Recent {

    private const val KEY = "jusplayer.recent.v1"
    private const val LIMIT = 100
    val items = mutableStateListOf<MediaItem>()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { Storage.get(KEY)?.let { persistJson.decodeFromString<List<MediaItem>>(it) } }
            .getOrNull()
            ?.let { items.addAll(it) }
    }

    fun record(item: MediaItem) {
        if (item.videoId == null) return
        items.removeAll { it.videoId == item.videoId }
        items.add(0, item)
        while (items.size > LIMIT) items.removeAt(items.lastIndex)
        persist()
    }

    fun clear() {
        items.clear()
        persist()
    }

    private fun persist() {
        runCatching { Storage.put(KEY, persistJson.encodeToString(items.toList())) }
    }
}

// -------------------------------------------------------------- statistics ---

@Serializable
data class PlayEvent(
    val videoId: String,
    val title: String,
    val subtitle: String = "",
    val thumbnail: String? = null,
    val artist: String = "",
    val seconds: Int = 0,
)

object Stats {

    private const val KEY = "jusplayer.stats.v1"
    private const val LIMIT = 2000
    val events = mutableStateListOf<PlayEvent>()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { Storage.get(KEY)?.let { persistJson.decodeFromString<List<PlayEvent>>(it) } }
            .getOrNull()
            ?.let { events.addAll(it) }
    }

    fun record(item: MediaItem) {
        val videoId = item.videoId ?: return
        events.add(
            0,
            PlayEvent(
                videoId = videoId,
                title = item.title,
                subtitle = item.subtitle,
                thumbnail = item.thumbnail,
                artist = item.artists.firstOrNull()?.name
                    ?: item.subtitle.split(" • ", " · ").firstOrNull().orEmpty(),
                seconds = parseDuration(item.duration),
            ),
        )
        while (events.size > LIMIT) events.removeAt(events.lastIndex)
        persist()
    }

    fun totalPlays(): Int = events.size

    fun totalSeconds(): Int = events.sumOf { it.seconds }

    fun uniqueTracks(): Int = events.map { it.videoId }.distinct().size

    fun uniqueArtists(): Int = events.map { it.artist }.filter { it.isNotBlank() }.distinct().size

    /** Track id -> (representative item, play count), most played first. */
    fun topTracks(limit: Int = 10): List<Pair<MediaItem, Int>> {
        val counts = linkedMapOf<String, Int>()
        val items = linkedMapOf<String, MediaItem>()
        for (event in events) {
            counts[event.videoId] = (counts[event.videoId] ?: 0) + 1
            items.getOrPut(event.videoId) {
                MediaItem(
                    id = event.videoId,
                    title = event.title,
                    subtitle = event.subtitle,
                    thumbnail = event.thumbnail,
                    kind = "song",
                    videoId = event.videoId,
                    artists = if (event.artist.isBlank()) emptyList() else listOf(Credit(event.artist)),
                )
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).mapNotNull { entry ->
            items[entry.key]?.let { it to entry.value }
        }
    }

    fun topArtists(limit: Int = 8): List<Pair<String, Int>> =
        events.filter { it.artist.isNotBlank() }
            .groupingBy { it.artist }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    fun clear() {
        events.clear()
        persist()
    }

    private fun persist() {
        runCatching { Storage.put(KEY, persistJson.encodeToString(events.toList())) }
    }

    private fun parseDuration(duration: String?): Int {
        if (duration.isNullOrBlank()) return 0
        return duration.split(':').mapNotNull { it.trim().toIntOrNull() }
            .fold(0) { acc, part -> acc * 60 + part }
    }
}

// ----------------------------------------------------------- search history ---

object SearchHistory {

    private const val KEY = "jusplayer.searches.v1"
    private const val LIMIT = 12
    val queries = mutableStateListOf<String>()
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching { Storage.get(KEY)?.let { persistJson.decodeFromString<List<String>>(it) } }
            .getOrNull()
            ?.let { queries.addAll(it) }
    }

    fun add(query: String) {
        val clean = query.trim()
        if (clean.isEmpty()) return
        queries.remove(clean)
        queries.add(0, clean)
        while (queries.size > LIMIT) queries.removeAt(queries.lastIndex)
        persist()
    }

    fun remove(query: String) {
        queries.remove(query)
        persist()
    }

    fun clear() {
        queries.clear()
        persist()
    }

    private fun persist() {
        runCatching { Storage.put(KEY, persistJson.encodeToString(queries.toList())) }
    }
}

// ---------------------------------------------------------- player settings ---

/** Volume/shuffle/repeat/rate survive reloads. */
object PlayerPrefs {

    private const val VOLUME = "jusplayer.volume"
    private const val SHUFFLE = "jusplayer.shuffle"
    private const val REPEAT = "jusplayer.repeat"
    private const val RATE = "jusplayer.rate"
    private const val ACCENT = "jusplayer.accent"
    private const val DYNAMIC = "jusplayer.dynamic"
    private const val BACKGROUND = "jusplayer.player.background"
    private const val ART_STYLE = "jusplayer.player.art"

    var volume by mutableStateOf(0.8f)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf("off")
        private set
    var rate by mutableStateOf(1.0)
        private set
    /** Tint the UI from the current artwork's dominant colour. */
    var dynamicColor by mutableStateOf(true)
        private set
    /** aurora | artwork | midnight */
    var playerBackground by mutableStateOf("aurora")
        private set
    /** immersive | classic */
    var playerArtStyle by mutableStateOf("immersive")
        private set

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        Storage.get(VOLUME)?.toFloatOrNull()?.let { volume = it.coerceIn(0f, 1f) }
        shuffle = Storage.get(SHUFFLE) == "true"
        repeat = Storage.get(REPEAT) ?: "off"
        rate = Storage.get(RATE)?.toDoubleOrNull()?.coerceIn(0.5, 2.0) ?: 1.0
        dynamicColor = Storage.get(DYNAMIC)?.let { it == "true" } ?: true
        playerBackground = Storage.get(BACKGROUND) ?: "aurora"
        playerArtStyle = Storage.get(ART_STYLE) ?: "immersive"
    }

    fun updateVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        Storage.put(VOLUME, volume.toString())
    }

    fun updateShuffle(value: Boolean) {
        shuffle = value
        Storage.put(SHUFFLE, value.toString())
    }

    fun updateRepeat(value: String) {
        repeat = value
        Storage.put(REPEAT, value)
    }

    fun updateRate(value: Double) {
        rate = value.coerceIn(0.5, 2.0)
        Storage.put(RATE, rate.toString())
    }

    fun accent(): Int = Storage.get(ACCENT)?.toIntOrNull() ?: 0

    fun setAccent(index: Int) = Storage.put(ACCENT, index.toString())

    fun updateDynamicColor(value: Boolean) {
        dynamicColor = value
        Storage.put(DYNAMIC, value.toString())
    }

    fun updatePlayerBackground(value: String) {
        playerBackground = value
        Storage.put(BACKGROUND, value)
    }

    fun updatePlayerArtStyle(value: String) {
        playerArtStyle = value
        Storage.put(ART_STYLE, value)
    }
}
