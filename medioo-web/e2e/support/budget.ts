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
