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
import { cp, mkdir, rm, stat } from "node:fs/promises";
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

if (!skipBuild) {
  console.log("▸ Building the Compose/Wasm client (this takes a couple of minutes)…");
  const gradle = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
  const result = spawnSync(gradle, [":web:wasmJsBrowserDistribution"], {
    cwd: root,
    stdio: "inherit",
    env: {
      ...process.env,
      JAVA_HOME:
        process.env.JAVA_HOME ||
        "/home/reyansh/.local/share/mise/installs/java/temurin-21.0.13+9.0.LTS",
    },
  });
  if (result.status !== 0) {
    console.error("✗ Gradle build failed");
    process.exit(result.status ?? 1);
  }
} else {
  console.log("▸ Skipping the Gradle build (pre-built bundle expected).");
}

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
