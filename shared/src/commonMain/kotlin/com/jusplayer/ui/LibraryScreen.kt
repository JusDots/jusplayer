package com.jusplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jusplayer.data.AccountState
import com.jusplayer.data.Library as LikedLibrary
import com.jusplayer.data.LocalPlaylists
import com.jusplayer.data.MediaItem
import com.jusplayer.data.Recent
import com.jusplayer.data.Section
import com.jusplayer.data.YtMusicApi
import com.jusplayer.player.PlayerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf

private enum class LibraryTab(val label: String) {
    Liked("Liked"),
    Playlists("Playlists"),
    Recent("Recent"),
    YouTube("YouTube"),
}

@Composable
fun LibraryScreen(
    api: YtMusicApi,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    var tab by remember { mutableStateOf(LibraryTab.Liked) }
    val liked = LikedLibrary.liked.toList()
    val signedIn = AccountState.signedIn
    val tabs = if (signedIn) LibraryTab.entries else LibraryTab.entries.filter { it != LibraryTab.YouTube }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Your library", style = MaterialTheme.typography.headlineMedium, color = JusText)
                Text(
                    "${liked.size} liked · ${LocalPlaylists.playlists.size} playlists · ${Recent.items.size} recent",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                )
            }
            TextButton(onClick = onOpenHistory) { Text("History", color = JusGreen) }
        }

        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = JusGreen,
                        selectedLabelColor = JusInk,
                        containerColor = JusSurfaceHigh,
                        labelColor = JusMuted,
                    ),
                    border = null,
                    shape = RoundedCornerShape(50),
                )
            }
        }

        when (tab) {
            LibraryTab.Liked -> LikedTab(liked, onOpen, onOpenArtist)
            LibraryTab.Playlists -> PlaylistsTab(onOpenPlaylist)
            LibraryTab.Recent -> RecentTab(onOpen, onOpenArtist)
            LibraryTab.YouTube -> YouTubeTab(api, onOpen, onOpenArtist)
        }
    }
}

