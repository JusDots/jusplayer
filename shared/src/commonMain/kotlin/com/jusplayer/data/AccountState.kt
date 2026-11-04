package com.jusplayer.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Who is signed in to YouTube Music.
 *
 * The gateway keeps the session (a signed, http-only cookie), so the client
 * only has to ask "am I signed in?" once and refresh when the user signs in or
 * out. Everything is best effort: the app is fully usable while signed out.
 */
object AccountState {

    var account by mutableStateOf(Account())
        private set

    var loading by mutableStateOf(false)
        private set

    val signedIn: Boolean get() = account.signedIn

    /** Ask the gateway who we are. Safe to call on every app start. */
    fun refresh(api: YtMusicApi, scope: CoroutineScope? = null) {
        val target = scope ?: CoroutineScope(Dispatchers.Default)
        target.launch {
            loading = true
            val result = runCatching { api.account() }.getOrNull()
            if (result != null) account = result
            loading = false
        }
    }

    /** Synchronous refresh for callers that manage their own scope. */
    suspend fun refreshNow(api: YtMusicApi) {
        loading = true
        val result = runCatching { api.account() }.getOrNull()
        if (result != null) account = result
        loading = false
    }

    /** Signs in with a pasted YouTube cookie. Returns null on success. */
    suspend fun signInWithCookie(api: YtMusicApi, cookie: String): String? {
        loading = true
        return try {
            account = api.signInWithCookie(cookie)
            null
        } catch (error: Throwable) {
            api.apiErrorMessage(error) ?: error.message ?: "Could not sign in"
        } finally {
            loading = false
        }
    }

    /** Signs out and clears the gateway session. */
    suspend fun signOut(api: YtMusicApi) {
        loading = true
        runCatching { api.logout() }
        account = Account(oauthAvailable = account.oauthAvailable)
        loading = false
    }
}
