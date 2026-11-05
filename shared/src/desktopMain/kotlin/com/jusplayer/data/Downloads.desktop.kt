package com.jusplayer.data

import androidx.compose.runtime.mutableStateListOf
import com.jusplayer.platform.Session
import com.jusplayer.platform.apiBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Downloads live next to the user's other application data:
 *
 *   Linux   ~/.local/share/jusplayer/downloads
 *   Windows %APPDATA%\JusPlayer\downloads
 *   macOS   ~/Library/Application Support/JusPlayer/downloads
 *
 * Each track is a plain `.m4a` plus one index entry, so the folder can be
 * copied to another machine and still play.
 */
actual object Downloads {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class Index(val tracks: List<DownloadedTrack> = emptyList())

    private val root: Path by lazy {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            os.contains("win") ->
                Path.of(System.getenv("APPDATA") ?: home.resolve("AppData/Roaming").toString())
                    .resolve("JusPlayer")
            os.contains("mac") -> home.resolve("Library/Application Support/JusPlayer")
            else ->
                Path.of(System.getenv("XDG_DATA_HOME") ?: home.resolve(".local/share").toString())
                    .resolve("jusplayer")
        }
        val dir = base.resolve("downloads")
        runCatching { Files.createDirectories(dir) }
        dir
    }

    private val indexFile: Path get() = root.resolve("index.json")

    actual val available: Boolean = true

    private val state = mutableStateListOf<DownloadedTrack>()

    actual val items: List<DownloadedTrack> get() = state

    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = runCatching {
            indexFile.takeIf { Files.exists(it) }
                ?.let { json.decodeFromString<Index>(Files.readString(it)) }
                ?.tracks
        }.getOrNull().orEmpty()

        // Drop entries whose file disappeared (user deleted it by hand).
        val alive = saved.filter { it.file.isNotEmpty() && Files.exists(Path.of(it.file)) }
        state.clear()
        state.addAll(alive.sortedByDescending { it.addedAt })
        if (alive.size != saved.size) persist()
    }

    actual fun isDownloaded(videoId: String): Boolean = items.any { it.videoId == videoId }

    /** The downloaded file for a track, if there is one. */
    fun localFile(videoId: String): String? =
        items.firstOrNull { it.videoId == videoId }
            ?.file
            ?.takeIf { Files.exists(Path.of(it)) }

    actual fun remove(track: DownloadedTrack) {
        runCatching { if (track.file.isNotEmpty()) Files.deleteIfExists(Path.of(track.file)) }
        state.removeAll { it.videoId == track.videoId }
        persist()
    }

    actual suspend fun download(item: MediaItem): String? = withContext(Dispatchers.IO) {
        val videoId = item.videoId ?: return@withContext "Only songs can be downloaded."
        if (isDownloaded(videoId)) return@withContext null

        val target = root.resolve("$videoId.m4a")
        val partial = root.resolve("$videoId.part")
        try {
            val connection =
                URL("${apiBase().trimEnd('/')}/api/download?videoId=$videoId").openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("accept", "audio/*")
            Session.headers().forEach { (key, value) -> connection.setRequestProperty(key, value) }

            val code = connection.responseCode
            if (code !in 200..299) {
                return@withContext "The gateway answered HTTP $code for this track."
            }

            connection.inputStream.use { input ->
                Files.newOutputStream(partial).use { output -> input.copyTo(output) }
            }
            val bytes = Files.size(partial)
            if (bytes < 64_000) {
                Files.deleteIfExists(partial)
                return@withContext "YouTube only served a short preview of this track."
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)

            val track = DownloadedTrack(
                videoId = videoId,
                title = item.title,
                artist = item.primaryArtist,
                album = item.subtitle,
                thumbnail = item.thumbnail,
                bytes = bytes,
                file = target.toString(),
                addedAt = System.currentTimeMillis(),
            )
            state.removeAll { it.videoId == videoId }
            state.add(0, track)
            persist()
            null
        } catch (failure: Throwable) {
            runCatching { Files.deleteIfExists(partial) }
            failure.message ?: "The download failed."
        }
    }

    private fun persist() {
        runCatching {
            Files.writeString(indexFile, json.encodeToString(Index(state.toList())))
        }
    }
}
