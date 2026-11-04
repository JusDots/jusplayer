package com.jusplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jusplayer.data.Library as LikedLibrary
import com.jusplayer.data.LyricLine
import com.jusplayer.data.MediaItem
import com.jusplayer.data.PlayerPrefs
import com.jusplayer.data.YtMusicApi
import com.jusplayer.data.currentLyricIndex
import com.jusplayer.data.parseLrc
import com.jusplayer.platform.platformName
import com.jusplayer.player.PlayerState
import com.jusplayer.player.RepeatMode
import com.jusplayer.player.formatTime

@Composable
fun MiniPlayer(onExpand: () -> Unit) {
    val item = PlayerState.current ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(JusSurface.copy(alpha = 0.97f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(JusOutline),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(PlayerState.progress)
                    .height(2.dp)
                    .background(JusGreen),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(item.thumbnail, size = 46.dp, corner = 10.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = JusText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.subtitle.ifBlank { "YouTube Music" },
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { PlayerState.previous() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = JusText)
            }
            Surface(shape = CircleShape, color = JusGreen, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { PlayerState.toggle() }) {
                    if (PlayerState.buffering) {
                        CircularProgressIndicator(color = JusInk, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            if (PlayerState.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (PlayerState.playing) "Pause" else "Play",
                            tint = JusInk,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            IconButton(onClick = { PlayerState.next() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = JusText)
            }
            LikeButton(
                liked = LikedLibrary.isLiked(item.id),
                onClick = { LikedLibrary.toggle(item) },
            )
        }
    }
}

@Composable
fun FullPlayer(
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenArtist: (String) -> Unit,
    api: YtMusicApi,
) {
    val item = PlayerState.current ?: return
    val liked = LikedLibrary.isLiked(item.id)

    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val progress = if (dragging) dragValue else PlayerState.progress

    var showLyrics by remember { mutableStateOf(false) }
    var lyricsText by remember(item.videoId) { mutableStateOf<String?>(null) }
    var syncedText by remember(item.videoId) { mutableStateOf<String?>(null) }
    var lyricsSource by remember(item.videoId) { mutableStateOf<String?>(null) }
    var lyricsLoading by remember(item.videoId) { mutableStateOf(false) }
    var lyricsError by remember(item.videoId) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.videoId, showLyrics) {
        if (!showLyrics || item.videoId == null || lyricsText != null || syncedText != null) return@LaunchedEffect
        lyricsLoading = true
        lyricsError = null
        runCatching {
            api.lyrics(
                videoId = item.videoId,
                title = item.title,
                artist = item.primaryArtist,
                duration = item.durationSeconds,
            )
        }.onSuccess {
            lyricsText = it.lyrics
            syncedText = it.syncedLyrics
            lyricsSource = it.source
        }.onFailure { lyricsError = it.message ?: "Could not load lyrics" }
        lyricsLoading = false
    }

    val lyrics = remember(syncedText) { parseLrc(syncedText) }

    val background = when (PlayerPrefs.playerBackground) {
        "midnight" -> Brush.verticalGradient(listOf(Color(0xFF0B1712), JusInk))
        "artwork" -> Brush.verticalGradient(
            listOf(currentAccent.glow, JusInk, JusInk),
        )
        else -> Brush.verticalGradient(
            listOf(JusGreen.copy(alpha = 0.30f), Color(0xFF0D1F16), JusInk),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Minimise", tint = JusText, modifier = Modifier.size(28.dp))
                }
                Text(
                    "NOW PLAYING",
                    style = MaterialTheme.typography.labelSmall,
                    color = JusMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                AccentPill(if (PlayerState.buffering) "loading" else platformName())
            }

            Spacer(Modifier.height(20.dp))

            Artwork(
                url = item.thumbnail,
                size = if (PlayerPrefs.playerArtStyle == "classic") 212.dp else 300.dp,
                corner = 22.dp,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                item.title,
                style = MaterialTheme.typography.headlineSmall,
                color = JusText,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val credit = item.artists.firstOrNull()
            Text(
                item.subtitle.ifBlank { "YouTube Music" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (credit?.browseId?.isNotBlank() == true) JusGreen else JusMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (credit?.browseId?.isNotBlank() == true) {
                    Modifier.clickable { onOpenArtist(credit.browseId) }
                } else {
                    Modifier
                },
            )

            Spacer(Modifier.height(16.dp))

            Slider(
                value = progress,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    PlayerState.seekToFraction(dragValue)
                    dragging = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = JusGreen,
                    activeTrackColor = JusGreen,
                    inactiveTrackColor = JusOutline,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatTime(PlayerState.position), style = MaterialTheme.typography.bodySmall, color = JusMuted)
                Spacer(Modifier.weight(1f))
                Text(formatTime(PlayerState.duration), style = MaterialTheme.typography.bodySmall, color = JusMuted)
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = { PlayerState.toggleShuffle() }) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (PlayerState.shuffle) JusGreen else JusMuted,
                    )
                }
                IconButton(onClick = { PlayerState.previous() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = JusText, modifier = Modifier.size(34.dp))
                }
                Surface(shape = CircleShape, color = JusGreen, modifier = Modifier.size(70.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.clickable { PlayerState.toggle() }) {
                        Icon(
                            if (PlayerState.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (PlayerState.playing) "Pause" else "Play",
                            tint = JusInk,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
                IconButton(onClick = { PlayerState.next() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = JusText, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { PlayerState.cycleRepeat() }) {
                    Icon(
                        if (PlayerState.repeatMode == RepeatMode.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (PlayerState.repeatMode == RepeatMode.Off) JusMuted else JusGreen,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = JusMuted, modifier = Modifier.size(20.dp))
                Slider(
                    value = PlayerState.volume.toFloat(),
                    onValueChange = { PlayerState.setVolume(it) },
                    colors = SliderDefaults.colors(
                        thumbColor = JusText,
                        activeTrackColor = JusMuted,
                        inactiveTrackColor = JusOutline,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                LikeButton(liked = liked, onClick = { LikedLibrary.toggle(item) })
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Rounded.Lyrics, contentDescription = null, tint = if (showLyrics) JusGreen else JusMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Lyrics", color = if (showLyrics) JusGreen else JusMuted)
                }
                TextButton(onClick = onOpenQueue) {
                    Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = JusMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Queue", color = JusMuted)
                }
                SleepMenu()
                SpeedMenu()
            }

            val sleep = PlayerState.sleepRemaining
            if (sleep != null || PlayerState.sleepAfterTrack) {
                Text(
                    sleep?.let { "Sleep timer · ${formatTime(it.toDouble())} left" }
                        ?: "Sleep timer · stops after this track",
                    style = MaterialTheme.typography.labelMedium,
                    color = JusGreen,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (showLyrics) {
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(JusSurface.copy(alpha = 0.75f))
                        .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LYRICS", style = MaterialTheme.typography.labelSmall, color = JusMuted)
                        Spacer(Modifier.weight(1f))
                        if (lyrics.isNotEmpty()) {
                            AccentPill("synced")
                        } else if (!lyricsText.isNullOrBlank()) {
                            AccentPill("plain")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        lyricsLoading -> CircularProgressIndicator(color = JusGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        lyricsError != null -> Text(lyricsError!!, style = MaterialTheme.typography.bodySmall, color = JusMuted)
                        lyrics.isNotEmpty() -> SyncedLyrics(lyrics)
                        lyricsText.isNullOrBlank() -> Text(
                            "No lyrics available for this track yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JusMuted,
                        )
                        else -> Text(
                            lyricsText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = JusText,
                            textAlign = TextAlign.Start,
                        )
                    }
                    if (!lyricsText.isNullOrBlank() || lyrics.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            when {
                                lyrics.isNotEmpty() -> "Time-synced lyrics · ${lyricsSource ?: "LRCLIB"} · auto-scrolls with playback"
                                else -> "Plain-text lyrics · YouTube Music only publishes unsynced text"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = JusMuted,
                        )
                    }
                }
            }

            val upNext = PlayerState.queue.drop(PlayerState.index + 1).take(8)
            if (upNext.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = JusOutline)
                SectionHeader("Up next", action = "Open queue", onAction = onOpenQueue)
                upNext.forEachIndexed { offset, next ->
                    QueueRow(
                        item = next,
                        onClick = { PlayerState.playAt(PlayerState.index + 1 + offset) },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Audio streamed through YouTube · JusPlayer does not host any media",
                style = MaterialTheme.typography.labelSmall,
                color = JusMuted.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>) {
    val state = rememberLazyListState()
    val active = currentLyricIndex(lines, PlayerState.position)
    LaunchedEffect(active) {
        if (active >= 0 && active < lines.size) {
            runCatching { state.animateScrollToItem(active) }
        }
    }
    LazyColumn(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val isActive = index == active
            Text(
                line.text.ifBlank { "♪" },
                style = if (isActive) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = when {
                    isActive -> JusGreen
                    index == active + 1 -> JusText
                    else -> JusMuted
                },
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun SleepMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Timer, contentDescription = null, tint = if (PlayerState.sleepRemaining != null || PlayerState.sleepAfterTrack) JusGreen else JusMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Sleep", color = if (PlayerState.sleepRemaining != null || PlayerState.sleepAfterTrack) JusGreen else JusMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(5, 15, 30, 45, 60).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes minutes") },
                    onClick = {
                        PlayerState.startSleepTimer(minutes)
                        open = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("End of this track") },
                onClick = {
                    PlayerState.sleepAtEndOfTrack()
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text("Off") },
                onClick = {
                    PlayerState.cancelSleepTimer()
                    open = false
                },
            )
        }
    }
}

@Composable
private fun SpeedMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Speed, contentDescription = null, tint = if (PlayerState.rate != 1.0) JusGreen else JusMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${PlayerState.rate}×", color = if (PlayerState.rate != 1.0) JusGreen else JusMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                DropdownMenuItem(
                    text = { Text(if (value == 1.0) "1× (normal)" else "$value×") },
                    onClick = {
                        PlayerState.changeRate(value)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(item: MediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Artwork(item.thumbnail, size = 40.dp, corner = 8.dp)
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = JusText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = JusMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(item.duration ?: "", style = MaterialTheme.typography.bodySmall, color = JusMuted)
        Spacer(Modifier.width(2.dp))
    }
}
