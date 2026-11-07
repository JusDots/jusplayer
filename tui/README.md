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
| `h` `l` `u` `y` | home · library · queue · lyrics |
| `+` / `-` / `m` | volume up / down / mute |
| `e` | restart the track |
| `w` | download the track |
| `r` | reload the current view |
| `?` | help |
| `q` / `ctrl-c` | quit |

## Configuration

`~/.config/jusplayer/config.json` (`%APPDATA%\jusplayer\config.json` on
Windows), or `$JUSPLAYER_CONFIG`:

```json
{
  "base": "http://127.0.0.1:8787",
  "player": "mpv",
  "musicDir": "/home/you/Music/JusPlayer",
  "discordClientId": "000000000000000000"
}
```

Environment overrides: `JUSPLAYER_API`, `JUSPLAYER_DISCORD_CLIENT_ID`,
`JUSPLAYER_CONFIG`.

- The gateway defaults to <http://127.0.0.1:8787>; run
  `cd server && npm start`, or pass `--api https://jusplayer.vercel.app`. The
  local gateway plays whole tracks, the hosted one is limited to the range
  YouTube serves without a PO token.
- Sign-in reuses the gateway's signed session cookie: the client keeps
  `jus_session` in memory after a cookie sign-in.
- Downloads land in `musicDir` (default `~/Music/JusPlayer`).

## Backends

`mpv` runs headless with an IPC socket in `/tmp`, which gives exact position,
pause and seek. If `mpv` is missing, `ffplay` is used. If neither exists the
client runs in browse-only mode and says so on the help screen.

## Discord rich presence

Set `discordClientId` (or `JUSPLAYER_DISCORD_CLIENT_ID`) to your Discord
application id. The help screen (`?`) shows whether Discord was found at
`$XDG_RUNTIME_DIR/discord-ipc-0` (Windows: the named pipe).
