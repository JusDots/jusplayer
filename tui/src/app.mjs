/**
 * The JusPlayer terminal client.
 *
 * Views: home · search · library · queue · lyrics · help
 *
 * Everything is rendered into the alternate screen buffer with plain ANSI
 * escapes, and the app is a single state machine fed by raw keypresses.
 */

import { Gateway, loadConfig } from "./api.mjs";
import { DiscordPresence } from "./discord.mjs";
import { Player, ensureMusicDir } from "./player.mjs";
import {
  ansi,
  bar,
  box,
  centre,
  color,
  formatTime,
  meter,
  pad,
  rule,
  truncate,
  width,
} from "./ui.mjs";

const VIEWS = ["home", "search", "library", "queue", "lyrics", "help"];

const KEYS = {
  home: { key: "h", label: "home" },
  search: { key: "/", label: "search" },
  library: { key: "l", label: "library" },
  queue: { key: "q", label: "queue" },
  lyrics: { key: "y", label: "lyrics" },
  help: { key: "?", label: "help" },
};

export class App {
  constructor({ gateway, config }) {
    this.gateway = gateway;
    this.config = config;

    this.view = "home";
    this.message = "";
    this.messageKind = "info";
    this.rows = [];
    this.rowCursor = 0;
    this.scroll = 0;
    this.status = "starting…";
    this.account = null;
    this.busy = false;

    this.query = "";
    this.searchResults = [];

    this.queue = [];
    this.index = -1;

    this.lyrics = { lines: [], title: "", plain: "" };
    this.lyricIndex = -1;

    this.player = new Player({
      backend: config.player,
      onEnded: () => this.onTrackEnded(),
      onState: (state) => this.onPlayerState(state),
    });

    this.discord = new DiscordPresence({
      clientId: config.discordClientId,
      logger: (message) => this.log(`discord: ${message}`),
    });

    this.renderedLines = 0;
    this.size = { rows: process.stdout.rows || 30, cols: process.stdout.columns || 100 };
  }

  // ------------------------------------------------------------- lifecycle --

  async start() {
    process.stdout.write(ansi.altScreen + ansi.hideCursor + ansi.clear);
    process.stdin.setRawMode?.(true);
    process.stdin.resume();
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (data) => this.onKey(data));

    process.stdout.on("resize", () => {
      this.size = { rows: process.stdout.rows || 30, cols: process.stdout.columns || 100 };
      this.render();
    });

    this.log(`player backend: ${this.player.backend}`);
    if (this.player.backend === "none") {
      this.setMessage("No audio backend found (install mpv or ffmpeg). Browsing still works.", "warn");
    }

    await this.refreshAccount();
    await this.loadHome();

    if (this.config.discordClientId) {
      await this.discord.connect();
    }

    this.timer = setInterval(() => {
      this.player.poll();
      this.updateLyricCursor();
      this.render();
    }, 500);

