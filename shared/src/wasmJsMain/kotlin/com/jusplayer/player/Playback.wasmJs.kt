package com.jusplayer.player

@JsFun("(id) => { if (window.jusPlayer) window.jusPlayer.load(id); }")
private external fun jsLoad(id: String)

@JsFun("() => { if (window.jusPlayer) window.jusPlayer.play(); }")
private external fun jsPlay()

@JsFun("() => { if (window.jusPlayer) window.jusPlayer.pause(); }")
private external fun jsPause()

@JsFun("(s) => { if (window.jusPlayer) window.jusPlayer.seek(s); }")
private external fun jsSeek(seconds: Double)

@JsFun("(v) => { if (window.jusPlayer) window.jusPlayer.setVolume(v); }")
private external fun jsVolume(volume: Double)

@JsFun("(r) => { if (window.jusPlayer) window.jusPlayer.setRate(r); }")
private external fun jsRate(rate: Double)

@JsFun("(t, a, art) => { if (window.jusPlayer) window.jusPlayer.setMetadata(t, a, art); }")
private external fun jsMetadata(title: String, artist: String, artwork: String?)

@JsFun("() => (window.jusPlayer ? window.jusPlayer.position() : 0)")
private external fun jsPosition(): Double

@JsFun("() => (window.jusPlayer ? window.jusPlayer.duration() : 0)")
private external fun jsDuration(): Double

@JsFun("() => (window.jusPlayer ? window.jusPlayer.isPlaying() : false)")
private external fun jsIsPlaying(): Boolean

@JsFun("() => (window.jusPlayer ? window.jusPlayer.ready() : false)")
private external fun jsReady(): Boolean

@JsFun("() => (window.jusPlayer ? window.jusPlayer.consumeEnded() : false)")
private external fun jsConsumeEnded(): Boolean

/**
 * Actual playback on the web: audio comes from a YouTube IFrame player that is
 * driven by the glue code in index.html. We never touch raw stream URLs.
 */
actual object Playback {
    actual fun load(videoId: String) = jsLoad(videoId)
    actual fun play() = jsPlay()
    actual fun pause() = jsPause()
    actual fun seek(seconds: Double) = jsSeek(seconds)
    actual fun setVolume(volume: Double) = jsVolume(volume)
    actual fun setRate(rate: Double) = jsRate(rate)
    actual fun setMetadata(title: String, artist: String, artwork: String?) = jsMetadata(title, artist, artwork)
    actual fun position(): Double = jsPosition()
    actual fun duration(): Double = jsDuration()
    actual fun isPlaying(): Boolean = jsIsPlaying()
    actual fun isReady(): Boolean = jsReady()
    actual fun consumeEnded(): Boolean = jsConsumeEnded()
}
