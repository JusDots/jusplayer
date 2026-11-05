package com.jusplayer.data

import com.jusplayer.platform.Session
import com.jusplayer.platform.apiBase
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Talks to the local JusPlayer gateway (server/index.mjs), never to YouTube directly
 * from the browser — that keeps InnerTube + CORS details in one place.
 */
class YtMusicApi(private val base: String = apiBase()) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
    }

    suspend fun home(): Feed = get<Feed>("/api/home").withAbsoluteImages()

    suspend fun search(query: String, filter: String = "all"): Feed {
        val params = buildMap {
            put("q", query)
            if (filter != "all") put("filter", filter)
        }
        return get<Feed>("/api/search", params).withAbsoluteImages()
    }

    suspend fun collection(browseId: String): CollectionResponse =
        get<CollectionResponse>("/api/collection", mapOf("id" to browseId)).withAbsoluteImages()

    suspend fun album(browseId: String): PageResponse =
        get<PageResponse>("/api/album", mapOf("id" to browseId)).withAbsoluteImages()

    suspend fun playlist(browseId: String): PageResponse =
        get<PageResponse>("/api/playlist", mapOf("id" to browseId)).withAbsoluteImages()

    suspend fun artist(browseId: String): PageResponse =
        get<PageResponse>("/api/artist", mapOf("id" to browseId)).withAbsoluteImages()

    suspend fun suggestions(query: String): List<String> =
        get<SuggestResponse>("/api/suggest", mapOf("q" to query)).suggestions

    suspend fun related(videoId: String): Feed =
        get<Feed>("/api/related", mapOf("videoId" to videoId)).withAbsoluteImages()

    suspend fun moods(): MoodsResponse = get("/api/moods")

    suspend fun mood(params: String, title: String): MoodResponse =
        get<MoodResponse>("/api/mood", mapOf("params" to params, "title" to title)).withAbsoluteImages()

    suspend fun charts(): HubResponse = get<HubResponse>("/api/charts").withAbsoluteImages()

    suspend fun newReleases(): HubResponse = get<HubResponse>("/api/new").withAbsoluteImages()

    suspend fun lyrics(videoId: String, title: String = "", artist: String = "", duration: Int = 0, album: String = ""): LyricsResponse =
        get(
            "/api/lyrics",
            buildMap {
                put("videoId", videoId)
                if (title.isNotBlank()) put("title", title)
                if (artist.isNotBlank()) put("artist", artist)
                if (duration > 0) put("duration", duration.toString())
                if (album.isNotBlank()) put("album", album)
            },
        )

    // ------------------------------------------------------------- account ---

    /** Who is signed in (if anybody). */
    suspend fun account(): Account = get("/api/account")

    /** The signed-in user's "Liked songs". */
    suspend fun liked(): PageResponse = get<PageResponse>("/api/liked").withAbsoluteImages()

    /** The signed-in user's library: playlists, artists, subscriptions. */
    suspend fun library(): Feed = get<Feed>("/api/library").withAbsoluteImages()

    /** The signed-in user's YouTube Music history. */
    suspend fun history(): Feed = get<Feed>("/api/history").withAbsoluteImages()

    /** URL that starts the Google sign-in flow in the gateway. */
    fun loginUrl(): String = "${base.trimEnd('/')}/api/auth/login"

    /** Ends the session (the gateway clears its signed cookie). */
    suspend fun logout(): Account {
        val url = "${base.trimEnd('/')}/api/auth/logout?json=1"
        val session = Session.headers()
        val account: Account = client.get(url) { session.forEach { (key, value) -> header(key, value) } }.body()
        Session.clear()
        return account
    }

    /** Signs in with a pasted YouTube cookie (the fallback for OAuth). */
    suspend fun signInWithCookie(cookie: String): Account {
        return try {
            val response = client.post("${base.trimEnd('/')}/api/auth/cookie") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CookieRequest.serializer(), CookieRequest(cookie)))
            }
            // Outside a browser the app has to keep the session cookie itself.
            Session.rememberCookies(response.headers.getAll("Set-Cookie").orEmpty())
            response.body()
        } catch (error: Throwable) {
            throw IllegalStateException(apiErrorMessage(error) ?: "Could not sign in", error)
        }
    }

    /** Ends the signed-in session. */
    suspend fun signOut() {
        Session.clear()
    }

    /** Pull the gateway's `{"error": "..."}` out of a failed request. */
    fun apiErrorMessage(error: Throwable): String? {
        val text = error.message ?: return null
        val marker = "Text: "
        val index = text.indexOf(marker)
        if (index < 0) return null
        val body = text.substring(index + marker.length).trim()
        return runCatching { json.decodeFromString(ErrorResponse.serializer(), body).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T {
        val qs = params.entries.joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        val url = buildString {
            append(base.trimEnd('/'))
            append(path)
            if (qs.isNotEmpty()) append('?').append(qs)
        }
        val session = Session.headers()
        return client.get(url) { session.forEach { (key, value) -> header(key, value) } }.body()
    }

    private fun absolute(thumb: String?): String? {
        if (thumb.isNullOrBlank()) return null
        return if (thumb.startsWith("/")) base.trimEnd('/') + thumb else thumb
    }

    private fun Feed.withAbsoluteImages(): Feed =
        copy(sections = sections.map { it.copy(items = it.items.map { item -> item.withAbsoluteImage() }) })

    private fun CollectionResponse.withAbsoluteImages(): CollectionResponse =
        copy(sections = sections.map { it.copy(items = it.items.map { item -> item.withAbsoluteImage() }) })

    private fun PageResponse.withAbsoluteImages(): PageResponse =
        copy(
            thumbnail = absolute(thumbnail),
            sections = sections.map { it.copy(items = it.items.map { item -> item.withAbsoluteImage() }) },
        )

    private fun HubResponse.withAbsoluteImages(): HubResponse =
        copy(sections = sections.map { it.copy(items = it.items.map { item -> item.withAbsoluteImage() }) })

    private fun MoodResponse.withAbsoluteImages(): MoodResponse =
        copy(sections = sections.map { it.copy(items = it.items.map { item -> item.withAbsoluteImage() }) })

    private fun MediaItem.withAbsoluteImage(): MediaItem =
        if (thumbnail.isNullOrBlank()) this else copy(thumbnail = absolute(thumbnail))
}

@Serializable
private data class CookieRequest(val cookie: String)

@Serializable
private data class ErrorResponse(val error: String = "")
