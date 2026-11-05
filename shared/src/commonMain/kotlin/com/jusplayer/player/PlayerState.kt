package com.jusplayer.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jusplayer.data.MediaItem
import com.jusplayer.data.PlayerPrefs
import com.jusplayer.data.Recent
import com.jusplayer.data.Stats
import kotlin.random.Random

enum class RepeatMode { Off, All, One }

/**
 * Application-wide playback + queue state. Compose observes these properties,
 * so any screen can render the mini player without extra plumbing.
 */
object PlayerState {

    var queue by mutableStateOf<List<MediaItem>>(emptyList())
        private set

    var index by mutableStateOf(-1)
        private set

    var playing by mutableStateOf(false)
        private set

    var buffering by mutableStateOf(false)
        private set

    var position by mutableStateOf(0.0)
        private set

    var duration by mutableStateOf(0.0)
        private set

    var volume by mutableStateOf(0.8)
        private set

    var shuffle by mutableStateOf(false)
        private set

    var repeatMode by mutableStateOf(RepeatMode.Off)
        private set

    var expanded by mutableStateOf(false)
        private set

    var rate by mutableStateOf(1.0)
        private set

    /** Remaining sleep-timer seconds, or null when no timer is running. */
    private var sleepSeconds by mutableStateOf<Double?>(null)
    val sleepRemaining: Int? get() = sleepSeconds?.toInt()

