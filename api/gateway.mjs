/**
 * Vercel serverless entry point for the JusPlayer gateway.
 *
 * Vercel serves the compiled Wasm client from `public/` and routes every
 * `/api/*` request here through the rewrite in `vercel.json`.
 */

import { handleRequest } from "../server/router.mjs";

export default async function handler(req, res) {
  try {
    await handleRequest(req, res, { static: false });
  } catch (err) {
    console.error(`[${req.method} ${req.url}]`, err);
    if (!res.headersSent) {
      res.statusCode = 500;
      res.setHeader("content-type", "application/json; charset=utf-8");
      res.end(JSON.stringify({ error: err.message || "internal error" }));
    } else {
      res.end();
    }
  }
}
