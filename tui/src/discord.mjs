/**
 * Discord Rich Presence for the TUI and the desktop app.
 *
 * Discord exposes a local IPC socket. We speak the same protocol Discord's
 * own clients do: a 4-byte little-endian length, a 4-byte little-endian
 * opcode, then a JSON payload.
 *
 *   opcode 0 — handshake
 *   opcode 1 — frame
 *   opcode 2 — ping (we answer with a pong)
 *
 * The application id comes from your own Discord application (Developer
 * Portal → OAuth2 → Client ID); set it in `~/.config/jusplayer/config.json`
 * as `discordClientId`, or via `JUSPLAYER_DISCORD_CLIENT_ID`.
 */

import net from "node:net";
import os from "node:os";
import path from "node:path";
import { existsSync } from "node:fs";

const OPCODE = { HANDSHAKE: 0, FRAME: 1, CLOSE: 2, PING: 3, PONG: 4 };

/** Where Discord keeps its IPC socket on each platform. */
export function discordSocketPaths() {
  if (process.platform === "win32") {
    const appData = process.env.APPDATA || path.join(os.homedir(), "AppData", "Roaming");
    return ["Discord", "DiscordCanary", "DiscordPTB"].map((name) =>
      path.join(appData, name, "IPC", "0"),
    );
  }
  const runtime = process.env.XDG_RUNTIME_DIR || `/run/user/${process.getuid?.() ?? 0}`;
  const tmp = os.tmpdir();
  return [
    path.join(runtime, "discord-ipc-0"),
    path.join(tmp, "discord-ipc-0"),
    path.join(runtime, "snap.discord/discord-ipc-0"),
    path.join(runtime, "flatpak.discord.Discord/discord-ipc-0"),
  ];
}

function encode(opcode, payload) {
  const body = Buffer.from(JSON.stringify(payload), "utf8");
  const header = Buffer.alloc(8);
  header.writeUInt32LE(body.length, 0);
  header.writeUInt32LE(opcode, 4);
  return Buffer.concat([header, body]);
}

export class DiscordPresence {
  constructor({ clientId = "", logger = null } = {}) {
    this.clientId = clientId;
    this.logger = logger;
    this.socket = null;
    this.connected = false;
    this.buffer = Buffer.alloc(0);
    this.lastActivity = null;
  }

  get enabled() {
    return Boolean(this.clientId);
  }

  log(...args) {
    if (this.logger) this.logger(...args);
  }

  /** Connect and set the activity. Silently does nothing if Discord is off. */
  async connect(activity = {}) {
    if (!this.enabled) return false;
    for (const socketPath of discordSocketPaths()) {
      const connected = await this._connectTo(socketPath);
      if (connected) {
        this.connected = true;
        this.log(`Discord connected via ${socketPath}`);
        this.setActivity(activity);
        return true;
      }
    }
    this.log("Discord IPC socket not found (is Discord running?)");
    return false;
  }

  _connectTo(socketPath) {
    return new Promise((resolve) => {
      if (!exists(socketPath)) return resolve(false);
      const socket = net.createConnection(socketPath);
      const fail = () => {
        socket.destroy();
        resolve(false);
      };
      socket.once("connect", () => {
        socket.removeAllListeners("error");
        this.socket = socket;
        socket.on("error", () => {});
        socket.on("close", () => {
          this.connected = false;
          this.socket = null;
        });
        socket.on("data", (chunk) => this._onData(chunk));
        // handshake
        socket.write(encode(OPCODE.HANDSHAKE, { v: 1, client_id: this.clientId }));
        resolve(true);
      });
      socket.once("error", fail);
      socket.setTimeout(1500, () => {
        socket.destroy();
        resolve(false);
      });
    });
  }

  _onData(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    for (;;) {
      if (this.buffer.length < 8) return;
      const length = this.buffer.readUInt32LE(0);
      const opcode = this.buffer.readUInt32LE(4);
      if (this.buffer.length < 8 + length) return;
      const body = this.buffer.subarray(8, 8 + length).toString("utf8");
      this.buffer = this.buffer.subarray(8 + length);

      if (opcode === OPCODE.PING) {
        this.socket?.write(encode(OPCODE.PONG, JSON.parse(body || "{}")));
      }
      if (opcode === OPCODE.FRAME) {
        try {
          const message = JSON.parse(body);
          if (message.cmd === "DISPATCH" && message.evt?.t === "READY") {
            this.log("Discord handshake complete");
          }
        } catch {
          /* ignore malformed frames */
        }
      }
    }
  }

  /** Update the "now playing" card. */
  setActivity({ details = "", state = "", largeText = "", smallText = "", start = 0, end = 0, artwork = "" }) {
    if (!this.enabled) return;
    this.lastActivity = { details, state, largeText, smallText, start, end, artwork };
    if (!this.socket || !this.connected) return;

    const payload = {
      cmd: "SET_ACTIVITY",
      args: {
        pid: process.pid,
        activity: {
          type: 2,
          details,
          state,
          large_text: largeText,
          small_text: smallText,
          ...(start ? { timestamps: { start, ...(end ? { end } : {}) } } : {}),
          ...(artwork ? { assets: { large_image: artwork } } : {}),
        },
      },
    };
    this.socket.write(encode(OPCODE.FRAME, payload));
  }

  clear() {
    if (this.socket && this.connected) {
      this.socket.write(
        encode(OPCODE.FRAME, { cmd: "SET_ACTIVITY", args: { pid: process.pid, activity: null } }),
      );
    }
  }

  close() {
    this.clear();
    this.socket?.destroy();
    this.socket = null;
    this.connected = false;
  }
}

function exists(file) {
  try {
    return existsSync(file);
  } catch {
    return false;
  }
}
