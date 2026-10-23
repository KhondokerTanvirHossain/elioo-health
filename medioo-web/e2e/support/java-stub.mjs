import { readFileSync } from "node:fs";
import { createServer } from "node:http";
import { basename, join } from "node:path";
import { FIXTURES, SCENARIOS } from "./scenarios.mjs";

/*
 * Stub Java for the e2e suite (plan Wave 3, Shared interfaces → e2e support). `next start` reads it as
 * JAVA_INTERNAL_URL; browser-api.ts answers the browser's own /app/api calls through the same `answer()`. It picks the
 * scenario from the `e2e_scenario` cookie; `baymax_session` means only "signed in". In AppApiGuard.filter's order: a
 * route that needs a session and has none is 401 UNAUTHENTICATED, whatever its headers; then a mutation is refused as
 * AppApiGuard.csrf does, in its order: no `X-Medioo: 1` is 403 MISSING_HEADER; a foreign Origin, or none on an open
 * route, is 403 FORBIDDEN_ORIGIN; no JSON or multipart type (a body-less POST or DELETE included) is 415
 * UNSUPPORTED_MEDIA_TYPE. Then a route the scenario does not map is 404 NOT_FOUND; a mapped one answers for the
 * request's query (`forQuery`), and a fixture never read for that query is 500 STUB_NO_FIXTURE_FOR_QUERY (`servesQuery`). It records every request and serves the log at GET /__requests (DELETE clears it). It
 * logs nothing.
 */

/** The routes that need no session (AppApiGuard.OPEN, W3-api). */
export const OPEN = new Set(["POST /app/api/sign-in/request", "POST /app/api/sign-in/verify", "GET /app/api/sign-in/state"]);
const MUTATIONS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** One cookie's value from a Cookie header. */
export function cookieValue(header, name) {
  for (const part of (header ?? "").split(";")) {
    const at = part.indexOf("=");
    if (at > 0 && part.slice(0, at).trim() === name) return part.slice(at + 1).trim();
  }
  return null;
}

/** Whether `path` matches the route pattern (a `{name}` segment is any one non-empty segment). */
function matches(pattern, path) {
  const want = pattern.split("/");
  const got = path.split("/");
  return want.length === got.length && want.every((s, i) => (/^\{\w+\}$/.test(s) ? got[i] !== "" : s === got[i]));
}

/** The scenario key (`METHOD pattern`) a request is answered from, or null. */
export function routeKey(scenario, method, path) {
  for (const key of Object.keys(scenario)) {
    const [m, pattern] = key.split(" ");
    if (m === method && matches(pattern, path)) return key;
  }
  return null;
}

/**
 * The value a scenario key answers for this request's query. A `{ byQuery, otherwise }` value answers with the first
 * `byQuery` entry whose every `when` parameter the query carries with exactly that value (a page's `?type=lab`, a
 * `?cursor=`, a `?month=`), else with `otherwise`; any other value answers every query alike.
 */
export function forQuery(value, query) {
  return chosen(value, query).answer;
}

/** forQuery's answer, the `when` that chose it (none for `otherwise` or a plain value), and the parameters the table keys on. */
function chosen(value, query) {
  if (value === null || typeof value !== "object" || Array.isArray(value) || !("byQuery" in value)) {
    return { answer: value, when: {}, keyed: [] };
  }
  const params = new URLSearchParams(query ?? "");
  const hit = value.byQuery.find(({ when }) => Object.entries(when).every(([k, v]) => params.get(k) === v));
  const keyed = [...new Set(value.byQuery.flatMap(({ when }) => Object.keys(when)))];
  return hit ? { answer: hit.answer, when: hit.when, keyed } : { answer: value.otherwise, when: {}, keyed };
}

/** What the stub answers when the fixture it would serve was never read for this query: never a body Java did not write. */
const NO_FIXTURE_FOR_QUERY = { status: 500, body: { reason: "STUB_NO_FIXTURE_FOR_QUERY" } };

/**
 * Whether a fixture file may answer this query (plan W8 review). Its `_meta.source` names the query Java answered
 * (`GET …/calendar?month=2026-10 over …`): a parameter it names, carried with another value, is a request it never
 * answered (`busy.json` for `?month=2026-11`); a parameter the table keys on, carried by the request but neither in the
 * `when` that chose the fixture nor named by its source, is a page it is not (`lab-only.json` for its own next cursor).
 * A parameter the request leaves out is served: that is Java's default for it.
 */
function servesQuery(source, query, when, keyed) {
  const path = typeof source === "string" ? (source.split(" ")[1] ?? "") : "";
  const read = new URLSearchParams(path.includes("?") ? path.slice(path.indexOf("?") + 1) : "");
  const params = new URLSearchParams(query ?? "");
  for (const [k, v] of read) if (params.has(k) && params.get(k) !== v) return false;
  for (const k of keyed) if (params.has(k) && !(k in when) && read.get(k) !== params.get(k)) return false;
  return true;
}

