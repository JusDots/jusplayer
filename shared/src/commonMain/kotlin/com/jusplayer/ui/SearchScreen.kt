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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jusplayer.data.Feed
import com.jusplayer.data.Library as LikedLibrary
import com.jusplayer.data.MediaItem
import com.jusplayer.data.SearchHistory
import com.jusplayer.data.YtMusicApi
import com.jusplayer.player.PlayerState
import kotlinx.coroutines.delay

private val FILTERS = listOf(
    "all" to "Everything",
    "songs" to "Songs",
    "albums" to "Albums",
    "playlists" to "Playlists",
    "artists" to "Artists",
    "videos" to "Videos",
)

@Composable
fun SearchScreen(
    api: YtMusicApi,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (MediaItem, List<MediaItem>) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    var filter by remember { mutableStateOf("all") }
    var feed by remember { mutableStateOf<Feed?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        runCatching { api.suggestions(q) }
            .onSuccess { suggestions = it.take(7) }
            .onFailure { suggestions = emptyList() }
    }

    LaunchedEffect(query, filter) {
        val q = query.trim()
        if (q.isEmpty()) {
            feed = null
            error = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        delay(350) // debounce
        runCatching { api.search(q, filter) }
            .onSuccess { feed = it }
            .onFailure { error = it.message ?: "Search failed" }
        loading = false
    }

    fun commit(text: String) {
        onQueryChange(text)
        SearchHistory.add(text)
        showSuggestions = false
    }

    Column(Modifier.fillMaxWidth()) {
        SearchField(
            query = query,
            onQueryChange = {
                onQueryChange(it)
                showSuggestions = true
            },
            onSubmit = {
                if (query.isNotBlank()) commit(query)
            },
            modifier = Modifier.padding(start = 20.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
        )

        if (showSuggestions && suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.take(4).forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { commit(suggestion) },
                        label = {
                            Text(
                                suggestion,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = JusSurface,
                            labelColor = JusMuted,
                        ),
                        border = null,
                        shape = RoundedCornerShape(50),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTERS.forEach { (value, label) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
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
            query.isBlank() -> SearchLanding(
                onPick = { commit(it) },
                onClearHistory = { SearchHistory.clear() },
            )
            loading -> LoadingState()
            error != null -> MessageState(
                title = "Search failed",
                message = error!!,
                actionLabel = "Retry",
                onAction = { filter = filter },
            )
            else -> {
                val items = feed?.allItems().orEmpty()
                if (items.isEmpty()) {
                    MessageState(
                        title = "No matches",
                        message = "Nothing for \"$query\". Try a different spelling or filter.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 14.dp, end = 20.dp, bottom = 24.dp),
                    ) {
                        item {
                            Text(
                                "${items.size} result${if (items.size == 1) "" else "s"} for \"$query\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = JusMuted,
                                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp),
                            )
                        }
                        items(items, key = { it.id }) { item ->
                            if (item.isSong) {
                                SongRow(
                                    item = item,
                                    playing = PlayerState.current?.videoId == item.videoId,
                                    liked = LikedLibrary.isLiked(item.id),
                                    onPlay = {
                                        SearchHistory.add(query)
                                        onOpen(item, items)
                                    },
                                    onLike = { LikedLibrary.toggle(item) },
                                    onOpenArtist = onOpenArtist,
                                )
                            } else {
                                CollectionRow(
                                    item = item,
                                    onOpen = {
                                        SearchHistory.add(query)
                                        onOpen(item, items)
                                    },
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
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text("Songs, artists, albums…", color = JusMuted) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = JusMuted) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = JusMuted)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = JusSurfaceHigh,
            unfocusedContainerColor = JusSurface,
            focusedBorderColor = JusGreen,
            unfocusedBorderColor = JusOutline,
            cursorColor = JusGreen,
            focusedTextColor = JusText,
            unfocusedTextColor = JusText,
        ),
    )
}

@Composable
private fun SearchLanding(onPick: (String) -> Unit, onClearHistory: () -> Unit) {
    val suggestions = listOf(
        "lofi hip hop", "Arijit Singh", "weeknd", "jazz cafe", "workout mix", "anirudh",
    )
    Column(Modifier.padding(24.dp)) {
        if (SearchHistory.queries.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Recent searches", style = MaterialTheme.typography.titleLarge, color = JusText, modifier = Modifier.weight(1f))
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = JusMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClearHistory)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            SearchHistory.queries.take(6).chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { term ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(JusSurface)
                                .clickable { onPick(term) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(term, style = MaterialTheme.typography.bodyMedium, color = JusText)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(14.dp))
        }

        Text("Start with something", style = MaterialTheme.typography.titleLarge, color = JusText)
        Spacer(Modifier.height(6.dp))
        Text(
            "JusPlayer searches YouTube Music and never stores your queries on a server.",
            style = MaterialTheme.typography.bodyMedium,
            color = JusMuted,
        )
        Spacer(Modifier.height(18.dp))
        suggestions.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { term ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(JusSurfaceHigh)
                            .clickable { onPick(term) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(term, style = MaterialTheme.typography.bodyMedium, color = JusText)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Tap any album or playlist to open it. Songs start playing immediately.",
                style = MaterialTheme.typography.bodySmall,
                color = JusMuted,
            )
        }
    }
}

@Composable
private fun CollectionRow(item: MediaItem, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Artwork(item.thumbnail, size = 48.dp, corner = if (item.kind == "artist") 24.dp else 9.dp)
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = JusText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.subtitle.ifBlank { item.kind.replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.bodySmall,
                color = JusMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AccentPill(item.kind)
    }
}