    this.render();
  }

  async stop() {
    clearInterval(this.timer);
    await this.player.stop();
    this.discord.close();
    process.stdout.write(ansi.mainScreen + ansi.showCursor);
    process.exit(0);
  }

  log(message) {
    if (process.env.JUSPLAYER_DEBUG) console.error(`[jusplayer] ${message}`);
  }

  setMessage(message, kind = "info") {
    this.message = message;
    this.messageKind = kind;
  }

  // ------------------------------------------------------------------ data --

  async refreshAccount() {
    try {
      this.account = await this.gateway.account();
      this.status = this.account.signedIn
        ? `signed in as ${this.account.name || this.account.email || "YouTube Music"}`
        : "not signed in — anonymous mode";
    } catch (error) {
      this.account = null;
      this.status = `gateway unreachable: ${error.message}`;
    }
  }

  async loadHome() {
    this.busy = true;
    this.render();
    try {
      const feed = await this.gateway.home();
      this.rows = flatten(feed);
      this.rowCursor = 0;
      this.scroll = 0;
      this.setMessage(`${this.rows.length} items`);
    } catch (error) {
      this.setMessage(`Could not load the home feed: ${error.message}`, "error");
      this.rows = [];
    }
    this.busy = false;
    this.render();
  }

  async runSearch(query) {
    this.busy = true;
    this.render();
    try {
      const feed = await this.gateway.search(query);
      this.searchResults = flatten(feed);
      this.rows = this.searchResults;
      this.rowCursor = 0;
      this.scroll = 0;
      this.setMessage(`${this.searchResults.length} results for “${query}”`);
    } catch (error) {
      this.setMessage(`Search failed: ${error.message}`, "error");
    }
    this.busy = false;
    this.render();
  }

  async loadLibrary() {
    this.busy = true;
    this.render();
    const rows = [];
    try {
      const liked = await this.gateway.liked();
      rows.push(...flatten(liked));
    } catch {
      /* signed out */
    }
    try {
      const library = await this.gateway.library();
      rows.push(...flatten(library));
    } catch {
      /* signed out */
    }
    if (!rows.length) {
      try {
        const history = await this.gateway.history();
        rows.push(...flatten(history));
      } catch {
        /* signed out */
      }
    }
    this.rows = rows;
    this.rowCursor = 0;
    this.scroll = 0;
    this.setMessage(
      rows.length ? `${rows.length} items` : "Sign in to see your YouTube library (Settings → Account)",
      rows.length ? "info" : "warn",
    );
    this.busy = false;
    this.render();
  }

  // --------------------------------------------------------------- playback --

  get current() {
    return this.index >= 0 ? this.queue[this.index] : null;
  }

  async playRow(rowIndex) {
    const item = this.rows[rowIndex];
    if (!item || !item.videoId) {
      this.setMessage("That is not a playable track.", "warn");
      this.render();
      return;
    }
    this.queue = this.rows.filter((row) => row.videoId);
    const playableIndex = this.queue.findIndex((row) => row.videoId === item.videoId);
    this.index = playableIndex;
    this.log(
      `play row=${rowIndex} title="${item.title}" videoId=${item.videoId} queue=${this.queue.length} index=${this.index}`,
    );
    await this.playCurrent();
  }

  async playCurrent() {
    const item = this.current;
    if (!item) return;
    this.setMessage(`Loading “${item.title}”…`);
    this.render();
    try {
      const info = await this.gateway.stream(item.videoId);
      const proxyUrl = info.proxyUrl
        ? `${this.gateway.base}${info.proxyUrl}`
        : "";
      await this.player.play(info.url, {
        title: item.title,
        artist: item.subtitle || info.author,
        videoId: item.videoId,
        proxyUrl,
        watchUrl: info.watchUrl || "",
      });
      this.setMessage(`Playing “${item.title}”`);
      await this.loadLyrics(item);
      this.updateDiscord();
    } catch (error) {
      this.setMessage(`Could not play that track: ${error.message}`, "error");
    }
    this.render();
  }

  async loadLyrics(item) {
    this.lyrics = { lines: [], title: item.title, plain: "" };
    this.lyricIndex = -1;
    try {
      const response = await this.gateway.lyrics(
        item.videoId,
        item.title,
        item.subtitle,
        item.durationSeconds || 0,
      );
      const raw = response.syncedLyrics || response.lyrics || "";
      this.lyrics = { lines: parseLrc(raw), title: item.title, plain: raw };
    } catch {
      /* lyrics are optional */
    }
    this.render();
  }

  updateLyricCursor() {
    if (!this.lyrics.lines.length) return;
    const position = this.player.state.position;
    let index = -1;
    for (let i = 0; i < this.lyrics.lines.length; i += 1) {
      if (this.lyrics.lines[i].time <= position + 0.2) index = i;
      else break;
    }
    this.lyricIndex = index;
  }

  onTrackEnded() {
    this.next();
  }

  onPlayerState(state) {
    if (!state.playing) return;
    this.updateDiscord();
  }

  updateDiscord() {
    if (!this.discord.enabled) return;
    const item = this.current;
    const state = this.player.state;
    this.discord.setActivity({
      details: item?.title || "JusPlayer",
      state: item?.subtitle || "YouTube Music",
      largeText: item?.title || "JusPlayer",
      smallText: "JusPlayer",
      start: state.playing ? Date.now() - state.position * 1000 : 0,
    });
  }

  next() {
    if (this.index < 0 || this.queue.length === 0) return;
    this.index = (this.index + 1) % this.queue.length;
    this.playCurrent();
  }

  previous() {
    if (this.index < 0 || this.queue.length === 0) return;
    // Like every other player: restart the track unless we are near the end.
    if (this.player.state.position > 3) {
      this.player.seek(0);
      return;
    }
    this.index = (this.index - 1 + this.queue.length) % this.queue.length;
    this.playCurrent();
  }

  async downloadCurrent() {
    const item = this.current;
    if (!item) {
      this.setMessage("Nothing is playing.", "warn");
      this.render();
      return;
    }
    try {
      const dir = ensureMusicDir(this.config.musicDir);
      const filename = `${(item.subtitle || "track").replace(/[\\/:*?"<>|]+/g, "").slice(0, 40)} - ${item.title.replace(/[\\/:*?"<>|]+/g, "").slice(0, 60)}.m4a`;
      const target = `${dir}/${filename}`;
      const bytes = await this.gateway.download(item.videoId, target);
      this.setMessage(`Downloaded ${filename} (${Math.round(bytes / 1024)} KB)`);
    } catch (error) {
      this.setMessage(`Download failed: ${error.message}`, "error");
    }
    this.render();
  }

  // ------------------------------------------------------------------- keys --

  onKey(data) {
    // A paste (or tmux send-keys) can deliver several characters in one
    // chunk, while arrow keys arrive as a three byte escape sequence.
    let index = 0;
    while (index < data.length) {
      let chunk;
      if (data[index] === "\x1b" && data[index + 1] === "[" && data.length - index >= 3) {
        chunk = data.slice(index, index + 3);
        index += 3;
      } else {
        chunk = data[index];
        index += 1;
      }
      this.handleKey(chunk);
    }
    this.render();
  }

  handleKey(raw) {
    const key = raw.length === 1 ? raw : decodeSpecial(raw);
    const number = /^[0-9]$/.test(key) ? Number(key) : -1;

    if (this.view === "search" && this.searching) {
      if (key === "\r") {
        this.searching = false;
        const query = this.query;
        if (query.trim()) this.runSearch(query.trim());
        return;
      }
      if (key === "\x1b") {
        this.searching = false;
        this.view = "home";
        this.rows = this.homeRows || [];
        this.render();
        return;
      }
      if (key === "\x7f") {
        this.query = this.query.slice(0, -1);
        return;
      }
      if (key.length === 1 && key >= " ") {
        this.query += key;
        return;
      }
      return;
    }

    switch (key) {
      case "q":
      case "\x03":
        this.stop();
        return;
      case " ":
        this.player.toggle();
        return;
      case "n":
        this.next();
        return;
      case "p":
        this.previous();
        return;
      case "j":
      case "\x1b[B":
        this.moveCursor(1);
        return;
      case "k":
      case "\x1b[A":
        this.moveCursor(-1);
        return;
      case "\r":
        this.playRow(this.rowCursor);
        return;
      case "g":
        this.moveCursor(-this.rows.length);
        return;
      case "G":
        this.moveCursor(this.rows.length);
        return;
      case "s":
        this.player.seek(this.player.state.position - 10);
        return;
      case "d":
        this.player.seek(this.player.state.position + 10);
        return;
      case "e":
        this.player.seek(0);
        return;
      case "m":
        this.player.setVolume(this.player.state.volume > 0 ? 0 : 0.8);
        return;
      case "+":
        this.player.setVolume(this.player.state.volume + 0.1);
        return;
      case "-":
        this.player.setVolume(this.player.state.volume - 0.1);
        return;
      case "w":
        this.downloadCurrent();
        return;
      case "/":
        this.view = "search";
        this.searching = true;
        this.query = "";
        this.setMessage("Type a search and press Enter (Esc to cancel)");
        this.render();
        return;
      case "r":
        this.reload();
        return;
      case "y":
        this.view = "lyrics";
        this.render();
        return;
      case "?":
        this.view = this.view === "help" ? "home" : "help";
        this.render();
        return;
      case "\x1b":
        if (this.view !== "home") {
          this.view = "home";
          this.rows = this.homeRows || [];
          this.render();
        }
        return;
      default:
        break;
    }

    if (number >= 0 && number <= 9) {
      this.playRow(number - 1);
      return;
    }

    // view shortcuts
    for (const [name, meta] of Object.entries(KEYS)) {
      if (meta.key === key) {
        this.switchView(name);
        return;
      }
    }
  }

  async switchView(name) {
    this.view = name;
    if (name === "home") {
      this.rows = this.homeRows || [];
      await this.loadHome();
    } else if (name === "library") {
      await this.loadLibrary();
    } else if (name === "queue") {
      this.rows = this.queue;
      this.rowCursor = Math.max(0, this.index);
    } else if (name === "search") {
      this.rows = this.searchResults;
      if (!this.rows.length) {
        this.searching = true;
        this.query = "";
        this.setMessage("Type a search and press Enter (Esc to cancel)");
      }
    } else {
      this.render();
    }
  }

  async reload() {
    if (this.view === "home") await this.loadHome();
    else if (this.view === "library") await this.loadLibrary();
    else if (this.view === "search" && this.query) await this.runSearch(this.query);
    else this.setMessage("Nothing to refresh");
    this.render();
  }

  moveCursor(delta) {
    if (!this.rows.length) return;
    this.rowCursor = Math.max(0, Math.min(this.rows.length - 1, this.rowCursor + delta));
    this.render();
  }

  // ---------------------------------------------------------------- drawing --

  render() {
    const cols = this.size.cols;
    const rows = this.size.rows;
    const lines = [];

    const header = this.drawHeader(cols);
    const nowPlaying = this.drawNowPlaying(cols);
    lines.push(...header, "", ...nowPlaying);

    // header + blank + now playing + blank + body box + blank + footer
    const chrome = header.length + 1 + nowPlaying.length + 1 + 1 + 1;
    const boxHeight = Math.max(4, rows - chrome);
    const contentHeight = Math.max(2, boxHeight - 2);

    const body = this.drawBody(cols, contentHeight);
    lines.push(...body, "", ...this.drawFooter(cols));

    // Never write more rows than the terminal has — that would scroll the
    // header out of view.
    const overflow = lines.length - rows;
    if (overflow > 0) lines.splice(lines.length - overflow - 1, overflow);

    let output = ansi.home + ansi.clear;
    for (const line of lines) output += `${ansi.clearLine}${line}\n`;
    process.stdout.write(output);
    this.renderedLines = lines.length;
  }

  drawHeader(cols) {
    const title = `${color.accent}${ansi.bold}♪ JusPlayer${ansi.reset} ${color.muted}terminal${ansi.reset}`;
    const tabs = VIEWS.map((view) =>
      view === this.view
        ? `${color.accent}${ansi.bold}${view}${ansi.reset}`
        : `${color.muted}${view}${ansi.reset}`,
    ).join(color.muted + " │ " + ansi.reset);
    const account = this.account?.signedIn
      ? `${color.accent}●${ansi.reset} ${color.muted}${truncate(this.account.name || "signed in", 24)}${ansi.reset}`
      : `${color.muted}○ anonymous${ansi.reset}`;

    const left = `${title}  ${tabs}`;
    const gap = Math.max(1, cols - 1 - width(left) - width(account));
    return [truncate(left + " ".repeat(gap) + account, cols - 1)];
  }

  drawNowPlaying(cols) {
    const state = this.player.state;
    const item = this.current;
    const inner = cols - 4;

    const left = item
      ? `${color.accent}${ansi.bold}${truncate(item.title, inner - 30)}${ansi.reset}\n  ${color.muted}${truncate(item.subtitle || "", inner - 30)}${ansi.reset}`
      : `${color.muted}Nothing playing — press Enter on a track, or / to search${ansi.reset}`;

    const position = formatTime(state.position);
    const duration = formatTime(state.duration);
    const progress = state.duration > 0 ? state.position / state.duration : 0;

    const info = [
      left,
      "",
      `${bar(progress, Math.max(10, inner - 26))} ${color.muted}${position} / ${duration}${ansi.reset}`,
      `${color.muted}vol${ansi.reset} ${meter(state.volume, 8)} ${color.muted}${Math.round(state.volume * 100)}%${ansi.reset}   ${color.muted}backend${ansi.reset} ${color.text}${this.player.backend}${ansi.reset}${
        item && !state.playing ? `   ${color.warn}${ansi.bold}❚❚ paused${ansi.reset}` : ""
      }`,
    ];

    const lines = box(info, cols - 1, { title: "now playing" });
    if (this.message) {
      const tint = this.messageKind === "error" ? color.error : this.messageKind === "warn" ? color.warn : color.accent2;
      lines.splice(lines.length - 1, 0, `${color.muted}│${ansi.reset} ${tint}${truncate(this.message, inner)}${ansi.reset} ${color.muted}│${ansi.reset}`);
      lines[lines.length - 1] = color.muted + `└${"─".repeat(cols - 2)}┘` + ansi.reset;
    }
    return lines;
  }

  drawBody(cols, height) {
    if (this.view === "help") return this.drawHelp(cols, height);
    if (this.view === "lyrics") return this.drawLyrics(cols, height);
    if (this.view === "queue") return this.drawQueue(cols, height);
    return this.drawList(cols, height);
  }

  drawList(cols, height) {
    const inner = cols - 4;
    const lines = [];

    if (this.searching) {
      lines.push(
        `${color.accent2}search ›${ansi.reset} ${color.text}${this.query}${ansi.reset}${color.accent}▏${ansi.reset}`,
      );
    }

    if (this.busy) lines.push(`${color.muted}loading…${ansi.reset}`);
    if (!this.rows.length && !this.busy) {
      lines.push(`${color.muted}Nothing here yet.${ansi.reset}`);
    }

    const visible = Math.max(1, height - lines.length);
    if (this.rowCursor < this.scroll) this.scroll = this.rowCursor;
    if (this.rowCursor >= this.scroll + visible) this.scroll = this.rowCursor - visible + 1;
    this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.rows.length - visible)));

    for (let i = this.scroll; i < Math.min(this.rows.length, this.scroll + visible); i += 1) {
      const row = this.rows[i];
      const active = i === this.rowCursor;
      const number = String(i + 1).padStart(2, " ");
      const title = truncate(row.title || "—", Math.max(10, inner - 42));
      const subtitle = truncate(row.subtitle || "", 22);
      const duration = row.duration ? formatTime(row.durationSeconds || 0) : "";
      const marker = active ? `${color.accent}▸${ansi.reset}` : " ";
      const name = active ? `${color.accent}${ansi.bold}${title}${ansi.reset}` : `${color.text}${title}${ansi.reset}`;
      const tail = `${color.muted}${pad(subtitle, 24)}${pad(duration, 8)}${ansi.reset}`;
      lines.push(`${marker} ${color.muted}${number}${ansi.reset} ${name} ${tail}`);
    }

    const heading =
      this.view === "search" ? "search results" : this.view === "library" ? "your library" : "home";
    return box(lines, cols - 1, { title: `${heading}  (${this.rows.length})` });
  }

  drawQueue(cols, height) {
    const inner = cols - 4;
    const lines = [];
    if (!this.queue.length) {
      lines.push(`${color.muted}The queue is empty.${ansi.reset}`);
    }
    for (let i = 0; i < this.queue.length; i += 1) {
      const row = this.queue[i];
      const active = i === this.index;
      const marker = active ? `${color.accent}▸${ansi.reset}` : " ";
      const title = truncate(row.title || "—", Math.max(10, inner - 30));
      const name = active ? `${color.accent}${ansi.bold}${title}${ansi.reset}` : `${color.text}${title}${ansi.reset}`;
      lines.push(`${marker} ${color.muted}${String(i + 1).padStart(2, " ")}${ansi.reset} ${name} ${color.muted}${truncate(row.subtitle || "", 24)}${ansi.reset}`);
    }
    return box(lines.slice(0, height), cols - 1, { title: `queue  (${this.queue.length})` });
  }

  drawLyrics(cols, height) {
    const inner = cols - 4;
    const lines = [];
    if (!this.lyrics.lines.length) {
      lines.push(`${color.muted}No lyrics for this track yet.${ansi.reset}`);
      if (this.lyrics.plain) lines.push("", truncate(this.lyrics.plain, inner));
    } else {
      const start = Math.max(0, this.lyricIndex - Math.floor(height / 2));
      for (let i = start; i < Math.min(this.lyrics.lines.length, start + height); i += 1) {
        const line = this.lyrics.lines[i];
        const text = truncate(line.text, inner);
        if (i === this.lyricIndex) {
          lines.push(`${color.accent}${ansi.bold}${text}${ansi.reset}`);
        } else {
          lines.push(`${color.muted}${text}${ansi.reset}`);
        }
      }
    }
    return box(lines, cols - 1, { title: `lyrics — ${this.lyrics.title || "—"}` });
  }

  drawHelp(cols, height) {
    const rows = [
      ["space", "play / pause"],
      ["n / p", "next / previous track"],
      ["← / →", "seek 10 seconds back / forward"],
      ["0-9", "play the numbered track"],
      ["j / k", "move down / up the list"],
      ["g / G", "jump to the top / bottom"],
      ["/", "search"],
      ["h l q y", "home · library · queue · lyrics"],
      ["+ / - / m", "volume up / down / mute"],
      ["e", "restart the track"],
      ["w", "download the track for offline listening"],
      ["r", "reload the current view"],
      ["?", "toggle this help"],
      ["q", "quit"],
    ];
    const lines = rows.map(([key, description]) => {
      return `${color.accent}${pad(key, 10)}${ansi.reset}${color.text}${description}${ansi.reset}`;
    });
    lines.push("");
    lines.push(`${color.muted}Audio backend:${ansi.reset} ${color.text}${this.player.backend}${ansi.reset}`);
    lines.push(
      `${color.muted}Discord:${ansi.reset} ${color.text}${
        this.discord.enabled ? (this.discord.connected ? "connected" : "configured (Discord not running?)") : "not configured"
      }${ansi.reset}`,
    );
    lines.push(`${color.muted}Gateway:${ansi.reset} ${color.text}${this.gateway.base}${ansi.reset}`);
    return box(lines, cols - 1, { title: "keyboard shortcuts" });
  }

  drawFooter(cols) {
    const hints = [
      "space play",
      "n/p next",
      "enter play row",
      "/ search",
      "l library",
      "q queue",
      "y lyrics",
      "w download",
      "? help",
      "q quit",
    ];
    const status = this.busy ? `${color.warn}working…${ansi.reset}` : `${color.muted}${this.status}${ansi.reset}`;
    const left = hints.map((hint) => `${color.muted}${hint}${ansi.reset}`).join(`${color.muted} · ${ansi.reset}`);
    const gap = Math.max(1, cols - 1 - width(left) - width(this.status));
    return [truncate(`${left}${" ".repeat(gap)}${status}`, cols - 1)];
  }
}

