package com.jusplayer.data

import androidx.compose.runtime.mutableStateListOf
import com.jusplayer.platform.Storage
import kotlinx.serialization.json.Json

/** Liked songs, persisted locally. No account, no server round-trip. */
object Library {

    private const val KEY = "jusplayer.liked.v1"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val liked = mutableStateListOf<MediaItem>()

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            Storage.get(KEY)?.let { json.decodeFromString<List<MediaItem>>(it) }
        }.getOrNull()?.let { liked.addAll(it) }
    }

    fun isLiked(id: String): Boolean = liked.any { it.id == id }

    fun toggle(item: MediaItem) {
        val existing = liked.indexOfFirst { it.id == item.id }
        if (existing >= 0) liked.removeAt(existing) else liked.add(0, item)
        persist()
    }

    fun clear() {
        liked.clear()
        persist()
    }

    private fun persist() {
        runCatching { Storage.put(KEY, json.encodeToString(liked.toList())) }
    }
}
