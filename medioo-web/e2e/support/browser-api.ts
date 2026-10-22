import { randomUUID } from "node:crypto";
import { test, type Page, type Request } from "@playwright/test";
import { answer, record } from "./java-stub.mjs";
import { pngFor } from "./png.mjs";
import { signedOut } from "./scenarios.mjs";

/*
 * One test's Java (plan Wave 3, Shared interfaces → e2e support). `useScenario` sets the scenario cookie (and the
 * session cookie, unless the scenario is a signed-out one) for path /app, so `next start` reads the scenario from stub
 * Java; and it answers the browser's own `/app/api/**` calls from the same table, under the same guard, so a screen's
 * mutation is refused exactly where Java would refuse it. Java's image routes are answered with `pngFor(row)`.
 */

/** One part of a multipart body, its bytes as sent (a file's are binary, so never read as text to be compared). */
export type Part = { name: string; filename: string | null; data: Buffer };

export type BrowserRequest = {
  method: string;
  path: string;
  query: string;
  headers: Record<string, string>;
  /** The JSON body, or a multipart body's parts by field name, or null. */
  body: unknown;
  /** A multipart body's parts in the order sent, repeated names kept (every `file` of a press); null otherwise. */
  parts: Part[] | null;
  /** What the browser was answered: the status, or "aborted" when the scenario cut the request on the wire. */
  answered: number | "aborted";
  /** The `{"reason"}` of a refusal (the guard's MISSING_HEADER, a route's VIEW_ONLY), else null. */
  reason: string | null;
};

export type StubRecord = {
  method: string;
  path: string;
  query: string;
  run: string | null;
  headerNames: string[];
  "x-medioo": string | null;
  origin: string | null;
  "content-type": string | null;
  /** The status stub Java answered. */
  status: number;
};

/** A row whose image Java serves: its crop's href and stored size (PaperValueJson.crop). */
export type CropRow = { crop: { src: string; width: number | null; height: number | null } };

export type Recorder = {
  /** The browser's /app/api requests in this test, in order. */
  requests(): BrowserRequest[];
  /** What stub Java received from the Next server in this test (matched by this test's own run cookie). */
  java(): Promise<StubRecord[]>;
  run: string;
};

/** Where stub Java listens (playwright.config.ts sets it for the workers). */
export const STUB_URL = process.env.E2E_STUB_URL ?? "http://127.0.0.1:3290";

/** The multipart parts by field name, or the JSON body, or null. */
function parseBody(request: Request, headers: Record<string, string>): unknown {
  const type = headers["content-type"] ?? "";
  const raw = request.postDataBuffer();
  if (!raw) return null;
  if (type.startsWith("multipart/form-data")) {
    const boundary = /boundary=([^;]+)/.exec(type)?.[1];
    const parts: Record<string, string> = {};
    for (const part of raw.toString("latin1").split(`--${boundary}`)) {
      const name = /name="([^"]+)"/.exec(part)?.[1];
      if (name) parts[name] = part.split("\r\n\r\n").slice(1).join("\r\n\r\n").replace(/\r\n$/, "");
    }
    return parts;
  }
  try {
    return JSON.parse(raw.toString("utf8"));
  } catch {
    return raw.toString("utf8");
  }
}

/**
 * A multipart body's parts, read from its bytes: each part's name, its filename if it is a file, and its own bytes
 * exactly (the CRLF before the next boundary is the framing, not the part). Binary-safe, so a file's SHA-256 is the
 * file's.
 */
export function multipartParts(raw: Buffer, boundary: string): Part[] {
  const delimiter = Buffer.from(`--${boundary}`);
  const parts: Part[] = [];
  let at = raw.indexOf(delimiter);
  while (at !== -1) {
    const start = at + delimiter.length;
    if (raw.subarray(start, start + 2).toString("latin1") === "--") break;
    const next = raw.indexOf(delimiter, start);
    if (next === -1) break;
    const chunk = raw.subarray(start + 2, next - 2);
    const headEnd = chunk.indexOf("\r\n\r\n");
    if (headEnd !== -1) {
      const head = chunk.subarray(0, headEnd).toString("utf8");
      const name = /\bname="([^"]*)"/.exec(head)?.[1];
      const filename = /\bfilename="([^"]*)"/.exec(head)?.[1] ?? null;
      if (name !== undefined) parts.push({ name, filename, data: Buffer.from(chunk.subarray(headEnd + 4)) });
    }
    at = next;
  }
  return parts;
}

