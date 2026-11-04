package com.jusplayer.platform

/** Base URL of the JusPlayer gateway, e.g. `http://localhost:8787`. */
expect fun apiBase(): String

/** "web" or "desktop" — used for a small badge in the UI. */
expect fun platformName(): String

/** Open a URL in the host browser. */
expect fun openExternal(url: String)

/** Share a link with the OS/browser sheet, falling back to the clipboard. */
expect fun shareLink(url: String, title: String)

/** Dominant colour (0xRRGGBB) of the current artwork, or null when unavailable. */
expect fun artworkAccent(): Int?

/** Tiny key/value persistence (localStorage on web, java.util.prefs on desktop). */
expect object Storage {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
