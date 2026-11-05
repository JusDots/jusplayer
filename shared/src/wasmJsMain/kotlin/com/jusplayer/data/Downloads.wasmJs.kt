package com.jusplayer.data

/**
 * Offline downloads in the browser.
 *
 * Browsers cannot stream a response straight to disk, and a WebAssembly
 * build has no filesystem, so tracks would have to be held in IndexedDB.
 * Until that lands the feature is reported as unavailable rather than
 * silently doing nothing.
 */
actual object Downloads {

    actual val available: Boolean = false

    actual val items: List<DownloadedTrack> = emptyList()

    actual fun ensureLoaded() = Unit

    actual fun remove(track: DownloadedTrack) = Unit

    actual fun isDownloaded(videoId: String): Boolean = false

    actual suspend fun download(item: MediaItem): String? =
        "Offline downloads are not available in the browser build yet."
}
