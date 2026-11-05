package com.jusplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jusplayer.data.AccountState
import com.jusplayer.data.LocalPlaylists
import com.jusplayer.data.PlayerPrefs
import com.jusplayer.data.Recent
import com.jusplayer.data.SearchHistory
import com.jusplayer.data.Stats
import com.jusplayer.data.YtMusicApi
import com.jusplayer.player.PlayerState
import com.jusplayer.platform.openExternal
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(api: YtMusicApi) {
    val scope = rememberCoroutineScope()
    val account = AccountState.account

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = JusText)
            Text(
                "Preferences stay in this browser. No telemetry, no tracking.",
                style = MaterialTheme.typography.bodySmall,
                color = JusMuted,
            )
        }

        item { SectionHeader("Account") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(JusSurfaceHigh)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (account.signedIn) {
                    Text(account.name, style = MaterialTheme.typography.titleSmall, color = JusText)
                    if (account.email.isNotBlank()) {
                        Text(account.email, style = MaterialTheme.typography.bodySmall, color = JusMuted)
                    }
                    Text(
                        "Signed in to YouTube Music · personalised feeds, liked songs and your library are available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JusMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { AccountState.signOut(api) } }) {
                            Text("Sign out", color = JusGreen)
                        }
                    }
                } else {
                    Text("Not signed in", style = MaterialTheme.typography.titleSmall, color = JusText)
                    Text(
                        "Sign in to get your account, liked songs, library and personalised recommendations. " +
                            "Anonymous browsing keeps working without an account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = JusMuted,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (account.oauthAvailable) {
                            TextButton(
                                onClick = {
                                    openExternal(api.loginUrl())
                                    AccountState.awaitGoogleSignIn(api, scope)
                                },
                            ) {
                                Text("Sign in with Google", color = JusGreen)
                            }
                        }
                        TextButton(onClick = { Modals.openSignInCookie() }) {
                            Text("Use a cookie", color = if (account.oauthAvailable) JusMuted else JusGreen)
                        }
                        TextButton(onClick = { AccountState.refresh(api, scope) }) {
                            Text("Refresh", color = JusMuted)
                        }
                    }
                    if (AccountState.awaitingGoogle) {
                        Text(
                            "Waiting for Google… finish signing in in the other tab; this page updates by itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = JusGreen,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Accent") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JusAccents.forEachIndexed { index, accent ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(accent.primary)
                                .then(
                                    if (index == accentIndex) Modifier.border(2.dp, JusText, CircleShape)
                                    else Modifier,
                                )
                                .clickable { applyAccent(index) },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            accent.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (index == accentIndex) JusText else JusMuted,
                        )
                    }
                }
            }
        }

        item { SectionHeader("Appearance") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Dynamic colour from artwork",
                    style = MaterialTheme.typography.titleSmall,
                    color = JusText,
                )
                Text(
                    "Tint the interface with the dominant colour of the current cover, like the Android app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "On", false to "Off").forEach { (value, label) ->
                        FilterChip(
                            selected = PlayerPrefs.dynamicColor == value,
                            onClick = { PlayerPrefs.updateDynamicColor(value) },
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
                Spacer(Modifier.height(4.dp))
                Text("Player background", style = MaterialTheme.typography.titleSmall, color = JusText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("aurora" to "Aurora", "artwork" to "Artwork", "midnight" to "Midnight").forEach { (value, label) ->
                        FilterChip(
                            selected = PlayerPrefs.playerBackground == value,
                            onClick = { PlayerPrefs.updatePlayerBackground(value) },
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
                Spacer(Modifier.height(4.dp))
                Text("Player artwork", style = MaterialTheme.typography.titleSmall, color = JusText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("immersive" to "Immersive", "classic" to "Classic").forEach { (value, label) ->
                        FilterChip(
                            selected = PlayerPrefs.playerArtStyle == value,
                            onClick = { PlayerPrefs.updatePlayerArtStyle(value) },
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
            }
        }

        item { SectionHeader("Playback speed") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.75, 1.0, 1.25, 1.5, 2.0).forEach { value ->
                    FilterChip(
                        selected = PlayerState.rate == value,
                        onClick = { PlayerState.changeRate(value) },
                        label = { Text("${value}x", style = MaterialTheme.typography.labelMedium) },
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
        }

        item { SectionHeader("Keyboard shortcuts") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShortcutRow("Space", "Play / pause")
                ShortcutRow("N / P", "Next / previous track")
                ShortcutRow("→ / ←", "Seek 10 seconds")
                ShortcutRow("L", "Like the current song")
                ShortcutRow("F", "Open / close the full player")
                ShortcutRow("Q", "Open the queue")
                ShortcutRow("Esc", "Close the player")
            }
        }

        item { SectionHeader("Local data") }
        item {
            Column {
                DataRow(
                    title = "Liked songs",
                    value = com.jusplayer.data.Library.liked.size.toString(),
                    action = "Clear",
                    onAction = { com.jusplayer.data.Library.clear() },
                )
                DataRow(
                    title = "Local playlists",
                    value = LocalPlaylists.playlists.size.toString(),
                    action = "Clear",
                    onAction = { LocalPlaylists.playlists.toList().forEach { LocalPlaylists.remove(it.id) } },
                )
                DataRow(
                    title = "Recently played",
                    value = Recent.items.size.toString(),
                    action = "Clear",
                    onAction = { Recent.clear() },
                )
                DataRow(
                    title = "Search history",
                    value = SearchHistory.queries.size.toString(),
                    action = "Clear",
                    onAction = { SearchHistory.clear() },
                )
                DataRow(
                    title = "Play statistics",
                    value = "${Stats.totalPlays()} plays",
                    action = "Clear",
                    onAction = { Stats.clear() },
                )
            }
        }

        item { SectionHeader("Not possible on the web") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(JusSurfaceHigh)
                    .padding(14.dp),
            ) {
                Text(
                    "These mobile features need native Android APIs that browsers do not expose:",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    "Offline downloads, local-file playback, cache management",
                    "System equalizer, spatial audio, EBU R128 loudness, crossfade",
                    "Pitch shifting and tempo change independent of speed",
                    "ShazamKit music recognition and Spotify playlist import",
                    "Last.fm / ListenBrainz scrobbling and Discord rich presence",
                    "Subsonic servers and music-together sessions",
                    "Synced lyrics translation, romanization and word-by-word timing",
                ).forEach { line ->
                    Text("•  $line", style = MaterialTheme.typography.bodySmall, color = JusText)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item { SectionHeader("About") }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(JusSurfaceHigh)
                    .padding(14.dp),
            ) {
                Text("JusPlayer web edition", style = MaterialTheme.typography.titleSmall, color = JusText)
                Spacer(Modifier.height(4.dp))
                Text(
                    "A Compose Multiplatform client for YouTube Music, inspired by Greeny-Goblins / JusPlayer. " +
                        "Audio is played through YouTube's official IFrame player; nothing is re-streamed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                )
                Spacer(Modifier.height(8.dp))
                Text("GNU GPL-3.0 · no affiliation with Google or YouTube", style = MaterialTheme.typography.labelSmall, color = JusMuted)
            }
        }
    }
}

@Composable
private fun ShortcutRow(keys: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(JusSurfaceHigh)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(keys, style = MaterialTheme.typography.labelMedium, color = JusGreen)
        }
        Spacer(Modifier.width(10.dp))
        Text(action, style = MaterialTheme.typography.bodySmall, color = JusText)
    }
}

@Composable
private fun DataRow(title: String, value: String, action: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = JusText, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = JusMuted)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAction) { Text(action, color = JusGreen) }
    }
}
