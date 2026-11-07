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
  box,
  chip,
  clip,
  color,
  dot,
  equalizer,
  formatTime,
  meter,
  pad,
  progressBar,
  truncate,
  width,
} from "./ui.mjs";

const VIEWS = ["home", "search", "library", "queue", "lyrics", "help"];

const KEYS = {
  home: { key: "h", label: "home" },
  search: { key: "/", label: "search" },
  library: { key: "l", label: "library" },
  queue: { key: "u", label: "queue" },
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
    this.homeRows = [];
    this.listTitle = "";
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
      this.homeRows = this.rows;
      this.listTitle = "";
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

  /** Enter on a track plays it; Enter on a playlist or album opens it. */
  async activateRow(rowIndex) {
    const item = this.rows[rowIndex];
    if (!item) return;
    if (item.videoId) return this.playRow(rowIndex);
    if (item.browseId && (item.kind === "playlist" || item.kind === "album")) {
      return this.openCollection(item);
    }
    this.setMessage("That is not a playable track.", "warn");
    this.render();
  }

  async openCollection(item) {
    this.busy = true;
    this.setMessage(`Opening “${item.title}”…`);
    this.render();
    try {
      const feed =
        item.kind === "album"
          ? await this.gateway.album(item.browseId)
          : await this.gateway.playlist(item.browseId);
      const tracks = flatten(feed).filter((row) => row.videoId);
      if (!tracks.length) {
        this.setMessage("This collection has no playable tracks.", "warn");
      } else {
        this.rows = tracks;
        this.rowCursor = 0;
        this.scroll = 0;
        this.listTitle = item.title || "collection";
        this.setMessage(`${tracks.length} tracks · Enter to play`);
      }
    } catch (error) {
      this.setMessage(`Could not open this ${item.kind}: ${error.message}`, "error");
    }
    this.busy = false;
    this.render();
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
        rowSeconds(item),
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
      case "down":
      case "\x1b[B":
        this.moveCursor(1);
        return;
      case "k":
      case "up":
      case "\x1b[A":
        this.moveCursor(-1);
        return;
      case "\r":
        this.activateRow(this.rowCursor);
        return;
      case "g":
        this.moveCursor(-this.rows.length);
        return;
      case "G":
        this.moveCursor(this.rows.length);
        return;
      case "s":
      case "left":
      case "\x1b[D":
        this.player.seek(this.player.state.position - 10);
        return;
      case "d":
      case "right":
      case "\x1b[C":
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
          this.listTitle = "";
          this.render();
        }
        return;
      default:
        break;
    }

    if (number >= 0 && number <= 9) {
      this.activateRow(number - 1);
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
    const footer = this.drawFooter(cols);
    lines.push(...header, "", ...nowPlaying);

    // header + blank + now playing + blank + body box + blank + footer
    const chrome = header.length + 1 + nowPlaying.length + 1 + 1 + footer.length;
    const boxHeight = Math.max(4, rows - chrome);
    const contentHeight = Math.max(2, boxHeight - 2);

    const body = this.drawBody(cols, contentHeight);
    lines.push("", ...body, "", ...footer);

    // Never write more rows than the terminal has — that would scroll the
    // header out of view.
    const overflow = lines.length - rows;
    if (overflow > 0) lines.splice(lines.length - overflow - 1, overflow);

    // Repaint in place instead of clearing the whole screen: each line is
    // cleared as it is written, and anything below the last line is wiped.
    // This keeps the now-playing equaliser from flashing on every tick.
    let output = ansi.home;
    output += lines.map((line) => `${ansi.clearLine}${line}`).join("\n");
    output += ansi.clearDown;
    process.stdout.write(output);
    this.renderedLines = lines.length;
  }

  drawHeader(cols) {
    const brand = `${color.accent}${ansi.bold}♪ JusPlayer${ansi.reset} ${color.faint}terminal${ansi.reset}`;
    const account = this.account?.signedIn
      ? `${dot()} ${color.muted}${truncate(this.account.name || "signed in", 26)}${ansi.reset}`
      : `${color.faint}○ anonymous${ansi.reset}`;
    const gap = Math.max(1, cols - 1 - width(brand) - width(account));
    const top = truncate(brand + " ".repeat(gap) + account, cols - 1);

    const tabs = VIEWS.map((view) =>
      view === this.view ? chip(view) : `${color.faint} ${view} ${ansi.reset}`,
    ).join(`${color.border}│${ansi.reset}`);

    return [
      top,
      truncate(`  ${tabs}`, cols - 1),
      `${color.border}${"─".repeat(cols - 1)}${ansi.reset}`,
    ];
  }

  drawNowPlaying(cols) {
    const state = this.player.state;
    const item = this.current;
    const inner = cols - 5;
    const playing = Boolean(item && state.playing);

    const clock = `${color.faint}${formatTime(state.position)}${ansi.reset} ${color.border}/${ansi.reset} ${color.faint}${formatTime(state.duration)}${ansi.reset}`;
    const progress = state.duration > 0 ? state.position / state.duration : 0;
    const progressWidth = Math.max(12, inner - width(clock) - 2);

    const status = !item ? "idle" : playing ? "playing" : "paused";
    const statusTint = !item ? color.faint : playing ? color.accent : color.warn;

    const info = [];
    if (item) {
      info.push(`${equalizer(playing, 6)}  ${color.text}${ansi.bold}${clip(item.title, Math.max(8, inner - 8))}${ansi.reset}`);
      info.push(`         ${color.muted}${clip(item.subtitle || "", Math.max(8, inner - 9))}${ansi.reset}`);
    } else {
      info.push(`${equalizer(false, 6)}  ${color.muted}Nothing playing — press Enter on a track, or / to search${ansi.reset}`);
    }
    info.push("");
    info.push(`${progressBar(progress, progressWidth)}  ${clock}`);
    info.push(
      `${color.faint}vol${ansi.reset} ${meter(state.volume, 8)} ${color.muted}${String(Math.round(state.volume * 100)).padStart(3)}%${ansi.reset}` +
        `   ${color.faint}backend${ansi.reset} ${color.text}${this.player.backend}${ansi.reset}` +
        `   ${statusTint}● ${status}${ansi.reset}`,
    );

    const lines = box(info, cols - 1, { title: "now playing" });
    if (this.message) {
      const tint = this.messageKind === "error" ? color.error : this.messageKind === "warn" ? color.warn : color.accent2;
      lines.splice(
        lines.length - 1,
        0,
        `${color.border}│${ansi.reset} ${tint}●${ansi.reset} ${truncate(this.message, inner - 2)}${ansi.reset} ${color.border}│${ansi.reset}`,
      );
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
    const inner = cols - 5;
    const lines = [];

    if (this.searching) {
      lines.push(
        `${color.accent2}${ansi.bold}search${ansi.reset} ${color.border}›${ansi.reset} ${color.text}${this.query}${ansi.reset}${color.accent}▏${ansi.reset}`,
      );
    }

    if (this.busy) lines.push(`${color.muted}loading…${ansi.reset}`);
    if (!this.rows.length && !this.busy) {
      lines.push(`${color.faint}Nothing here yet.${ansi.reset}`);
      lines.push(`${color.faint}Press ${ansi.reset}${color.accent}/${ansi.reset}${color.faint} to search YouTube Music.${ansi.reset}`);
    }

    const numberWidth = String(Math.max(1, this.rows.length)).length + 1;
    const durationWidth = 6;
    const subtitleWidth = Math.max(12, Math.min(32, inner - 28));
    const titleWidth = Math.max(12, inner - numberWidth - subtitleWidth - durationWidth - 4);

    // A small column header makes long lists much easier to scan.
    if (this.rows.length && !this.searching && height > 6) {
      lines.push(
        `${color.faint} ${"#".padStart(numberWidth)} ${pad("TITLE", titleWidth)} ` +
          `${pad("ARTIST", subtitleWidth)} ${pad("TIME", durationWidth)}${ansi.reset}`,
      );
    }

    const visible = Math.max(1, height - lines.length);
    if (this.rowCursor < this.scroll) this.scroll = this.rowCursor;
    if (this.rowCursor >= this.scroll + visible) this.scroll = this.rowCursor - visible + 1;
    this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, this.rows.length - visible)));

    for (let i = this.scroll; i < Math.min(this.rows.length, this.scroll + visible); i += 1) {
      const row = this.rows[i];
      const active = i === this.rowCursor;
      const number = String(i + 1).padStart(numberWidth, " ");
      const title = pad(clip(row.title || "—", titleWidth), titleWidth);
      const subtitle = clip(row.subtitle || "", subtitleWidth);
      const duration = row.duration ? formatTime(rowSeconds(row)) : "";

      const line =
        (active ? color.surfaceHigh : "") +
        (active ? color.accent : color.border) + (active ? "▌" : " ") +
        color.faint + `${number} ` +
        (active ? color.text + ansi.bold : color.text) + title +
        `${ansi.reset}${active ? color.surfaceHigh : ""}` +
        color.muted + ` ${pad(subtitle, subtitleWidth)}` +
        color.faint + ` ${pad(duration, durationWidth)}`;
      lines.push(pad(line, inner) + ansi.reset);
    }

    const heading = this.listTitle
      ? this.listTitle
      : this.view === "search"
        ? "search results"
        : this.view === "library"
          ? "your library"
          : "home";
    return box(lines, cols - 1, { title: `${heading} · ${this.rows.length}`, minHeight: height });
  }

  drawQueue(cols, height) {
    const inner = cols - 5;
    const lines = [];
    if (!this.queue.length) {
      lines.push(`${color.faint}The queue is empty.${ansi.reset}`);
      lines.push(`${color.faint}Press ${ansi.reset}${color.accent}/${ansi.reset}${color.faint} to find something to play.${ansi.reset}`);
    }
    for (let i = 0; i < Math.min(this.queue.length, height - lines.length); i += 1) {
      const row = this.queue[i];
      const active = i === this.index;
      const number = String(i + 1).padStart(2, " ");
      const titleWidth = Math.max(10, inner - 39);
      const title = pad(clip(row.title || "—", titleWidth), titleWidth);
      const subtitle = clip(row.subtitle || "", 34);
      const line =
        (active ? color.surfaceHigh : "") +
        (active ? color.accent : color.border) + (active ? "▌" : " ") +
        color.faint + `${number} ` +
        (active ? color.text + ansi.bold : color.text) + title +
        `${ansi.reset}${active ? color.surfaceHigh : ""}` +
        color.muted + ` ${pad(subtitle, 34)}`;
      lines.push(pad(line, inner) + ansi.reset);
    }
    return box(lines, cols - 1, { title: `queue · ${this.queue.length}`, minHeight: height });
  }

  drawLyrics(cols, height) {
    const inner = cols - 5;
    const lines = [];
    if (!this.lyrics.lines.length) {
      if (this.lyrics.plain) {
        lines.push(`${color.faint}unsynced lyrics${ansi.reset}`);
        lines.push("");
        const plain = String(this.lyrics.plain)
          .split(/\r?\n/)
          .map((line) => line.trim())
          .filter(Boolean)
          .slice(0, Math.max(0, height - 2));
        for (const line of plain) {
          lines.push(`${color.muted}${clip(line, inner)}${ansi.reset}`);
        }
      } else {
        lines.push(`${color.faint}No lyrics for this track yet.${ansi.reset}`);
      }
    } else {
      const start = Math.max(0, this.lyricIndex - Math.floor(height / 2));
      for (let i = start; i < Math.min(this.lyrics.lines.length, start + height); i += 1) {
        const line = this.lyrics.lines[i];
        const text = clip(line.text, inner - 2);
        if (i === this.lyricIndex) {
          lines.push(`${color.accent}${ansi.bold}▌ ${text}${ansi.reset}`);
        } else {
          lines.push(`${color.border}│${ansi.reset} ${color.muted}${text}${ansi.reset}`);
        }
      }
    }
    return box(lines, cols - 1, { title: `lyrics · ${this.lyrics.title || "—"}`, minHeight: height });
  }

  drawHelp(cols, height) {
    const inner = cols - 5;
    const sections = [
      ["playback", [
        ["space", "play / pause"],
        ["n / p", "next / previous"],
        ["← / →", "seek 10 seconds"],
        ["+ / -", "volume"],
        ["m", "mute"],
        ["e", "restart track"],
        ["w", "download track"],
      ]],
      ["browse", [
        ["enter", "play or open"],
        ["j / k", "move down / up"],
        ["g / G", "top / bottom"],
        ["0-9", "play numbered"],
        ["/", "search"],
        ["r", "reload view"],
        ["esc", "back home"],
      ]],
      ["views", [
        ["h", "home"],
        ["l", "library"],
        ["u", "queue"],
        ["y", "lyrics"],
        ["?", "this help"],
        ["q", "quit"],
      ]],
    ];

    const gap = 3;
    const columnWidth = Math.max(16, Math.floor((inner - gap * (sections.length - 1)) / sections.length));
    const rendered = sections.map(([title, rows]) => {
      const lines = [`${color.accent2}${ansi.bold}${title.toUpperCase()}${ansi.reset}`];
      for (const [key, description] of rows) {
        lines.push(
          `${color.accent}${pad(key, 9)}${ansi.reset}${color.text}${clip(description, columnWidth - 9)}${ansi.reset}`,
        );
      }
      return lines;
    });

    const lines = [];
    const rowCount = Math.max(...rendered.map((column) => column.length));
    for (let i = 0; i < rowCount; i += 1) {
      lines.push(rendered.map((column) => pad(column[i] || "", columnWidth)).join(" ".repeat(gap)));
    }

    lines.push(
      "",
      `${color.faint}backend${ansi.reset} ${color.text}${this.player.backend}${ansi.reset}   ` +
        `${color.faint}discord${ansi.reset} ${color.text}${
          this.discord.enabled ? (this.discord.connected ? "connected" : "configured") : "off"
        }${ansi.reset}`,
      `${color.faint}gateway${ansi.reset} ${color.text}${this.gateway.base}${ansi.reset}`,
    );

    return box(lines.slice(0, Math.max(0, height)), cols - 1, { title: "keyboard shortcuts", minHeight: height });
  }

  drawFooter(cols) {
    const hints = [
      ["space", "play"],
      ["enter", "open"],
      ["/", "search"],
      ["u", "queue"],
      ["?", "help"],
    ];
    const kind = this.messageKind === "error" ? "error" : this.busy ? "warn" : "ok";
    const right = `${dot(kind)} ${color.faint}${this.busy ? "working…" : this.status}${ansi.reset}`;

    // Keep the status visible on narrow terminals: drop hints from the end
    // until the two sides fit.
    const separator = `${color.border}  ${ansi.reset}`;
    let left = "";
    for (const [key, action] of hints) {
      const hint = `${color.accent}${key}${ansi.reset}${color.faint} ${action}${ansi.reset}`;
      const candidate = left ? `${left}${separator}${hint}` : hint;
      if (width(candidate) + 1 + width(right) > cols - 1) break;
      left = candidate;
    }

    const gap = Math.max(1, cols - 1 - width(left) - width(right));
    return [
      `${color.border}${"─".repeat(cols - 1)}${ansi.reset}`,
      truncate(`${left}${" ".repeat(gap)}${right}`, cols - 1),
    ];
  }
}

// ------------------------------------------------------------------ helpers --

/** Seconds for a row, whose length may be a number or a "3:42" string. */
function rowSeconds(row) {
  if (Number.isFinite(row?.durationSeconds)) return row.durationSeconds;
  const text = String(row?.duration || "");
  if (!/^\d+(?::\d{1,2}){0,2}$/.test(text)) return 0;
  return text.split(":").reduce((total, part) => total * 60 + Number(part), 0);
}

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
