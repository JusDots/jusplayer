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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jusplayer.data.Credit
import com.jusplayer.data.Library as LikedLibrary
import com.jusplayer.data.MediaItem
import com.jusplayer.data.PageResponse
import com.jusplayer.data.Section
import com.jusplayer.data.YtMusicApi
import com.jusplayer.player.PlayerState

private sealed interface PageKind {
    data class Album(val id: String) : PageKind
    data class Playlist(val id: String) : PageKind
    data class Artist(val id: String) : PageKind
}

@Composable
fun DetailScreen(
    api: YtMusicApi,
    route: Route,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val kind = when (route) {
        is Route.Album -> PageKind.Album(route.browseId)
        is Route.Playlist -> PageKind.Playlist(route.browseId)
        is Route.Artist -> PageKind.Artist(route.browseId)
        else -> return
    }

    var page by remember(kind) { mutableStateOf<PageResponse?>(null) }
    var loading by remember(kind) { mutableStateOf(true) }
    var error by remember(kind) { mutableStateOf<String?>(null) }
    var attempt by remember(kind) { mutableIntStateOf(0) }

    LaunchedEffect(kind, attempt) {
        loading = true
        error = null
        runCatching {
            when (kind) {
                is PageKind.Album -> api.album(kind.id)
                is PageKind.Playlist -> api.playlist(kind.id)
                is PageKind.Artist -> api.artist(kind.id)
            }
        }.onSuccess { page = it }
            .onFailure { error = it.message ?: "Could not load page" }
        loading = false
    }

    val data = page
    when {
        loading && data == null -> LoadingState()
        error != null && data == null -> MessageState(
            title = "Could not load this page",
            message = error!!,
            actionLabel = "Try again",
            onAction = { attempt++ },
        )
        data == null -> MessageState("Nothing here", "YouTube Music returned an empty page.")
        kind is PageKind.Artist -> ArtistPage(data, onOpen, onOpenArtist)
        else -> CollectionPage(data, onOpen, onOpenArtist)
    }
}

@Composable
private fun CollectionPage(
    page: PageResponse,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val songs = page.songs()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item {
            PageHeader(page = page, songs = songs, onOpenArtist = onOpenArtist)
        }
        item { Spacer(Modifier.height(8.dp)) }
        if (songs.isEmpty()) {
            item {
                Text(
                    "No playable tracks on this page yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JusMuted,
                    modifier = Modifier.padding(vertical = 24.dp),
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
                )
            }
        }
    }
}

@Composable
private fun ArtistPage(
    page: PageResponse,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val topSongs = page.sections.firstOrNull { it.title.contains("song", ignoreCase = true) }
    val otherSections = page.sections.filter { it !== topSongs }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item { PageHeader(page = page, songs = page.songs(), onOpenArtist = onOpenArtist) }

        if (topSongs != null && topSongs.items.isNotEmpty()) {
            item {
                SectionHeader(
                    title = topSongs.title,
                    action = "Play all",
                    onAction = { PlayerState.playQueue(topSongs.items, 0) },
                )
            }
            items(topSongs.items, key = { it.videoId ?: it.id }) { item ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = LikedLibrary.isLiked(item.id),
                    onPlay = { onOpen(item, topSongs.items) },
                    onLike = { LikedLibrary.toggle(item) },
                    onOpenArtist = onOpenArtist,
                )
            }
        }

        items(otherSections, key = { it.title.ifBlank { it.items.firstOrNull()?.id ?: "section" } }) { section ->
            SectionCards(section = section, onOpen = onOpen)
        }
    }
}

@Composable
fun SectionCards(section: Section, onOpen: (MediaItem, List<MediaItem>) -> Unit) {
    if (section.items.isEmpty()) return
    Column {
        SectionHeader(title = section.title.ifBlank { "More" })
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            items(section.items, key = { it.id }) { item ->
                CollectionCard(item = item, onOpen = { onOpen(item, section.items) })
            }
        }
    }
}

@Composable
private fun PageHeader(
    page: PageResponse,
    songs: List<MediaItem>,
    onOpenArtist: (String) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Artwork(page.thumbnail, size = 156.dp, corner = 18.dp)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = JusText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = page.meta.ifBlank { page.subtitle }
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = JusMuted)
                }
                if (page.artists.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        page.artists.take(3).forEach { credit -> CreditChip(credit, onOpenArtist) }
                    }
                }
            }
        }

        if (page.description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                page.description,
                style = MaterialTheme.typography.bodySmall,
                color = JusMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { PlayerState.playQueue(songs, 0) },
                enabled = songs.isNotEmpty(),
                shape = RoundedCornerShape(50),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
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
            TextButton(
                onClick = { PlayerState.addToQueue(songs) },
                enabled = songs.isNotEmpty(),
            ) {
                Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = JusMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Queue", color = JusMuted)
            }
        }
    }
}

@Composable
fun CreditChip(credit: Credit, onOpenArtist: (String) -> Unit) {
    if (credit.browseId.isBlank()) {
        AccentPill(credit.name)
        return
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(JusSurfaceHigh)
            .clickable { onOpenArtist(credit.browseId) }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(credit.name, style = MaterialTheme.typography.labelMedium, color = JusText, maxLines = 1)
    }
}
