/*
 * How a page that waits for a paper to be read checks on it with JavaScript (PO ruling P6, 2026-10-30; WCAG F41), as
 * rules with no React in them: how long to wait before each check, when to give up, and what a status answer means.
 * They are Medioo's one reading of P6, kept here rather than in one route's folder so that every reading page follows
 * the same rules: the papers page's QuietCheck (timeline/QuietCheck.tsx) and the paper page's reading check
 * (components/ReadingProgress, plan W6) both run them (DR-42 amendment).
 * reading-check.test.ts judges them on their own.
 */

import { READING_STATUSES } from "./types";

/** A paper still being read (Java's AppTabs.papersBody `inFlight`, DocumentPages.inFlight). */
export const IN_FLIGHT: ReadonlySet<string> = new Set(READING_STATUSES);

/** The no-JavaScript reload interval and the first checks' spacing: Java's 5 s (`refresh(inFlight ? 5 : 0)`, progress.js). */
export const RELOAD_SECONDS = 5;
/** Checks at Java's own pace before backing off: the first half minute, when most papers are read. */
export const STEADY_CHECKS = 6;
/** Each later wait is this much longer than the one before, up to MAX_WAIT_MS. */
export const BACKOFF = 1.5;
export const MAX_WAIT_MS = 30_000;
/**
 * Checks before the page stops asking and offers its link instead: about six and a half minutes of checks, past
 * Java's "taking longer than usual" line at 5 min (DocumentPages.SLOW). A hidden tab's time is not counted.
 */
export const MAX_CHECKS = 20;

/** How long to wait before check number `done + 1`, after `done` checks: 5 s six times, then ×1.5 a time up to 30 s. */
export function waitBefore(done: number): number {
  const steady = RELOAD_SECONDS * 1000;
  if (done < STEADY_CHECKS) return steady;
  return Math.min(MAX_WAIT_MS, Math.round(steady * BACKOFF ** (done - STEADY_CHECKS + 1)));
}

/** What one paper's status answer says: still being read, read to `status`, gone, or not known this time. */
export type Seen = { kind: "reading" } | { kind: "settled"; status: string } | { kind: "gone" } | { kind: "unknown" };

/**
 * One status answer, judged. A 404 is a paper that is no longer there (deleted, or never this family's): the page is
 * re-rendered without it. Offline, a 5xx or an answer without a status is not a change: the next check asks again.
 */
export function judge(answer: { kind: string; status?: number; body?: unknown }): Seen {
  if (answer.kind === "refused" && answer.status === 404) return { kind: "gone" };
  if (answer.kind !== "ok") return { kind: "unknown" };
  const status = (answer.body as { status?: unknown } | null)?.status;
  if (typeof status !== "string") return { kind: "unknown" };
  return IN_FLIGHT.has(status) ? { kind: "reading" } : { kind: "settled", status };
}

/**
 * What the live region says once the page has re-rendered: each settled status's words (resolved by the server
 * parent from the bundle), once each, in the order first seen, joined as the page joins its parts. A paper that is
 * gone, or a status the words do not name, adds nothing; nothing said is an empty string.
 */
export function announcement(seen: Seen[], words: Readonly<Record<string, string>>): string {
  const said: string[] = [];
  for (const s of seen) {
    const w = s.kind === "settled" ? words[s.status] : undefined;
    if (w && !said.includes(w)) said.push(w);
  }
  return said.join(" · ");
}
