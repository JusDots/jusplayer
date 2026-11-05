package com.jusplayer.data

import com.jusplayer.platform.apiBase
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trips one track through the gateway into the downloads folder.
 *
 * Skipped unless a local gateway is running — it downloads a real file and
 * takes a few seconds.
 */
class DownloadsTest {

    private fun gatewayUp(): Boolean = runCatching {
        val connection = java.net.URL("${apiBase().trimEnd('/')}/api/health")
            .openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 1_500
        connection.readTimeout = 1_500
        val ok = connection.responseCode == 200
        connection.disconnect()
        ok
    }.getOrDefault(false)

    @Test
    fun downloadsAWholeTrackAndRemovesItAgain() {
        if (!gatewayUp()) {
            println("DownloadsTest skipped: no gateway at ${apiBase()}")
            return
        }

        Downloads.ensureLoaded()
        val videoId = "khnokW3Mw24"
        val item = MediaItem(
            id = "v:$videoId",
            title = "Instant Crush",
            subtitle = "Daft Punk",
            kind = "song",
            videoId = videoId,
        )

        Downloads.items.firstOrNull { it.videoId == videoId }?.let { Downloads.remove(it) }

        val failure = runBlocking { Downloads.download(item) }
        assertEquals(null, failure, "download failed: $failure")
        assertTrue(Downloads.isDownloaded(videoId), "track is not in the list")

        val track = Downloads.items.first { it.videoId == videoId }
        assertTrue(track.bytes > 100_000, "file is only ${track.bytes} bytes")
        assertTrue(Files.exists(Path.of(track.file)), "file is missing: ${track.file}")
        println("downloaded ${track.bytes} bytes to ${track.file}")

        Downloads.remove(track)
        assertTrue(!Downloads.isDownloaded(videoId), "track was not removed")
        assertTrue(!Files.exists(Path.of(track.file)), "file was not deleted")
    }
}
