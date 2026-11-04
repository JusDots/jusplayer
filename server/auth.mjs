/**
 * YouTube Music account support for the JusPlayer gateway.
 *
 * Two ways to sign in, both ending in the same place: the gateway holds a
 * signed session cookie that carries the YouTube cookies needed to call
 * InnerTube as a signed-in user.
 *
 *   1. Google OAuth  — the user clicks "Sign in with Google" in the app, the
 *      gateway runs the OAuth dance and converts the access token into
 *      YouTube cookies (the same trick ytmusicapi uses).
 *   2. Cookie paste  — the user pastes their YouTube cookie string, which is
 *      validated against InnerTube before it is accepted.
 *
 * The session cookie is signed with HMAC-SHA256 (JUSPLAYER_SECRET) and is
 * therefore stateless: it works on Vercel's serverless functions where there
 * is no shared memory between invocations.
 */

import crypto from "node:crypto";

const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";

const GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
const GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
const USERINFO = "https://openidconnect.googleapis.com/v1/userinfo";

export const SESSION_COOKIE = "jus_session";
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

// ------------------------------------------------------------------ cookies --

export function parseCookies(header = "") {
  const out = {};
  for (const part of String(header).split(";")) {
    const idx = part.indexOf("=");
    if (idx < 0) continue;
    const key = part.slice(0, idx).trim();
    if (!key) continue;
    try {
      out[key] = decodeURIComponent(part.slice(idx + 1).trim());
    } catch {
      out[key] = part.slice(idx + 1).trim();
    }
  }
  return out;
}

/** Keep only the cookies InnerTube actually cares about. */
export function normaliseYtCookie(cookie) {
  if (!cookie) return "";
  const wanted = [
    "SID",
    "HSID",
    "SSID",
    "APISID",
    "SAPISID",
    "__Secure-3PSID",
    "__Secure-1PSID",
    "__Secure-3PAPISID",
    "__Secure-1PAPISID",
    "LOGIN_INFO",
    "PREF",
  ];
  const parsed = parseCookies(cookie);
  const parts = [];
  for (const key of wanted) {
    if (parsed[key]) parts.push(`${key}=${parsed[key]}`);
  }
  return parts.join("; ");
}

export function sapisidFromCookie(cookie) {
  const parsed = parseCookies(cookie);
  return (
    parsed.SAPISID ||
    parsed["__Secure-3PAPISID"] ||
    parsed["__Secure-1PAPISID"] ||
    ""
  );
}

/** `Authorization: SAPISIDHASH <ts>_<sha1>` — what YouTube expects. */
export function sapisidHash(sapisid, timestamp = Math.floor(Date.now() / 1000)) {
  const hash = crypto
    .createHash("sha1")
    .update(`${timestamp} ${sapisid} ${sapisid}`)
    .digest("base64");
  return `SAPISIDHASH ${timestamp}_${hash}`;
}

/** Headers that turn an InnerTube call into a signed-in call. */
export function authHeaders(cookie) {
  const normalised = normaliseYtCookie(cookie);
  if (!normalised) return null;
  const sapisid = sapisidFromCookie(normalised);
  if (!sapisid) return null;
  return {
    cookie: normalised,
    authorization: sapisidHash(sapisid),
  };
}

// ------------------------------------------------------------------ session --

export function signSession(payload, secret) {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const mac = crypto.createHmac("sha256", secret).update(body).digest("base64url");
  return `${body}.${mac}`;
}

export function verifySession(token, secret) {
  if (!token || !secret) return null;
  const [body, mac] = String(token).split(".");
  if (!body || !mac) return null;
  const expected = crypto.createHmac("sha256", secret).update(body).digest("base64url");
  const a = Buffer.from(mac);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, "base64url").toString("utf8"));
    if (payload.exp && Date.now() > payload.exp) return null;
    return payload;
  } catch {
    return null;
  }
}

export function sessionCookieHeader(token, { secure = true, maxAge = SESSION_TTL_MS } = {}) {
  const attrs = [
    `${SESSION_COOKIE}=${token}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Lax",
    `Max-Age=${Math.floor(maxAge / 1000)}`,
  ];
  if (secure) attrs.push("Secure");
  return attrs.join("; ");
}

export function clearSessionCookieHeader({ secure = true } = {}) {
  const attrs = [`${SESSION_COOKIE}=`, "Path=/", "HttpOnly", "SameSite=Lax", "Max-Age=0"];
  if (secure) attrs.push("Secure");
  return attrs.join("; ");
}

export function createSession(ytCookie, extra = {}, secret) {
  return signSession(
    { yt: ytCookie, exp: Date.now() + SESSION_TTL_MS, ...extra },
    secret,
  );
}

// -------------------------------------------------------------------- OAuth --

export function oauthConfigured(env = process.env) {
  return Boolean(env.YT_CLIENT_ID && env.YT_CLIENT_SECRET);
}

export function oauthState() {
  return crypto.randomBytes(16).toString("base64url");
}

export function oauthUrl({ clientId, redirectUri, state }) {
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: "code",
    scope: "openid email profile https://www.googleapis.com/auth/youtube",
    access_type: "offline",
    prompt: "consent",
    include_granted_scopes: "true",
    state,
  });
  return `${GOOGLE_AUTH}?${params}`;
}

export async function exchangeCode({ code, clientId, clientSecret, redirectUri }) {
  const res = await fetch(GOOGLE_TOKEN, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code,
      client_id: clientId,
      client_secret: clientSecret,
      redirect_uri: redirectUri,
      grant_type: "authorization_code",
    }),
    signal: AbortSignal.timeout(20_000),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) {
    throw new Error(data.error_description || data.error || "OAuth token exchange failed");
  }
  return data;
}

/**
 * Exchange an OAuth access token for YouTube cookies: requesting
 * music.youtube.com with the bearer token makes YouTube hand back the
 * SAPISID family of cookies in `Set-Cookie`.
 */
export async function youtubeCookieFromToken(accessToken) {
  const res = await fetch("https://music.youtube.com/", {
    headers: {
      authorization: `Bearer ${accessToken}`,
      "x-goog-authuser": "0",
      "user-agent": UA,
      "accept-language": "en-US,en;q=0.9",
      origin: "https://music.youtube.com",
      referer: "https://music.youtube.com/",
    },
    redirect: "manual",
    signal: AbortSignal.timeout(20_000),
  });

  const raw = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  const jar = {};
  for (const line of raw) {
    const [pair] = String(line).split(";");
    const idx = pair.indexOf("=");
    if (idx < 0) continue;
    jar[pair.slice(0, idx).trim()] = pair.slice(idx + 1).trim();
  }
  const cookie = normaliseYtCookie(
    Object.entries(jar)
      .map(([k, v]) => `${k}=${v}`)
      .join("; "),
  );
  if (!cookie) throw new Error("YouTube did not return session cookies");
  return cookie;
}

/** Name / e-mail / avatar straight from Google. */
export async function fetchUserInfo(accessToken) {
  try {
    const res = await fetch(USERINFO, {
      headers: { authorization: `Bearer ${accessToken}`, "user-agent": UA },
      signal: AbortSignal.timeout(15_000),
    });
    if (!res.ok) return null;
    const data = await res.json();
    return {
      name: data.name || data.email || "YouTube Music",
      email: data.email || "",
      avatar: data.picture || "",
      googleId: data.sub || "",
    };
  } catch {
    return null;
  }
}
