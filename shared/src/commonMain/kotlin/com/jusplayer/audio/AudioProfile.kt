package com.jusplayer.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jusplayer.platform.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One band of the graphic equalizer. */
@Serializable
data class EqBand(val frequency: Int, val gain: Double)

/**
 * The sound settings every platform shares.
 *
 * The desktop engine turns these into ffmpeg filters, the web build feeds
 * them into a Web Audio graph — so the equalizer, the widened stereo image
 * and the loudness normalisation behave the same everywhere.
 */
object AudioProfile {

    private const val KEY = "jusplayer.audio.v1"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The classic five band layout of the mobile app. */
    val frequencies = listOf(60, 230, 910, 3600, 14000)

    @Serializable
    private data class Saved(
        val equalizer: Boolean = false,
        val gains: List<Double> = emptyList(),
        val spatial: Boolean = false,
        val spatialWidth: Double = 0.4,
        val loudness: Boolean = false,
        val crossfade: Int = 0,
        val preamp: Double = 0.0,
    )

    var equalizerEnabled by mutableStateOf(false)
        private set

    /** Gain in dB for every band in [frequencies]. */
    var gains by mutableStateOf(List(frequencies.size) { 0.0 })
        private set

    /** Widened stereo image (haas effect). */
    var spatialEnabled by mutableStateOf(false)
        private set

    /** How wide the stereo image is, 0.0 – 1.0. */
    var spatialWidth by mutableStateOf(0.4)
        private set

    /** EBU R128 loudness normalisation. */
    var loudnessEnabled by mutableStateOf(false)
        private set

    /** Seconds of overlap between two tracks, 0 – 12. */
    var crossfadeSeconds by mutableStateOf(0)
        private set

    /** Extra gain applied before everything else, in dB. */
    var preamp by mutableStateOf(0.0)
        private set

    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = runCatching { Storage.get(KEY)?.let { json.decodeFromString<Saved>(it) } }.getOrNull() ?: return
        equalizerEnabled = saved.equalizer
        gains = normaliseGains(saved.gains)
        spatialEnabled = saved.spatial
        spatialWidth = saved.spatialWidth.coerceIn(0.0, 1.0)
        loudnessEnabled = saved.loudness
        crossfadeSeconds = saved.crossfade.coerceIn(0, 12)
        preamp = saved.preamp.coerceIn(-12.0, 12.0)
    }

    fun updateEqualizerEnabled(value: Boolean) {
        equalizerEnabled = value
        persist()
    }

    fun updateGain(index: Int, gain: Double) {
        if (index !in gains.indices) return
        val next = gains.toMutableList()
        next[index] = gain.coerceIn(-12.0, 12.0)
        gains = next
        persist()
    }

    fun updateSpatialEnabled(value: Boolean) {
        spatialEnabled = value
        persist()
    }

    fun updateSpatialWidth(value: Double) {
        spatialWidth = value.coerceIn(0.0, 1.0)
        persist()
    }

    fun updateLoudnessEnabled(value: Boolean) {
        loudnessEnabled = value
        persist()
    }

    fun updateCrossfade(value: Int) {
        crossfadeSeconds = value.coerceIn(0, 12)
        persist()
    }

    fun updatePreamp(value: Double) {
        preamp = value.coerceIn(-12.0, 12.0)
        persist()
    }

    /** Reset every band back to flat. */
    fun resetEqualizer() {
        gains = List(frequencies.size) { 0.0 }
        persist()
    }

    private fun normaliseGains(saved: List<Double>): List<Double> =
        List(frequencies.size) { index -> saved.getOrNull(index)?.coerceIn(-12.0, 12.0) ?: 0.0 }

    private fun persist() {
        runCatching {
            Storage.put(
                KEY,
                json.encodeToString(
                    Saved(
                        equalizer = equalizerEnabled,
                        gains = gains,
                        spatial = spatialEnabled,
                        spatialWidth = spatialWidth,
                        loudness = loudnessEnabled,
                        crossfade = crossfadeSeconds,
                        preamp = preamp,
                    ),
                ),
            )
        }
    }
}