function partsOf(request: Request, headers: Record<string, string>): Part[] | null {
  const type = headers["content-type"] ?? "";
  const raw = request.postDataBuffer();
  const boundary = /boundary=([^;]+)/.exec(type)?.[1];
  if (!raw || !type.startsWith("multipart/form-data") || !boundary) return null;
  return multipartParts(raw, boundary);
}

/** The scenario for this test, the session cookie unless it is a signed-out one, and the browser's Java. */
export async function useScenario(
  page: Page,
  name: string,
  opts: { images?: Record<string, number[]>; rows?: CropRow[] } = {},
): Promise<Recorder> {
  const images = opts.images ?? {};
  const rows = new Map((opts.rows ?? []).map((r) => [r.crop.src, r]));
  // The origin the page under test is served from: its project's baseURL (the catalog build for /app/shell-test, W4),
  // so the guard's Origin check refuses exactly what Java would refuse for that page.
  const base = new URL(
    test.info().project.use.baseURL ?? process.env.E2E_BASE_URL ?? `http://127.0.0.1:${process.env.E2E_PORT ?? 3217}`,
  );
  const run = randomUUID();
  const cookie = (n: string, value: string) => ({ name: n, value, domain: base.hostname, path: "/app" });
  // A second call in one test (signed in, then signed out) must not keep the first scenario's session.
  await page.context().clearCookies({ name: "baymax_session" });
  await page.context().addCookies([
    cookie("e2e_scenario", name),
    cookie("e2e_run", run),
    ...(signedOut(name) ? [] : [cookie("baymax_session", "e2e")]),
  ]);

  const seen: BrowserRequest[] = [];
  const counters = new Map<string, number>();
  await page.route("**/app/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const headers = await request.allHeaders();
    const entry: BrowserRequest = {
      method: request.method(),
      path: url.pathname,
      query: url.search.slice(1),
      headers,
      body: parseBody(request, headers),
      parts: partsOf(request, headers),
      answered: 0,
      reason: null,
    };
    seen.push(entry);
    const result = answer({ method: request.method(), path: url.pathname, headers, origin: base.origin, counters });
    // `{ abort: "<reason>" }`: the network dies under the request (a phone losing signal mid-press), after the guard
    // let it through, so what reaches the route is a request Java would have accepted.
    if ("abort" in result) {
      entry.answered = "aborted";
      return route.abort(result.abort);
    }
    const { status, body } = result;
    entry.answered = status;
    const reason = (body as { reason?: unknown } | null)?.reason;
    entry.reason = status >= 400 && typeof reason === "string" ? reason : null;
    if (status === 204) return route.fulfill({ status });
    return route.fulfill({
      status,
      contentType: typeof body === "string" ? "text/plain" : "application/json",
      body: typeof body === "string" ? body : JSON.stringify(body),
    });
  });

  // Java's crop and page images: each row's own PNG, or the per-key answer list (`{ "<key>": [403, 200] }`), where a
  // 303 answers as Java's image route does without a session.
  const served = new Map<string, number>();
  const image = async (route: import("@playwright/test").Route) => {
    const url = new URL(route.request().url());
    const key = url.searchParams.get("key") ?? url.pathname;
    const list = images[key];
    if (list) {
      const n = served.get(key) ?? 0;
      served.set(key, n + 1);
      const status = list[Math.min(n, list.length - 1)]!;
      if (status === 303) {
        return route.fulfill({ status, headers: { location: `/app/?next=${encodeURIComponent(url.pathname + url.search)}` } });
      }
      if (status !== 200) return route.fulfill({ status });
    }
    const src = url.pathname + url.search;
    const row = rows.get(src) ?? { crop: { src, width: null, height: null } };
    return route.fulfill({ status: 200, contentType: "image/png", body: pngFor(row) });
  };
  await page.route("**/app/documents/*/image?key=*", image);
  await page.route("**/app/documents/*/page/*", image);

  return {
    run,
    requests: () => [...seen],
    java: async () => {
      const response = await fetch(`${STUB_URL}/__requests`);
      const all = (await response.json()) as StubRecord[];
      return all.filter((r) => r.run === run);
    },
  };
}

export { record };
