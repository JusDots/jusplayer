/**
 * ANSI helpers for the TUI.
 *
 * No dependencies: the terminal is driven with plain escape sequences and
 * a line-based renderer, which is plenty for a music player.
 */

const ESC = "\x1b";

export const ansi = {
  reset: `${ESC}[0m`,
  bold: `${ESC}[1m`,
  dim: `${ESC}[2m`,
  italic: `${ESC}[3m`,
  underline: `${ESC}[4m`,
  reverse: `${ESC}[7m`,

  fg: {
    black: `${ESC}[30m`,
    red: `${ESC}[31m`,
    green: `${ESC}[32m`,
    yellow: `${ESC}[33m`,
    blue: `${ESC}[34m`,
    magenta: `${ESC}[35m`,
    cyan: `${ESC}[36m`,
    white: `${ESC}[37m`,
    grey: `${ESC}[90m`,
    brightGreen: `${ESC}[92m`,
    brightCyan: `${ESC}[96m`,
    brightWhite: `${ESC}[97m`,
  },
  bg: {
    black: `${ESC}[40m`,
    green: `${ESC}[42m`,
    grey: `${ESC}[100m`,
  },

  clear: `${ESC}[2J${ESC}[H`,
  clearLine: `${ESC}[2K`,
  hideCursor: `${ESC}[?25l`,
  showCursor: `${ESC}[?25h`,
  altScreen: `${ESC}[?1049h`,
  mainScreen: `${ESC}[?1049l`,
  home: `${ESC}[H`,
  up: (n = 1) => `${ESC}[${n}A`,
  down: (n = 1) => `${ESC}[${n}B`,
};

/** The palette, matching the app's "goblin" green identity. */
export const color = {
  accent: ansi.fg.brightGreen,
  accent2: ansi.fg.brightCyan,
  muted: ansi.fg.grey,
  text: ansi.fg.brightWhite,
  warn: ansi.fg.yellow,
  error: ansi.fg.red,
};

/** Visible width of a string, ignoring escape sequences. */
export function width(text) {
  return String(text).replace(/\x1b\[[0-9;]*m/g, "").length;
}

export function pad(text, size) {
  const gap = size - width(text);
  return gap > 0 ? text + " ".repeat(gap) : text;
}

export function truncate(text, size) {
  const str = String(text);
  if (width(str) <= size) return str;
  // Slice the *visible* characters while keeping the colour escapes.
  let out = "";
  let visible = 0;
  let i = 0;
  while (i < str.length && visible < size - 1) {
    if (str[i] === "\x1b") {
      const match = /^\x1b\[[0-9;]*m/.exec(str.slice(i));
      if (match) {
        out += match[0];
        i += match[0].length;
        continue;
      }
    }
    out += str[i];
    visible += 1;
    i += 1;
  }
  return `${out}…${ansi.reset}`;
}

/** A horizontal rule with a title. */
export function rule(title, size, char = "─") {
  if (!title) return char.repeat(size);
  const head = `${char}${char} ${title} `;
  return color.muted + head + char.repeat(Math.max(0, size - width(head))) + ansi.reset;
}

/**
 * A boxed panel; returns the lines to print.
 *
 * The panel is deliberately one column narrower than the terminal: a line
 * that is exactly the terminal width makes the cursor wrap, and the newline
 * after it scrolls the whole screen — which would push the header out of
 * view on every redraw.
 */
export function box(lines, size, { title = "" } = {}) {
  const inner = size - 4;
  const out = [];
  const top = title
    ? `┌─ ${title} ${"─".repeat(Math.max(0, size - width(title) - 5))}┐`
    : `┌${"─".repeat(size - 2)}┐`;
  out.push(color.muted + top + ansi.reset);
  for (const line of lines) {
    const body = truncate(line, inner);
    out.push(`${color.muted}│${ansi.reset} ${pad(body, inner)} ${color.muted}│${ansi.reset}`);
  }
  out.push(color.muted + `└${"─".repeat(size - 2)}┘` + ansi.reset);
  return out;
}

/** A horizontal progress bar. */
export function bar(fraction, size) {
  const value = Math.max(0, Math.min(1, Number(fraction) || 0));
  const filled = Math.round(value * size);
  return color.accent + "━".repeat(filled) + color.muted + "━".repeat(Math.max(0, size - filled)) + ansi.reset;
}

export function formatTime(seconds) {
  const total = Math.max(0, Math.floor(Number(seconds) || 0));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = h > 0 ? String(m).padStart(2, "0") : String(m);
  return h > 0 ? `${h}:${mm}:${String(s).padStart(2, "0")}` : `${mm}:${String(s).padStart(2, "0")}`;
}

/** The unicode blocks used for the volume meter. */
export function meter(value, size = 8) {
  const levels = ["▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"];
  const amount = Math.max(0, Math.min(1, Number(value) || 0));
  const active = Math.round(amount * size);
  let out = "";
  for (let i = 0; i < size; i += 1) {
    if (i < active) {
      const level = Math.min(levels.length - 1, Math.round(((i + 1) / size) * levels.length) - 1);
      out += color.accent + levels[level] + ansi.reset;
    } else {
      out += color.muted + "▁" + ansi.reset;
    }
  }
  return out;
}

/** Move the cursor up `n` lines and clear them. */
export function clearLines(n) {
  if (n <= 0) return "";
  let out = "";
  for (let i = 0; i < n; i += 1) out += `${ESC}[2K${ESC}[1A`;
  out += `${ESC}[2K`;
  return out;
}

export function centre(text, size) {
  const gap = Math.max(0, Math.floor((size - width(text)) / 2));
  return " ".repeat(gap) + text;
}