    var sleepAfterTrack by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)

    val current: MediaItem? get() = queue.getOrNull(index)
    val hasTrack: Boolean get() = current?.videoId != null
    val progress: Float
        get() = if (duration <= 0.0) 0f else (position / duration).toFloat().coerceIn(0f, 1f)

    /** Apply the values saved by [PlayerPrefs] to the live player. */
    fun restorePrefs() {
        volume = PlayerPrefs.volume.toDouble()
        shuffle = PlayerPrefs.shuffle
        repeatMode = when (PlayerPrefs.repeat) {
            "all" -> RepeatMode.All
            "one" -> RepeatMode.One
            else -> RepeatMode.Off
        }
        rate = PlayerPrefs.rate
    }

    fun playQueue(items: List<MediaItem>, startIndex: Int = 0) {
        val songs = items.filter { it.videoId != null }.distinctBy { it.videoId }
        if (songs.isEmpty()) return
        queue = songs
        index = startIndex.coerceIn(0, songs.lastIndex)
        error = null
        load()
    }

    fun playNext(items: List<MediaItem>) {
        val songs = items.filter { it.videoId != null }.distinctBy { it.videoId }
        if (songs.isEmpty()) return
        val head = queue.take(index + 1)
        val tail = queue.drop(index + 1)
        queue = (head + songs + tail).distinctBy { it.videoId }
    }

    fun addToQueue(items: List<MediaItem>) {
        val songs = items.filter { it.videoId != null }.distinctBy { it.videoId }
        if (songs.isEmpty()) return
        if (queue.isEmpty()) {
            playQueue(songs, 0)
            return
        }
        queue = (queue + songs).distinctBy { it.videoId }
    }

    fun toggle() {
        if (!hasTrack) {
            if (queue.isNotEmpty()) load() else return
            return
        }
        if (playing) {
            Playback.pause()
            playing = false
        } else {
            Playback.play()
            playing = true
        }
    }

    fun playAt(position: Int) {
        if (position !in queue.indices) return
        index = position
        load()
    }

    fun jumpTo(videoId: String) {
        val position = queue.indexOfFirst { it.videoId == videoId }
        if (position >= 0) playAt(position)
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && sleepAfterTrack) {
            sleepAfterTrack = false
            sleepSeconds = null
            playing = false
            position = duration
            Playback.pause()
            return
        }
        if (auto && repeatMode == RepeatMode.One) {
            Playback.seek(0.0)
            Playback.play()
            playing = true
            position = 0.0
            return
        }
        val target = if (shuffle && queue.size > 1) {
            var candidate: Int
            do {
                candidate = Random.nextInt(queue.size)
            } while (candidate == index)
            candidate
        } else {
            index + 1
        }
        if (target > queue.lastIndex) {
            if (repeatMode == RepeatMode.All) index = 0 else {
                playing = false
                position = duration
                Playback.pause()
                return
            }
        } else {
            index = target
        }
        load()
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (position > 3.0) {
            seekTo(0.0)
            return
        }
        index = if (index <= 0) 0 else index - 1
        load()
    }

    fun seekTo(seconds: Double) {
        position = seconds.coerceIn(0.0, duration.coerceAtLeast(0.0))
        Playback.seek(position)
    }

    fun seekToFraction(fraction: Float) {
        seekTo(fraction.toDouble() * duration)
    }

    fun nudge(seconds: Double) {
        seekTo(position + seconds)
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f).toDouble()
        PlayerPrefs.updateVolume(volume.toFloat())
        Playback.setVolume(volume)
    }

    fun toggleMute() {
        setVolume(if (volume > 0.0) 0f else 0.8f)
    }

    fun changeRate(value: Double) {
        rate = value.coerceIn(0.5, 2.0)
        PlayerPrefs.updateRate(rate)
        Playback.setRate(rate)
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        PlayerPrefs.updateShuffle(shuffle)
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        PlayerPrefs.updateRepeat(
            when (repeatMode) {
                RepeatMode.Off -> "off"
                RepeatMode.All -> "all"
                RepeatMode.One -> "one"
            },
        )
    }

    fun expand() {
        if (hasTrack) expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun removeAt(position: Int) {
        if (position !in queue.indices) return
        val removingCurrent = position == index
        queue = queue.filterIndexed { i, _ -> i != position }
        if (queue.isEmpty()) {
            clearQueue()
            return
        }
        index = when {
            position < index -> index - 1
            index > queue.lastIndex -> queue.lastIndex
            else -> index
        }
        if (removingCurrent) load()
    }

    fun move(from: Int, to: Int) {
        if (from !in queue.indices || to !in queue.indices || from == to) return
        val mutable = queue.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        val currentId = current?.videoId
        queue = mutable
        index = queue.indexOfFirst { it.videoId == currentId }.coerceAtLeast(0)
    }

    fun clearQueue() {
        Playback.pause()
        queue = emptyList()
        index = -1
        playing = false
        position = 0.0
        duration = 0.0
        expanded = false
    }

    fun startSleepTimer(minutes: Int) {
        sleepAfterTrack = false
        sleepSeconds = (minutes * 60).coerceAtLeast(1).toDouble()
    }

    fun sleepAtEndOfTrack() {
        sleepSeconds = null
        sleepAfterTrack = true
    }

    fun cancelSleepTimer() {
        sleepSeconds = null
        sleepAfterTrack = false
    }

    /** Called by the UI ticker every ~500 ms. */
    fun tick() {
        if (!hasTrack) return
        val ready = Playback.isReady()
        buffering = !ready && playing
        if (!ready) return

        val liveDuration = Playback.duration()
        if (liveDuration > 0.0) duration = liveDuration
        position = Playback.position().coerceAtLeast(0.0)
        playing = Playback.isPlaying()

        val remaining = sleepSeconds
        if (remaining != null && playing) {
            val next = (remaining - 0.5).coerceAtLeast(0.0)
            sleepSeconds = next
            if (next <= 0.0) {
                Playback.pause()
                playing = false
                sleepSeconds = null
            }
        }

        if (Playback.consumeEnded()) {
            // With a crossfade the engine may already be playing the next
            // track — follow it instead of blindly advancing.
            val actual = Playback.currentVideoId()
            val target = if (actual != null) queue.indexOfFirst { it.videoId == actual } else -1
            if (target >= 0 && target != index) playAt(target) else next(auto = true)
        }
    }

    private fun load() {
        val item = current ?: return
        val id = item.videoId ?: return
        playing = true
        buffering = true
        position = 0.0
        duration = 0.0
        Recent.record(item)
        Stats.record(item)
        Playback.setVolume(volume)
        Playback.setRate(rate)
        Playback.load(id)
        Playback.play()
        Playback.setMetadata(
            title = item.title,
            artist = item.artists.firstOrNull()?.name ?: item.subtitle,
            artwork = item.thumbnail,
        )
    }
}

fun formatTime(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0.0) return "0:00"
    val total = seconds.toInt()
    val minutes = total / 60
    val secs = total % 60
    return "$minutes:${secs.toString().padStart(2, '0')}"
}
