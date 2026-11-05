package com.jusplayer.audio

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Smoke test for the native desktop engine.
 *
 * It needs a running gateway (the local one on 127.0.0.1:8787 is enough) and
 * an audio device, so it quietly skips itself when either is missing.
 */
class DesktopAudioEngineTest {

    private val gateway = "http://127.0.0.1:8787"

    private fun gatewayAvailable(): Boolean = GatewayHttp.alive(gateway)

    @Test
    fun playsThroughTheGateway() {
        if (!gatewayAvailable()) return

        val engine = DesktopAudioEngine(gatewayBase = { gateway })
        engine.load("dQw4w9WgXcQ")
        engine.play()

        val deadline = System.currentTimeMillis() + 30_000
        while (!engine.ready && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
        }
        assertTrue(engine.ready, "the engine never became ready: ${engine.error}")
        assertTrue(engine.error == null, "the engine reported ${engine.error}")

        // The position has to move.
        val firstDeadline = System.currentTimeMillis() + 20_000
        var moved = false
        while (System.currentTimeMillis() < firstDeadline) {
            Thread.sleep(500)
            if (engine.positionSeconds > 1.0) {
                moved = true
                break
            }
        }
        assertTrue(moved, "the position never advanced (${engine.error})")

        // Pausing has to freeze it.
        engine.pause()
        Thread.sleep(500)
        val frozen = engine.positionSeconds
        Thread.sleep(1_500)
        assertTrue(
            kotlin.math.abs(engine.positionSeconds - frozen) < 1.0,
            "the position kept moving while paused",
        )

        engine.stop()
    }
}
