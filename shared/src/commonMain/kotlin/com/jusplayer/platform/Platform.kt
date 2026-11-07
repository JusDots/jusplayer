package com.jusplayer.platform

/** Base URL of the JusPlayer gateway, e.g. `http://localhost:8787`. */
expect fun apiBase(): String

/** Unix time in seconds, used by scrobbling. */
expect fun epochSeconds(): Long

/** "web" or "desktop" — used for a small badge in the UI. */
expect fun platformName(): String

/** Open a URL in the host browser. */
expect fun openExternal(url: String)

/** Share a link with the OS/browser sheet, falling back to the clipboard. */
expect fun shareLink(url: String, title: String)

/** Dominant colour (0xRRGGBB) of the current artwork, or null when unavailable. */
expect fun artworkAccent(): Int?

/**
 * A one-shot message about a sign-in round trip, or null.
 *
 * The web app leaves for Google and comes back with `?signin=…&reason=…`;
 * this turns that into something the user can read and removes it from the
 * address bar. Desktop clients sign in with a cookie, so they return null.
 */
expect fun signInNotice(): String?

/** Tiny key/value persistence (localStorage on web, java.util.prefs on desktop). */
expect object Storage {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Keeps the signed-in session alive outside a browser.
 *
 * On the web the browser sends the gateway's session cookie by itself, so
 * this is a no-op. The desktop app talks to the gateway over plain HTTP and
 * therefore has to carry the cookie itself.
 */
expect object Session {
    /** Headers that every gateway request needs. */
    fun headers(): Map<String, String>

    /** Called after a sign-in so the app can remember the session cookie. */
    fun rememberCookies(cookies: List<String>)

    /** Forget the session (sign out). */
    fun clear()
}
