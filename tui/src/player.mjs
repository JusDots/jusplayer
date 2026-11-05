/**
 * Audio playback for the TUI.
 *
 * Backends, tried in order:
 *
 *   1. **mpv** — best option: real pause/seek/volume/position control over
 *      its JSON IPC socket, and it is present on most Linux systems.
 *   2. **ffplay** — ships with ffmpeg; plays the track but only exposes
 *      wall-clock position.
 *   3. **none**  — no audio; the TUI still browses, searches and shows
 *      lyrics, and prints the direct stream URL.
 *
 * The same direct audio URL the desktop app uses comes from the gateway's
 * `/api/stream` endpoint.
 */

import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, unlinkSync } from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";

function which(command) {
  // `command -v` is a shell builtin, so it has to run through a shell.
  const probe = spawnSync(
    process.platform === "win32" ? "where" : "sh",
    process.platform === "win32" ? [command] : ["-c", `command -v ${command}`],
    { encoding: "utf8" },
  );
  if (probe.status !== 0) return null;
  const first = String(probe.stdout || "").split(/\r?\n/).find(Boolean);
  return first ? first.trim() : null;
}

export function detectBackend(preference = "auto") {
  if (preference && preference !== "auto") return preference;
  if (which("mpv")) return "mpv";
  if (which("ffplay")) return "ffplay";
  return "none";
}

export class Player {
  constructor({ backend = "auto", onEnded = null, onState = null } = {}) {
    this.backend = detectBackend(backend);
    this.onEnded = onEnded;
    this.onState = onState;
    this.process = null;
    this.socket = null;
    this.socketPath = null;
    this.requestId = 0;
    this.pending = new Map();
    this.state = {
      playing: false,
      position: 0,
      duration: 0,
      volume: 0.8,
      muted: false,
      title: "",
      artist: "",
      url: "",
      videoId: "",
      error: "",
    };
    this.wallClock = { startedAt: 0, offset: 0 };
    // Bumped every time we explicitly pause or resume. Poll replies that
    // were already on their way when that happened are ignored, so a late
    // answer can never undo the user's pause.
    this.pauseEpoch = 0;
  }

  get available() {
    return this.backend !== "none";
  }

  _emit() {
    if (this.onState) this.onState({ ...this.state });
  }

  _set(patch) {
    Object.assign(this.state, patch);
    this._emit();
  }

  /** Play a track from a direct audio URL. */
  async play(url, { title = "", artist = "", videoId = "", proxyUrl = "", watchUrl = "" } = {}) {
    await this.stop();

    this._set({
      url,
      title,
      artist,
      videoId,
      position: 0,
      duration: 0,
      playing: true,
      error: "",
    });
    this.wallClock = { startedAt: Date.now(), offset: 0 };

    // Prefer the gateway proxy: YouTube's CDN rejects unknown User-Agents,
    // and the proxy carries the signed-in session for us.
    const source = proxyUrl || url;

    if (this.backend === "mpv") {
      await this._playMpv(source);
      // If the proxy could not serve the track (YouTube sometimes limits
      // how much a signed link may return), fall back to letting mpv
      // resolve the YouTube page itself through yt-dlp.
      if (this.state.error && watchUrl) {
        this._set({ error: "" });
        await this._playMpv(watchUrl);
      }
      return undefined;
    }
    if (this.backend === "ffplay") return this._playFfplay(source);
    return undefined;
  }

  async _playMpv(url) {
    const socketPath =
      process.platform === "win32"
        ? `\\\\.\\pipe\\jusplayer-mpv-${process.pid}`
        : path.join(os.tmpdir(), `jusplayer-mpv-${process.pid}.sock`);

    this.socketPath = socketPath;

    const args = [
      "--no-video",
      "--really-quiet",
      "--no-terminal",
      "--idle=yes",
      "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
      `--input-ipc-server=${socketPath}`,
      "--volume=80",
      url,
    ];

    this.process = spawn("mpv", args, { stdio: ["ignore", "ignore", "ignore"] });

    this.process.on("error", (err) => {
      this._set({ error: `mpv failed to start: ${err.message}`, playing: false });
    });

    this.process.on("exit", (code) => {
      if (this.stopping) return;
      if (code !== 0 && code !== null && this.state.playing) {
        this._set({ error: `mpv exited with code ${code}`, playing: false });
      }
    });

    await this._connectMpv(socketPath);
  }

  async _connectMpv(socketPath) {
    // mpv creates the socket a moment after it starts.
    for (let attempt = 0; attempt < 60; attempt += 1) {
      const socket = await new Promise((resolve) => {
        const client = net.createConnection(socketPath);
        const fail = () => {
          client.destroy();
          resolve(null);
        };
        client.once("connect", () => resolve(client));
        client.once("error", fail);
        client.setTimeout(500, fail);
      });
      if (socket) {
        this.socket = socket;
        this._readMpv();
        this._command(["set_property", "pause", false]);
        this._command(["set_property", "volume", this.state.volume * 100]);
        return;
      }
      await new Promise((r) => setTimeout(r, 100));
    }
    this._set({ error: "Could not connect to mpv", playing: false });
  }

  _readMpv() {
    let buffer = "";
    this.socket.on("data", (chunk) => {
      buffer += chunk.toString("utf8");
      let index;
      while ((index = buffer.indexOf("\n")) >= 0) {
        const line = buffer.slice(0, index).trim();
        buffer = buffer.slice(index + 1);
        if (!line) continue;
        let message;
        try {
          message = JSON.parse(line);
        } catch {
          continue;
        }
        this._handleMpv(message);
      }
    });
    this.socket.on("error", () => {});
  }

