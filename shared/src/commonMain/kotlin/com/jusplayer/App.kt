package com.jusplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.jusplayer.data.Library
import com.jusplayer.data.LocalPlaylists
import com.jusplayer.data.MediaItem
import com.jusplayer.data.PlayerPrefs
import com.jusplayer.data.Recent
import com.jusplayer.data.SearchHistory
import com.jusplayer.data.Stats
import com.jusplayer.data.YtMusicApi
import com.jusplayer.platform.artworkAccent
import com.jusplayer.platform.platformName
import com.jusplayer.platform.signInNotice
import com.jusplayer.player.PlayerState
import com.jusplayer.ui.AccentPill
import com.jusplayer.data.AccountState
import com.jusplayer.ui.AddToPlaylistDialog
import com.jusplayer.ui.DetailScreen
import com.jusplayer.ui.ExploreScreen
import com.jusplayer.ui.FullPlayer
import com.jusplayer.ui.GreetingHeader
import com.jusplayer.ui.HistoryScreen
import com.jusplayer.ui.HomeScreen
import com.jusplayer.ui.JusBackdrop
import com.jusplayer.ui.JusGreen
import com.jusplayer.ui.JusInk
import com.jusplayer.ui.JusMuted
import com.jusplayer.ui.JusOutline
import com.jusplayer.ui.JusSurface
import com.jusplayer.ui.JusSurfaceHigh
import com.jusplayer.ui.JusText
import com.jusplayer.ui.JusTheme
import com.jusplayer.ui.LibraryScreen
import com.jusplayer.ui.LocalPlaylistScreen
import com.jusplayer.ui.MiniPlayer
import com.jusplayer.ui.Modals
import com.jusplayer.ui.Notices
import com.jusplayer.ui.MoodScreen
import com.jusplayer.ui.QueueScreen
import com.jusplayer.ui.Route
import com.jusplayer.ui.NewPlaylistDialog
import com.jusplayer.ui.SaveQueueDialog
import com.jusplayer.ui.SearchScreen
import com.jusplayer.ui.SettingsScreen
import com.jusplayer.ui.SignInCookieDialog
import com.jusplayer.ui.StatsScreen
import com.jusplayer.ui.applyAccent
import com.jusplayer.ui.applyArtworkAccent
import com.jusplayer.ui.currentAccent
import com.jusplayer.ui.restoreAccent
import com.jusplayer.ui.rootTab
import com.jusplayer.ui.title
import kotlinx.coroutines.delay

