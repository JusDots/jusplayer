/**
 * Gateway client for the JusPlayer TUI.
 *
 * The TUI talks to exactly the same HTTP gateway as the web and desktop
 * apps, so anything you can do in the browser you can do here.
 */

import { readFile, writeFile, mkdir } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

const LOCAL_BASE = "http://127.0.0.1:8787";
const LIVE_BASE = "https://jusplayer.vercel.app";
const DEFAULT_BASE = process.env.JUSPLAYER_API || LOCAL_BASE;

/** Is a gateway answering on this address? */
export async function gatewayAlive(base, timeoutMs = 1500) {
  try {
    const response = await fetch(`${base}/api/health`, {
      signal: AbortSignal.timeout(timeoutMs),
    });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Pick a gateway.
 *
 * A gateway running on this machine is preferred — it is faster and can
 * resolve whole tracks. Otherwise the hosted one is used, so the client
 * works out of the box with no setup.
 */
export async function resolveBase() {
  if (process.env.JUSPLAYER_API) return process.env.JUSPLAYER_API;
  if (await gatewayAlive(LOCAL_BASE)) return LOCAL_BASE;
  return LIVE_BASE;
}

/** Config lives next to the user's other dotfiles. */
export async function configPath() {
  if (process.env.JUSPLAYER_CONFIG) return process.env.JUSPLAYER_CONFIG;
  const home = os.homedir();
  if (process.platform === "win32") {
    return path.join(process.env.APPDATA || path.join(home, "AppData", "Roaming"), "jusplayer", "config.json");
  }
  const xdg = process.env.XDG_CONFIG_HOME || path.join(home, ".config");
  return path.join(xdg, "jusplayer", "config.json");
}

export async function loadConfig() {
  const config = {
    base: DEFAULT_BASE,
    discordClientId: "",
    player: "auto",
    musicDir: path.join(os.homedir(), "Music", "JusPlayer"),
    session: "",
  };
  let savedBase = "";
  try {
    const raw = await readFile(await configPath(), "utf8");
    Object.assign(config, JSON.parse(raw));
    savedBase = String(config.base || "");
  } catch {
    /* no config yet — the defaults are fine */
  }
  if (process.env.JUSPLAYER_API) {
    config.base = process.env.JUSPLAYER_API;
  } else if (!savedBase) {
    // Nothing was chosen by hand: use a local gateway when there is one,
    // otherwise the hosted one so the client works straight away.
    config.base = await resolveBase();
  }
  if (process.env.JUSPLAYER_DISCORD_CLIENT_ID) config.discordClientId = process.env.JUSPLAYER_DISCORD_CLIENT_ID;
  return config;
}

/** Write the configuration back to disk (sessions, preferences). */
export async function saveConfig(config) {
  const target = await configPath();
  await mkdir(path.dirname(target), { recursive: true });
  const clean = { ...config };
  delete clean.base; // the base is chosen automatically, not persisted
  await writeFile(target, `${JSON.stringify(clean, null, 2)}\n`, "utf8");
}

export class Gateway {
  constructor(base) {
    this.base = String(base || DEFAULT_BASE).replace(/\/+$/, "");
    this.cookie = null;
  }

  /** Reuse the signed session cookie across calls, like a browser would. */
  _headers(extra = {}) {
    const headers = { ...extra };
    if (this.cookie) headers.cookie = this.cookie;
    return headers;
  }

  async _request(pathname, { method = "GET", body = null, raw = false } = {}) {
    const response = await fetch(`${this.base}${pathname}`, {
      method,
      headers: this._headers({
        ...(body ? { "content-type": "application/json" } : {}),
      }),
      body: body ? JSON.stringify(body) : undefined,
      redirect: "manual",
    });

    const setCookie = response.headers.getSetCookie?.() || [];
    for (const line of setCookie) {
      const pair = line.split(";")[0];
      if (pair.startsWith("jus_session=")) {
        this.cookie = pair.endsWith("=") ? null : pair;
      }
    }

    if (raw) {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return response;
    }

    const text = await response.text();
    let data = {};
    try {
      data = text ? JSON.parse(text) : {};
    } catch {
      data = { error: text.slice(0, 200) };
    }
    if (!response.ok) {
      const error = new Error(data.error || `HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return data;
  }

  health() {
    return this._request("/api/health");
  }

  home() {
    return this._request("/api/home");
  }

  search(query, filter = "all") {
    const params = new URLSearchParams({ q: query });
    if (filter && filter !== "all") params.set("filter", filter);
    return this._request(`/api/search?${params}`);
  }

  suggest(query) {
    return this._request(`/api/suggest?q=${encodeURIComponent(query)}`);
  }

  collection(browseId) {
    return this._request(`/api/collection?id=${encodeURIComponent(browseId)}`);
  }

  playlist(browseId) {
    return this._request(`/api/playlist?id=${encodeURIComponent(browseId)}`);
  }

  album(browseId) {
    return this._request(`/api/album?id=${encodeURIComponent(browseId)}`);
  }

  artist(browseId) {
    return this._request(`/api/artist?id=${encodeURIComponent(browseId)}`);
  }

  related(videoId) {
    return this._request(`/api/related?videoId=${encodeURIComponent(videoId)}`);
  }

  moods() {
    return this._request("/api/moods");
  }

  charts() {
    return this._request("/api/charts");
  }

  newReleases() {
    return this._request("/api/new");
  }

  lyrics(videoId, title = "", artist = "", duration = 0) {
    const params = new URLSearchParams({ videoId });
    if (title) params.set("title", title);
    if (artist) params.set("artist", artist);
    if (duration > 0) params.set("duration", String(duration));
    return this._request(`/api/lyrics?${params}`);
  }

  account() {
    return this._request("/api/account");
  }

  liked() {
    return this._request("/api/liked");
  }

  library() {
    return this._request("/api/library");
  }

  history() {
    return this._request("/api/history");
  }

  /** The direct, unciphered audio URL for a track. */
  stream(videoId) {
    return this._request(`/api/stream?videoId=${encodeURIComponent(videoId)}`);
  }

  /** Sign in with a pasted YouTube cookie. */
  signInWithCookie(cookie) {
    return this._request("/api/auth/cookie", { method: "POST", body: { cookie } });
  }

  logout() {
    return this._request("/api/auth/logout?json=1");
  }

  /** Download a track to disk through the gateway. */
  async download(videoId, destination) {
    const response = await this._request(`/api/download?videoId=${encodeURIComponent(videoId)}`, { raw: true });
    const { writeFile } = await import("node:fs/promises");
    const buffer = Buffer.from(await response.arrayBuffer());
    await writeFile(destination, buffer);
    return buffer.length;
  }
}
