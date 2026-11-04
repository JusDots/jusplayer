package com.jusplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jusplayer.player.PlayerState
import com.jusplayer.player.formatTime

@Composable
fun QueueScreen() {
    val queue = PlayerState.queue

    if (queue.isEmpty()) {
        MessageState(
            title = "The queue is empty",
            message = "Play a song or use Play all on any album, playlist or mood.",
        )
        return
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Queue", style = MaterialTheme.typography.headlineMedium, color = JusText)
                Text(
                    "${queue.size} tracks · ${PlayerState.index + 1} now playing",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                )
            }
            TextButton(onClick = { Modals.openSaveQueue() }) {
                Text("Save", color = JusGreen)
            }
            TextButton(onClick = { PlayerState.clearQueue() }) { Text("Clear", color = JusMuted) }
        }

        if (PlayerState.sleepAfterTrack || PlayerState.sleepRemaining != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(JusSurfaceHigh)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    PlayerState.sleepRemaining?.let { "Sleep timer · ${formatTime(it.toDouble())} left" }
                        ?: "Sleep timer · pauses after this track",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusText,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { PlayerState.cancelSleepTimer() }) { Text("Cancel", color = JusGreen) }
            }
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp),
        ) {
            itemsIndexed(queue, key = { _, item -> item.videoId ?: item.id }) { position, item ->
                val current = position == PlayerState.index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (current) JusGreen.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { PlayerState.playAt(position) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (current) "▶" else "${position + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current) JusGreen else JusMuted,
                        modifier = Modifier.width(18.dp),
                    )
                    Artwork(item.thumbnail, size = 42.dp, corner = 8.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (current) JusGreen else JusText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = JusMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(item.duration ?: "", style = MaterialTheme.typography.bodySmall, color = JusMuted)
                    IconButton(
                        onClick = { PlayerState.move(position, position - 1) },
                        enabled = position > 0,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", tint = JusMuted, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { PlayerState.move(position, position + 1) },
                        enabled = position < queue.lastIndex,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down", tint = JusMuted, modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { PlayerState.removeAt(position) },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = JusMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

}
