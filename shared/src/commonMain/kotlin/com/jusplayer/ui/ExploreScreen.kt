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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.jusplayer.data.MediaItem
import com.jusplayer.data.MoodEntry
import com.jusplayer.data.MoodsResponse
import com.jusplayer.data.Section
import com.jusplayer.data.YtMusicApi

private enum class ExploreTab(val label: String) {
    Moods("Moods & genres"),
    Charts("Charts"),
    New("New releases"),
}

@Composable
fun ExploreScreen(
    api: YtMusicApi,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenMood: (MoodEntry) -> Unit,
) {
    var tab by remember { mutableStateOf(ExploreTab.Moods) }
    var moods by remember { mutableStateOf<MoodsResponse?>(null) }
    var hub by remember { mutableStateOf<com.jusplayer.data.HubResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab, attempt) {
        loading = true
        error = null
        runCatching {
            when (tab) {
                ExploreTab.Moods -> moods = api.moods()
                ExploreTab.Charts -> hub = api.charts()
                ExploreTab.New -> hub = api.newReleases()
            }
        }.onFailure { error = it.message ?: "Could not load explore" }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExploreTab.entries.forEach { entry ->
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

        when {
            loading -> LoadingState()
            error != null -> MessageState(
                title = "Could not load explore",
                message = error!!,
                actionLabel = "Retry",
                onAction = { attempt++ },
            )
            tab == ExploreTab.Moods -> MoodsList(moods, onOpenMood)
            else -> HubList(hub?.sections.orEmpty(), onOpen)
        }
    }
}

@Composable
private fun MoodsList(moods: MoodsResponse?, onOpenMood: (MoodEntry) -> Unit) {
    val groups = moods?.groups.orEmpty()
    if (groups.isEmpty()) {
        MessageState("No moods found", "YouTube Music returned no mood categories.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 28.dp),
    ) {
        items(groups, key = { it.title }) { group ->
            SectionHeader(group.title)
            group.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { entry ->
                        MoodTile(entry, Modifier.weight(1f), onOpenMood)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MoodTile(entry: MoodEntry, modifier: Modifier = Modifier, onOpenMood: (MoodEntry) -> Unit) {
    val tint = entry.color?.let { Color(it.toInt()) } ?: JusSurfaceHigh
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint)
            .clickable { onOpenMood(entry) }
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Text(
            entry.title,
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFF07140D),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HubList(sections: List<Section>, onOpen: (MediaItem, List<MediaItem>) -> Unit) {
    if (sections.isEmpty()) {
        MessageState("Nothing here yet", "YouTube Music returned an empty page. Try again in a moment.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 28.dp),
    ) {
        items(sections, key = { it.title }) { section ->
            SectionCards(section, onOpen)
        }
    }
}

@Composable
fun MoodScreen(
    api: YtMusicApi,
    route: Route.Mood,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
) {
    var sections by remember(route) { mutableStateOf<List<Section>>(emptyList()) }
    var loading by remember(route) { mutableStateOf(true) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var attempt by remember(route) { mutableIntStateOf(0) }

    LaunchedEffect(route, attempt) {
        loading = true
        error = null
        runCatching { api.mood(route.params, route.title) }
            .onSuccess { sections = it.sections }
            .onFailure { error = it.message ?: "Could not load mood" }
        loading = false
    }

    when {
        loading -> LoadingState()
        error != null -> MessageState("Could not load ${route.title}", error!!, "Retry", { attempt++ })
        else -> LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 4.dp, bottom = 28.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(route.title, style = MaterialTheme.typography.headlineSmall, color = JusText)
                }
            }
            items(sections, key = { it.title }) { section ->
                SectionCards(section, onOpen)
            }
        }
    }
}
