#!/usr/bin/env node
/**
 * JusPlayer terminal client — entry point.
 *
 *   node src/main.mjs
 *   node src/main.mjs --api http://127.0.0.1:8787
 */

import { main } from "./app.mjs";

main().catch((error) => {
  console.error(`jusplayer: ${error.message}`);
  process.exit(1);
});