@Composable
private fun LikedTab(
    songs: List<MediaItem>,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    if (songs.isEmpty()) {
        MessageState(
            title = "Your library is quiet",
            message = "Tap the heart on any track and it will live here, stored only on this device.",
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { PlayerState.playQueue(songs, 0) },
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { PlayerState.playQueue(songs.shuffled(), 0) }) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Shuffle", color = JusGreen)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { LikedLibrary.clear() }) { Text("Clear", color = JusMuted) }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp),
        ) {
            items(songs, key = { it.id }) { item ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = true,
                    onPlay = { onOpen(item, songs) },
                    onLike = { LikedLibrary.toggle(item) },
                    onOpenArtist = onOpenArtist,
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTab(onOpenPlaylist: (String) -> Unit) {
    val playlists = LocalPlaylists.playlists

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { Modals.openNewPlaylist() }) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New playlist", color = JusGreen)
            }
        }

        if (playlists.isEmpty()) {
            MessageState(
                title = "No playlists yet",
                message = "Create one here, or use the ⋮ menu on any song and choose “Add to playlist”.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpenPlaylist(playlist.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Artwork(playlist.items.firstOrNull()?.thumbnail, size = 52.dp, corner = 10.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                playlist.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = JusText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${playlist.items.size} track${if (playlist.items.size == 1) "" else "s"} · local",
                                style = MaterialTheme.typography.bodySmall,
                                color = JusMuted,
                            )
                        }
                        if (playlist.items.isNotEmpty()) {
                            IconButton(onClick = { PlayerState.playQueue(playlist.items, 0) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = JusGreen)
                            }
                        }
                        IconButton(onClick = { LocalPlaylists.remove(playlist.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = JusMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun RecentTab(onOpen: (MediaItem, List<MediaItem>) -> Unit, onOpenArtist: (String) -> Unit) {
    val recent = Recent.items.take(30)
    if (recent.isEmpty()) {
        MessageState(
            title = "No recent plays",
            message = "Songs you play will be remembered here, on this device only.",
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp, top = 4.dp),
    ) {
        items(recent, key = { it.videoId ?: it.id }) { item ->
            SongRow(
                item = item,
                playing = PlayerState.current?.videoId == item.videoId,
                liked = LikedLibrary.isLiked(item.id),
                onPlay = { onOpen(item, recent) },
                onLike = { LikedLibrary.toggle(item) },
                onOpenArtist = onOpenArtist,
            )
        }
    }
}

@Composable
fun LocalPlaylistScreen(
    playlistId: String,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val playlist = LocalPlaylists.byId(playlistId)
    if (playlist == null) {
        MessageState("Playlist not found", "It may have been deleted on this device.")
        return
    }
    val songs = playlist.items

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Artwork(songs.firstOrNull()?.thumbnail, size = 156.dp, corner = 18.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.headlineMedium, color = JusText)
                    Text(
                        "${songs.size} track${if (songs.size == 1) "" else "s"} · local playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = JusMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = { PlayerState.playQueue(songs, 0) },
                            enabled = songs.isNotEmpty(),
                            shape = RoundedCornerShape(50),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play")
                        }
                        TextButton(
                            onClick = { PlayerState.playQueue(songs.shuffled(), 0) },
                            enabled = songs.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Shuffle", color = JusGreen)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        if (songs.isEmpty()) {
            item {
                Text(
                    "This playlist is empty. Use the ⋮ menu on any song to add it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JusMuted,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
        } else {
            items(songs, key = { it.videoId ?: it.id }) { item ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = LikedLibrary.isLiked(item.id),
                    onPlay = { onOpen(item, songs) },
                    onLike = { LikedLibrary.toggle(item) },
                    onOpenArtist = onOpenArtist,
                    trailing = {
                        IconButton(
                            onClick = {
                                val index = songs.indexOfFirst { it.videoId == item.videoId }
                                LocalPlaylists.moveTrack(playlist.id, index, index - 1)
                            },
                            enabled = songs.firstOrNull()?.videoId != item.videoId,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", tint = JusMuted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = {
                                val index = songs.indexOfFirst { it.videoId == item.videoId }
                                LocalPlaylists.moveTrack(playlist.id, index, index + 1)
                            },
                            enabled = songs.lastOrNull()?.videoId != item.videoId,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down", tint = JusMuted, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { LocalPlaylists.removeTrack(playlist.id, item.videoId.orEmpty()) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = JusMuted, modifier = Modifier.size(18.dp))
                        }
                    },
                )
            }
        }
    }
}


/**
 * The signed-in YouTube Music account: liked songs, playlists, subscriptions
 * and watch history straight from YouTube.
 */
@Composable
private fun YouTubeTab(
    api: YtMusicApi,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    var liked by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var library by remember { mutableStateOf<List<Section>>(emptyList()) }
    var history by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        val likedResult = runCatching { api.liked() }
        val libraryResult = runCatching { api.library() }
        val historyResult = runCatching { api.history() }
        liked = likedResult.getOrNull()?.songs().orEmpty()
        library = libraryResult.getOrNull()?.sections.orEmpty()
        history = historyResult.getOrNull()?.allItems().orEmpty().filter { it.videoId != null }
        error = listOf(likedResult, libraryResult, historyResult)
            .firstOrNull { it.isFailure }
            ?.exceptionOrNull()
            ?.message
        loading = false
    }

    if (loading) {
        MessageState(title = "Loading your library…", message = "Talking to YouTube Music.")
        return
    }

    if (liked.isEmpty() && library.isEmpty() && history.isEmpty()) {
        MessageState(
            title = "Nothing to show yet",
            message = error ?: "Your YouTube Music library is empty.",
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp)) {
        if (liked.isNotEmpty()) {
            item {
                Text(
                    "Liked songs",
                    style = MaterialTheme.typography.titleMedium,
                    color = JusText,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(liked, key = { "liked-${it.id}" }) { item ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = true,
                    onPlay = { onOpen(item, liked) },
                    onLike = {},
                    onOpenArtist = onOpenArtist,
                )
            }
        }

        library.forEach { section ->
            item {
                Text(
                    section.title.ifBlank { "Library" },
                    style = MaterialTheme.typography.titleMedium,
                    color = JusText,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(section.items, key = { "lib-${section.title}-${it.id}" }) { item ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = false,
                    onPlay = { onOpen(item, section.items) },
                    onLike = {},
                    onOpenArtist = onOpenArtist,
                )
            }
        }

        if (history.isNotEmpty()) {
            item {
                Text(
                    "History",
                    style = MaterialTheme.typography.titleMedium,
                    color = JusText,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                )
            }
            items(history, key = { "hist-${it.id}" }) { item ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = false,
                    onPlay = { onOpen(item, history) },
                    onLike = {},
                    onOpenArtist = onOpenArtist,
                )
            }
        }
    }
}
