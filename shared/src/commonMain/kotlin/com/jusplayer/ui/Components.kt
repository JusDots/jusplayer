package com.jusplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jusplayer.data.AccountState
import com.jusplayer.data.Api
import com.jusplayer.data.LocalPlaylists
import com.jusplayer.data.MediaItem
import com.jusplayer.data.YtMusicApi
import com.jusplayer.platform.shareLink
import com.jusplayer.player.PlayerState
import kotlinx.coroutines.launch

@Composable
fun Artwork(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1D3B2B), Color(0xFF12231A)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = JusMuted,
                modifier = Modifier.size(size * 0.35f),
            )
        }
    }
}

@Composable
fun SongRow(
    item: MediaItem,
    playing: Boolean,
    liked: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
    onOpenArtist: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Artwork(item.thumbnail, size = 48.dp, corner = 9.dp)
            if (playing) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = "Now playing",
                        tint = JusGreen,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (playing) JusGreen else JusText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = JusMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else if (!item.duration.isNullOrBlank()) {
            Text(item.duration, style = MaterialTheme.typography.bodySmall, color = JusMuted)
        }

        LikeButton(liked = liked, onClick = onLike)
        SongMenu(item = item, onOpenArtist = onOpenArtist)
    }
}

/** Overflow menu: queue actions + local playlists + artist navigation. */
@Composable
fun SongMenu(item: MediaItem, onOpenArtist: ((String) -> Unit)? = null) {
    var menuOpen by remember { mutableStateOf(false) }
    val artist = item.artists.firstOrNull()
    val scope = rememberCoroutineScope()

    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = JusMuted)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = {
                    PlayerState.playNext(listOf(item))
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = {
                    PlayerState.addToQueue(listOf(item))
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                onClick = {
                    menuOpen = false
                    Modals.openPlaylistPicker(item)
                },
            )
            if (item.videoId != null) {
                DropdownMenuItem(
                    text = { Text("Start radio") },
                    onClick = {
                        menuOpen = false
                        scope.launch {
                            val extra = runCatching { Api.yt.related(item.videoId).songs() }
                                .getOrNull()
                                .orEmpty()
                                .filter { it.videoId != null && it.videoId != item.videoId }
                                .distinctBy { it.videoId }
                                .take(40)
                            PlayerState.playQueue(listOf(item) + extra, 0)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("Share song") },
                    onClick = {
                        menuOpen = false
                        shareLink(
                            "https://music.youtube.com/watch?v=${item.videoId}",
                            item.title,
                        )
                    },
                )
            }
            if (artist != null && onOpenArtist != null && artist.browseId.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Go to ${artist.name}") },
                    onClick = {
                        menuOpen = false
                        onOpenArtist(artist.browseId)
                    },
                )
            }
        }
    }
}

/** Root-level modal state. Compose/Wasm does not draw `Popup` layers reliably
 *  from the root composition, so every sheet in the app is rendered by the
 *  overlay host at the top of [com.jusplayer.App] instead of by the screen that
 *  opens it. Keeping the state global also means a sheet can be opened from a
 *  lazy-list row without being nested in that row's layout. */
object Modals {
    var addToPlaylist by mutableStateOf<MediaItem?>(null)
        private set
    var saveQueue by mutableStateOf(false)
        private set
    var newPlaylist by mutableStateOf(false)
        private set
    var signInCookie by mutableStateOf(false)
        private set

    fun openPlaylistPicker(item: MediaItem) {
        addToPlaylist = item
    }

    fun openSaveQueue() {
        saveQueue = true
    }

    fun openNewPlaylist() {
        newPlaylist = true
    }

    fun openSignInCookie() {
        signInCookie = true
    }

    fun closeAll() {
        addToPlaylist = null
        saveQueue = false
        newPlaylist = false
        signInCookie = false
    }

    val hasOpen: Boolean
        get() = addToPlaylist != null || saveQueue || newPlaylist || signInCookie
}

@Composable
fun AddToPlaylistDialog(item: MediaItem, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    JusModal(onDismiss = onDismiss, title = "Add to playlist") {
        Column(
            modifier = Modifier
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (LocalPlaylists.playlists.isEmpty()) {
                Text("No local playlists yet.", color = JusMuted)
            }
            LocalPlaylists.playlists.forEach { playlist ->
                Text(
                    "${playlist.name}  ·  ${playlist.items.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JusText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            LocalPlaylists.addTrack(playlist.id, item)
                            onDismiss()
                        }
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (creating) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Playlist name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { creating = false }) { Text("Cancel", color = JusMuted) }
                TextButton(
                    onClick = {
                        val created = LocalPlaylists.create(newName)
                        LocalPlaylists.addTrack(created.id, item)
                        onDismiss()
                    },
                ) { Text("Create and add", color = JusGreen) }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New playlist", color = JusGreen)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close", color = JusMuted) }
            }
        }
    }
}

