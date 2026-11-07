#!/usr/bin/env node
/**
 * Build the web client and stage it for Vercel.
 *
 *   node scripts/build-web.mjs              # build the Wasm bundle, then copy it
 *   JUSPLAYER_PREBUILT=1 node scripts/...   # only copy an already-built bundle
 *
 * The Wasm output lands in `public/`, which Vercel serves as static files.
 * The gateway itself is a serverless function (`api/gateway.mjs`).
 */

import { spawnSync } from "node:child_process";
import { cp, mkdir, readdir, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const dist = path.join(root, "web/build/dist/wasmJs/productionExecutable");
const out = path.join(root, "public");

const prebuilt = process.env.JUSPLAYER_PREBUILT === "1";
const skipBuild = prebuilt || process.env.JUSPLAYER_SKIP_BUILD === "1";

async function exists(target) {
  try {
    await stat(target);
    return true;
  } catch {
    return false;
  }
}

/** Rank a JDK directory: 21+ first, then 17, then anything plausible. */
function scoreJavaHome(dir) {
  const name = path.basename(dir).toLowerCase();
  if (name.includes("21")) return 30;
  if (name.includes("17")) return 20;
  if (name.includes("22") || name.includes("23") || name.includes("24")) return 25;
  if (/(jdk|jre|java|temurin|corretto|zulu|graal)/.test(name)) return 10;
  return 0;
}

/** Find a JDK 17+ so the Gradle build works on any machine. */
async function findJavaHome() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);

  const roots = [
    "/usr/lib/jvm",
    "/usr/java",
    "/opt/java",
    "/opt/jdk",
    "/Library/Java/JavaVirtualMachines",
    path.join(process.env.HOME || "", ".local/share/mise/installs/java"),
  ];

  for (const rootDir of roots) {
    let entries = [];
    try {
      entries = await readdir(rootDir);
    } catch {
      continue;
    }
    for (const entry of entries) {
      candidates.push(path.join(rootDir, entry));
      // macOS bundles live one level deeper: …/JavaVirtualMachines/temurin.jdk/Contents/Home
      candidates.push(path.join(rootDir, entry, "Contents", "Home"));
    }
  }

  let best = null;
  for (const dir of candidates) {
    if (!dir || scoreJavaHome(dir) === 0) continue;
    if (!(await exists(path.join(dir, "bin", "java")))) continue;
    if (!best || scoreJavaHome(dir) > scoreJavaHome(best)) best = dir;
  }
  return best;
}

if (!skipBuild) {
  console.log("▸ Building the Compose/Wasm client (this takes a couple of minutes)…");
  const javaHome = await findJavaHome();
  if (!javaHome) {
    console.error("✗ No JDK 17+ found. Install one (Temurin 21 is ideal) or set JAVA_HOME.");
    console.error("  Alternatively deploy the pre-built bundle:");
    console.error("    JUSPLAYER_PREBUILT=1 node scripts/build-web.mjs");
    process.exit(1);
  }
  console.log(`  using JDK: ${javaHome}`);
  const gradle = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  const result = spawnSync(gradle, [":web:wasmJsBrowserDistribution"], {
    cwd: root,
    stdio: "inherit",
    env: { ...process.env, JAVA_HOME: javaHome },
  });
  if (result.status !== 0) {
    console.error("✗ Gradle build failed");
    process.exit(result.status ?? 1);
  }
} else {
  console.log("▸ Skipping the Gradle build (pre-built bundle expected).");
}

/**
 * Stage a static yt-dlp binary next to the serverless function.
 *
 * YouTube's plain InnerTube URLs stop after about 1 MB, which is fine for the
 * first minute of a song but breaks long playback and offline downloads. The
 * gateway uses yt-dlp when it is available; on Vercel we ship the static
 * Linux build with the function (see `includeFiles` in vercel.json).
 */
async function stageYtDlp() {
  if (process.env.JUSPLAYER_SKIP_YTDLP === "1") return;
  if (!process.env.VERCEL && process.env.JUSPLAYER_YTDLP !== "1") return;

  const dir = path.join(root, "bin");
  const target = path.join(dir, "yt-dlp");
  if (await exists(target)) {
    console.log("▸ yt-dlp already staged in bin/");
    return;
  }

  const url =
    process.env.JUSPLAYER_YTDLP_URL ||
    "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux";
  try {
    console.log("▸ Downloading yt-dlp (for whole-track playback and downloads)…");
    const res = await fetch(url, { redirect: "follow", signal: AbortSignal.timeout(240_000) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const buffer = Buffer.from(await res.arrayBuffer());
    if (buffer.length < 5_000_000) throw new Error(`unexpected size: ${buffer.length} bytes`);
    await mkdir(dir, { recursive: true });
    await writeFile(target, buffer, { mode: 0o755 });
    console.log(`  staged ${(buffer.length / 1e6).toFixed(1)} MB at bin/yt-dlp`);
  } catch (err) {
    console.warn(`! yt-dlp download failed (${err.message})`);
    console.warn("  Hosted playback and downloads will be limited to YouTube's ~1 MB URLs.");
  }
}

await stageYtDlp();

if (!(await exists(path.join(dist, "index.html")))) {
  console.error(`✗ No web build found at ${dist}`);
  console.error("  Run: ./gradlew :web:wasmJsBrowserDistribution");
  process.exit(1);
}

console.log("▸ Staging the client in public/ …");
await rm(out, { recursive: true, force: true });
await mkdir(out, { recursive: true });
await cp(dist, out, { recursive: true });
console.log(`✓ ${path.relative(root, out)} is ready for Vercel.`);
