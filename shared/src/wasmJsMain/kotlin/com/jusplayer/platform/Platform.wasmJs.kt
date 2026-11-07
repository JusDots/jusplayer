package com.jusplayer.platform

@JsFun("() => (window.JUS_API_BASE || window.location.origin)")
private external fun jsApiBase(): String

actual fun apiBase(): String = jsApiBase()

@JsFun("() => Math.floor(Date.now() / 1000)")
private external fun jsEpochSeconds(): Double

actual fun epochSeconds(): Long = jsEpochSeconds().toLong()

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

@JsFun(
    """() => {
  try {
    const params = new URLSearchParams(window.location.search);
    const status = params.get('signin');
    if (!status) return null;
    const reason = (params.get('reason') || '').trim();
    const stage = (params.get('stage') || '').trim();

    params.delete('signin');
    params.delete('reason');
    params.delete('stage');
    const rest = params.toString();
    window.history.replaceState({}, '', window.location.pathname + (rest ? '?' + rest : ''));

    if (status === 'ok') return 'Signed in to YouTube Music.';
    if (status === 'out') return 'Signed out.';
    if (status === 'denied') return 'Sign-in was cancelled.';
    if (status === 'bad_state') return 'That sign-in link expired. Please try again.';
    if (status === 'access_denied') {
      return 'Google blocked the sign-in' + (reason ? ': ' + reason : '.') +
        ' If the app is still in testing mode, add this Google account as a test user, or publish the app.';
    }
    if (status === 'org_internal') {
      return 'This Google OAuth client only allows accounts inside its organisation. ' +
        'Set the consent screen user type to External, or sign in with a cookie.';
    }
    if (status === 'admin_policy_enforced') {
      return 'Your Google Workspace administrator blocks this app. Ask them to allow it, ' +
        'or sign in with a cookie.';
    }
    const where = stage ? ' (' + stage + ')' : '';
    const why = reason ? ': ' + reason : '.';
    return 'Google sign-in failed' + where + why;
  } catch (e) {
    return null;
  }
}"""
)
private external fun jsSignInNotice(): String?

actual fun signInNotice(): String? = jsSignInNotice()

@JsFun("(k) => { try { return window.localStorage.getItem(k); } catch (e) { return null; } }")
private external fun jsStorageGet(key: String): String?

@JsFun("(k, v) => { try { window.localStorage.setItem(k, v); } catch (e) {} }")
private external fun jsStoragePut(key: String, value: String)

actual object Storage {
    actual fun get(key: String): String? = jsStorageGet(key)
    actual fun put(key: String, value: String) = jsStoragePut(key, value)
}

/** The browser keeps the session cookie for us — nothing to do here. */
actual object Session {
    actual fun headers(): Map<String, String> = emptyMap()
    actual fun rememberCookies(cookies: List<String>) = Unit
    actual fun clear() = Unit
}
