package com.jusplayer.player

import com.jusplayer.audio.AudioProfile
import com.jusplayer.audio.DesktopAudioEngine

/**
 * Desktop playback: ffmpeg decodes the track and Java Sound plays it, so the
 * desktop build gets real pause/seek/volume control and a full DSP chain
 * (equalizer, stereo widening, loudness normalisation, crossfade).
 */
actual object Playback {

    private val engine = DesktopAudioEngine(
        gatewayBase = { com.jusplayer.platform.apiBase() },
        onError = { message -> PlayerState.error = message },
    )

    actual fun load(videoId: String) {
        engine.setCrossfade(AudioProfile.crossfadeSeconds.toDouble())
        engine.setNextTrack(nextVideoId())
        engine.load(videoId)
    }

    actual fun play() = engine.play()

    actual fun pause() = engine.pause()

    actual fun seek(seconds: Double) = engine.seek(seconds)

    actual fun setVolume(volume: Double) = engine.setVolume(volume)

    actual fun setRate(rate: Double) = engine.setRate(rate)

    actual fun setMetadata(title: String, artist: String, artwork: String?) =
        engine.setMetadata(title, artist, artwork)

    actual fun position(): Double = engine.positionSeconds

    actual fun duration(): Double = engine.durationSeconds

    actual fun isPlaying(): Boolean = engine.playing

    actual fun isReady(): Boolean = engine.ready

    actual fun consumeEnded(): Boolean = engine.consumeEnded()

    actual fun currentVideoId(): String? = engine.currentVideoId().ifBlank { null }

    /** The track that follows the current one — used for the crossfade. */
    private fun nextVideoId(): String? {
        val next = PlayerState.queue.getOrNull(PlayerState.index + 1) ?: return null
        return next.videoId
    }
}
