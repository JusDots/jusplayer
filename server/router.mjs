/**
 * JusPlayer gateway router.
 *
 * One small, dependency-free router that:
 *   1. Talks to YouTube Music's InnerTube API (no API key of your own required).
 *   2. Normalises its deeply nested responses into a tiny JSON shape the Compose UI understands.
 *   3. Proxies artwork so the WebAssembly canvas can draw it (CORS-safe).
 *   4. Signs users in to YouTube Music (Google OAuth or cookie paste) so account
 *      data and personalised recommendations can be fetched.
 *
 * It is used by two front doors:
 *   - `server/index.mjs`  — a normal Node HTTP server for local development.
 *   - `api/gateway.mjs`   — a Vercel serverless function for production.
 */

import { readFile, stat } from "node:fs/promises";
import { createReadStream, existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import crypto from "node:crypto";
import { spawnSync } from "node:child_process";
import {
  SESSION_COOKIE,
  authHeaders,
  bearerToken,
  clearSessionCookieHeader,
  createSession,
  exchangeCode,
  fetchUserInfo,
  normaliseYtCookie,
  oauthConfigured,
  oauthState,
  oauthUrl,
  parseCookies,
  refreshAccessToken,
  sessionCookieHeader,
  streamToken,
  verifySession,
  verifyStreamToken,
  youtubeCookieFromToken,
} from "./auth.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DIST =
  process.env.JUSPLAYER_DIST ||
  path.resolve(__dirname, "../web/build/dist/wasmJs/productionExecutable");
const SECRET = process.env.JUSPLAYER_SECRET || "jusplayer-dev-secret";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

const SEARCH_FILTERS = {
  songs: "EgWKAQIIAWoKEAkQBRAKEAMQBA==",
  videos: "EgWKAQIQAWoKEAkQChAFEAMQBA==",
  albums: "EgWKAQIYAWoKEAkQChAFEAMQBA==",
  artists: "EgWKAQIgAWoKEAkQChAFEAMQBA==",
  playlists: "EgWKAQIoAWoKEAkQChAFEAMQBA==",
};

// ---------------------------------------------------------------- InnerTube ---

let yt = null;
let ytFetchedAt = 0;

async function bootstrap(force = false) {
  if (!force && yt && Date.now() - ytFetchedAt < 30 * 60_000) return yt;

  try {
    const res = await fetch("https://music.youtube.com/", {
      headers: { "user-agent": UA, "accept-language": "en-US,en;q=0.9" },
      signal: AbortSignal.timeout(15_000),
    });
    const html = await res.text();
    const pick = (re) => html.match(re)?.[1];
    const cfg = {
      key: pick(/"INNERTUBE_API_KEY":"([^"]+)"/),
      version: pick(/"INNERTUBE_CLIENT_VERSION":"([^"]+)"/),
      visitor: pick(/"VISITOR_DATA":"([^"]+)"/),
    };
    if (cfg.key && cfg.version) {
      yt = cfg;
      ytFetchedAt = Date.now();
      console.log(`[innertube] client ${cfg.version}`);
      return yt;
    }
    console.warn("[innertube] could not read ytcfg, using fallback client");
  } catch (err) {
    console.warn("[innertube] bootstrap failed:", err.message);
  }

  if (!yt) {
    yt = {
      key: "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30",
      version: "1.20250101.01.00",
      visitor: undefined,
    };
    ytFetchedAt = Date.now();
  }
  return yt;
}

async function innertube(endpoint, body, opts = {}) {
  const { auth = null, retried = false, client = null } = opts;
  const cfg = await bootstrap();
  const url = `https://music.youtube.com/youtubei/v1/${endpoint}?key=${cfg.key}&prettyPrint=false`;
  const chosen = client ? { ...client } : { clientName: "WEB_REMIX", clientVersion: cfg.version };
  const headers = {
    "content-type": "application/json",
    "user-agent": chosen.userAgent || UA,
    "accept-language": "en-US,en;q=0.9",
    "x-youtube-client-name": chosen.clientName,
    "x-youtube-client-version": chosen.clientVersion,
    origin: "https://music.youtube.com",
    referer: "https://music.youtube.com/",
  };
  if (cfg.visitor) headers["x-goog-visitor-id"] = cfg.visitor;

  // Signed-in calls need either the user's cookies plus the SAPISIDHASH
  // signature, or an OAuth access token as a plain Bearer credential.
  const signed = auth ? authHeaders(auth) : null;
  if (signed) {
    if (signed.cookie) headers.cookie = signed.cookie;
    headers.authorization = signed.authorization;
    if (signed.requestTime) headers["x-goog-request-time"] = signed.requestTime;
    headers["x-goog-authuser"] = "0";
  }

  const payload = {
    context: {
      client: {
        clientName: chosen.clientName,
        clientVersion: chosen.clientVersion,
        hl: "en",
        gl: "US",
        visitorData: cfg.visitor,
      },
      user: { lockedSafetyMode: false },
      request: { useSsl: true },
    },
    ...body,
  };

  const res = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(20_000),
  });

  if (!res.ok) {
    if (!retried && (res.status === 400 || res.status === 401 || res.status === 403)) {
      await bootstrap(true);
      return innertube(endpoint, body, { ...opts, retried: true });
    }
    const error = new Error(`InnerTube ${endpoint} -> HTTP ${res.status}`);
    error.status = res.status;
    throw error;
  }
  return res.json();
}

/**
 * InnerTube clients.
 *
 * `WEB_REMIX` is what the web app itself uses and only ever returns
 * storyboard/metadata. The mobile clients are the ones that hand back
 * *direct, unciphered* audio URLs — that is what powers native playback on
 * desktop, offline downloads and the audio DSP chain.
 *
 * Not every client works for every track: some CDN URLs are signed for one
 * client only and answer 403 for the others, so the resolver tries them in
 * turn and keeps the first URL that actually plays.
 */
const ANDROID_CLIENT = {
  clientName: "ANDROID",
  clientVersion: "20.10.38",
  userAgent:
    "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip",
};
const IOS_CLIENT = {
  clientName: "IOS",
  clientVersion: "20.10.4",
  userAgent:
    "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
};
const ANDROID_VR_CLIENT = {
  clientName: "ANDROID_VR",
  clientVersion: "1.62.27",
  userAgent:
    "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12; en_US) gzip",
};
const AUDIO_CLIENTS = [ANDROID_CLIENT, IOS_CLIENT, ANDROID_VR_CLIENT];

// ---------------------------------------------------------------- yt-dlp ---

let ytDlpState = null;
let ytDlpBinary = null;

/**
 * Where yt-dlp lives.
 *
 * Locally it is usually on `$PATH`. On Vercel the build stages a static
 * PyInstaller binary in `bin/`, which is bundled with the function through
 * `includeFiles` in vercel.json.
 */
function ytDlpBinaryPath() {
  if (ytDlpBinary) return ytDlpBinary;
  const staged = path.resolve(__dirname, "../bin/yt-dlp");
  ytDlpBinary = existsSync(staged) ? staged : (process.env.YT_DLP_PATH || "yt-dlp");
  return ytDlpBinary;
}

