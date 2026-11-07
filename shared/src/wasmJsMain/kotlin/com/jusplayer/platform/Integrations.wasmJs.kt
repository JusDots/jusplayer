package com.jusplayer.platform

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local audio files in the browser.
 *
 * The file picker and IndexedDB are asynchronous, so JavaScript owns them and
 * this object polls. The resulting Blob plays through the same Web Audio chain
 * as a downloaded track.
 */
actual object LocalFiles {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Entry(
        val videoId: String,
        val title: String,
        val artist: String = "",
        val bytes: Long = 0,
        val file: String = "",
    )

    private val state = mutableStateListOf<LocalTrack>()

    actual val available: Boolean = true

    actual val items: List<LocalTrack> get() = state

    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    private fun refresh() {
        val tracks = runCatching { json.decodeFromString<List<Entry>>(jsList()) }.getOrDefault(emptyList())
        state.clear()
        state.addAll(tracks.map { LocalTrack(it.videoId, it.title, it.artist, it.file, it.bytes) })
    }

    actual suspend fun pickAndImport(): Int {
        ensureLoaded()
        jsPick()
        // Keep waiting while the OS dialog is open; a cancel flips `picking`
        // back to false without importing anything.
        repeat(1200) {
            if (!jsPicking()) {
                val count = jsImported().toInt()
                refresh()
                return count
            }
            delay(250)
        }
        return 0
    }

    actual fun remove(track: LocalTrack) {
        jsRemove(track.id)
        state.removeAll { it.id == track.id }
    }

    actual fun pathOf(videoId: String): String? = null
}

// Discord and scrobbling are desktop-only: the browser has no local IPC socket
// and ListenBrainz would need a CORS proxy on the gateway.
actual object DiscordPresence {
    actual val available: Boolean = false
    actual val configured: Boolean = false
    actual val clientId: String = ""
    actual fun configure(clientId: String) = Unit

    actual fun update(
        title: String,
        artist: String,
        artwork: String?,
        playing: Boolean,
        positionSeconds: Double,
        durationSeconds: Double,
    ) = Unit

    actual fun clear() = Unit
}

actual object Scrobbler {
    actual val available: Boolean = false
    actual val token: String = ""
    actual fun configure(token: String) = Unit
    actual fun nowPlaying(title: String, artist: String, album: String, durationSeconds: Int) = Unit

    actual fun scrobble(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        startedAtSeconds: Long,
    ) = Unit
}

@JsFun("() => window.jusLocalFiles.list()")
private external fun jsList(): String

@JsFun("() => window.jusLocalFiles.pick()")
private external fun jsPick()

@JsFun("() => window.jusLocalFiles.picking()")
private external fun jsPicking(): Boolean

@JsFun("() => window.jusLocalFiles.imported()")
private external fun jsImported(): Double

@JsFun("(id) => window.jusLocalFiles.remove(id)")
private external fun jsRemove(id: String)
