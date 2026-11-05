package com.jusplayer.platform

import com.jusplayer.audio.GatewayHttp
import java.awt.Desktop
import java.net.URI
import java.util.prefs.Preferences

private const val LOCAL_GATEWAY = "http://127.0.0.1:8787"
private const val LIVE_GATEWAY = "https://jusplayer.vercel.app"
private const val API_KEY = "jusplayer.apiBase"
private const val SESSION_KEY = "jusplayer.session"

private var cachedBase: String? = null

actual fun apiBase(): String {
    cachedBase?.let { return it }
    val configured = System.getProperty("jusplayer.api")
        ?: System.getenv("JUSPLAYER_API")
        ?: Storage.get(API_KEY)
    val base = configured?.trim()?.takeIf { it.isNotEmpty() } ?: defaultBase()
    cachedBase = base
    return base
}

/** Point the app at a different gateway (used by the settings screen). */
fun setApiBase(value: String) {
    val clean = value.trim().trimEnd('/')
    Storage.put(API_KEY, clean)
    cachedBase = clean
}

/**
 * Without an explicit setting we prefer a gateway running on this machine:
 * it can resolve whole tracks, while the hosted one is limited to short
 * previews unless yt-dlp is installed locally.
 */
private fun defaultBase(): String =
    if (GatewayHttp.alive(LOCAL_GATEWAY)) LOCAL_GATEWAY else LIVE_GATEWAY

actual fun platformName(): String = "desktop"

actual fun openExternal(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
}

actual fun shareLink(url: String, title: String) {
    openExternal(url)
}

actual fun artworkAccent(): Int? = null

/** Desktop sign-in uses a pasted cookie, so there is never a redirect to report. */
actual fun signInNotice(): String? = null

actual object Storage {
    private val prefs: Preferences = Preferences.userNodeForPackage(Storage::class.java)

    actual fun get(key: String): String? = prefs.get(key, null)
    actual fun put(key: String, value: String) = prefs.put(key, value)
}

/**
 * The desktop app is not a browser, so it keeps the gateway's session
 * cookie itself and sends it with every request.
 */
actual object Session {
    private var cookie: String? = Storage.get(SESSION_KEY)

    actual fun headers(): Map<String, String> {
        val value = cookie ?: return emptyMap()
        return mapOf("Cookie" to value)
    }

    actual fun rememberCookies(cookies: List<String>) {
        val session = cookies
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("jus_session=") }
        if (session != null) {
            cookie = session
            Storage.put(SESSION_KEY, session)
        }
    }

    actual fun clear() {
        cookie = null
        Storage.put(SESSION_KEY, "")
    }
}
