import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * The DR-42 first-load budget, as budget.spec.ts enforces it (DR-42 items 2-4; screens plan S9).
 *
 * The two ceilings live in one committed file and nowhere else: `probe` (the framework floor) and `route` (every page
 * in NEXT_PAGES). The spec compares a measurement with the file, never with a probe measured in the same run, which
 * would move with the framework. The file changes only with PO sign-off, and the PR that changes it says so.
 */
export const CEILINGS_FILE = join(__dirname, "..", "budget-ceilings.json");

export type Ceilings = { probe: number; route: number };
export type Ceiling = keyof Ceilings;

export function readCeilings(file: string = CEILINGS_FILE): Ceilings {
  let text: string;
  try {
    text = readFileSync(file, "utf8");
  } catch (e) {
    throw new Error(`budget-ceilings: cannot read ${file} (${(e as NodeJS.ErrnoException).code ?? e})`);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error(`budget-ceilings: ${file} is not JSON`);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    throw new Error(`budget-ceilings: ${file} must be an object`);
  }
  const keys = Object.keys(parsed).sort();
  if (JSON.stringify(keys) !== JSON.stringify(["probe", "route"])) {
    throw new Error(`budget-ceilings: ${file} must hold exactly "probe" and "route", found ${JSON.stringify(keys)}`);
  }
  const record = parsed as Record<Ceiling, unknown>;
  for (const key of ["probe", "route"] as const) {
    const value = record[key];
    if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
      throw new Error(`budget-ceilings: "${key}" must be a positive whole number of bytes, found ${JSON.stringify(value)}`);
    }
  }
  return { probe: record.probe as number, route: record.route as number };
}

/**
 * Java's image routes: a value's crop (`/app/documents/{id}/image?key=…`) and a page scan
 * (`/app/documents/{id}/page/{n}`). They are served by Java at the photo's real size, so they say nothing about the
 * page's code, and against the e2e stub their bytes are synthetic. S9 (PO, 2026-10-22) leaves them out of a page's
 * route total; every other response counts, images from anywhere else included.
 */
const JAVA_IMAGE = /^\/app\/documents\/[^/]+\/(?:image|page\/[^/]+)$/;

export function isJavaImage(url: string): boolean {
  return JAVA_IMAGE.test(new URL(url, "http://budget.invalid").pathname);
}

/** A page's route total: the wire bytes of every response of its cold load, minus Java's images (S9). */
export function routeTotal(rows: readonly { url: string; wire: number }[]): number {
  return rows.filter((r) => !isJavaImage(r.url)).reduce((sum, r) => sum + r.wire, 0);
}

const bytes = (n: number) => n.toLocaleString("en-US");

/** The failure message: which ceiling, the page, the measured and the allowed bytes. */
export function ceilingMessage(ceiling: Ceiling, page: string, measured: number, allowed: number): string {
  const over = measured > allowed ? ` (${bytes(measured - allowed)} B over)` : "";
  return `${ceiling} ceiling (budget-ceilings.json "${ceiling}"): ${page} is ${bytes(measured)} B on the wire, allowed ${bytes(allowed)} B${over}`;
}

/**
 * The path a NEXT_PAGES entry is loaded at. A dynamic segment needs a fixture to fill it (W3's PAGE_SCENARIOS); until
 * one exists the page is refused here, never loaded with a made-up id whose 404 would measure small.
 */
export function concretePath(pattern: string): string {
  if (/\[[^\]]*\]|\{[^}]*\}/.test(pattern)) {
    throw new Error(`budget: ${pattern} has a dynamic segment and no fixture fills it; give it a scenario`);
  }
  return pattern;
}

/** The classes Caddy's `encode` compresses on a page load (text/html, JavaScript, CSS, RSC's text/x-component). */
const ENCODED_KINDS = new Set(["document", "script", "stylesheet", "rsc"]);
/** Caddy's encode MinLength: a body whose first write (or Content-Length) is 512 B or less is sent as it is. */
export const CADDY_MIN_LENGTH = 512;

/**
 * The response header the harness's measuring proxy (encode-proxy.mjs) adds to a streamed answer: the size of the
 * upstream's first write, the one Caddy decides on. Harness-only; Caddy sends nothing like it, so behind Caddy a row
 * has no first write on record.
 */
