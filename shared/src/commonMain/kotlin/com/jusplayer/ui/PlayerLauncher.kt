package com.jusplayer.ui

import com.jusplayer.data.MediaItem
import com.jusplayer.player.PlayerState

/** Shared "play this" behaviour for songs, albums and playlists. */
object PlayerLauncher {

    fun playAll(items: List<MediaItem>) {
        val songs = items.filter { it.videoId != null }
        if (songs.isNotEmpty()) PlayerState.playQueue(songs, 0)
    }
}
