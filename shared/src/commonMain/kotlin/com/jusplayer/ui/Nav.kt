package com.jusplayer.ui

/** Lightweight in-app navigation stack (no browser URLs, no back-end routes). */
sealed interface Route {
    data object Home : Route
    data object Explore : Route
    data object Search : Route
    data object Library : Route
    data object History : Route
    data object Stats : Route
    data object Queue : Route
    data object Settings : Route

    data class Album(val browseId: String) : Route
    data class Playlist(val browseId: String) : Route
    data class Artist(val browseId: String) : Route
    data class Mood(val params: String, val title: String) : Route
    data class LocalPlaylistRoute(val id: String) : Route
}

val Route.title: String
    get() = when (this) {
        Route.Home -> "Listen"
        Route.Explore -> "Explore"
        Route.Search -> "Search"
        Route.Library -> "Your library"
        Route.History -> "Recently played"
        Route.Stats -> "Listening stats"
        Route.Queue -> "Queue"
        Route.Settings -> "Settings"
        is Route.Album -> "Album"
        is Route.Playlist -> "Playlist"
        is Route.Artist -> "Artist"
        is Route.Mood -> (this as Route.Mood).title
        is Route.LocalPlaylistRoute -> "Playlist"
    }

val Route.rootTab: Route?
    get() = when (this) {
        Route.Home, Route.Explore, Route.Search, Route.Library -> this
        else -> null
    }
