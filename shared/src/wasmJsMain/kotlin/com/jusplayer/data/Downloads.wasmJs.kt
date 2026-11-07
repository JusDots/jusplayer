package com.jusplayer.data

import androidx.compose.runtime.mutableStateListOf
import com.jusplayer.platform.apiBase
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Offline downloads in the browser.
 *
 * The audio bytes are kept in IndexedDB and the metadata in localStorage.
 * Downloading happens in JavaScript (see index.html) because the fetch and the
 * IndexedDB writes are asynchronous anyway; this object only starts the job
 * and polls its status, which keeps the Kotlin/Wasm side small.
 *
 * Downloaded tracks play through an HTMLAudioElement into a Web Audio graph,
 * so — unlike streamed YouTube audio — they really do get the equalizer,
 * stereo widening and loudness normalisation.
 */
actual object Downloads {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Status(val state: String = "idle", val bytes: Long = 0, val error: String? = null)

    private val state = mutableStateListOf<DownloadedTrack>()

    actual val available: Boolean = true

    actual val items: List<DownloadedTrack> get() = state

    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
        // Ask the browser to re-apply the saved profile to the audio graph.
        com.jusplayer.player.Playback.uploadAudioProfile()
    }

    private fun refresh() {
        val tracks = runCatching {
            json.decodeFromString<List<DownloadedTrack>>(jsList())
        }.getOrDefault(emptyList())
        state.clear()
        state.addAll(tracks)
    }

    actual fun isDownloaded(videoId: String): Boolean = state.any { it.videoId == videoId }

    actual fun remove(track: DownloadedTrack) {
        jsRemove(track.videoId)
        state.removeAll { it.videoId == track.videoId }
    }

    actual suspend fun download(item: MediaItem): String? {
        val videoId = item.videoId ?: return "Only songs can be downloaded."
        if (isDownloaded(videoId)) return null

        val metadata = DownloadedTrack(
            videoId = videoId,
            title = item.title,
            artist = item.primaryArtist,
            album = item.subtitle,
            thumbnail = item.thumbnail,
        )
        val url = "${apiBase().trimEnd('/')}/api/download?videoId=$videoId"
        jsStart(videoId, json.encodeToString(metadata), url)

        // Local downloads finish in a few seconds; a stalled fetch gives up
        // after two minutes rather than leaving the menu spinning forever.
        repeat(240) {
            val status = runCatching { json.decodeFromString<Status>(jsStatus(videoId)) }.getOrNull()
            when (status?.state) {
                "done" -> {
                    refresh()
                    return null
                }

                "error" -> return status.error ?: "The download failed."
            }
            delay(500)
        }
        return "The download timed out."
    }
}

@JsFun("() => window.jusOffline.list()")
private external fun jsList(): String

@JsFun("(videoId) => window.jusOffline.status(videoId)")
private external fun jsStatus(videoId: String): String

@JsFun("(videoId, meta, url) => window.jusOffline.start(videoId, meta, url)")
private external fun jsStart(videoId: String, meta: String, url: String)

@JsFun("(videoId) => window.jusOffline.remove(videoId)")
private external fun jsRemove(videoId: String)