// ------------------------------------------------------------------ helpers --

function flatten(feed) {
  if (!feed) return [];
  if (Array.isArray(feed)) return feed;
  if (Array.isArray(feed.items)) return feed.items;
  const sections = feed.sections || [];
  const out = [];
  for (const section of sections) {
    for (const item of section.items || []) {
      out.push({ ...item, section: section.title || "" });
    }
  }
  return out;
}

/** Parse an LRC file into timed lines. */
export function parseLrc(raw) {
  if (!raw) return [];
  const lines = [];
  const stamp = /\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]/g;
  for (const rawLine of String(raw).split(/\r?\n/)) {
    const times = [...rawLine.matchAll(stamp)];
    if (!times.length) continue;
    const text = rawLine.replace(stamp, "").trim();
    if (!text) continue;
    for (const match of times) {
      const minutes = Number(match[1]);
      const seconds = Number(match[2]);
      const fraction = match[3] ? Number(`0.${match[3]}`) : 0;
      lines.push({ time: minutes * 60 + seconds + fraction, text });
    }
  }
  return lines.sort((a, b) => a.time - b.time);
}

/** Decode a raw stdin chunk into a key name. */
function decodeSpecial(data) {
  const map = {
    "\x1b[A": "up",
    "\x1b[B": "down",
    "\x1b[C": "right",
    "\x1b[D": "left",
    "\r": "enter",
    "\n": "enter",
    "\x7f": "backspace",
    "\t": "tab",
  };
  if (map[data]) return map[data];
  return data;
}

export async function main() {
  const config = await loadConfig();
  const gateway = new Gateway(config.base);
  const app = new App({ gateway, config });

  // `--help`
  if (process.argv.includes("--help") || process.argv.includes("-h")) {
    console.log(`JusPlayer terminal client

  jusplayer                    start the player
  jusplayer --api <url>       use a different gateway (default ${config.base})
  jusplayer --no-audio        browse without playing anything

Environment:
  JUSPLAYER_API               gateway base URL
  JUSPLAYER_DISCORD_CLIENT_ID Discord application id for rich presence
  JUSPLAYER_CONFIG            path to config.json
`);
    return;
  }

  if (process.argv.includes("--no-audio")) config.player = "none";
  const apiIndex = process.argv.indexOf("--api");
  if (apiIndex >= 0 && process.argv[apiIndex + 1]) gateway.base = process.argv[apiIndex + 1].replace(/\/+$/, "");

  await app.start();
}