@Composable
fun App() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }

    JusTheme {
        Library.ensureLoaded()
        LocalPlaylists.ensureLoaded()
        Recent.ensureLoaded()
        Stats.ensureLoaded()
        com.jusplayer.audio.AudioProfile.ensureLoaded()
        com.jusplayer.data.Downloads.ensureLoaded()
        com.jusplayer.platform.LocalFiles.ensureLoaded()
        SearchHistory.ensureLoaded()
        PlayerPrefs.ensureLoaded()
        PlayerState.restorePrefs()
        restoreAccent()

        var stack by remember { mutableStateOf(listOf<Route>(Route.Home)) }
        var query by remember { mutableStateOf("") }
        var notice by remember { mutableStateOf(signInNotice()) }

        val api = remember { YtMusicApi() }
        val snackbar = remember { SnackbarHostState() }
        val focusRequester = remember { FocusRequester() }
        val current = stack.last()

        fun push(route: Route) {
            stack = stack + route
        }

        fun pop() {
            if (stack.size > 1) stack = stack.dropLast(1)
        }

        fun openRoot(route: Route) {
            stack = listOf(route)
        }

        LaunchedEffect(Unit) {
            while (true) {
                PlayerState.tick()
                if (PlayerPrefs.dynamicColor) {
                    applyArtworkAccent(artworkAccent())
                } else {
                    applyArtworkAccent(null)
                }
                delay(500)
            }
        }

        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }

        LaunchedEffect(Unit) {
            // Ask the gateway whether a YouTube Music session is active.
            AccountState.refreshNow(api)
        }

        LaunchedEffect(notice) {
            val message = notice ?: return@LaunchedEffect
            snackbar.showSnackbar(message)
            notice = null
        }

        LaunchedEffect(Notices.message) {
            val message = Notices.message ?: return@LaunchedEffect
            snackbar.showSnackbar(message)
            Notices.message = null
        }

        LaunchedEffect(current) {
            // Keeps the account chip fresh after a Google sign-in redirect.
            if (current == Route.Settings) AccountState.refresh(api)
        }

        val onOpenArtist: (String) -> Unit = { browseId ->
            if (browseId.isNotBlank()) push(Route.Artist(browseId))
        }

        val onOpen: (MediaItem, List<MediaItem>) -> Unit = { item, context ->
            when {
                item.isSong -> {
                    val songs = context.filter { it.videoId != null }
                    val start = songs.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
                    PlayerState.playQueue(songs, start)
                }

                item.kind == "album" && item.browseId != null -> push(Route.Album(item.browseId))
                item.kind == "playlist" && item.browseId != null -> push(Route.Playlist(item.browseId))
                item.isArtist -> push(Route.Artist(item.browseId!!))
                else -> notice = "This ${item.kind} cannot be opened yet."
            }
        }

        Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(JusInk)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Spacebar -> {
                            PlayerState.toggle()
                            true
                        }
                        Key.N -> {
                            PlayerState.next()
                            true
                        }
                        Key.P -> {
                            PlayerState.previous()
                            true
                        }
                        Key.DirectionRight -> {
                            PlayerState.nudge(10.0)
                            true
                        }
                        Key.DirectionLeft -> {
                            PlayerState.nudge(-10.0)
                            true
                        }
                        Key.L -> {
                            PlayerState.current?.let { Library.toggle(it) }
                            true
                        }
                        Key.F -> {
                            if (PlayerState.expanded) PlayerState.collapse() else PlayerState.expand()
                            true
                        }
                        Key.Q -> {
                            if (current == Route.Queue) pop() else push(Route.Queue)
                            true
                        }
                        Key.Escape -> {
                            when {
                                Modals.hasOpen -> Modals.closeAll()
                                PlayerState.expanded -> PlayerState.collapse()
                                stack.size > 1 -> pop()
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 1000.dp
                val activeRoot = stack.firstOrNull()?.rootTab ?: Route.Home

                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        SideNav(selected = activeRoot, onSelect = { openRoot(it) }, onSettings = { push(Route.Settings) })
                    }
                    Column(Modifier.fillMaxSize()) {
                        TopBar(
                            route = current,
                            canBack = stack.size > 1,
                            busy = PlayerState.buffering,
                            onBack = { pop() },
                            onSettings = { if (current == Route.Settings) pop() else push(Route.Settings) },
                        )
                        Box(Modifier.weight(1f)) {
                            when (current) {
                                Route.Home -> HomeScreen(api, onOpen) {
                                    Column {
                                        GreetingHeader()
                                        BrowseShortcuts(
                                            onOpen = { openRoot(it) },
                                            onMood = { push(Route.Explore) },
                                        )
                                    }
                                }
                                Route.Explore -> ExploreScreen(
                                    api = api,
                                    onOpen = onOpen,
                                    onOpenMood = { push(Route.Mood(it.params, it.title)) },
                                )
                                Route.Search -> SearchScreen(
                                    api = api,
                                    query = query,
                                    onQueryChange = { query = it },
                                    onOpen = onOpen,
                                    onOpenArtist = onOpenArtist,
                                )
                                Route.Library -> LibraryScreen(
                                    api = api,
                                    onOpen = onOpen,
                                    onOpenArtist = onOpenArtist,
                                    onOpenPlaylist = { push(Route.LocalPlaylistRoute(it)) },
                                    onOpenHistory = { push(Route.History) },
                                )
                                Route.History -> HistoryScreen(onOpen, onOpenArtist)
                                Route.Stats -> StatsScreen(onOpen)
                                Route.Queue -> QueueScreen()
                                Route.Settings -> SettingsScreen(api)
                                is Route.Album, is Route.Playlist, is Route.Artist ->
                                    DetailScreen(api, current, onOpen, onOpenArtist)
                                is Route.Mood ->
                                    MoodScreen(api, current, onOpen)
                                is Route.LocalPlaylistRoute ->
                                    LocalPlaylistScreen(current.id, onOpen, onOpenArtist)
                            }
                        }
                        if (PlayerState.hasTrack) {
                            MiniPlayer(onExpand = { PlayerState.expand() })
                        }
                        if (!wide) {
                            BottomNav(
                                selected = activeRoot,
                                onSelect = { openRoot(it) },
                            )
                        }
                    }
                }
            }

            if (PlayerState.expanded) {
                FullPlayer(
                    onClose = { PlayerState.collapse() },
                    onOpenQueue = {
                        PlayerState.collapse()
                        push(Route.Queue)
                    },
                    onOpenArtist = { browseId ->
                        PlayerState.collapse()
                        onOpenArtist(browseId)
                    },
                    api = api,
                )
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp, start = 24.dp, end = 24.dp),
            ) { data ->
                Text(
                    data.visuals.message,
                    color = JusText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(JusSurfaceHigh)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }

        Modals.addToPlaylist?.let { item ->
            AddToPlaylistDialog(item = item, onDismiss = { Modals.closeAll() })
        }
        if (Modals.saveQueue) {
            SaveQueueDialog(onDismiss = { Modals.closeAll() })
        }
        if (Modals.signInCookie) {
            SignInCookieDialog(
                api = api,
                onDismiss = { Modals.closeAll() },
                onSignedIn = { name -> notice = "Signed in as $name" },
            )
        }
        if (Modals.newPlaylist) {
            NewPlaylistDialog(
                onDismiss = { Modals.closeAll() },
                onCreated = { id ->
                    Modals.closeAll()
                    push(Route.LocalPlaylistRoute(id))
                },
            )
        }
        }
    }
}

