package com.jusplayer.platform

import java.awt.Desktop
import java.net.URI
import java.util.prefs.Preferences

actual fun apiBase(): String = "http://127.0.0.1:8787"

actual fun platformName(): String = "desktop"

actual fun openExternal(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

actual fun shareLink(url: String, title: String) {
    openExternal(url)
}

actual fun artworkAccent(): Int? = null

actual object Storage {
    private val prefs: Preferences = Preferences.userNodeForPackage(Storage::class.java)

    actual fun get(key: String): String? = prefs.get(key, null)
    actual fun put(key: String, value: String) = prefs.put(key, value)
}
