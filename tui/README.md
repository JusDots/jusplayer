# JusPlayer terminal client

A dependency-free Node client that talks to the same gateway as the web and
desktop apps. It plays through `mpv` (IPC-controlled) and falls back to
`ffplay`, keeps a synced-lyrics view, downloads tracks and can publish Discord
rich presence.

```bash
cd tui
node src/main.mjs                 # or: npm start
node src/main.mjs --api http://127.0.0.1:8787
node src/main.mjs --no-audio      # browse only
```

Requires Node 20+, `mpv` is strongly recommended (position, pause, seek);
`ffplay` from ffmpeg works as a fallback but can only start and stop.

## Look

Rounded panels, a true-colour goblin-green palette (with an automatic
256-colour fallback for older terminals), a live equaliser, a playhead progress
bar and a quiet status line. The layout scales from a 60×20 terminal up to a
full screen, and the help screen uses three columns so every shortcut fits
without scrolling.

## Keys

| Key | Action |
| --- | --- |
| `space` | play / pause |
| `n` / `p` | next / previous track |
| `←` / `→` | seek 10 s back / forward |
| `0`–`9` | play the numbered row |
| `j` / `k`, `g` / `G` | move down / up, jump to top / bottom |
| `enter` | open the selected row |
| `/` | search |
| `h` `l` `u` `y` `a` | home · library · queue · lyrics · account |
| `+` / `-` / `m` | volume up / down / mute |
| `e` | restart the track |
| `w` | download the track |
| `r` | reload the current view |
| `g` | (account view) sign in with Google — opens your browser |
| `s` | (account view) sign in with a YouTube cookie |
| `o` | (account view) sign out |
| `x` | (queue view) remove the selected track |
| `c` | (queue view) clear the queue |
| `?` | help |
| `q` / `ctrl-c` | quit |

## Signing in

Press `a` for the account view. Two ways to sign in:

**Google (`g`)** — the client starts a tiny web server on `127.0.0.1`, asks
the gateway to run the OAuth flow and names that loopback address. The
browser does the Google part, the gateway hands the finished session to the
loopback address as a short-lived ticket, and the client exchanges the
ticket for a normal session — no cookie copying, no second window to close.

**A YouTube cookie (`s`)** — for terminals on a machine where opening a
browser is not practical:

1. Open <https://music.youtube.com> in a browser and sign in.
2. Copy the whole `Cookie` request header (or at least `VISITOR_INFO1_LIVE`,
   `SIDCC` and `__Secure-3PAPISID`) from the signed-in tab.
3. Paste it in the client — the session is stored in the config file.

Either way the session is remembered in the config file, so the next start is
signed in already, and the account view opens **Liked songs**, **Library** and
**History** with `enter`.

## Configuration

`~/.config/jusplayer/config.json` (`%APPDATA%\jusplayer\config.json` on
Windows), or `$JUSPLAYER_CONFIG`:

```json
{
  "base": "http://127.0.0.1:8787",
  "player": "mpv",
  "musicDir": "/home/you/Music/JusPlayer",
  "discordClientId": "000000000000000000",
  "session": "jus_session=…"
}
```

Environment overrides: `JUSPLAYER_API`, `JUSPLAYER_DISCORD_CLIENT_ID`,
`JUSPLAYER_CONFIG`.

- The gateway is chosen automatically: a gateway running on
  <http://127.0.0.1:8787> is preferred, otherwise the hosted one at
  <https://jusplayer.vercel.app> is used, so the client works with no setup.
  Pass `--api <url>` to pick another one.
- The hosted gateway resolves whole tracks (it ships `yt-dlp`), so playback
  and downloads are not cut short.
- Sign-in reuses the gateway's signed session cookie: the client keeps
  `jus_session` in memory after a cookie sign-in and writes it to the config
  file so the next start is signed in already.
- Downloads land in `musicDir` (default `~/Music/JusPlayer`).

## Backends

`mpv` runs headless with an IPC socket in `/tmp`, which gives exact position,
pause and seek. If `mpv` is missing, `ffplay` is used. If neither exists the
client runs in browse-only mode and says so on the help screen.

## Discord rich presence

Set `discordClientId` (or `JUSPLAYER_DISCORD_CLIENT_ID`) to your Discord
application id. The help screen (`?`) shows whether Discord was found at
`$XDG_RUNTIME_DIR/discord-ipc-0` (Windows: the named pipe).
