package com.jusplayer.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** What the gateway tells us about a track's audio. */
@Serializable
data class StreamInfo(
    val videoId: String = "",
    val url: String = "",
    val proxyUrl: String = "",
    val watchUrl: String = "",
    val userAgent: String = "",
    val mimeType: String = "",
    val duration: Int = 0,
    val title: String = "",
    val author: String = "",
    val approxBytes: Long = 0,
)

/** A place we can pull audio from. */
private class Candidate(val kind: Kind, val url: String, val userAgent: String = "") {
    enum class Kind { Direct, Proxy, YtDlp, File }
}

/** Blocking HTTP helpers — the engine runs on its own threads. */
object GatewayHttp {

    fun get(base: String, path: String, timeoutMs: Int = 20_000): String {
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("accept", "application/json")
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun alive(base: String): Boolean = try {
        val connection = URL(base.trimEnd('/') + "/api/health").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_500
        connection.readTimeout = 2_500
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    } catch (_: Throwable) {
        false
    }
}

/**
 * Native desktop playback.
 *
 * ffmpeg decodes the track to raw PCM and Java Sound pushes it to the
 * speakers, which gives us real pause/seek/volume control plus a place to
 * hang the equalizer, the stereo widening and the loudness normalisation.
 *
 * The audio itself comes from the gateway: it hands out a direct CDN link
 * (with the matching User-Agent) and a same-origin proxy. If a link turns
 * out to be truncated — YouTube caps the links that need no token at about
 * one megabyte — the engine quietly falls back to the next candidate, and
 * finally to a local yt-dlp.
 */
class DesktopAudioEngine(
    private val gatewayBase: () -> String,
    private val onError: (String) -> Unit = {},
) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val FRAME_BYTES = CHANNELS * 2
        private const val QUEUE_FRAMES = 128
    }

    private val lock = Any()
    private val generation = AtomicInteger(0)

    private var loader: Thread? = null
    private var writer: Thread? = null
    private var line: SourceDataLine? = null

    @Volatile private var source: PcmSource? = null
    @Volatile private var incoming: PcmSource? = null

    @Volatile private var currentVideoId: String = ""
    @Volatile private var nextVideoId: String? = null
    @Volatile private var candidates: List<Candidate> = emptyList()
    @Volatile private var expectedDuration = 0.0
    @Volatile private var crossfadeSeconds = 0.0

    @Volatile var positionSeconds = 0.0
        private set
    @Volatile var durationSeconds = 0.0
        private set
    @Volatile var playing = false
        private set
    @Volatile var ready = false
        private set
    @Volatile var ended = false
        private set
    @Volatile var error: String? = null
        private set
    @Volatile var volume = 0.8
        private set
    @Volatile var rate = 1.0
        private set
    @Volatile var title = ""
        private set
    @Volatile var artist = ""
        private set
    @Volatile var artwork: String? = null
        private set

    /** True once per finished track; resets itself when read. */
    fun consumeEnded(): Boolean {
        if (!ended) return false
        ended = false
        return true
    }

    // ---------------------------------------------------------------- loading --

    fun load(videoId: String) {
        synchronized(lock) {
            // The crossfade may already have moved us on to this track.
            if (currentVideoId == videoId && source != null) return
            stopSources()
            generation.incrementAndGet()
            currentVideoId = videoId
            positionSeconds = 0.0
            durationSeconds = 0.0
            expectedDuration = 0.0
            ready = false
            ended = false
            error = null
            ensureWriter()
            loader = Thread({ prepare(videoId, generation.get()) }, "jusplayer-loader").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** The track that should start when the current one runs out. */
    fun setNextTrack(videoId: String?) {
        nextVideoId = videoId
    }

    private fun prepare(videoId: String, myGeneration: Int) {
        try {
            // A downloaded track never touches the network.
            val local = com.jusplayer.data.Downloads.localFile(videoId)
            if (local != null) {
                title = com.jusplayer.data.Downloads.items
                    .firstOrNull { it.videoId == videoId }?.title.orEmpty()
                artist = com.jusplayer.data.Downloads.items
                    .firstOrNull { it.videoId == videoId }?.artist.orEmpty()
                expectedDuration = 0.0
                durationSeconds = 0.0
                candidates = listOf(Candidate(Candidate.Kind.File, local))
                val started = startSource(videoId, candidates.first(), 0.0, myGeneration) ?: return
                ready = true
                return
            }

            val base = gatewayBase()
            val info = json.decodeFromString<StreamInfo>(
                GatewayHttp.get(base, "/api/stream?videoId=$videoId"),
            )
            if (generation.get() != myGeneration) return

            expectedDuration = info.duration.toDouble()
            durationSeconds = expectedDuration
            title = info.title
            artist = info.author

            val list = mutableListOf<Candidate>()
            if (info.url.isNotBlank()) list += Candidate(Candidate.Kind.Direct, info.url, info.userAgent)
            if (info.proxyUrl.isNotBlank()) list += Candidate(Candidate.Kind.Proxy, base.trimEnd('/') + info.proxyUrl)
            if (info.watchUrl.isNotBlank()) list += Candidate(Candidate.Kind.YtDlp, info.watchUrl)
            if (list.isEmpty()) {
                fail("The gateway did not return an audio stream for this track")
                return
            }
            candidates = list

            var index = 0
            var offset = 0.0
            while (index < list.size) {
                val started = startSource(videoId, list[index], offset, myGeneration) ?: return
                ready = true
                started.done.await()
                if (generation.get() != myGeneration) return
                if (started.failed && started.error != null && index + 1 < list.size) {
                    offset = started.position
                    index += 1
                    continue
                }
                // A stream that stops long before the end was cut short by
                // YouTube — try the next way of getting the audio.
                if (expectedDuration > 5.0 &&
                    started.position < expectedDuration - 3.0 &&
                    index + 1 < list.size
                ) {
                    offset = started.position
                    index += 1
                    continue
                }
                break
            }
        } catch (failure: Throwable) {
            if (generation.get() == myGeneration) {
                fail("Could not start playback: ${failure.message ?: failure::class.simpleName}")
            }
        }
    }

    /** Starts ffmpeg for one candidate and returns the source it feeds. */
    private fun startSource(
        videoId: String,
        candidate: Candidate,
        offset: Double,
        myGeneration: Int,
    ): PcmSource? {
        return try {
            var url = candidate.url
            var userAgent = candidate.userAgent
            if (candidate.kind == Candidate.Kind.YtDlp) {
                val resolved = resolveWithYtDlp(candidate.url)
                if (resolved.isNullOrBlank()) {
                    fail("yt-dlp could not resolve this track")
                    return null
                }
                url = resolved
                userAgent = ""
            }
            val process = ProcessBuilder(buildCommand(url, userAgent, offset))
                .redirectErrorStream(false)
                .start()
            val source = PcmSource(videoId, process, ArrayBlockingQueue(QUEUE_FRAMES), expectedDuration)
            source.start()
            this.source = source
            source
        } catch (failure: Throwable) {
            if (failure is java.io.IOException && failure.message?.contains("Cannot run program") == true) {
                fail("ffmpeg was not found — install ffmpeg to play audio on the desktop")
            } else {
                fail("Could not start the audio decoder: ${failure.message}")
            }
            null
        }
    }

    private fun buildCommand(url: String, userAgent: String, offset: Double): List<String> {
        val command = mutableListOf(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel", "error",
            "-progress", "pipe:2",
        )
        if (userAgent.isNotBlank()) command += listOf("-user_agent", userAgent)
        if (offset > 0.5) command += listOf("-ss", formatOffset(offset))
        command += listOf("-i", url, "-vn", "-sn", "-dn")
        val filters = buildFilters()
        if (filters.isNotBlank()) command += listOf("-af", filters)
        command += listOf(
            "-ar", SAMPLE_RATE.toString(),
            "-ac", CHANNELS.toString(),
            "-acodec", "pcm_s16le",
            "-f", "s16le",
            "pipe:1",
        )
        return command
    }

    /** Turns the shared audio profile into an ffmpeg filter chain. */
    private fun buildFilters(): String {
        val filters = mutableListOf<String>()
        val profile = AudioProfile
        if (profile.preamp != 0.0) {
            filters += "volume=${"%.2f".format(profile.preamp / 20.0)}dB"
        }
        if (profile.equalizerEnabled) {
            profile.frequencies.forEachIndexed { index, frequency ->
                val gain = profile.gains.getOrElse(index) { 0.0 }
                if (gain != 0.0) {
                    filters += "equalizer=f=$frequency:t=q:w=1:g=${"%.1f".format(gain)}"
                }
            }
        }
        if (profile.spatialEnabled && profile.spatialWidth > 0.0) {
            filters += "haas=lr=${"%.2f".format(profile.spatialWidth)}"
        }
        if (profile.loudnessEnabled) {
            filters += "loudnorm=I=-16:TP=-1.5:LRA=11"
        }
        if (rate != 1.0) {
            filters += "atempo=${"%.3f".format(rate)}"
        }
        return filters.joinToString(",")
    }

    private fun resolveWithYtDlp(watchUrl: String): String? = try {
        val process = ProcessBuilder(
            "yt-dlp",
            "--js-runtimes", "node",
            "--no-playlist",
            "--no-warnings",
            "-f", "140/bestaudio",
            "-g", watchUrl,
        ).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(90, TimeUnit.SECONDS)
        output.lineSequence().firstOrNull { it.startsWith("http") }
    } catch (_: Throwable) {
        null
    }

    private fun formatOffset(seconds: Double): String {
        val total = seconds.toInt()
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    // --------------------------------------------------------------- controls --

    fun play() {
        playing = true
        ensureWriter()
    }

    fun pause() {
        playing = false
    }

    fun seek(seconds: Double) {
        val target = seconds.coerceAtLeast(0.0)
        synchronized(lock) {
            val videoId = currentVideoId
            if (videoId.isBlank()) return
            stopSources()
            generation.incrementAndGet()
            val myGeneration = generation.get()
            ready = false
            ended = false
            positionSeconds = target
            loader = Thread({
                val list = candidates
                if (list.isEmpty()) {
                    prepare(videoId, myGeneration)
                    return@Thread
                }
                try {
                    startSource(videoId, list.first(), target, myGeneration)
                    ready = true
                } catch (failure: Throwable) {
                    fail("Could not seek: ${failure.message}")
                }
            }, "jusplayer-seek").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun setVolume(value: Double) {
        volume = value.coerceIn(0.0, 1.0)
    }

    fun setRate(value: Double) {
        val next = value.coerceIn(0.5, 2.0)
        if (next == rate) return
        rate = next
        // The tempo is an ffmpeg filter, so the decoder has to be restarted.
        val videoId = currentVideoId
        if (videoId.isNotBlank()) {
            val at = positionSeconds
            seek(at)
        }
    }

    fun setMetadata(title: String, artist: String, artwork: String?) {
        this.title = title
        this.artist = artist
        this.artwork = artwork
    }

    fun setCrossfade(seconds: Double) {
        crossfadeSeconds = seconds.coerceIn(0.0, 12.0)
    }

    fun stop() {
        synchronized(lock) {
            generation.incrementAndGet()
            stopSources()
            currentVideoId = ""
            ready = false
            playing = false
            positionSeconds = 0.0
            durationSeconds = 0.0
        }
    }

    private fun stopSources() {
        source?.cancel()
        source = null
        incoming?.cancel()
        incoming = null
    }

    private fun fail(message: String) {
        error = message
        ready = false
        playing = false
        onError(message)
    }

    // ----------------------------------------------------------------- mixer --

    private fun ensureWriter() {
        if (writer != null) return
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val opened = runCatching {
            val source = AudioSystem.getSourceDataLine(format)
            source.open(format, (format.sampleRate * FRAME_BYTES * 2).toInt())
            source.start()
            source
        }.getOrNull()
        if (opened == null) {
            fail("No audio output device is available")
            return
        }
        line = opened
        writer = Thread({ writeLoop() }, "jusplayer-mixer").apply {
            isDaemon = true
            start()
        }
    }

    /** The video id the engine is really playing right now. */
    fun currentVideoId(): String = currentVideoId

    private fun writeLoop() {
        val target = line ?: return
        while (true) {
            val current = source
            if (current == null || !playing) {
                Thread.sleep(40)
                continue
            }
            val frame = current.queue.poll(200, TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (current.finished && current.queue.isEmpty()) onSourceFinished(current)
                continue
            }

            var output = frame
            val next = incoming
            if (next != null) {
                val incomingFrame = next.queue.poll(50, TimeUnit.MILLISECONDS)
                val progress = crossfadeProgress(current)
                output = mix(frame, incomingFrame, progress)
            }

            applyVolume(output)
            try {
                target.write(output, 0, output.size)
            } catch (failure: Throwable) {
                return
            }
            positionSeconds = current.position
            // Start the next track early enough to fade into it.
            prepareCrossfade()
        }
    }

    /** 0.0 at the start of the fade, 1.0 when the old track is gone. */
    private fun crossfadeProgress(current: PcmSource): Double {
        if (crossfadeSeconds <= 0.0) return 0.0
        val start = (expectedDuration - crossfadeSeconds).coerceAtLeast(0.0)
        return ((current.position - start) / crossfadeSeconds).coerceIn(0.0, 1.0)
    }

    private fun mix(outgoing: ByteArray, incomingFrame: ByteArray?, progress: Double): ByteArray {
        val outGain = 1.0 - progress
        val inGain = progress
        var index = 0
        while (index + 1 < outgoing.size) {
            val left = readShort(outgoing, index)
            val right = readShort(outgoing, index + 2)
            val inLeft = if (incomingFrame != null && index + 1 < incomingFrame.size) readShort(incomingFrame, index) else 0
            val inRight = if (incomingFrame != null && index + 3 < incomingFrame.size) readShort(incomingFrame, index + 2) else 0
            writeShort(outgoing, index, scale(left * outGain + inLeft * inGain))
            writeShort(outgoing, index + 2, scale(right * outGain + inRight * inGain))
            index += 4
        }
        return outgoing
    }

    private fun applyVolume(buffer: ByteArray) {
        val gain = volume
        if (gain >= 0.999) return
        var index = 0
        while (index + 1 < buffer.size) {
            writeShort(buffer, index, scale(readShort(buffer, index) * gain))
            writeShort(buffer, index + 2, scale(readShort(buffer, index + 2) * gain))
            index += 4
        }
    }

    private fun readShort(buffer: ByteArray, index: Int): Int {
        val low = buffer[index].toInt() and 0xFF
        val high = buffer[index + 1].toInt()
        return (high shl 8) or low
    }

    private fun writeShort(buffer: ByteArray, index: Int, value: Int) {
        buffer[index] = (value and 0xFF).toByte()
        buffer[index + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun scale(value: Double): Int {
        val clamped = value.coerceIn(-32768.0, 32767.0)
        return clamped.toInt()
    }

    /**
     * The current track ran out.
     *
     * With a crossfade the next track is already playing, so we simply swap
     * it in and let the app catch up; without one the track is over.
     */
    private fun onSourceFinished(current: PcmSource) {
        val next = incoming
        if (next != null && !next.finished) {
            incoming = null
            source = next
            currentVideoId = next.videoId
            expectedDuration = next.duration
            durationSeconds = next.duration
            positionSeconds = next.position
            ended = true
            return
        }
        if (current === source) {
            positionSeconds = current.position
            ended = true
            playing = false
        }
    }

    /** Starts the next track a little before the current one ends. */
    fun prepareCrossfade() {
        if (crossfadeSeconds <= 0.0) return
        if (incoming != null) return
        val videoId = nextVideoId ?: return
        val current = source ?: return
        if (expectedDuration <= 0.0) return
        if (current.position < expectedDuration - crossfadeSeconds) return
        val list = candidates
        if (list.isEmpty()) return
        val next = startSource(videoId, list.first(), 0.0, generation.get()) ?: return
        incoming = next
    }

    // ---------------------------------------------------------------- source --

    /** One ffmpeg process, decoded to PCM frames. */
    private class PcmSource(
        val videoId: String,
        val process: Process,
        val queue: ArrayBlockingQueue<ByteArray>,
        val duration: Double,
    ) {
        val done = CountDownLatch(1)

        @Volatile var position = 0.0
            private set
        @Volatile var finished = false
            private set
        @Volatile var failed = false
            private set
        @Volatile var error: String? = null
            private set
        @Volatile var cancelled = false
            private set

        fun start() {
            Thread({ readProgress() }, "jusplayer-ffmpeg-progress").apply {
                isDaemon = true
                start()
            }
            Thread({ pump() }, "jusplayer-ffmpeg").apply {
                isDaemon = true
                start()
            }
        }

        fun cancel() {
            cancelled = true
            runCatching { process.destroy() }
            runCatching { process.destroyForcibly() }
            queue.clear()
            done.countDown()
        }

        private fun pump() {
            try {
                BufferedInputStream(process.inputStream).use { input ->
                    val buffer = ByteArray(16384)
                    while (!cancelled) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read <= 0) continue
                        val usable = read - (read % FRAME_BYTES)
                        if (usable <= 0) continue
                        val frame = buffer.copyOf(usable)
                        while (!cancelled && !queue.offer(frame, 200, TimeUnit.MILLISECONDS)) {
                            // the mixer is behind — wait for it
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (!cancelled) {
                    failed = true
                    error = failure.message
                }
            } finally {
                finished = true
                done.countDown()
            }
        }

        private fun readProgress() {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        when {
                            line.startsWith("out_time_us=") -> {
                                line.removePrefix("out_time_us=").toLongOrNull()?.let {
                                    position = it / 1_000_000.0
                                }
                            }
                            line.startsWith("out_time=") && !line.startsWith("out_time_ms") -> {
                                position = parseClock(line.removePrefix("out_time="))
                            }
                            line.contains("403") || line.contains("Invalid") ||
                                line.contains("Error") || line.contains("error") -> {
                                if (error == null) error = line.trim()
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // the process went away — nothing to report
            }
        }

        private fun parseClock(value: String): Double = try {
            val parts = value.trim().split(":")
            var seconds = 0.0
            parts.forEach { seconds = seconds * 60 + it.toDouble() }
            seconds
        } catch (_: Throwable) {
            0.0
        }
    }
}
