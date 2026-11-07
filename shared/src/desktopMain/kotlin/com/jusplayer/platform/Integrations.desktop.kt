package com.jusplayer.platform

import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Local audio files, stored as a small JSON list in the desktop preferences.
 *
 * Nothing is copied or uploaded: the queue entry keeps the absolute path and
 * the audio engine opens it directly.
 */
actual object LocalFiles {

    private const val KEY = "jusplayer.localfiles.v1"
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Saved(val tracks: List<LocalTrack> = emptyList())

    private val state = mutableStateListOf<LocalTrack>()

    actual val available: Boolean = true

    actual val items: List<LocalTrack> get() = state

    private var loaded = false

    actual fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val saved = runCatching {
            Storage.get(KEY)?.let { json.decodeFromString<Saved>(it) }?.tracks
        }.getOrNull().orEmpty()
        // Drop files the user moved or deleted.
        val alive = saved.filter { File(it.path).isFile }
        state.clear()
        state.addAll(alive)
        if (alive.size != saved.size) persist()
    }

    actual suspend fun pickAndImport(): Int {
        ensureLoaded()
        val dialog = FileDialog(null as Frame?, "Add music files", FileDialog.LOAD).apply {
            isMultipleMode = true
            setFilenameFilter { _, name ->
                val lower = name.lowercase()
                listOf(".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".webm")
                    .any { lower.endsWith(it) }
            }
        }
        dialog.isVisible = true

        var added = 0
        dialog.files.orEmpty().forEach { file ->
            val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            if (state.any { it.path == path }) return@forEach
            val title = file.nameWithoutExtension
                .replace('_', ' ')
                .trim()
                .ifBlank { file.name }
            state.add(
                LocalTrack(
                    id = idFor(path),
                    title = title,
                    path = path,
                    bytes = runCatching { file.length() }.getOrDefault(0),
                ),
            )
            added++
        }
        if (added > 0) persist()
        return added
    }

    actual fun remove(track: LocalTrack) {
        state.removeAll { it.id == track.id }
        persist()
    }

    actual fun pathOf(videoId: String): String? =
        state.firstOrNull { it.id == videoId }?.path?.takeIf { File(it).isFile }

    private fun idFor(path: String): String =
        "file:" + Integer.toHexString(path.hashCode()) + "-" + path.length.toString(16)

    private fun persist() {
        runCatching {
            Storage.put(KEY, json.encodeToString(Saved(state.toList())))
        }
    }
}

// ------------------------------------------------------------------ scrobbling --

/**
 * ListenBrainz accepts a plain token in the `Authorization` header, so this is
 * a couple of HTTP calls and no OAuth dance. Failures are swallowed on a
 * background thread — scrobbling must never get in the way of the music.
 */
actual object Scrobbler {

    private const val KEY = "jusplayer.listenbrainz.token"
    private const val ENDPOINT = "https://api.listenbrainz.org/1/submit-listens"

    private var stored = runCatching { Storage.get(KEY) }.getOrNull().orEmpty()

    actual val available: Boolean = true

    actual val token: String get() = stored

    actual fun configure(token: String) {
        stored = token.trim()
        Storage.put(KEY, stored)
    }

    actual fun nowPlaying(title: String, artist: String, album: String, durationSeconds: Int) {
        submit("playing_now", title, artist, album, durationSeconds, listenedAt = null)
    }

    actual fun scrobble(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        startedAtSeconds: Long,
    ) {
        submit("single", title, artist, album, durationSeconds, listenedAt = startedAtSeconds)
    }

    private fun submit(
        type: String,
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        listenedAt: Long?,
    ) {
        if (stored.isBlank() || title.isBlank() || artist.isBlank()) return
        val body = buildString {
            append("{\"listen_type\":\"").append(type).append("\",\"payload\":[{")
            if (listenedAt != null) append("\"listened_at\":").append(listenedAt).append(',')
            append("\"track_metadata\":{")
            append("\"artist_name\":").append(quote(artist)).append(',')
            append("\"track_name\":").append(quote(title))
            if (album.isNotBlank()) append(",\"release_name\":").append(quote(album))
            if (durationSeconds > 0) {
                append(",\"additional_info\":{\"duration_ms\":").append(durationSeconds * 1000).append('}')
            }
            append("}}]}")
        }
        val token = stored
        Thread({
            runCatching {
                val connection = java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 6_000
                connection.readTimeout = 6_000
                connection.doOutput = true
                connection.setRequestProperty("authorization", "Token $token")
                connection.setRequestProperty("content-type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
                connection.responseCode
                connection.disconnect()
            }
        }, "jusplayer-scrobbler").apply {
            isDaemon = true
            start()
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