function load(value, query = "", when = {}, keyed = []) {
  if (typeof value === "string") {
    const fixture = JSON.parse(readFileSync(join(FIXTURES, value), "utf8"));
    if (!servesQuery(fixture._meta?.source, query, when, keyed)) return NO_FIXTURE_FOR_QUERY;
    return { status: fixture.status ?? 200, body: fixture.body };
  }
  return value;
}

/**
 * The answer to one request. `counters` holds how many times each `scenario key` was answered (for answer lists); the
 * caller owns it, so the stub's is per run and the browser's per test.
 */
export function answer({ method, path, query = "", headers, origin, counters, scenarios = SCENARIOS }) {
  const name = cookieValue(headers.cookie, "e2e_scenario");
  const scenario = scenarios[name ?? ""];
  if (!scenario) return { status: 404, body: { reason: "NOT_FOUND" } };
  const signature = `${method} ${path}`;
  // AppApiGuard.filter's order: the session first, then (inside admitted()) CSRF for a mutation, then the route.
  if (!OPEN.has(signature) && cookieValue(headers.cookie, "baymax_session") === null) {
    return { status: 401, body: { reason: "UNAUTHENTICATED" } };
  }
  if (MUTATIONS.has(method)) {
    if (headers["x-medioo"] !== "1") return { status: 403, body: { reason: "MISSING_HEADER" } };
    const from = headers.origin;
    if ((from !== undefined && from !== origin) || (from === undefined && OPEN.has(signature))) {
      return { status: 403, body: { reason: "FORBIDDEN_ORIGIN" } };
    }
    const type = (headers["content-type"] ?? "").split(";")[0].trim().toLowerCase();
    if (type !== "application/json" && type !== "multipart/form-data") {
      return { status: 415, body: { reason: "UNSUPPORTED_MEDIA_TYPE" } };
    }
  }
  const key = routeKey(scenario, method, path);
  if (key === null) return { status: 404, body: { reason: "NOT_FOUND" } };
  const { answer: value, when, keyed } = chosen(scenario[key], query);
  if (!Array.isArray(value)) return load(value, query, when, keyed);
  const counter = `${name} ${key}`;
  const n = counters.get(counter) ?? 0;
  counters.set(counter, n + 1);
  return load(value[Math.min(n, value.length - 1)], query, when, keyed);
}

/** What the log keeps of one request: never a body, and of the headers only their names and the guard's three. */
export function record({ method, path, query, headers }) {
  return {
    method,
    path,
    query,
    run: cookieValue(headers.cookie, "e2e_run"),
    headerNames: Object.keys(headers).sort(),
    "x-medioo": headers["x-medioo"] ?? null,
    origin: headers.origin ?? null,
    "content-type": headers["content-type"] ?? null,
  };
}

function serve(port, host, origin) {
  let log = [];
  const counters = new Map();
  createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://stub.invalid");
    const send = (status, body) => {
      const text = typeof body === "string" ? body : JSON.stringify(body);
      res.writeHead(status, { "content-type": typeof body === "string" ? "text/plain" : "application/json" });
      res.end(text);
    };
    if (url.pathname === "/__requests") {
      if (req.method === "DELETE") {
        log = [];
        counters.clear();
        return send(200, { cleared: true });
      }
      return send(200, log);
    }
    req.resume();
    req.on("end", () => {
      const entry = { method: req.method ?? "GET", path: url.pathname, query: url.search.slice(1), headers: req.headers };
      const result = answer({ ...entry, origin, counters });
      // `{ abort }`: the connection dies under the request, as a phone's does mid-press (no status reaches anyone).
      if ("abort" in result) {
        log.push({ ...record(entry), status: 0 });
        return req.socket.destroy();
      }
      // `{ hold }`: the network goes quiet; nothing is ever answered (the browser's own watchdog ends it).
      if ("hold" in result) {
        log.push({ ...record(entry), status: 0 });
        return;
      }
      const { status, body } = result;
      // The status it answered, beside what it received, so a test can see which side of the guard a read fell on.
      log.push({ ...record(entry), status });
      if (status === 204) {
        res.writeHead(204);
        return res.end();
      }
      send(status, body);
    });
  }).listen(port, host);
}

// STUB_HOST 0.0.0.0 when it runs as the `java-stub` container on the Caddy replica's network (the image run). Started
// as a script, never when a test worker imports `answer` (no `import.meta`: those workers load this file as CommonJS).
if (basename(process.argv[1] ?? "") === "java-stub.mjs") {
  serve(
    Number(process.env.STUB_PORT ?? 3290),
    process.env.STUB_HOST ?? "127.0.0.1",
    process.env.E2E_ORIGIN ?? "http://127.0.0.1:3217",
  );
}
