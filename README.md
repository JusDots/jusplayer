# JusPlayer

A privacy-first YouTube Music client, built with **Kotlin + Compose Multiplatform** and sharing one UI across three targets:

| Folder     | Target                          | Status                    |
|------------|---------------------------------|---------------------------|
| `shared/`  | Common UI, data layer, player   | ✅ working                |
| `web/`     | Kotlin/Wasm + Compose for Web   | ✅ working                |
| `windows/` | Compose Desktop → `.msi` / `.exe` | 🚧 shell + packaging ready |
| `linux/`   | Compose Desktop → `.deb` / `.rpm` | 🚧 shell + packaging ready |
| `server/`  | Zero-dependency Node gateway    | ✅ working                |
| `api/`     | The same gateway as a Vercel function | ✅ working           |
| `tui/`     | Terminal client (reserved)      | 🚧 planned                |

This is a Compose Multiplatform re-imagining of
[Greeny-Goblins / JusPlayer](https://github.com/shubh72010/Greeny-Goblins)
(the Android app). The Android project is GPL-3.0; this web port is a separate
client that reuses the same design language and product ideas, not its Android
code (Media3, Hilt and Room do not run in a browser or on Compose/Wasm).

---

## How the web version works

```
┌──────────────────────────────┐        ┌───────────────────────────┐
│  Compose Multiplatform (Wasm)│  HTTP  │  server/index.mjs         │
│  web/  +  shared/            │ ─────► │  • InnerTube client       │
│  Material 3 UI, queue, likes │        │  • normalises JSON        │
└──────────────┬───────────────┘        │  • proxies artwork (CORS) │
               │ postMessage/JS         └─────────────┬─────────────┘
               ▼                                      │
      YouTube IFrame Player API  ◄────────────────────┘
      (official embed, no stream extraction)
```

* **Search / home / collections** come from YouTube Music's InnerTube API via
  the local gateway, normalised to a tiny `{sections:[{title, items:[]}]}` shape.
* **Audio** is played by YouTube's official IFrame player. Nothing is
  downloaded, re-hosted or extracted — the player just receives a `videoId`.
* **Liked songs, local playlists, recents, stats and preferences** live in
  `localStorage`; nothing is tracked.
* **Signing in is optional.** With a Google OAuth client configured on the
  gateway, the app can sign you in to YouTube Music and show your account,
  liked songs, library and personalised feeds. Without it, everything still
  works anonymously (see [Accounts](#accounts)).
* **Lyrics** combine YouTube Music's plain-text panel with time-coded lyrics
  from [LRCLIB](https://lrclib.net) so the player can highlight lines in sync.
* If YouTube is unavailable in your region, use a VPN/proxy for the gateway.

---

## Run the web build

Requirements: **JDK 21** and **Node.js 20+**.

```bash
# 1. gateway (also serves the production build)
node server/index.mjs                # http://localhost:8787
#    or: cd server && npm start

# 2. in another terminal: build the Wasm app
./gradlew :web:wasmJsBrowserDistribution

# 3. open the app
#    http://localhost:8787
```

### Hot-reload development

```bash
node server/index.mjs                # gateway on :8787
./gradlew :web:wasmJsBrowserDevelopmentRun
# open http://localhost:8080
```

`index.html` automatically points `JUS_API_BASE` at `http://localhost:8787`
when served from the Gradle dev server (`:8080`), and uses same-origin URLs in
production.

> The first Gradle run downloads the Kotlin/Wasm toolchain — expect a few
> minutes. If `services.gradle.org` is slow, raise `networkTimeout` in
> `gradle/wrapper/gradle-wrapper.properties`.

### Just want to hack on the gateway?

```bash
curl 'http://localhost:8787/api/search?q=arijit'
curl 'http://localhost:8787/api/home'
curl 'http://localhost:8787/api/collection?id=VLRDCLAK5uy_...'
```

---

## Desktop builds (Windows / Linux)

The desktop shells reuse the exact same `shared/` Compose UI, plus a native
audio engine: ffmpeg decodes the track into raw PCM and Java Sound plays it, so
there is no browser in the loop. That is what makes the equalizer, spatial
widening, loudness normalisation, crossfade, offline downloads and the seek bar
real on the desktop.

```bash
./gradlew :linux:run                     # run from Gradle
./scripts/package-desktop.sh             # app image + dist/jusplayer-linux-x64.tar.gz
./scripts/package-desktop.sh --deb       # plus .deb/.rpm when dpkg-deb/rpmbuild exist

./gradlew :windows:run                   # on Windows
.\gradlew.bat :windows:createDistributable
.\gradlew.bat :windows:packageMsi        # needs WiX, run on Windows
```

jpackage cannot cross-compile: `.deb`/`.rpm` have to be built on Linux and the
`.msi`/`.exe` on Windows. The app image and the `.tar.gz` always work.

Runtime dependencies:

| Tool | Needed for |
| --- | --- |
| `ffmpeg` | **required** — decodes every track |
| `yt-dlp` | optional — whole tracks when YouTube truncates the direct URL |
| `mpv` | optional — only the terminal client |

The desktop app talks to the gateway too. It prefers a local one
(`http://127.0.0.1:8787`) because that can serve whole tracks, and falls back to
<https://jusplayer.vercel.app>. Point it somewhere else with
`-Djusplayer.api=<url>`, `JUSPLAYER_API=<url>` or Settings → Gateway.

Downloads are stored per-user (`~/.local/share/jusplayer/downloads`,
`%APPDATA%\JusPlayer\downloads`, `~/Library/Application Support/JusPlayer`) and
play with no network at all. Settings → Audio and Settings → Offline drive the
engine and the download list.

### Terminal client

`tui/` is a full client as well: browse, search, library, queue, synced lyrics,
downloads and Discord rich presence, on top of `mpv`. See
[`tui/README.md`](tui/README.md).

```bash
cd tui && node src/main.mjs
```

---

## Deploy to Vercel (live site)

**Live now:** <https://jusplayer.vercel.app>

The web build is a static bundle plus one serverless function:

```
public/            # the compiled Compose/Wasm app (generated)
api/gateway.mjs    # the gateway, running as a Vercel function
server/router.mjs  # the router both entry points share
vercel.json        # rewrites /api/* → /api/gateway, build command, headers
```

### 1. One-off: create the project

```bash
npx vercel link            # pick a project name, e.g. jusplayer
npx vercel env add JUSPLAYER_SECRET production   # a long random string
```

`JUSPLAYER_SECRET` signs the session cookie that holds your YouTube session.
Without it the app still runs, but sessions only survive on a single instance.

### 2. Deploy

```bash
npm run build        # compiles the Wasm client into public/
npx vercel build --prod --yes
npx vercel deploy --prebuilt --prod
```

`npm run build` runs `./gradlew :web:wasmJsBrowserDistribution` (needs JDK 21)
and copies the result into `public/`. The `vercel build` step has to use the
same target as the deploy (`--prod`), otherwise Vercel refuses the prebuilt
output. If you would rather not install a JDK on the machine that deploys, use
the GitHub workflow instead (below).

### 3. Optional: Google sign-in

Create a **Web application** in the
[Google Cloud console](https://console.cloud.google.com/apis/credentials) and
add this authorised redirect URI (and JavaScript origin):

```
https://jusplayer.vercel.app/api/auth/callback
```

Then hand the credentials to the helper — it stores them on Vercel and
redeploys:

```bash
npm run setup:oauth -- <client-id> <client-secret>
```

Or do it by hand:

```bash
npx vercel env add YT_CLIENT_ID production
npx vercel env add YT_CLIENT_SECRET production
```

### 4. GitHub Actions (recommended)

`.github/workflows/deploy.yml` builds the client on a runner (with JDK 21) and
deploys it to Vercel on every push to `main`. Add these repository secrets:

| Secret            | Meaning                                   |
|-------------------|-------------------------------------------|
| `VERCEL_TOKEN`    | Vercel access token                       |
| `VERCEL_ORG_ID`   | your Vercel team id (optional)            |
| `VERCEL_PROJECT_ID` | the project id from `npx vercel link`   |

`.github/workflows/ci.yml` runs the same builds on every pull request.

---

## Accounts

YouTube Music has no public API for a web player, so the gateway talks to
YouTube's own InnerTube API with your session cookies. Two ways to sign in:

1. **Google OAuth** (recommended) — the app opens `/api/auth/login`, the
   gateway runs the OAuth dance and converts the access token into YouTube
   cookies. Needs `YT_CLIENT_ID` / `YT_CLIENT_SECRET` (see above).
2. **Cookie paste** — in *Settings → Account → Use a cookie* paste the value of
   the `SAPISID` cookie from `music.youtube.com`. The gateway validates it
   before accepting it.

Either way the gateway stores the session in a signed, http-only cookie, so
your credentials never touch the client. When signed in, the app can show your
account name, liked songs, library, history and a personalised home feed.

Without an account everything still works — the home feed falls back to curated
public shelves.

> **Current deployment:** the live site at <https://jusplayer.vercel.app> has
> Google OAuth configured; *Settings → Account* shows **Sign in with Google**
> (`/api/health` reports `"oauth":true`). The cookie paste remains as a fallback.

### If Google blocks the sign-in

Google shows the failure **on its own page**, before it ever returns to
JusPlayer. The block of text headed *Request details* is only the parameters it
received — the actual error is the headline above it. The common ones:

| Google says | Fix |
| --- | --- |
| `Error 403: access_denied` and mentions testing / verification | The OAuth consent screen is in **Testing**. Add the Google address under **Test users**, or click **Publish app**. |
| `Error 403: org_internal` | The consent screen's **User type** is *Internal*. Change it to **External**. |
| `Error 403: admin_policy_enforced` | A Google Workspace admin blocks the app; ask them to allow it, or use cookie sign-in. |
| `Error 400: redirect_uri_mismatch` | The authorised redirect URI must be exactly `https://jusplayer.vercel.app/api/auth/callback` (no trailing slash). |

Open the consent screen for this project directly:

```
https://console.cloud.google.com/apis/credentials/consent?project=458368175642
```

The redirect URI itself is registered — requesting `/api/auth/login` reaches
Google's account chooser rather than failing with `redirect_uri_mismatch`.

---

## Project layout

```
JusPlayer/
├── shared/
│   └── src/commonMain/kotlin/com/jusplayer/
│       ├── App.kt              # shell, navigation, snackbar, player overlay
│       ├── data/               # models, InnerTube gateway client, liked songs
│       ├── player/             # queue/repeat/shuffle state + Playback expect
│       └── ui/                 # theme, home, search, library, player views
├── web/                        # Wasm entry point + index.html (player bridge)
├── windows/  linux/            # Compose Desktop packaging shells
├── tui/                        # reserved for a terminal client
├── server/index.mjs            # local Node server
├── server/router.mjs           # the gateway router (InnerTube, images, auth)
├── server/auth.mjs             # OAuth + cookie sessions
├── api/gateway.mjs             # Vercel serverless entry point
├── scripts/build-web.mjs       # build the client and stage it in public/
├── vercel.json                 # Vercel config (rewrites, headers, build)
└── gradle/libs.versions.toml
```

Platform-specific code lives behind `expect`/`actual`:

* `platform/apiBase()`, `platformName()`, `openExternal()`, `shareLink()`,
  `artworkAccent()`, `Storage`
* `player/Playback` — YouTube IFrame on web, browser fallback on desktop

---

## Features

### Working in the web build

- [x] YouTube Music search with filters (songs / albums / playlists / artists)
- [x] Search suggestions, search history and recent queries
- [x] Home feed (with curated fallback when anonymous home is unavailable)
- [x] Optional YouTube Music sign-in: account details, liked songs, library,
      history and personalised feeds (Google OAuth or cookie paste)
- [x] Explore: moods & genres, charts and new releases
- [x] Album, playlist, artist and local-playlist pages
- [x] Start radio from any song (InnerTube auto-mix queue)
- [x] Full player: seek, volume, shuffle, repeat one/all, up-next queue
- [x] Queue management: reorder, remove, play next, add to queue, clear
- [x] Save the current queue as a local playlist
- [x] Liked songs, recent plays and listening stats persisted locally
- [x] Local playlists: create, add/remove tracks, reorder, delete
- [x] Synced (time-coded) lyrics with auto-scroll + plain-text fallback
- [x] Sleep timer (after track or timed) and playback speed (0.75×–2×)
- [x] Keyboard shortcuts (space, N/P, arrows, L, F)
- [x] Dynamic colour extracted from artwork, plus accent palettes
- [x] Player backgrounds (Aurora / Artwork / Midnight) and artwork sizes
- [x] Media Session metadata for OS/browser media controls
- [x] Share song link (Web Share API with clipboard fallback)
- [x] Responsive layout (side rail on wide screens, bottom nav on narrow)
- [x] Coil image loading through the CORS-safe artwork proxy
- [x] Offline downloads stored in IndexedDB (survive reload, play without network)
- [x] Local file import (picked files are copied into IndexedDB)
- [x] Real Web Audio DSP for downloaded/imported audio: 5-band EQ, preamp,
      stereo widening, loudness compression

### Desktop and terminal parity

| Feature | web | desktop | tui |
| --- | :---: | :---: | :---: |
| Browse, search, explore, playlists | ✓ | ✓ | ✓ |
| Streaming | YouTube IFrame | native (ffmpeg) | mpv |
| Offline downloads | IndexedDB | disk | music folder |
| Equalizer / spatial / loudness | saved audio | all audio | — |
| Crossfade | — | ✓ | — |
| Local file import | IndexedDB | disk | — |
| Synced lyrics | ✓ | ✓ | ✓ |
| Discord rich presence | — | ✓ | ✓ |
| ListenBrainz scrobbling | — | ✓ | — |

Web playback of *streamed* YouTube audio cannot be routed through Web Audio
(the iframe is cross-origin), so the equalizer applies to downloaded and
imported tracks. That is a browser security boundary, not a missing feature —
everything else in the table is implemented.

Still genuinely unavailable on every platform:

- [ ] ShazamKit music recognition (an Android/iOS API)
- [ ] Pitch shifting independent of tempo (needs a time-stretch stage)
- [ ] Spotify playlist import (needs Spotify OAuth credentials)
- [ ] Multi-account switching (the gateway keeps one signed-in session)
- [ ] Subsonic servers and music-together sessions

These are listed inside **Settings → Platform support**.

---

## Legal

JusPlayer is an independent client and is not affiliated with Google or
YouTube. It does not bypass YouTube's technical protections and does not host
or re-stream media. Support artists through official channels.

Released under the **GNU General Public License v3.0** — see [`LICENSE`](LICENSE).
This matches the license of the upstream
[Greeny-Goblins / JusPlayer](https://github.com/shubh72010/Greeny-Goblins) project.
