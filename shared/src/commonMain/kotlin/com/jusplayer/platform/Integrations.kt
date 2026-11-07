package com.jusplayer.platform

import kotlinx.serialization.Serializable

/** One audio file the user imported from this machine. */
@Serializable
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val path: String,
    val bytes: Long = 0,
)

/**
 * Audio files that already live on this machine.
 *
 * The desktop app can add them to the queue without uploading anything: the
 * engine opens the file directly. Browsers cannot read an arbitrary path, so
 * the web build reports the feature as unavailable.
 */
expect object LocalFiles {
    val available: Boolean

    val items: List<LocalTrack>

    fun ensureLoaded()

    /** Opens the OS picker and returns how many files were added (0 = cancelled). */
    suspend fun pickAndImport(): Int

    fun remove(track: LocalTrack)

    /** Absolute path behind a queue id such as `file:abc123`, or null. */
    fun pathOf(videoId: String): String?
}

/**
 * Discord Rich Presence ("Listening to …").
 *
 * The desktop app speaks the local Discord IPC protocol directly, so no
 * library or bot token is needed — only the user's application client id.
 */
expect object DiscordPresence {
    val available: Boolean

    /** True once a client id is configured and stored. */
    val configured: Boolean

    val clientId: String

    fun configure(clientId: String)

    /** Publish the current track. Cheap to call often; it throttles itself. */
    fun update(
        title: String,
        artist: String,
        artwork: String?,
        playing: Boolean,
        positionSeconds: Double,
        durationSeconds: Double,
    )

    fun clear()
}

/**
 * Scrobbling to ListenBrainz.
 *
 * The token is created at <https://listenbrainz.org/settings/>. Everything is
 * fire-and-forget: a network failure never interrupts playback.
 */
expect object Scrobbler {
    val available: Boolean

    val token: String

    fun configure(token: String)

    /** Submit the "playing now" notification for a track that just started. */
    fun nowPlaying(title: String, artist: String, album: String, durationSeconds: Int)

    /** Submit a completed play. `startedAtSeconds` is a Unix timestamp. */
    fun scrobble(
        title: String,
        artist: String,
        album: String,
        durationSeconds: Int,
        startedAtSeconds: Long,
    )
}
