package com.jusplayer.player

/**
 * Thin bridge to the platform audio engine.
 *
 * Web   -> YouTube IFrame player (see web/src/wasmJsMain/resources/index.html)
 * Desktop -> opens the track in the default browser (WIP desktop playback)
 */
expect object Playback {
    fun load(videoId: String)
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun setVolume(volume: Double)
    fun setRate(rate: Double)

    /** Publish the current track to OS/browser media controls (Media Session). */
    fun setMetadata(title: String, artist: String, artwork: String?)

    fun position(): Double
    fun duration(): Double
    fun isPlaying(): Boolean
    fun isReady(): Boolean

    /** True once per finished track; resets itself when read. */
    fun consumeEnded(): Boolean
}
