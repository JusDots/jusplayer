package com.jusplayer.data

import kotlinx.serialization.Serializable

/**
 * One track that is stored on this device.
 *
 * `file` is an opaque handle: a path on the desktop, an IndexedDB key in the
 * browser. The UI only ever shows the metadata.
 */
@Serializable
data class DownloadedTrack(
    val videoId: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val thumbnail: String? = null,
    val bytes: Long = 0,
    val file: String = "",
    val addedAt: Long = 0,
)

/**
 * Offline downloads.
 *
 * On the desktop the gateway's `/api/download` endpoint is streamed straight
 * to disk, and the audio engine prefers that file over the network — so a
 * downloaded album plays with no connection at all. The browser build cannot
 * stream to disk, so it reports that downloads are unavailable instead of
 * pretending.
 */
expect object Downloads {

    /** True when this platform can store and replay tracks. */
    val available: Boolean

    /** Newest first. */
    val items: List<DownloadedTrack>

    fun ensureLoaded()

    /** Delete one download (file and record). */
    fun remove(track: DownloadedTrack)

    fun isDownloaded(videoId: String): Boolean

    /** Downloads a track. Returns an error message, or null on success. */
    suspend fun download(item: MediaItem): String?
}