export const FIRST_WRITE_HEADER = "x-encode-proxy-first-write";

export type EncodedRow = {
  kind: string;
  url: string;
  status: number;
  encoding: string;
  decoded: number;
  /** The Content-Length the browser received, or null for a streamed body (an encoded body never has one). */
  contentLength: number | null;
  /** A streamed body's first write, when the harness recorded it (FIRST_WRITE_HEADER); undefined or null otherwise. */
  firstWrite?: number | null;
};

/**
 * Whether Caddy's encode handler, as configured, encodes this unencoded body (encode.go, MinLength 512): it decides on
 * the first write, encoding when that write or the Content-Length is over 512 B, and never on the total. A streamed
 * body with no first write on record (behind Caddy) is judged only when it is a document, whose first flush is Next's
 * HTML shell and always far over 512 B; any other streamed body (an RSC payload) may rightly have gone unencoded.
 */
function caddyEncodes(r: EncodedRow): boolean {
  if (r.contentLength !== null) return r.contentLength > CADDY_MIN_LENGTH;
  if (typeof r.firstWrite === "number") return r.firstWrite > CADDY_MIN_LENGTH;
  return r.kind === "document" && r.decoded > CADDY_MIN_LENGTH;
}

/**
 * The rows that did not cross the wire as Caddy encodes them (PO ruling P3), for a browser Caddy answers in `expected`
 * (zstd for Chrome, which offers it; gzip for a browser that does not). Of every document, script, stylesheet and RSC
 * body answered 200: one in another encoding is refused outright (`gzip` to Chrome means Next encoded it itself,
 * compress: false undone), and an unencoded one is refused when Caddy would have encoded it (caddyEncodes), which means
 * nothing in front of Next encoded it: a measurement no phone gets. Empty when the load is the shipped path.
 */
export function unencodedByCaddy(rows: readonly EncodedRow[], expected: "zstd" | "gzip" = "zstd"): string[] {
  return rows
    .filter((r) => ENCODED_KINDS.has(r.kind) && r.status === 200 && r.encoding !== expected)
    .filter((r) => r.encoding !== "identity" || caddyEncodes(r))
    .map((r) => `${r.url} (${r.kind}, ${r.decoded} B, content-encoding ${r.encoding})`);
}

/** The two self-hosted Noto subsets the W1a probe measured, both on every measured load (DR-42 item 2). */
export const NOTO_SUBSETS = ["/fonts/noto-sans-bengali.woff2", "/fonts/noto-sans-latin.woff2"] as const;

export type MeasuredRow = { kind: string; url: string; status: number; wire: number };

/**
 * Why a load's fonts do not make it a whole DR-42 measurement: empty when both Noto subsets crossed the wire, once
 * each, answering 200 with non-zero wire bytes, and no other font came with them. globals.css loads the subsets by
 * unicode-range, so a page that lays out no Bangla (the `en` cookie, a Latin-only error state) fetches no Bengali
 * subset, and a broken @font-face, path or CSP font-src fetches nothing at all; either would measure up to 97,648 B
 * under the truth and pass. A measurement that missed a class is flattering, not small.
 */
export function fontProblems(rows: readonly MeasuredRow[]): string[] {
  const pathOf = (url: string) => new URL(url, "http://budget.invalid").pathname;
  const fonts = rows.filter((r) => r.kind === "font");
  const problems: string[] = [];
  for (const subset of NOTO_SUBSETS) {
    const seen = fonts.filter((r) => pathOf(r.url) === subset);
    if (seen.length === 0) {
      problems.push(`missing ${subset}`);
      continue;
    }
    if (seen.length > 1) problems.push(`${subset} fetched ${seen.length} times`);
    for (const r of seen) {
      if (r.status !== 200) problems.push(`${subset} answered ${r.status}`);
      else if (r.wire <= 0) problems.push(`${subset} has ${r.wire} B on the wire`);
    }
  }
  const known: readonly string[] = NOTO_SUBSETS;
  for (const r of fonts) if (!known.includes(pathOf(r.url))) problems.push(`unexpected font ${r.url}`);
  return problems;
}