/** Is yt-dlp installed? It is the only way to get unrestricted stream URLs. */
export function ytDlpAvailable() {
  if (ytDlpState === null) {
    try {
      ytDlpState =
        spawnSync(ytDlpBinaryPath(), ["--version"], { encoding: "utf8", timeout: 15_000 }).status === 0;
    } catch {
      ytDlpState = false;
    }
  }
  return ytDlpState;
}

/**
 * Resolve a stream URL with yt-dlp.
 *
 * YouTube limits the URLs that the plain InnerTube clients hand out to about
 * one megabyte per request. yt-dlp solves YouTube's JavaScript challenge and
 * uses a client whose URLs have no such limit — so when it is installed the
 * gateway can serve whole tracks (and therefore offline downloads).
 */
function resolveWithYtDlp(videoId) {
  if (!ytDlpAvailable()) return null;
  const result = spawnSync(
    ytDlpBinaryPath(),
    [
      "--no-playlist",
      "--no-warnings",
      "--no-progress",
      "-f",
      "140/bestaudio[ext=m4a]/bestaudio",
      "-g",
      `https://www.youtube.com/watch?v=${videoId}`,
    ],
    { encoding: "utf8", timeout: 90_000 },
  );
  if (result.status !== 0) return null;
  const url = String(result.stdout || "")
    .split("\n")
    .map((line) => line.trim())
    .find((line) => line.startsWith("http"));
  return url || null;
}


/** Short, stable id for a signed-in user — used to keep caches separate. */
function userKey(auth) {
  if (!auth) return "anon";
  return crypto.createHash("sha256").update(auth).digest("hex").slice(0, 12);
}

// ------------------------------------------------------------------ parsing ---

function walk(node, visit) {
  if (!node || typeof node !== "object") return;
  if (Array.isArray(node)) {
    for (const child of node) walk(child, visit);
    return;
  }
  visit(node);
  for (const key of Object.keys(node)) walk(node[key], visit);
}

const textOf = (runs) =>
  Array.isArray(runs) ? runs.map((r) => r?.text ?? "").join("").trim() : "";

const titleOf = (obj) =>
  textOf(obj?.title?.runs) ||
  textOf(obj?.title?.simpleText?.runs) ||
  (typeof obj?.title?.simpleText === "string" ? obj.title.simpleText : "");

function thumbOf(renderer) {
  const thumbs =
    renderer?.musicThumbnailRenderer?.thumbnail?.thumbnails ??
    renderer?.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails ??
    renderer?.thumbnail?.thumbnails ??
    renderer?.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails ??
    [];
  const url = thumbs[thumbs.length - 1]?.url;
  return url ? `/api/img?u=${encodeURIComponent(url)}` : null;
}

function creditsOf(runs) {
  const out = [];
  for (const run of runs ?? []) {
    const browse = run?.navigationEndpoint?.browseEndpoint;
    if (!browse?.browseId) continue;
    const pageType =
      browse.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType ?? "";
    if (!browse.browseId.startsWith("UC") && !pageType.includes("ARTIST")) continue;
    const name = (run.text ?? "").trim();
    if (!name) continue;
    if (!out.some((c) => c.browseId === browse.browseId)) {
      out.push({ name, browseId: browse.browseId });
    }
  }
  return out;
}

/** Pull the page header (album, playlist, artist) into a flat shape. */
function parseHeader(data) {
  let header = null;
  walk(data, (node) => {
    if (header) return;
    header =
      node.musicImmersiveHeaderRenderer ??
      node.musicVisualHeaderRenderer ??
      node.musicResponsiveHeaderRenderer ??
      node.musicDetailHeaderRenderer ??
      null;
  });
  if (!header) return {};

  const description =
    textOf(header.description?.runs) ||
    textOf(header.description?.musicDescriptionShelfRenderer?.description?.runs) ||
    textOf(header.description?.simpleText) ||
    "";
  const subtitle = textOf(header.subtitle?.runs);
  const artists = creditsOf(
    header.straplineTextOne?.runs ?? header.subtitle?.runs ?? header.description?.runs,
  );

  return {
    title: titleOf(header),
    subtitle,
    description,
    thumbnail: thumbOf(header),
    artists,
    meta: textOf(header.secondSubtitle?.runs),
  };
}

function endpointOf(r) {
  const nav = r?.navigationEndpoint ?? r?.title?.runs?.[0]?.navigationEndpoint ?? {};
  const videoId =
    nav.watchEndpoint?.videoId ??
    nav.watchPlaylistEndpoint?.videoId ??
    r?.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
      ?.playNavigationEndpoint?.watchEndpoint?.videoId ??
    r?.playlistItemData?.videoId ??
    null;
  const browseId =
    nav.browseEndpoint?.browseId ??
    nav.browsePlaylistEndpoint?.browseId ??
    nav.watchPlaylistEndpoint?.playlistId ??
    null;
  return { videoId, browseId };
}

function kindOf(videoId, browseId) {
  if (videoId) return "song";
  if (!browseId) return "song";
  if (browseId.startsWith("MPRE")) return "album";
  if (browseId.startsWith("VL") || browseId.startsWith("RD")) return "playlist";
  if (browseId.startsWith("UC")) return "artist";
  return "playlist";
}

function durationOf(r) {
  const fixed = r?.fixedColumns?.[0]?.musicResponsiveListItemFixedColumnRenderer?.text;
  return textOf(fixed?.runs) || null;
}