  _handleMpv(message) {
    if (message.request_id && this.pending.has(message.request_id)) {
      const resolve = this.pending.get(message.request_id);
      this.pending.delete(message.request_id);
      resolve(message);
      return;
    }
    if (message.event === "end-file") {
      this._set({ playing: false, position: this.state.duration });
      // Only a natural end means "the track finished". An error or an
      // explicit stop must not skip to the next track.
      if (message.reason === "eof" && !this.stopping && this.onEnded) {
        this.onEnded();
      }
      if (message.reason === "error" && !this.stopping) {
        this._set({ error: `mpv could not play this track (${message.file_error || "unknown"})` });
      }
    }
    if (message.event === "property-change") {
      const name = message.name;
      if (name === "duration") this._set({ duration: Number(message.data) || 0 });
      if (name === "time-pos") this._set({ position: Number(message.data) || 0 });
    }
  }

  _command(command) {
    if (!this.socket || this.socket.destroyed) return Promise.resolve(null);
    const requestId = ++this.requestId;
    return new Promise((resolve) => {
      this.pending.set(requestId, resolve);
      this.socket.write(`${JSON.stringify({ command, request_id: requestId })}\n`);
      setTimeout(() => {
        if (this.pending.has(requestId)) {
          this.pending.delete(requestId);
          resolve(null);
        }
      }, 4000);
    });
  }

  async _playFfplay(url) {
    this.process = spawn("ffplay", ["-nodisp", "-autoexit", "-loglevel", "quiet", url], {
      stdio: ["ignore", "ignore", "ignore"],
    });
    this.process.on("error", (err) => {
      this._set({ error: `ffplay failed to start: ${err.message}`, playing: false });
    });
    this.process.on("exit", () => {
      if (this.state.playing) {
        this._set({ playing: false });
        if (this.onEnded) this.onEnded();
      }
    });
  }

  /** Refresh the position/duration from the backend. */
  poll() {
    if (this.backend === "mpv") {
      // Always ask mpv — its own state is the only trustworthy one. If we
      // stopped polling while paused we would never notice the app and the
      // player disagreeing about the pause state.
      if (!this.socket || this.socket.destroyed) return;
      const epoch = this.pauseEpoch;
      this._command(["get_property", "time-pos"]).then((reply) => {
        if (reply?.data !== undefined) this._set({ position: Number(reply.data) || 0 });
      });
      this._command(["get_property", "duration"]).then((reply) => {
        if (reply?.data !== undefined) this._set({ duration: Number(reply.data) || 0 });
      });
      this._command(["get_property", "pause"]).then((reply) => {
        if (reply?.data === undefined) return;
        // A pause/resume issued after this poll went out wins.
        if (epoch !== this.pauseEpoch) return;
        this._set({ playing: !reply.data });
      });
      return;
    }
    if (this.backend === "ffplay" && this.state.playing) {
      const elapsed = (Date.now() - this.wallClock.startedAt) / 1000 + this.wallClock.offset;
      this._set({ position: elapsed });
    }
  }

  /** What does the backend think — is playback paused? */
  async isPaused() {
    if (this.backend === "mpv") {
      const reply = await this._command(["get_property", "pause"]);
      if (reply?.data !== undefined) return Boolean(reply.data);
    }
    return !this.state.playing;
  }

  pause() {
    this.pauseEpoch += 1;
    if (this.backend === "mpv") {
      this._command(["set_property", "pause", true]);
    } else {
      this.process?.kill("SIGSTOP");
    }
    this.wallClock.offset = this.state.position;
    this._set({ playing: false });
  }

  resume() {
    this.pauseEpoch += 1;
    if (this.backend === "mpv") {
      this._command(["set_property", "pause", false]);
    } else {
      this.process?.kill("SIGCONT");
    }
    this.wallClock.startedAt = Date.now();
    this._set({ playing: true });
  }

  async toggle() {
    // Ask the backend what it is really doing instead of trusting the
    // cached flag — the two can drift apart.
    if (this.backend === "mpv") {
      const paused = await this.isPaused();
      if (paused) this.resume();
      else this.pause();
      return;
    }
    if (this.state.playing) this.pause();
    else this.resume();
  }

  seek(seconds) {
    const target = Math.max(0, seconds);
    if (this.backend === "mpv") {
      this._command(["seek", target, "absolute"]);
    } else {
      // ffplay cannot seek; restart from the new offset.
      this.wallClock.offset = target;
      this.wallClock.startedAt = Date.now();
    }
    this._set({ position: target });
  }

  setVolume(value) {
    const volume = Math.max(0, Math.min(1, Number(value) || 0));
    if (this.backend === "mpv") this._command(["set_property", "volume", volume * 100]);
    this._set({ volume });
  }

  async stop() {
    // Tell the event handlers that this stop was deliberate, so a dying
    // mpv does not look like "the track ended".
    this.stopping = true;
    this.pauseEpoch += 1;
    if (this.process) {
      this.process.kill();
      this.process = null;
    }
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
    if (this.socketPath && process.platform !== "win32" && existsSync(this.socketPath)) {
      try {
        unlinkSync(this.socketPath);
      } catch {
        /* already gone */
      }
    }
    this.socketPath = null;
    this._set({ playing: false, position: 0, duration: 0 });
    // Give mpv's exit handler a moment to run before clearing the flag.
    await new Promise((r) => setTimeout(r, 120));
    this.stopping = false;
  }
}

/** Where downloaded tracks go. */
export function ensureMusicDir(configured) {
  const dir = configured || path.join(os.homedir(), "Music", "JusPlayer");
  mkdirSync(dir, { recursive: true });
  return dir;
}
