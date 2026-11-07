/**
 * Browser sign-in for the terminal client.
 *
 * A terminal cannot own a browser session, so the gateway hands the finished
 * session to a tiny HTTP server running on 127.0.0.1:
 *
 *   1. the client asks the gateway to start the Google flow and names its own
 *      loopback address (`/api/auth/login?next=…`),
 *   2. the browser goes to Google and back to the gateway,
 *   3. the gateway redirects to the loopback address with a short-lived
 *      ticket,
 *   4. the client exchanges the ticket for a normal session.
 */

import { createServer } from "node:http";
import { spawn } from "node:child_process";

/** Open a URL in the user's browser. */
export function openBrowser(url) {
  const command =
    process.platform === "darwin"
      ? { file: "open", args: [url] }
      : process.platform === "win32"
        ? { file: "cmd", args: ["/c", "start", "", url] }
        : { file: "xdg-open", args: [url] };

  try {
    const child = spawn(command.file, command.args, { stdio: "ignore", detached: true });
    child.on("error", () => {});
    child.unref();
    return true;
  } catch {
    return false;
  }
}

const PAGE = (title, body) =>
  `<!doctype html><meta charset=utf-8><title>${title}</title>` +
  `<body style="font-family:system-ui;background:#06100b;color:#e6f4eb;padding:3rem">` +
  `<h1>${title}</h1><p>${body}</p>`;

/**
 * Start the loopback server and wait for the gateway to call it back.
 *
 * Returns the port, the address the gateway should redirect to and a promise
 * that resolves with the ticket (or null when the wait runs out).
 */
export function startSignInServer({ timeoutMs = 5 * 60 * 1000 } = {}) {
  let resolveTicket;
  const ticket = new Promise((resolve) => {
    resolveTicket = resolve;
  });

  let settled = false;
  const finish = (value) => {
    if (settled) return;
    settled = true;
    clearTimeout(timer);
    server.close();
    resolveTicket(value);
  };

  const server = createServer((req, res) => {
    const url = new URL(req.url || "/", "http://127.0.0.1");
    const code = url.searchParams.get("code");

    res.writeHead(200, {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
    });
    res.end(
      code
        ? PAGE("You are signed in", "You can close this tab and go back to the terminal.")
        : PAGE("Sign-in failed", "Go back to the terminal and try again."),
    );

    finish(code || null);
  });

  const timer = setTimeout(() => finish(null), timeoutMs);

  server.on("error", () => finish(null));

  return new Promise((resolve, reject) => {
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      const next = `http://127.0.0.1:${port}/jusplayer-signin`;
      resolve({ port, next, ticket });
    });
    server.on("error", reject);
  });
}

/**
 * The whole flow: open the browser, wait for the ticket, exchange it.
 *
 * `exchange` receives the ticket and should return the account (or throw).
 */
export async function signInWithBrowser({ base, exchange, onWaiting }) {
  const { next, ticket } = await startSignInServer();
  const url = `${String(base).replace(/\/+$/, "")}/api/auth/login?next=${encodeURIComponent(next)}`;
  if (onWaiting) onWaiting(url);
  openBrowser(url);

  const code = await ticket;
  if (!code) throw new Error("The sign-in was cancelled or timed out");
  return exchange(code);
}