function parseItem(node) {
  const r =
    node?.musicTwoRowItemRenderer ??
    node?.musicResponsiveListItemRenderer ??
    node?.musicCardShelfRenderer ??
    node;
  if (!r || typeof r !== "object") return null;

  const flex = r.flexColumns ?? [];
  const title =
    titleOf(r) ||
    textOf(flex[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs);
  if (!title) return null;

  const subtitleRuns =
    r.subtitle?.runs ??
    flex[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs ??
    [];
  const subtitle =
    textOf(subtitleRuns) ||
    textOf(r.subtitle?.simpleText);

  const { videoId, browseId } = endpointOf(r);
  let duration = durationOf(r);

  // Songs often carry their length at the tail of the subtitle: "Artist • Album • 3:42"
  const durationMatch = subtitle.match(/(?:^|\s•\s)(\d{1,2}:\d{2}(?::\d{2})?)$/);
  let cleanSubtitle = subtitle;
  if (durationMatch) {
    duration = duration ?? durationMatch[1];
    cleanSubtitle = subtitle.slice(0, durationMatch.index).trim();
  }

  const thumbnail =
    thumbOf(r) ??
    (videoId ? `/api/img?u=${encodeURIComponent(`https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`)}` : null);

  return {
    id: videoId ? `v:${videoId}` : `b:${browseId ?? title}`,
    title,
    subtitle: cleanSubtitle,
    thumbnail,
    kind: kindOf(videoId, browseId),
    videoId,
    browseId,
    duration: duration,
    artists: creditsOf(subtitleRuns),
  };
}

/** Parse an item from the "Up next"/queue panel used by `next` responses. */
function parsePanelVideo(r) {
  if (!r?.videoId) return null;
  const title = titleOf(r);
  if (!title) return null;
  const subtitleRuns = r.longBylineText?.runs ?? r.shortBylineText?.runs ?? [];
  const subtitle = textOf(subtitleRuns);
  const duration =
    textOf(r.lengthText?.runs) ||
    (typeof r.lengthText?.simpleText === "string" ? r.lengthText.simpleText : null);
  const thumbnail =
    thumbOf(r) ??
    `/api/img?u=${encodeURIComponent(`https://i.ytimg.com/vi/${r.videoId}/hqdefault.jpg`)}`;
  return {
    id: `v:${r.videoId}`,
    title,
    subtitle,
    thumbnail,
    kind: "song",
    videoId: r.videoId,
    browseId: null,
    duration,
    artists: creditsOf(subtitleRuns),
  };
}

function dedupe(items) {
  const seen = new Set();
  return items.filter((item) => {
    if (!item || seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
}

function sectionTitle(header) {
  return (
    titleOf(header?.musicCarouselShelfBasicHeaderRenderer) ||
    titleOf(header?.musicShelfRenderer?.title) ||
    titleOf(header?.musicHeaderRenderer) ||
    titleOf(header?.gridHeaderRenderer) ||
    titleOf(header) ||
    ""
  );
}

function collectSections(root) {
  const sections = [];

  walk(root, (node) => {
    const carousel = node.musicCarouselShelfRenderer;
    const shelf = node.musicShelfRenderer;
    const grid = node.gridRenderer;

    let contents = null;
    let title = "";

    if (carousel) {
      contents = carousel.contents;
      title =
        sectionTitle(carousel.header) ||
        textOf(carousel.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs);
    } else if (shelf) {
      contents = shelf.contents;
      title = sectionTitle(shelf.header);
    } else if (grid) {
      contents = grid.items;
      title = sectionTitle(grid.header);
    }

    if (!Array.isArray(contents)) return;
    const items = dedupe(contents.map(parseItem)).slice(0, 24);
    if (items.length >= 2) sections.push({ title, items });
  });

  // Innertube often nests the "Quick picks" shelf inside another shelf; drop repeats.
  const seen = new Set();
  return sections.filter((s) => {
    const key = s.items.map((i) => i.id).join(",");
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function flattenItems(root) {
  const items = [];
  walk(root, (node) => {
    if (
      node.musicResponsiveListItemRenderer ||
      node.musicTwoRowItemRenderer
    ) {
      const item = parseItem(node);
      if (item) items.push(item);
    }
  });
  return dedupe(items);
}

// ------------------------------------------------------------------ account ---

/** Read the signed session (if any) from the request cookies. */
function readSession(req) {
  const cookies = parseCookies(req.headers.cookie || "");
  return verifySession(cookies[SESSION_COOKIE], SECRET);
}

/**
 * The credential InnerTube should use for a session.
 *
 * OAuth tokens win while they are fresh because they are the supported
 * mobile path; the cookie (when YouTube handed one over during sign-in) is
 * the durable fallback.
 */
function sessionCredential(session) {
  if (!session) return null;
  const oauth = session.oauth;
  if (oauth?.accessToken) {
    const expiresAt = Number(oauth.expiresAt) || 0;
    if (!expiresAt || expiresAt - 60_000 > Date.now()) {
      return `Bearer ${oauth.accessToken}`;
    }
  }
  return session.yt || null;
}

function isFreshCredential(session) {
  return Boolean(sessionCredential(session)?.startsWith("Bearer "));
}

/**
 * Refresh an OAuth access token when it is about to expire.
 *
 * Runs once per request, before any handler reaches the network, and rewrites
 * the session cookie in place so the browser keeps a valid 30-day session
 * without ever seeing Google's short-lived tokens.
 */
async function prepareSession(req, res, isSecure) {
  const session = readSession(req);
  if (!session?.oauth?.refreshToken || isFreshCredential(session)) return session;
  if (!oauthConfigured()) return session;

  try {
    const tokens = await refreshAccessToken({
      refreshToken: session.oauth.refreshToken,
      clientId: process.env.YT_CLIENT_ID,
      clientSecret: process.env.YT_CLIENT_SECRET,
    });
    const next = {
      ...session,
      oauth: {
        ...session.oauth,
        accessToken: tokens.access_token,
        expiresAt: Date.now() + (Number(tokens.expires_in) || 3600) * 1000,
      },
    };
    const token = createSession(session.yt || "", next, SECRET);
    res.setHeader("set-cookie", sessionCookieHeader(token, { secure: isSecure }));
    return verifySession(token, SECRET) || next;
  } catch (err) {
    console.warn("[auth] token refresh failed:", err.message);
    return session;
  }
}


/** Best-effort account details straight from YouTube's account page. */
async function youtubeAccountPage(cookie) {
  if (!cookie) return null;
  try {
    const res = await fetch("https://www.youtube.com/account/account_menu", {
      headers: {
        cookie: normaliseYtCookie(cookie),
        "user-agent": UA,
        "accept-language": "en-US,en;q=0.9",
      },
      redirect: "follow",
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) return null;
    const html = await res.text();
    const pick = (re) => {
      const found = html.match(re)?.[1];
      return found ? found.replace(/\\u003d/g, "=").replace(/\\u0026/g, "&") : null;
    };
    const name = pick(/"display_name":"((?:[^"\\]|\\.)*)"/) || pick(/"DISPLAY_NAME":"((?:[^"\\]|\\.)*)"/);
    const email = pick(/"email":"((?:[^"\\]|\\.)*)"/) || pick(/"EMAIL":"((?:[^"\\]|\\.)*)"/);
    const avatar = pick(/"avatar_url":"((?:[^"\\]|\\.)*)"/);
    const channelId = pick(/"channel_id":"((?:[^"\\]|\\.)*)"/);
    if (!name && !email && !channelId) return null;
    return { name, email, avatar, channelId };
  } catch {
    return null;
  }
}

/** Account summary for the signed-in user. */
async function accountInfo(auth, profile = null) {
  if (!auth && !profile?.name && !profile?.email) {
    return { signedIn: false, name: "", email: "", avatar: "", channelId: "", source: null };
  }
  if (profile?.name || profile?.email) {
    return {
      signedIn: true,
      name: profile.name || profile.email,
      email: profile.email || "",
      avatar: profile.avatar || "",
      channelId: profile.channelId || "",
      source: "Google",
    };
  }

  // OAuth sign-ins may not have a cookie at all; ask InnerTube directly.
  if (bearerToken(auth)) {
    let name = "";
    let channelId = "";
    try {
      const data = await innertube("account/account_menu", {}, { auth });
      walk(data, (node) => {
        const header = node.accountItemRenderer || node.accountSectionListRenderer;
        if (!header) return;
        const title = textOf(header.title) || textOf(header.accountName);
        if (title && !name) name = title;
        const browseId = header.endpoint?.browseEndpoint?.browseId;
        if (browseId?.startsWith("UC") && !channelId) channelId = browseId;
      });
    } catch {
      // Best effort only.
    }
    return {
      signedIn: true,
      name: name || "YouTube Music",
      email: "",
      avatar: "",
      channelId,
      source: "Google",
    };
  }

  const page = await youtubeAccountPage(auth);
  return {
    signedIn: true,
    name: page?.name || "YouTube Music",
    email: page?.email || "",
    avatar: page?.avatar || "",
    channelId: page?.channelId || "",
    source: "YouTube",
  };
}

/** Does this cookie really belong to a signed-in YouTube Music user? */
async function validateCookie(cookie) {
  const normalised = normaliseYtCookie(cookie);
  if (!normalised) return false;

  const page = await youtubeAccountPage(normalised);
  if (page?.channelId || page?.email) return true;

  try {
    const data = await innertube(
      "browse",
      { browseId: "FEmusic_liked_videos" },
      { auth: normalised },
    );
    let signedIn = false;
    walk(data, (node) => {
      if (node.signInEndpoint) signedIn = false;
      if (node.musicResponsiveListItemRenderer) signedIn = true;
    });
    return signedIn;
  } catch {
    return false;
  }
}

/** "Liked songs" from the signed-in account. */
async function likedSongs(auth) {
  const data = await innertube("browse", { browseId: "FEmusic_liked_videos" }, { auth });
  const header = parseHeader(data);
  const items = flattenItems(data).filter((item) => item.videoId);
  return {
    ...header,
    kind: "playlist",
    title: header.title || "Liked songs",
    sections: items.length ? [{ title: "Songs", items }] : [],
  };
}

/** Watch history from the signed-in account. */
async function accountHistory(auth) {
  const data = await innertube("browse", { browseId: "FEmusic_history" }, { auth });
  const items = flattenItems(data).filter((item) => item.videoId);
  return { sections: items.length ? [{ title: "History", items }] : [] };
}

/** The signed-in library: playlists, artists, albums and subscriptions. */
async function accountLibrary(auth) {
  const data = await innertube("browse", { browseId: "FEmusic_library_landing" }, { auth });

  const tabs = [];
  walk(data, (node) => {
    const tab = node.tabRenderer;
    if (!tab) return;
    const title = typeof tab.title === "string" ? tab.title : textOf(tab.title?.runs);
    const endpoint = tab.endpoint;
    const browseId = endpoint?.browseEndpoint?.browseId ?? null;
    const params = endpoint?.browseEndpoint?.params ?? null;
    if (title && browseId) tabs.push({ title, browseId, params });
  });

  const sections = [];
  for (const tab of tabs.slice(0, 8)) {
    try {
      const page = await innertube(
        "browse",
        { browseId: tab.browseId, ...(tab.params ? { params: tab.params } : {}) },
        { auth },
      );
      const items = flattenItems(page);
      if (items.length) {
        sections.push({ title: tab.title, items: items.slice(0, 50) });
        continue;
      }
      const shelves = collectSections(page);
      if (shelves.length) sections.push(...shelves);
    } catch (err) {
      console.warn(`[library] tab "${tab.title}" failed:`, err.message);
    }
  }

  if (!sections.length) {
    const liked = await likedSongs(auth).catch(() => null);
    if (liked?.sections?.length) sections.push(...liked.sections);
  }

  return { sections };
}

// ------------------------------------------------------------------- routes ---

const cache = new Map();
async function cached(key, ttl, compute) {
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < ttl) return hit.value;
  const value = await compute();
  cache.set(key, { at: Date.now(), value });
  if (cache.size > 200) cache.delete(cache.keys().next().value);
  return value;
}

async function search(query, filter, auth = null) {
  const params = SEARCH_FILTERS[filter];
  const data = await innertube("search", {
    query,
    ...(params ? { params } : {}),
  }, { auth });
  const sections = collectSections(data);
  if (sections.length) return { sections };

  const items = flattenItems(data);
  return { sections: items.length ? [{ title: "Results", items }] : [] };
}

async function home(auth = null) {
  let sections = [];
  try {
    const data = await innertube("browse", { browseId: "FEmusic_home" }, { auth });
    sections = collectSections(data).filter((s) => s.items.length >= 3);
  } catch (err) {
    console.warn("[home] FEmusic_home failed:", err.message);
  }

  // Anonymous home is not always available (or returns very few shelves).
  // Top it up with curated searches so the app never looks empty.
  if (sections.length < 3) {
    const seeds = [
      ["Made for you", "lofi hip hop mix"],
      ["Trending now", "top hits 2026"],
      ["Fresh finds", "new indie music"],
      ["Focus flow", "deep focus ambient"],
      ["Late night", "chill r&b playlist"],
    ];
    for (const [title, query] of seeds) {
      if (sections.length >= 5) break;
      if (sections.some((s) => s.title === title)) continue;
      try {
        const result = await search(query, "songs", auth);
        const items = result.sections.flatMap((s) => s.items).slice(0, 12);
        if (items.length) sections.push({ title, items });
      } catch (err) {
        console.warn(`[home] seed "${query}" failed:`, err.message);
      }
    }
  }

  return { sections: sections.slice(0, 12) };
}

async function pageOf(browseId, kind, auth = null) {
  if (!browseId) throw new Error("browseId required");
  const data = await innertube("browse", { browseId }, { auth });
  const header = parseHeader(data);

  if (kind === "artist") {
    const sections = collectSections(data).map((section, index) => ({
      ...section,
      title: section.title || (index === 0 ? "Top songs" : "More"),
    }));
    return { ...header, kind, sections };
  }

  // Album/playlist headings include a "Releases for you" carousel at the bottom;
  // only the actual track shelf belongs to the page body.
  const items = flattenItems(data).filter((item) => item.videoId);
  return {
    ...header,
    kind,
    sections: items.length ? [{ title: kind === "album" ? "Songs" : "Tracks", items }] : [],
  };
}

async function collection(browseId, auth = null) {
  const data = await innertube("browse", { browseId }, { auth });
  const header = parseHeader(data);
  const items = flattenItems(data);
  return {
    ...header,
    title: header.title || "Collection",
    sections: items.length ? [{ title: header.title || "Collection", items }] : [],
  };
}

async function hub(browseId, fallbackTitle, auth = null) {
  const data = await innertube("browse", { browseId }, { auth });
  const header = parseHeader(data);
  return {
    title: header.title || fallbackTitle,
    sections: collectSections(data),
  };
}

async function moods() {
  const data = await innertube("browse", { browseId: "FEmusic_moods_and_genres" });
  const groups = [];
  walk(data, (node) => {
    const grid = node.gridRenderer;
    if (!grid) return;
    const entries = (grid.items ?? [])
      .map((item) => item.musicNavigationButtonRenderer)
      .filter(Boolean)
      .map((button) => ({
        title: textOf(button.buttonText?.runs),
        params: button.clickCommand?.browseEndpoint?.params ?? "",
        color: button.solid?.leftStripeColor ?? null,
      }))
      .filter((entry) => entry.title && entry.params);
    if (entries.length) {
      groups.push({ title: sectionTitle(grid.header) || "Browse", entries });
    }
  });
  return { groups };
}

async function mood(params, title) {
  const data = await innertube("browse", {
    browseId: "FEmusic_moods_and_genres_category",
    params,
  });
  return { title: title || "Mood", sections: collectSections(data) };
}

async function suggestions(input) {
  const data = await innertube("music/get_search_suggestions", { input });
  const out = [];
  walk(data, (node) => {
    const suggestion = node.searchSuggestionRenderer;
    if (!suggestion) return;
    const text = textOf(suggestion.suggestion?.runs);
    if (text) out.push(text);
  });
  return { suggestions: [...new Set(out)].slice(0, 10) };
}

async function ytLyrics(videoId) {
  const next = await innertube("next", { videoId, isAudioOnly: true });
  let browseId = null;
  walk(next, (node) => {
    const tab = node.tabRenderer;
    if (!tab || browseId) return;
    const title = typeof tab.title === "string" ? tab.title : textOf(tab.title?.runs);
    if (title?.toLowerCase() === "lyrics") {
      browseId = tab.endpoint?.browseEndpoint?.browseId ?? null;
    }
  });
  if (!browseId) return { lyrics: null, source: null };

  const page = await innertube("browse", { browseId });
  let text = null;
  walk(page, (node) => {
    if (text) return;
    const shelf = node.musicDescriptionShelfRenderer;
    if (shelf) text = textOf(shelf.description?.runs).replace(/\n{3,}/g, "\n\n");
  });
  return { lyrics: text, source: text ? "YouTube Music" : null };
}

/**
 * LRCLIB is a free, key-less, community lyrics database that publishes
 * time-coded lyrics — exactly what YouTube Music's own panel lacks.
 * Best effort only: any failure falls back to YouTube's plain text.
 */
async function lrclib(title, artist, duration, album) {
  if (!title) return null;
  const headers = { "user-agent": "JusPlayer/1.0 (web music client)" };
  const seconds = Number(duration) || 0;

  const pick = (list) => {
    if (!Array.isArray(list) || !list.length) return null;
    const scored = list
      .filter((entry) => entry && (entry.syncedLyrics || entry.plainLyrics))
      .map((entry) => {
        const delta = seconds && entry.duration ? Math.abs(entry.duration - seconds) : 0;
        const titleMatch =
          entry.trackName?.toLowerCase().trim() === title.toLowerCase().trim() ? 0 : 1;
        return { entry, score: titleMatch * 100 + delta };
      })
      .sort((a, b) => a.score - b.score);
    return scored[0]?.entry ?? null;
  };

  try {
    const params = new URLSearchParams({ track_name: title });
    if (artist) params.set("artist_name", artist);
    if (album) params.set("album_name", album);
    const res = await fetch(`https://lrclib.net/api/search?${params}`, {
      headers,
      signal: AbortSignal.timeout(12_000),
    });
    if (res.ok) {
      const best = pick(await res.json());
      if (best) {
        return {
          synced: best.syncedLyrics || null,
          plain: best.plainLyrics || null,
          source: "LRCLIB",
        };
      }
    }
  } catch (err) {
    console.warn("[lyrics] lrclib failed:", err.message);
  }
  return null;
}

async function lyrics(videoId, title, artist, duration, album) {
  const [yt, synced] = await Promise.all([
    ytLyrics(videoId).catch((err) => {
      console.warn("[lyrics] youtube failed:", err.message);
      return { lyrics: null, source: null };
    }),
    lrclib(title, artist, duration, album),
  ]);

  const syncedLyrics = synced?.synced ?? null;
  const plain = yt.lyrics || synced?.plain || null;
  const source = syncedLyrics ? "LRCLIB" : yt.source || (plain ? "LRCLIB" : null);
  return { lyrics: plain, syncedLyrics, source };
}

async function related(videoId, auth = null) {
  // The first `next` call exposes the video's auto-mix ("radio") playlist id.
  const first = await innertube("next", { videoId, isAudioOnly: true }, { auth });
  let playlistId = null;
  walk(first, (node) => {
    if (playlistId) return;
    const radio = node.automixPlaylistVideoRenderer;
    if (radio) {
      playlistId =
        radio.navigationEndpoint?.watchPlaylistEndpoint?.playlistId ?? null;
    }
  });

  // Asking `next` again with that playlist id returns the full 25–50 song queue
  // instead of just the currently playing track.
  const data = playlistId
    ? await innertube("next", { videoId, playlistId, isAudioOnly: true }, { auth })
    : first;

  const items = [];
  walk(data, (node) => {
    if (node.playlistPanelVideoRenderer) {
      const item = parsePanelVideo(node.playlistPanelVideoRenderer);
      if (item) items.push(item);
    }
  });

  const sections = collectSections(first);
  const radio = dedupe(items).slice(0, 50);
  if (radio.length) sections.unshift({ title: "Radio", items: radio });
  return { sections: sections.slice(0, 4) };
}

const ALLOWED_IMAGE_HOSTS = new Set([
  "i.ytimg.com",
  "yt3.ggpht.com",
  "yt3.googleusercontent.com",
  "lh3.googleusercontent.com",
  "music.youtube.com",
]);

async function proxyImage(target) {
  let url;
  try {
    url = new URL(target);
  } catch {
    return null;
  }
  if (!ALLOWED_IMAGE_HOSTS.has(url.hostname)) return null;

  const res = await fetch(url, {
    headers: { "user-agent": UA, referer: "https://music.youtube.com/" },
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) return null;
  return {
    body: Buffer.from(await res.arrayBuffer()),
    contentType: res.headers.get("content-type") || "image/jpeg",
  };
}

// --------------------------------------------------------------------- audio ---

/**
 * Resolve a playable audio stream for a video.
 *
 * The mobile InnerTube clients return *direct* audio URLs (no signature
 * cipher), which is what lets the desktop app and the TUI play audio
 * natively, and what lets the web app cache tracks for offline listening.
 *
 * The returned URL is short-lived (Google expires them after a few hours),
 * so callers should resolve it again when a track starts rather than storing
 * it forever.
 */
async function resolveAudio(videoId, auth = null) {
  const attempts = await Promise.allSettled(
    AUDIO_CLIENTS.map((client) =>
      innertube(
        "player",
        { videoId, contentCheckOk: true, racyCheckOk: true },
        { auth, client },
      ),
    ),
  );

  const candidates = [];
  let firstError = null;

  for (const attempt of attempts) {
    if (attempt.status !== "fulfilled") {
      firstError = firstError || attempt.reason;
      continue;
    }
    const data = attempt.value;
    const status = data.playabilityStatus?.status;
    if (status && status !== "OK") {
      const reason =
        data.playabilityStatus?.reason ||
        data.playabilityStatus?.errorScreen?.playerErrorMessageRenderer?.subreason?.runs
          ?.map((r) => r.text)
          .join("") ||
        "not playable";
      firstError = firstError || new Error(`YouTube says this track is ${status}: ${reason}`);
      continue;
    }

    const formats = [
      ...(data.streamingData?.adaptiveFormats || []),
      ...(data.streamingData?.formats || []),
    ].filter((f) => f.mimeType?.startsWith("audio/") && f.url);

    // Prefer AAC/M4A: every desktop audio stack understands it, while Opus
    // in WebM needs a separate decoder.
    const rank = (format) => {
      const mime = format.mimeType || "";
      let score = 0;
      if (mime.includes("mp4a")) score += 100;
      if (mime.includes("opus")) score += 40;
      if (mime.includes("mp4")) score += 20;
      score += Math.min(40, Math.round((format.bitrate || 0) / 5000));
      return score;
    };

    for (const format of formats.sort((a, b) => rank(b) - rank(a)).slice(0, 2)) {
      candidates.push({
        url: format.url,
        mimeType: format.mimeType,
        bitrate: format.bitrate || 0,
        approxBytes: format.contentLength ? Number(format.contentLength) : 0,
      });
    }

    if (!candidates.length) {
      firstError = firstError || new Error("YouTube did not return an audio stream for this track");
    }
    if (candidates.length) {
      const details = data.videoDetails || {};
      candidates.forEach((candidate) => {
        candidate.duration = Number(details.lengthSeconds || 0);
        candidate.title = details.title || "";
        candidate.author = details.author || "";
        candidate.thumbnail = details.thumbnail?.thumbnails?.slice(-1)[0]?.url || "";
      });
      break;
    }
  }

  if (!candidates.length) {
    throw firstError || new Error("YouTube did not return an audio stream for this track");
  }

  // Some CDN URLs are signed for one client only and answer 403 for the
  // others — so probe each candidate and keep the first that really plays.
  const verified = await Promise.all(
    candidates.map(async (candidate) => {
      const client = AUDIO_CLIENTS.find(
        (entry) => candidate.url.includes(`c=${entry.clientName}`) || entry === ANDROID_CLIENT,
      );
      const userAgent = client?.userAgent || ANDROID_CLIENT.userAgent;
      try {
        const probe = await fetch(candidate.url, {
          headers: { "user-agent": userAgent, range: "bytes=0-2047" },
          signal: AbortSignal.timeout(15_000),
        });
        const ok = probe.status === 200 || probe.status === 206;
        await probe.body?.cancel();
        return ok ? { ...candidate, userAgent } : null;
      } catch {
        return null;
      }
    }),
  );

  let best = verified.find(Boolean);
  if (!best) {
    throw new Error("YouTube returned audio links, but none of them could be played");
  }

  // The plain InnerTube URLs only allow about a megabyte per request. If
  // yt-dlp is around, ask it for a link without that limit so whole tracks
  // (and offline downloads) work.
  const size = best.approxBytes || 0;
  if (size > 1024 * 1024) {
    const unrestricted = resolveWithYtDlp(videoId);
    if (unrestricted) {
      try {
        const probe = await fetch(unrestricted, {
          headers: { range: "bytes=0-2047" },
          signal: AbortSignal.timeout(20_000),
        });
        const ok = probe.status === 200 || probe.status === 206;
        await probe.body?.cancel();
        if (ok) {
          best = { ...best, url: unrestricted, userAgent: ANDROID_CLIENT.userAgent, unrestricted: true };
        }
      } catch {
        /* keep the InnerTube link */
      }
    }
  }

  return {
    videoId,
    url: best.url,
    mimeType: best.mimeType,
    bitrate: best.bitrate || 0,
    approxBytes: best.approxBytes || 0,
    duration: best.duration || 0,
    title: best.title || "",
    author: best.author || "",
    thumbnail: best.thumbnail || "",
    userAgent: best.userAgent || ANDROID_CLIENT.userAgent,
    expiresIn: 6 * 60 * 60,
  };
}

/** Pick a filesystem-safe name for a downloaded track. */
function audioFilename(info) {
  const safe = (value) =>
    String(value || "")
      .replace(/[\\/:*?"<>|]+/g, "")
      .replace(/\s+/g, " ")
      .trim()
      .slice(0, 80);
  const name = `${safe(info.author) ? `${safe(info.author)} - ` : ""}${safe(info.title) || info.videoId}`;
  return `${name.replace(/[^\w\- .()[\]]/g, "_")}.m4a`;
}

/**
 * Proxy the audio bytes through the gateway.
 *
 * Same-origin bytes mean the browser can decode them, cache them for
 * offline use and — most importantly — route them through the Web Audio API
 * so the equaliser, crossfade and spatial controls actually work.
 */
async function proxyAudio(req, res, info, { download = false } = {}) {
  // YouTube's signed CDN URLs only answer to an *explicit, bounded* range:
  // "bytes=0-" or no range at all comes back as 403. So we always send one
  // and translate the client's intent ourselves.
  const total = info.approxBytes || 0;
  const requested = req.headers.range || "";
  const openEnded = /^bytes=(\d*)-$/.exec(requested);
  const clientWantsRange = Boolean(requested) && !openEnded;

  const headers = { "user-agent": info.userAgent || ANDROID_CLIENT.userAgent };
  if (openEnded) {
    const start = Number(openEnded[1] || 0);
    headers.range = total
      ? `bytes=${start}-${Math.max(start, total - 1)}`
      : `bytes=${start}-${start + 32 * 1024 * 1024 - 1}`;
  } else if (requested) {
    headers.range = requested;
  } else {
    headers.range = total
      ? `bytes=0-${total - 1}`
      : "bytes=0-33554431";
  }

  const upstream = await fetch(info.url, {
    headers,
    signal: AbortSignal.timeout(120_000),
  });

  if (!upstream.ok && upstream.status !== 206) {
    throw new Error(`Audio stream -> HTTP ${upstream.status}`);
  }

  const responseHeaders = {
    "content-type": info.mimeType,
    "accept-ranges": "bytes",
    "cache-control": "private, max-age=3600",
    ...CORS,
  };

  // We always ask YouTube for a bounded range. If the client did not ask
  // for a range, we serve the whole track as a normal 200 response.
  const upstreamRange = upstream.headers.get("content-range") || "";
  if (clientWantsRange && upstream.status === 206) {
    responseHeaders["content-range"] = upstreamRange;
  }
  if (upstream.headers.get("content-length")) {
    responseHeaders["content-length"] = upstream.headers.get("content-length");
  } else if (total) {
    responseHeaders["content-length"] = String(total);
  }
  if (download) {
    responseHeaders["content-disposition"] = `attachment; filename="${audioFilename(info)}"`;
  }

  const status = clientWantsRange && upstream.status === 206 ? 206 : 200;
  res.writeHead(status, responseHeaders);
  if (!upstream.body) return res.end();
  const reader = upstream.body.getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!res.write(Buffer.from(value))) {
        await new Promise((resolve) => res.once("drain", resolve));
      }
    }
  } catch {
    /* the client went away — nothing to do */
  }
  res.end();
}

// -------------------------------------------------------------------- static ---

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".wasm": "application/wasm",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
  ".ttf": "font/ttf",
  ".otf": "font/otf",
  ".ico": "image/x-icon",
  ".map": "application/json; charset=utf-8",
};

const CORS = {
  "access-control-allow-headers": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};

/** Origins that may read the API with cookies (the Wasm dev server). */
const DEV_ORIGINS = new Set([
  "http://localhost:8080",
  "http://127.0.0.1:8080",
]);

/**
 * Echo the caller's origin only when it is us.
 *
 * `access-control-allow-origin: *` cannot be combined with credentials, and
 * reflecting every origin would let any site read the signed-in account. The
 * dev server is the one cross-origin caller we care about.
 */
function applyCors(req, res, selfOrigin) {
  const origin = req.headers.origin;
  if (!origin) return;
  if (!DEV_ORIGINS.has(origin) && origin !== selfOrigin) return;
  res.setHeader("access-control-allow-origin", origin);
  res.setHeader("access-control-allow-credentials", "true");
  res.setHeader("vary", "origin");
}

function sendJson(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    ...CORS,
  });
  res.end(body);
}

const MISSING_BUILD = `<!doctype html>
<html><body style="font:15px/1.6 system-ui;background:#07100c;color:#e7f5ec;padding:48px">
<h1 style="color:#6ee7a8">JusPlayer web build not found</h1>
<p>The gateway is running, but there is no compiled Wasm app in<br><code>web/build/dist/wasmJs/productionExecutable</code>.</p>
<p>Build it first:</p>
<pre style="background:#0f1a14;padding:16px;border-radius:12px">./gradlew :web:wasmJsBrowserDistribution</pre>
<p>Then reload this page. For hot-reload development run
<code>./gradlew :web:wasmJsBrowserDevelopmentRun</code> and open port 8080.</p>
</body></html>`;

async function serveStatic(req, res, urlPath) {
  let rel = decodeURIComponent(urlPath === "/" ? "/index.html" : urlPath);
  const full = path.normalize(path.join(DIST, rel));
  if (!full.startsWith(DIST)) {
    res.writeHead(403).end("forbidden");
    return;
  }
  try {
    const info = await stat(full);
    if (info.isDirectory()) return serveStatic(req, res, path.posix.join(rel, "index.html"));
    res.writeHead(200, {
      "content-type": MIME[path.extname(full)] || "application/octet-stream",
      "cache-control": rel.endsWith(".wasm") ? "no-cache" : "public, max-age=60",
    });
    createReadStream(full).pipe(res);
  } catch {
    // SPA fallback
    try {
      await readFile(path.join(DIST, "index.html"));
      res.writeHead(200, { "content-type": MIME[".html"] });
      createReadStream(path.join(DIST, "index.html")).pipe(res);
    } catch {
      res.writeHead(404, { "content-type": MIME[".html"] });
      res.end(MISSING_BUILD);
    }
  }
}

// -------------------------------------------------------------------- server ---

async function readBody(req, limit = 64 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new Error("request body too large");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

function redirect(res, location, headers = {}) {
  res.writeHead(302, { location, ...headers });
  res.end();
}

/**
 * The public origin of the request.
 *
 * Behind Vercel (or any proxy) the socket protocol is plain http, so the
 * real scheme and host arrive in `x-forwarded-proto` / `x-forwarded-host`.
 * Google matches the OAuth redirect URI byte for byte, so this has to be
 * right or the sign-in fails with "redirect_uri_mismatch".
 */
function requestOrigin(req, url) {
  const first = (value) => String(value || "").split(",")[0].trim();
  const proto = first(req.headers["x-forwarded-proto"]) || url.protocol.replace(":", "");
  const host = first(req.headers["x-forwarded-host"]) || url.host;
  return `${proto}://${host}`;
}

/**
 * The one entry point used by both the local Node server and the Vercel
 * function. `options.static` turns the built Wasm app on; on Vercel the files
 * are served straight from `public/` instead.
 */
export async function handleRequest(req, res, options = {}) {
  const withStatic = options.static !== false;
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const origin = requestOrigin(req, url);
  applyCors(req, res, origin);
  const isSecure = origin.startsWith("https:");
  const redirectUri = process.env.YT_REDIRECT_URI || `${origin}/api/auth/callback`;

  if (req.method === "OPTIONS") {
    res.writeHead(204, CORS).end();
    return;
  }

  try {
    if (url.pathname === "/api/health") {
      return sendJson(res, 200, {
        ok: true,
        service: "jusplayer-web",
        uptime: process.uptime(),
        oauth: oauthConfigured(),
        // Whole-track playback/downloads need yt-dlp; without it YouTube
        // caps each CDN URL at about one megabyte.
        wholeTracks: ytDlpAvailable(),
      });
    }

    // Keep OAuth credentials fresh before anything reads the session.
    const skipRefresh =
      url.pathname === "/api/auth/login" ||
      url.pathname === "/api/auth/callback" ||
      url.pathname === "/api/auth/logout";
    const session = skipRefresh ? readSession(req) : await prepareSession(req, res, isSecure);

    // ------------------------------------------------------------- account ---

    if (url.pathname === "/api/auth/login") {
      if (!oauthConfigured()) {
        return sendJson(res, 501, {
          error: "Google sign-in is not configured on this deployment.",
          hint: "Set YT_CLIENT_ID and YT_CLIENT_SECRET, or sign in with a YouTube cookie instead.",
        });
      }
      const state = oauthState();
      res.setHeader(
        "set-cookie",
        sessionCookieHeader(state, { secure: isSecure, maxAge: 10 * 60 * 1000 }),
      );
      return redirect(res, oauthUrl({ clientId: process.env.YT_CLIENT_ID, redirectUri, state }));
    }

    if (url.pathname === "/api/auth/callback") {
      // Google reports consent problems (unverified app, blocked account,
      // missing test user) as query parameters — pass them through instead of
      // pretending the user closed the window.
      const denied = url.searchParams.get("error");
      if (denied) {
        const reason = url.searchParams.get("error_description") || "";
        console.warn(`[auth] google rejected sign-in: ${denied} ${reason}`);
        return redirect(
          res,
          `/?signin=${encodeURIComponent(denied)}&reason=${encodeURIComponent(reason)}`,
        );
      }

      const code = url.searchParams.get("code");
      const state = url.searchParams.get("state");
      const cookies = parseCookies(req.headers.cookie || "");
      if (!code) return redirect(res, "/?signin=denied");
      if (!state || cookies[SESSION_COOKIE] !== state) {
        return redirect(res, "/?signin=bad_state");
      }

      let stage = "token exchange";
      try {
        const tokens = await exchangeCode({
          code,
          clientId: process.env.YT_CLIENT_ID,
          clientSecret: process.env.YT_CLIENT_SECRET,
          redirectUri,
        });

        // YouTube *may* hand back its own cookies for this token. It is a
        // nice-to-have: when it fails the Bearer token is used instead.
        stage = "YouTube session";
        let cookie = "";
        try {
          cookie = await youtubeCookieFromToken(tokens.access_token);
        } catch (err) {
          console.warn("[auth] no YouTube cookies, using the OAuth token:", err.message);
        }

        stage = "profile";
        const profile = await fetchUserInfo(tokens.access_token);

        const user = {
          yt: cookie || "",
          oauth: {
            accessToken: tokens.access_token,
            refreshToken: tokens.refresh_token || "",
            expiresAt: Date.now() + (Number(tokens.expires_in) || 3600) * 1000,
          },
          name: profile?.name || "",
          email: profile?.email || "",
          avatar: profile?.avatar || "",
        };
        res.setHeader("set-cookie", sessionCookieHeader(createSession(cookie, user, SECRET), { secure: isSecure }));
        return redirect(res, "/?signin=ok");
      } catch (err) {
        console.warn(`[auth] oauth ${stage} failed:`, err.message);
        return redirect(
          res,
          `/?signin=error&stage=${encodeURIComponent(stage)}&reason=${encodeURIComponent(err.message)}`,
        );
      }
    }

    if (url.pathname === "/api/auth/logout") {
      res.setHeader("set-cookie", clearSessionCookieHeader({ secure: isSecure }));
      if (url.searchParams.get("json") === "1") {
        return sendJson(res, 200, { signedIn: false });
      }
      return redirect(res, "/?signin=out");
    }

    if (url.pathname === "/api/auth/session" || url.pathname === "/api/account") {
      const auth = sessionCredential(session);
      const info = await accountInfo(auth, session?.name || session?.email ? session : null);
      return sendJson(res, 200, {
        ...info,
        method: bearerToken(auth) ? "google" : session?.yt ? "cookie" : null,
        oauthAvailable: oauthConfigured(),
      });
    }

    // ---------------------------------------------------------------- audio ---

    // The direct stream URL — used by the desktop app and the TUI to play
    // audio natively, without a browser in the loop.
    if (url.pathname === "/api/stream") {
      const videoId = url.searchParams.get("videoId") || "";
      if (!videoId) return sendJson(res, 400, { error: "missing videoId" });
      const auth = sessionCredential(session);
      const value = await cached(`stream:${videoId}`, 30 * 60_000, () => resolveAudio(videoId, auth));
      return sendJson(res, 200, {
        ...value,
        // A signed, short-lived URL so external players (mpv, VLC) can pull
        // the bytes through the gateway without needing the session cookie.
        proxyUrl: `/api/audio?token=${encodeURIComponent(streamToken(videoId, SECRET))}`,
        // For players that can resolve YouTube themselves (mpv + yt-dlp).
        watchUrl: `https://www.youtube.com/watch?v=${encodeURIComponent(videoId)}`,
      });
    }

    // The audio bytes themselves, proxied same-origin so the browser can
    // decode, cache and process them.
    if (url.pathname === "/api/audio" || url.pathname === "/api/download") {
      const token = url.searchParams.get("token") || "";
      const tokenVideoId = token ? verifyStreamToken(token, SECRET) : null;
      if (token && !tokenVideoId) {
        return sendJson(res, 401, { error: "this stream link has expired" });
      }
      const videoId = tokenVideoId || url.searchParams.get("videoId") || "";
      if (!videoId) return sendJson(res, 400, { error: "missing videoId" });
      const auth = sessionCredential(session);
      const info = await cached(`stream:${videoId}`, 30 * 60_000, () => resolveAudio(videoId, auth));
      await proxyAudio(req, res, info, { download: url.pathname === "/api/download" });
      return;
    }

    if (url.pathname === "/api/auth/cookie" && req.method === "POST") {
      let payload = {};
      try {
        payload = JSON.parse((await readBody(req)) || "{}");
      } catch {
        return sendJson(res, 400, { error: "invalid JSON body" });
      }
      const cookie = normaliseYtCookie(String(payload.cookie || ""));
      if (!cookie) return sendJson(res, 400, { error: "no YouTube cookies found in that value" });
      const valid = await validateCookie(cookie);
      if (!valid) {
        return sendJson(res, 401, { error: "Those cookies were not accepted by YouTube Music." });
      }
      const session = createSession(cookie, {}, SECRET);
      res.setHeader(
        "set-cookie",
        sessionCookieHeader(session, { secure: isSecure }),
      );
      const info = await accountInfo(cookie);
      return sendJson(res, 200, { ...info, method: "cookie" });
    }

    // ------------------------------------------------------ personalised ---

    const auth = sessionCredential(session);

    if (url.pathname === "/api/liked") {
      if (!auth) return sendJson(res, 401, { error: "sign in to see your liked songs" });
      const value = await cached(`liked:${userKey(auth)}`, 2 * 60_000, () => likedSongs(auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/library") {
      if (!auth) return sendJson(res, 401, { error: "sign in to see your library" });
      const value = await cached(`lib:${userKey(auth)}`, 5 * 60_000, () => accountLibrary(auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/history") {
      if (!auth) return sendJson(res, 401, { error: "sign in to see your YouTube history" });
      const value = await cached(`hist:${userKey(auth)}`, 2 * 60_000, () => accountHistory(auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/img") {
      const image = await proxyImage(url.searchParams.get("u") || "");
      if (!image) return sendJson(res, 404, { error: "image not found" });
      res.writeHead(200, {
        "content-type": image.contentType,
        "cache-control": "public, max-age=86400",
        ...CORS,
      });
      return res.end(image.body);
    }

    if (url.pathname === "/api/search") {
      const q = (url.searchParams.get("q") || "").trim();
      if (!q) return sendJson(res, 400, { error: "missing q" });
      const filter = url.searchParams.get("filter") || "all";
      const value = await cached(`s:${userKey(auth)}:${filter}:${q}`, 5 * 60_000, () => search(q, filter, auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/home") {
      const value = await cached(`home:${userKey(auth)}`, 10 * 60_000, () => home(auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/collection") {
      const id = url.searchParams.get("id") || "";
      const value = await cached(`c:${userKey(auth)}:${id}`, 5 * 60_000, () => collection(id, auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/related") {
      const id = url.searchParams.get("videoId") || "";
      const value = await cached(`r:${userKey(auth)}:${id}`, 5 * 60_000, () => related(id, auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/album" || url.pathname === "/api/artist") {
      const id = url.searchParams.get("id") || "";
      if (!id) return sendJson(res, 400, { error: "missing id" });
      const kind = url.pathname === "/api/album" ? "album" : "artist";
      const value = await cached(`p:${userKey(auth)}:${kind}:${id}`, 10 * 60_000, () => pageOf(id, kind, auth));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/playlist") {
      const id = url.searchParams.get("id") || "";
      if (!id) return sendJson(res, 400, { error: "missing id" });
      const value = await cached(`pl:${userKey(auth)}:${id}`, 5 * 60_000, async () => {
        const page = await pageOf(id, "playlist", auth);
        if (page.sections.some((s) => s.items.length)) return page;
        return collection(id, auth);
      });
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/moods") {
      const value = await cached("moods", 30 * 60_000, moods);
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/mood") {
      const params = url.searchParams.get("params") || "";
      if (!params) return sendJson(res, 400, { error: "missing params" });
      const title = url.searchParams.get("title") || "Mood";
      const value = await cached(`m:${params}`, 15 * 60_000, () => mood(params, title));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/charts") {
      const value = await cached("charts", 30 * 60_000, () => hub("FEmusic_charts", "Charts"));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/new") {
      const value = await cached("new", 30 * 60_000, () => hub("FEmusic_new_releases", "New releases"));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/suggest") {
      const q = (url.searchParams.get("q") || "").trim();
      if (!q) return sendJson(res, 200, { suggestions: [] });
      const value = await cached(`sg:${q}`, 10 * 60_000, () => suggestions(q));
      return sendJson(res, 200, value);
    }

    if (url.pathname === "/api/lyrics") {
      const id = url.searchParams.get("videoId") || "";
      if (!id) return sendJson(res, 400, { error: "missing videoId" });
      const title = url.searchParams.get("title") || "";
      const artist = url.searchParams.get("artist") || "";
      const duration = url.searchParams.get("duration") || "";
      const album = url.searchParams.get("album") || "";
      const value = await cached(
        `ly:${id}:${title}:${artist}:${duration}`,
        60 * 60_000,
        () => lyrics(id, title, artist, duration, album),
      );
      return sendJson(res, 200, value);
    }

    if (url.pathname.startsWith("/api/")) {
      return sendJson(res, 404, { error: "unknown endpoint" });
    }

    if (!withStatic) {
      res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
      return res.end("not found");
    }
    return serveStatic(req, res, url.pathname);
  } catch (err) {
    console.error(`[${req.method} ${url.pathname}]`, err);
    if (res.headersSent) return res.end();
    if (err.status === 401 || err.status === 403) {
      return sendJson(res, 401, {
        error: "YouTube rejected this session. Sign in again to refresh it.",
        code: "auth",
        status: err.status,
      });
    }
    sendJson(res, 500, { error: err.message || "internal error" });
  }
}
