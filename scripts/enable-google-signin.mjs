#!/usr/bin/env node
/**
 * Turn on "Sign in with Google" for the Vercel deployment.
 *
 *   node scripts/enable-google-signin.mjs <client-id> <client-secret>
 *
 * What it does:
 *   1. stores both credentials as sensitive production env vars on Vercel
 *   2. rebuilds the client and redeploys it
 *   3. prints the redirect URI you must have registered in Google Cloud
 *
 * To get the credentials:
 *   https://console.cloud.google.com/apis/credentials
 *     → Create credentials → OAuth client ID → Application type: Web application
 *     → Authorised redirect URIs: https://jusplayer.vercel.app/api/auth/callback
 *     → Authorised JavaScript origins: https://jusplayer.vercel.app
 */

import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const clientId = process.argv[2] || process.env.YT_CLIENT_ID;
const clientSecret = process.argv[3] || process.env.YT_CLIENT_SECRET;

if (!clientId || !clientSecret) {
  console.error("Usage: node scripts/enable-google-signin.mjs <client-id> <client-secret>");
  console.error("");
  console.error("Create the credentials at https://console.cloud.google.com/apis/credentials");
  console.error("and register this redirect URI:");
  console.error("  https://jusplayer.vercel.app/api/auth/callback");
  process.exit(1);
}

function vercel(args, { allowFail = false } = {}) {
  console.log(`\n▸ npx vercel ${args.join(" ")}`);
  const result = spawnSync("npx", ["--yes", "vercel", ...args], {
    cwd: root,
    stdio: "inherit",
  });
  if (result.status !== 0 && !allowFail) {
    console.error("✗ That step failed — stopping.");
    process.exit(result.status ?? 1);
  }
  return result.status;
}

vercel(["env", "add", "YT_CLIENT_ID", "production", "--value", clientId, "--sensitive", "--yes"]);
vercel(["env", "add", "YT_CLIENT_SECRET", "production", "--value", clientSecret, "--sensitive", "--yes"]);

console.log("\n▸ Rebuilding and redeploying…");
vercel(["build", "--prod", "--yes"]);
vercel(["deploy", "--prebuilt", "--prod"]);

console.log("\n✓ Google sign-in is live at https://jusplayer.vercel.app");
console.log("  Open Settings → Account → “Sign in with Google”.");