/** In-canvas modal. Material3 `AlertDialog` uses a platform Dialog which does
 *  not surface in the Wasm canvas, and a root-level `Popup` layer is not drawn
 *  either, so this is a plain full-window overlay that the app renders above
 *  its own content. Clicking the scrim dismisses; clicks inside the card are
 *  swallowed so they do not reach the screen underneath. */
@Composable
fun JusModal(
    onDismiss: () -> Unit,
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val noIndication = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.66f))
            .clickable(
                interactionSource = noIndication,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(JusSurfaceHigh)
                .clickable(
                    interactionSource = noIndication,
                    indication = null,
                    onClick = {},
                )
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = JusText)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
fun CollectionCard(
    item: MediaItem,
    onOpen: () -> Unit,
    width: Dp = 168.dp,
) {
    Column(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
            .padding(6.dp),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Artwork(item.thumbnail, size = width, corner = 14.dp)
            Surface(
                shape = CircleShape,
                color = JusGreen,
                modifier = Modifier.padding(10.dp),
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color(0xFF052014),
                    modifier = Modifier
                        .padding(4.dp)
                        .size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = JusText,
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
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp, bottom = 10.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = JusText,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action, color = JusGreen, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = JusGreen, strokeWidth = 2.dp)
    }
}

@Composable
fun MessageState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(JusSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = JusGreen)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = JusText)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = JusMuted,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, tint = JusGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionLabel, color = JusGreen)
            }
        }
    }
}

/** "Save queue as playlist" sheet. Rendered by the root modal host so it draws
 *  above the sidebar and the player bar. */
@Composable
fun SaveQueueDialog(onDismiss: () -> Unit) {
    val queue = PlayerState.queue
    var name by remember {
        mutableStateOf("Queue · " + queue.firstOrNull()?.title.orEmpty().take(24))
    }

    JusModal(onDismiss = onDismiss, title = "Save queue as playlist") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Playlist name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel", color = JusMuted) }
            TextButton(
                onClick = {
                    LocalPlaylists.create(name, queue)
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    "Save ${queue.size} track${if (queue.size == 1) "" else "s"}",
                    color = if (name.isNotBlank()) JusGreen else JusMuted,
                )
            }
        }
    }
}

/** "New playlist" sheet, shared by the library and the add-to-playlist flow. */
@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    JusModal(onDismiss = onDismiss, title = "New playlist") {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel", color = JusMuted) }
            TextButton(
                onClick = {
                    val created = LocalPlaylists.create(name)
                    onCreated(created.id)
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Create", color = if (name.isNotBlank()) JusGreen else JusMuted)
            }
        }
    }
}

/**
 * Sign-in sheet for the cookie fallback.
 *
 * Google OAuth is the nicer path, but it needs an OAuth client on the server.
 * When that is not configured the user can paste their YouTube cookies
 * instead; the gateway validates them before accepting them.
 */
@Composable
fun SignInCookieDialog(
    api: YtMusicApi,
    onDismiss: () -> Unit,
    onSignedIn: (String) -> Unit,
) {
    var cookie by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    JusModal(onDismiss = onDismiss, title = "Sign in with cookies") {
        Text(
            "Open music.youtube.com in a private window, copy the value of the " +
                "“SAPISID” cookie (Chrome: DevTools → Application → Cookies), " +
                "and paste the whole cookie string here.",
            style = MaterialTheme.typography.bodySmall,
            color = JusMuted,
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = cookie,
            onValueChange = { cookie = it; error = null },
            label = { Text("Cookie string") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, style = MaterialTheme.typography.bodySmall, color = JusError)
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel", color = JusMuted) }
            TextButton(
                onClick = {
                    if (cookie.isBlank()) {
                        error = "Paste your cookie first."
                        return@TextButton
                    }
                    busy = true
                    error = null
                    scope.launch {
                        val failure = AccountState.signInWithCookie(api, cookie.trim())
                        busy = false
                        if (failure == null) {
                            onDismiss()
                            onSignedIn(AccountState.account.name)
                        } else {
                            error = failure
                        }
                    }
                },
                enabled = !busy && cookie.isNotBlank(),
            ) {
                Text(
                    if (busy) "Signing in…" else "Sign in",
                    color = if (!busy && cookie.isNotBlank()) JusGreen else JusMuted,
                )
            }
        }
    }
}