@Composable
private fun BrowseShortcuts(onOpen: (Route) -> Unit, onMood: () -> Unit) {
    data class Shortcut(val label: String, val icon: ImageVector, val route: Route)
    val shortcuts = listOf(
        Shortcut("Moods", Icons.Rounded.Explore, Route.Explore),
        Shortcut("History", Icons.Rounded.History, Route.History),
        Shortcut("Stats", Icons.Rounded.BarChart, Route.Stats),
        Shortcut("Queue", Icons.Rounded.LibraryMusic, Route.Queue),
        Shortcut("Settings", Icons.Rounded.Settings, Route.Settings),
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(shortcuts, key = { it.label }) { shortcut ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(JusSurfaceHigh)
                    .clickable {
                        if (shortcut.label == "Moods") onMood() else onOpen(shortcut.route)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(shortcut.icon, contentDescription = null, tint = JusGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(shortcut.label, style = MaterialTheme.typography.labelMedium, color = JusText)
            }
        }
    }
}

@Composable
private fun SideNav(selected: Route, onSelect: (Route) -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .width(238.dp)
            .fillMaxHeight()
            .background(JusSurface.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(JusGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = JusInk)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("JusPlayer", style = MaterialTheme.typography.titleLarge, color = JusText)
                Text("web edition", style = MaterialTheme.typography.labelSmall, color = JusMuted)
            }
        }

        Spacer(Modifier.height(34.dp))

        listOf(
            Route.Home to Icons.Rounded.Home,
            Route.Explore to Icons.Rounded.Explore,
            Route.Search to Icons.Rounded.Search,
            Route.Library to Icons.Rounded.LibraryMusic,
            Route.History to Icons.Rounded.History,
            Route.Stats to Icons.Rounded.BarChart,
        ).forEach { (route, icon) ->
            NavItem(
                label = route.title,
                icon = icon,
                selected = selected == route,
                onClick = { onSelect(route) },
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.weight(1f))

        NavItem(
            label = "Settings",
            icon = Icons.Rounded.Settings,
            selected = false,
            onClick = onSettings,
        )
        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(JusSurfaceHigh)
                .padding(14.dp),
        ) {
            Text("${currentAccent.name} mode", style = MaterialTheme.typography.titleSmall, color = JusText)
            Spacer(Modifier.height(4.dp))
            Text(
                "Private by default. Search and playback happen through your own gateway.",
                style = MaterialTheme.typography.bodySmall,
                color = JusMuted,
            )
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) JusGreen.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) JusGreen else JusMuted,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) JusGreen else JusMuted,
        )
    }
}

@Composable
private fun TopBar(
    route: Route,
    canBack: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 14.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canBack) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = JusText)
            }
            Spacer(Modifier.width(2.dp))
        } else {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(JusGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = JusInk, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            route.title,
            style = MaterialTheme.typography.titleLarge,
            color = JusText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (busy) {
            AccentPill("loading…")
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = { applyAccent((com.jusplayer.ui.accentIndex + 1) % com.jusplayer.ui.JusAccents.size) }) {
            Text("◐", color = JusGreen, style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onSettings, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = JusMuted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(2.dp))
        AccentPill(platformName())
    }
}

@Composable
private fun BottomNav(selected: Route, onSelect: (Route) -> Unit) {
    val tabs = listOf(
        Route.Home to Icons.Rounded.Home,
        Route.Explore to Icons.Rounded.Explore,
        Route.Search to Icons.Rounded.Search,
        Route.Library to Icons.Rounded.LibraryMusic,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(JusSurface)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { (route, icon) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(route) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = route.title,
                    tint = if (selected == route) JusGreen else JusMuted,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    route.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected == route) JusGreen else JusMuted,
                )
            }
        }
    }
}

@Composable
fun JusFrame(content: @Composable () -> Unit) {
    JusBackdrop { content() }
}
