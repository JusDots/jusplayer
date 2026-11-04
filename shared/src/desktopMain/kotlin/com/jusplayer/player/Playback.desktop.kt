package com.jusplayer.player

import com.jusplayer.platform.openExternal

/**
 * Desktop playback is still WIP: we hand the track to the default browser so the
 * queue, search and library stay usable while a native engine is wired up.
 */
actual object Playback {
    private var pendingUrl: String? = null
    private var opened = false

    actual fun load(videoId: String) {
        pendingUrl = "https://music.youtube.com/watch?v=$videoId"
        opened = false
    }

    actual fun play() {
        val url = pendingUrl ?: return
        if (!opened) {
            openExternal(url)
            opened = true
        }
    }

    actual fun pause() = Unit
    actual fun seek(seconds: Double) = Unit
    actual fun setVolume(volume: Double) = Unit
    actual fun setRate(rate: Double) = Unit
    actual fun setMetadata(title: String, artist: String, artwork: String?) = Unit

    actual fun position(): Double = 0.0
    actual fun duration(): Double = 0.0
    actual fun isPlaying(): Boolean = false
    actual fun isReady(): Boolean = false
    actual fun consumeEnded(): Boolean = false
}
