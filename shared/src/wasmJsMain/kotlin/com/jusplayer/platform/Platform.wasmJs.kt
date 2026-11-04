package com.jusplayer.platform

@JsFun("() => (window.JUS_API_BASE || window.location.origin)")
private external fun jsApiBase(): String

actual fun apiBase(): String = jsApiBase()

actual fun platformName(): String = "web"

@JsFun("(url) => { window.open(url, '_blank', 'noopener'); }")
private external fun jsOpen(url: String)

actual fun openExternal(url: String) = jsOpen(url)

@JsFun("(url, title) => { const share = window.navigator && window.navigator.share; if (share) { window.navigator.share({ title: title, url: url }).catch(() => {}); } else if (window.navigator.clipboard) { window.navigator.clipboard.writeText(url); } }")
private external fun jsShare(url: String, title: String)

actual fun shareLink(url: String, title: String) = jsShare(url, title)

@JsFun("() => (window.jusArtworkColor || 0)")
private external fun jsArtworkAccent(): Int

actual fun artworkAccent(): Int? = jsArtworkAccent().takeIf { it != 0 }

@JsFun("(k) => { try { return window.localStorage.getItem(k); } catch (e) { return null; } }")
private external fun jsStorageGet(key: String): String?

@JsFun("(k, v) => { try { window.localStorage.setItem(k, v); } catch (e) {} }")
private external fun jsStoragePut(key: String, value: String)

actual object Storage {
    actual fun get(key: String): String? = jsStorageGet(key)
    actual fun put(key: String, value: String) = jsStoragePut(key, value)
}
