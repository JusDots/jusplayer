/**
 * ANSI helpers for the TUI.
 *
 * No dependencies: the terminal is driven with plain escape sequences and
 * a line-based renderer, which is plenty for a music player.
 *
 * Colour: the palette mirrors the app's dark, mossy "goblin" theme. Terminals
 * that advertise true colour get the exact RGB values; everyone else gets the
 * nearest colour from the 256-colour cube, so the UI still looks right over
 * SSH or in a plain xterm.
 */

const ESC = "\x1b";

const TRUE_COLOUR =
  /truecolor|24bit/i.test(process.env.COLORTERM || "") ||
  /-direct/.test(process.env.TERM || "");

/** Nearest xterm-256 index for an RGB triple. */
function cubeIndex(r, g, b) {
  const steps = [0, 95, 135, 175, 215, 255];
  const near = (value) => {
    let best = 0;
    for (let i = 1; i < steps.length; i += 1) {
      if (Math.abs(steps[i] - value) < Math.abs(steps[best] - value)) best = i;
    }
    return best;
  };
  const r6 = near(r);
  const g6 = near(g);
  const b6 = near(b);
  const cube = 16 + 36 * r6 + 6 * g6 + b6;
  const grey = Math.round(((r + g + b) / 3 / 255) * 23);
  const greyValue = 8 + grey * 10;
  const greyIndex = 232 + grey;
  const cubeError =
    (steps[r6] - r) ** 2 + (steps[g6] - g) ** 2 + (steps[b6] - b) ** 2;
  const greyError =
    (greyValue - r) ** 2 + (greyValue - g) ** 2 + (greyValue - b) ** 2;
  return greyError < cubeError ? greyIndex : cube;
}

function hex(value) {
  return [
    (value >> 16) & 0xff,
    (value >> 8) & 0xff,
    value & 0xff,
  ];
}

/** Foreground escape for a 0xRRGGBB colour. */
function fg(value) {
  const [r, g, b] = hex(value);
  return TRUE_COLOUR ? `${ESC}[38;2;${r};${g};${b}m` : `${ESC}[38;5;${cubeIndex(r, g, b)}m`;
}

/** Background escape for a 0xRRGGBB colour. */
function bg(value) {
  const [r, g, b] = hex(value);
  return TRUE_COLOUR ? `${ESC}[48;2;${r};${g};${b}m` : `${ESC}[48;5;${cubeIndex(r, g, b)}m`;
}

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
  clearDown: `${ESC}[0J`,
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
  accent: fg(0x6ee7a8),
  accent2: fg(0xb9f36b),
  muted: fg(0x9db4a6),
  text: fg(0xe6f4eb),
  faint: fg(0x5c7365),
  border: fg(0x27392e),
  warn: fg(0xffd166),
  error: fg(0xff8a80),

  // Surfaces, used for the selected row and the active tab.
  surface: bg(0x0c1711),
  surfaceHigh: bg(0x15241b),
  tab: bg(0x1f3a2b),
  onAccent: fg(0x06100b),
};

/** Visible width of a string, ignoring escape sequences. */
export function width(text) {
  return String(text).replace(/\x1b\[[0-9;]*m/g, "").length;
}

export function pad(text, size) {
  const gap = size - width(text);
  return gap > 0 ? text + " ".repeat(gap) : text;
}

/** Slice a styled string to `size` visible characters, keeping escapes. */
export function clip(text, size) {
  const str = String(text);
  if (width(str) <= size) return str;
  let out = "";
  let visible = 0;
  let i = 0;
  while (i < str.length && visible < size) {
    if (str[i] === ESC) {
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
  return out;
}

export function truncate(text, size) {
  const str = String(text);
  if (width(str) <= size) return str;
  return `${clip(str, Math.max(0, size - 1))}…${ansi.reset}`;
}

/** A horizontal rule with a title. */
export function rule(title, size, char = "─") {
  if (!title) return color.border + char.repeat(size) + ansi.reset;
  const head = `─ ${title} `;
  return color.border + head + char.repeat(Math.max(0, size - width(head))) + ansi.reset;
}

/**
 * A rounded panel; returns the lines to print.
 *
 * The panel is deliberately one column narrower than the terminal: a line
 * that is exactly the terminal width makes the cursor wrap, and the newline
 * after it scrolls the whole screen — which would push the header out of
 * view on every redraw.
 */
export function box(lines, size, { title = "", minHeight = 0 } = {}) {
  const inner = Math.max(0, size - 4);
  const out = [];
  const content = lines.slice();
  while (content.length < minHeight) content.push("");
  if (title) {
    const head = `─ ${title} `;
    const tail = "─".repeat(Math.max(0, size - width(head) - 3));
    out.push(
      `${color.border}╭${color.border}${head}${ansi.reset}${color.accent}${tail}${color.border}╮${ansi.reset}`,
    );
  } else {
    out.push(`${color.border}╭${"─".repeat(size - 2)}╮${ansi.reset}`);
  }
  for (const line of content) {
    const body = clip(line, inner);
    out.push(
      `${color.border}│${ansi.reset} ${body}${" ".repeat(Math.max(0, inner - width(body)))} ${color.border}│${ansi.reset}`,
    );
  }
  out.push(`${color.border}╰${"─".repeat(size - 2)}╯${ansi.reset}`);
  return out;
}

/** A filled progress bar with a playhead. */
export function progressBar(fraction, size, { knob = true } = {}) {
  const value = Math.max(0, Math.min(1, Number(fraction) || 0));
  const filled = Math.round(value * size);
  const knobWidth = knob && filled > 0 ? 1 : 0;
  const fill = Math.max(0, filled - knobWidth);
  let out = color.accent + "━".repeat(fill);
  if (knobWidth) out += `${ansi.bold}●${ansi.reset}${color.accent}`;
  out += ansi.reset + color.border + "━".repeat(Math.max(0, size - filled)) + ansi.reset;
  return out;
}

/** A horizontal progress bar (kept for compatibility). */
export function bar(fraction, size) {
  return progressBar(fraction, size);
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
      out += levels[level];
    } else {
      out += "▁";
    }
  }
  return color.accent + out + ansi.reset;
}

/** A tiny animated equaliser for the now-playing panel. */
export function equalizer(playing, size = 6) {
  const levels = ["▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"];
  const phase = Date.now() / 260;
  let out = "";
  for (let i = 0; i < size; i += 1) {
    const wave = Math.sin(phase + i * 1.7) * 0.5 + 0.5;
    const level = playing ? 1 + Math.round(wave * (levels.length - 2)) : 0;
    out += levels[level];
  }
  return color.accent + out + ansi.reset;
}

/** A filled label used for the active tab. */
export function chip(text, { fg: foreground = color.accent, bg: background = color.tab } = {}) {
  return `${background}${foreground}${ansi.bold} ${text} ${ansi.reset}`;
}

/** A small status dot. */
export function dot(kind = "ok") {
  const tint = kind === "error" ? color.error : kind === "warn" ? color.warn : color.accent;
  return `${tint}●${ansi.reset}`;
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
