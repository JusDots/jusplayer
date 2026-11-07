package com.jusplayer.platform

import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Discord Rich Presence over the local IPC socket.
 *
 * A desktop app does not need a bot or an OAuth token: Discord exposes a Unix
 * socket (Linux/macOS) and a named pipe (Windows), and the client only has to
 * say who it is and what is playing. Everything here is best effort — if
 * Discord is not running, the calls silently do nothing.
 */
actual object DiscordPresence {

    private const val KEY = "jusplayer.discord.clientId"
    private const val OP_HANDSHAKE = 0
    private const val OP_FRAME = 1
    private const val OP_PING = 3
    private const val OP_PONG = 4

    private var stored = runCatching { Storage.get(KEY) }.getOrNull().orEmpty()

    actual val available: Boolean = true

    actual val configured: Boolean get() = stored.isNotBlank()

    actual val clientId: String get() = stored

    private val lock = Any()
    private var link: Link? = null
    private var ready: CountDownLatch? = null
    private var lastSentAt = 0L
    private var lastFingerprint = ""

    actual fun configure(clientId: String) {
        val clean = clientId.trim()
        stored = clean
        Storage.put(KEY, clean)
        close()
    }

    actual fun update(
        title: String,
        artist: String,
        artwork: String?,
        playing: Boolean,
        positionSeconds: Double,
        durationSeconds: Double,
    ) {
        if (stored.isBlank() || title.isBlank()) return
        val fingerprint = "$title|$artist|$playing"
        val now = System.currentTimeMillis()
        // Presence is decorative; do not spam the socket on every tick.
        if (fingerprint == lastFingerprint && now - lastSentAt < 4_000) return
        lastFingerprint = fingerprint
        lastSentAt = now

        val payload = buildString {
            append("{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":")
            append(ProcessHandle.current().pid())
            append(",\"activity\":{")
            append("\"type\":2,")
            append("\"details\":").append(quote(title))
            if (artist.isNotBlank()) append(",\"state\":").append(quote(artist))
            if (playing) {
                val start = System.currentTimeMillis() - (positionSeconds.coerceAtLeast(0.0) * 1000).toLong()
                append(",\"timestamps\":{\"start\":").append(start).append('}')
            }
            if (!artwork.isNullOrBlank() && artwork.startsWith("https://")) {
                append(",\"assets\":{\"large_image\":").append(quote(artwork))
                append(",\"large_text\":\"JusPlayer\"}")
            }
            append(",\"instance\":true}}}")
            append(",\"nonce\":").append(quote(UUID.randomUUID().toString()))
            append('}')
        }
        val id = stored
        Thread({ send(id, payload) }, "jusplayer-discord").apply {
            isDaemon = true
            start()
        }
    }

    actual fun clear() {
        val id = stored
        if (id.isBlank()) return
        lastFingerprint = ""
        Thread({
            send(
                id,
                "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":${ProcessHandle.current().pid()},\"activity\":null},\"nonce\":\"${UUID.randomUUID()}\"}",
            )
        }, "jusplayer-discord").apply {
            isDaemon = true
            start()
        }
    }

    // ------------------------------------------------------------- internals --

    private fun send(id: String, payload: String) {
        val connection = connect(id) ?: return
        runCatching { writeFrame(connection, OP_FRAME, payload) }
    }

    private fun connect(id: String): Link? {
        synchronized(lock) {
            link?.let { return it }
            val path = socketPath() ?: return null
            val candidate = runCatching { open(path) }.getOrNull() ?: return null
            val latch = CountDownLatch(1)
            ready = latch
            link = candidate
            runCatching { writeFrame(candidate, OP_HANDSHAKE, "{\"v\":1,\"client_id\":\"$id\"}") }
                .onFailure { close(); return null }

            Thread({
                runCatching { readLoop(candidate) }
                close()
            }, "jusplayer-discord-reader").apply {
                isDaemon = true
                start()
            }
            latch.await(4, TimeUnit.SECONDS)
            return link
        }
    }

    private fun readLoop(source: Link) {
        while (true) {
            val header = source.readFully(8)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val opcode = buffer.int
            val length = buffer.int
            if (length < 0 || length > 1_000_000) return
            val payload = String(source.readFully(length), Charsets.UTF_8)
            when (opcode) {
                OP_PING -> synchronized(lock) { runCatching { writeFrame(source, OP_PONG, payload) } }
                OP_FRAME -> if (payload.contains("\"evt\":\"READY\"")) ready?.countDown()
            }
        }
    }

    private fun writeFrame(destination: Link, opcode: Int, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(opcode)
        header.putInt(payload.size)
        destination.write(header.array() + payload)
    }

    private fun close() {
        synchronized(lock) {
            runCatching { link?.close() }
            link = null
            ready = null
        }
    }

    private fun socketPath(): String? {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("win")) return "\\\\.\\pipe\\discord-ipc-0"

        val home = System.getProperty("user.home")
        val candidates = buildList {
            System.getenv("XDG_RUNTIME_DIR")?.let { add("$it/discord-ipc-0") }
            System.getenv("TMPDIR")?.let { add("$it/discord-ipc-0") }
            add("/tmp/discord-ipc-0")
            add("$home/.config/discord/discord-ipc-0")
            add("$home/Library/Application Support/discord/discord-ipc-0")
            // Flatpak and Snap installs use their own runtime directories.
            runCatching {
                java.io.File("/run/user").listFiles()?.forEach { add("${it.path}/discord-ipc-0") }
            }
            runCatching {
                java.io.File("/tmp").listFiles()?.forEach { file ->
                    if (file.name.startsWith("discord-ipc-") || file.name.startsWith("snap.discord")) {
                        add("${file.path}/discord-ipc-0")
                    }
                }
            }
        }
        return candidates.firstOrNull { java.io.File(it).exists() }
    }

    private fun open(path: String): Link =
        if (path.startsWith("\\\\.\\pipe")) PipeLink(path) else UnixLink(path)

    private interface Link {
        fun write(bytes: ByteArray)
        fun readFully(count: Int): ByteArray
        fun close()
    }

    private class UnixLink(path: String) : Link {
        private val channel = SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(path))
        }

        override fun write(bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        override fun readFully(count: Int): ByteArray {
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw java.io.EOFException("Discord closed the socket")
            }
            return buffer.array()
        }

        override fun close() = runCatching { channel.close() }.let { }
    }

    /** Windows named pipes are files to `RandomAccessFile`. */
    private class PipeLink(path: String) : Link {
        private val pipe = RandomAccessFile(path, "rw")

        override fun write(bytes: ByteArray) {
            pipe.write(bytes)
        }

        override fun readFully(count: Int): ByteArray {
            val buffer = ByteArray(count)
            pipe.readFully(buffer)
            return buffer
        }

        override fun close() = runCatching { pipe.close() }.let { }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(character)
            }
        }
        append('"')
    }
}
