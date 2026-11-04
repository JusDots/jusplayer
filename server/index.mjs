#!/usr/bin/env node
/**
 * JusPlayer gateway — local development server.
 *
 *   node server/index.mjs            # http://localhost:8787
 *   PORT=9000 node server/index.mjs
 *
 * The same router powers the Vercel deployment (`api/gateway.mjs`); this
 * wrapper only adds a plain Node HTTP server plus static hosting of the
 * compiled Compose/Wasm build.
 */

import http from "node:http";
import { handleRequest } from "./router.mjs";

const PORT = Number(process.env.PORT || 8787);

const server = http.createServer((req, res) => {
  handleRequest(req, res, { static: true }).catch((err) => {
    console.error(`[${req.method} ${req.url}]`, err);
    if (!res.headersSent) {
      res.writeHead(500, { "content-type": "application/json; charset=utf-8" });
      res.end(JSON.stringify({ error: err.message || "internal error" }));
    } else {
      res.end();
    }
  });
});

server.listen(PORT, () => {
  console.log(`\n  JusPlayer web gateway`);
  console.log(`  →  http://localhost:${PORT}`);
  console.log(`  static: ${process.env.JUSPLAYER_DIST || "web/build/dist/wasmJs/productionExecutable"}\n`);
});
