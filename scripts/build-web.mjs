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

/** Find a JDK 17+ (21 preferred) so the Gradle build works on any machine. */
function findJavaHome() {
  const candidates = [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);

  const roots = ["/usr/lib/jvm", "/usr/java", "/opt/java", "/Library/Java/JavaVirtualMachines"];
  for (const root of roots) {
    try {
      for (const name of require("node:fs").readdirSync(root)) {
        candidates.push(path.join(root, name));
      }
    } catch {
      /* directory not there — fine */
    }
  }
  candidates.push("/home/reyansh/.local/share/mise/installs/java/temurin-21.0.13+9.0.LTS");

  const score = (dir) => {
    const name = path.basename(dir);
    if (/21/.test(name)) return 30;
    if (/17/.test(name)) return 20;
    if (/default|jdk|jre|java/i.test(name)) return 10;
    return 0;
  };

  let best = null;
  for (const dir of candidates) {
    if (!dir || score(dir) === 0) continue;
    if (!(await exists(path.join(dir, "bin", "java")))) continue;
    if (!best || score(dir) > score(best)) best = dir;
  }
  return best;
}

if (!skipBuild) {
  console.log("▸ Building the Compose/Wasm client (this takes a couple of minutes)…");
  const javaHome = await findJavaHome();
  if (!javaHome) {
    console.error("✗ No JDK 17+ found. Install one (for example Temurin 21) or set JAVA_HOME.");
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
