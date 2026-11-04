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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jusplayer.data.Library as LikedLibrary
import com.jusplayer.data.MediaItem
import com.jusplayer.data.Recent
import com.jusplayer.data.Stats
import com.jusplayer.player.PlayerState

@Composable
fun HistoryScreen(
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val songs = Recent.items.toList()
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Recently played", style = MaterialTheme.typography.headlineMedium, color = JusText)
                Text(
                    "${songs.size} track${if (songs.size == 1) "" else "s"} · this device only",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                )
            }
            if (songs.isNotEmpty()) {
                TextButton(onClick = { Recent.clear() }) { Text("Clear", color = JusMuted) }
            }
        }

        if (songs.isEmpty()) {
            MessageState(
                title = "Nothing played yet",
                message = "Tracks you play will show up here, newest first. History never leaves this browser.",
            )
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp),
        ) {
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
fun StatsScreen(onOpen: (MediaItem, List<MediaItem>) -> Unit) {
    val topTracks = Stats.topTracks(12)
    val topArtists = Stats.topArtists(8)
    val allTop = topTracks.map { it.first }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item {
            Text("Listening stats", style = MaterialTheme.typography.headlineMedium, color = JusText)
            Text(
                "Counted locally from your plays. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = JusMuted,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Plays", Stats.totalPlays().toString(), Modifier.weight(1f))
                StatTile("Tracks", Stats.uniqueTracks().toString(), Modifier.weight(1f))
                StatTile("Artists", Stats.uniqueArtists().toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Listening time", listeningTime(Stats.totalSeconds()), Modifier.weight(1f))
                StatTile("Last played", Stats.events.firstOrNull()?.title ?: "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }

        if (topTracks.isEmpty()) {
            item {
                MessageState(
                    title = "No stats yet",
                    message = "Play a few songs and your top tracks and artists will appear here.",
                )
            }
        } else {
            item { SectionHeader("Top tracks") }
            items(topTracks, key = { it.first.videoId ?: it.first.id }) { (item, count) ->
                SongRow(
                    item = item,
                    playing = PlayerState.current?.videoId == item.videoId,
                    liked = LikedLibrary.isLiked(item.id),
                    onPlay = { PlayerState.playQueue(allTop, allTop.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)) },
                    onLike = { LikedLibrary.toggle(item) },
                    trailing = { Text("$count×", style = MaterialTheme.typography.bodySmall, color = JusGreen) },
                )
            }
            if (topArtists.isNotEmpty()) {
                item { SectionHeader("Top artists") }
                items(topArtists, key = { it.first }) { (name, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = JusText, modifier = Modifier.weight(1f))
                        Text("$count plays", style = MaterialTheme.typography.bodySmall, color = JusMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(JusSurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = JusGreen, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = JusMuted, maxLines = 1)
    }
}

private fun listeningTime(seconds: Int): String {
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}
