package com.jusplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jusplayer.data.Feed
import com.jusplayer.data.MediaItem
import com.jusplayer.data.YtMusicApi

@Composable
fun HomeScreen(
    api: YtMusicApi,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    header: @Composable () -> Unit = {},
) {
    var feed by remember { mutableStateOf<Feed?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        loading = true
        error = null
        runCatching { api.home() }
            .onSuccess { feed = it }
            .onFailure { error = it.message ?: "Could not reach the JusPlayer gateway" }
        loading = false
    }

    when {
        loading && feed == null -> LoadingState()
        error != null && feed == null -> MessageState(
            title = "Gateway offline",
            message = "$error\n\nStart it with: node server/index.mjs",
            actionLabel = "Try again",
            onAction = { attempt++ },
        )
        feed == null || feed!!.isEmpty -> MessageState(
            title = "Nothing to play yet",
            message = "The gateway answered, but YouTube Music returned no sections. Try again in a moment.",
            actionLabel = "Reload",
            onAction = { attempt++ },
        )
        else -> {
            val data = feed!!
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            ) {
                item { header() }

                itemsIndexed(data.sections) { sectionIndex, section ->
                    if (section.items.isEmpty()) return@itemsIndexed
                    val songs = section.items.filter { it.videoId != null }
                    Column {
                        SectionHeader(
                            title = section.title.ifBlank { if (sectionIndex == 0) "Quick picks" else "More to explore" },
                            action = if (songs.isNotEmpty()) "Play all" else null,
                            onAction = if (songs.isNotEmpty()) ({ PlayerLauncher.playAll(songs) }) else null,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            contentPadding = PaddingValues(end = 8.dp),
                        ) {
                            items(section.items, key = { it.id }) { item ->
                                CollectionCard(
                                    item = item,
                                    onOpen = { onOpen(item, section.items) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GreetingHeader(name: String = "goblin") {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        Text(
            text = "Good listening,",
            style = MaterialTheme.typography.bodyMedium,
            color = JusMuted,
        )
        Row {
            Text(
                text = name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.displaySmall,
                color = JusText,
            )
            Spacer(Modifier.height(0.dp))
        }
    }
}
