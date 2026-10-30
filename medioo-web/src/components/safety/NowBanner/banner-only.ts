/*
 * A banner-only refresh (plan W6, inherited from W5's review): a stale seen tap (`now.banner.seen`; Java answers
 * `show: true`) makes NowBannerHold re-read the page with `router.refresh()`, which re-runs the page's server reads. On
 * a paper page that would re-read GET /app/api/documents/{id} and record a DOCUMENT_VIEW the family did not make (one
 * open is one view).
 *
 * So a paper page holds itself: its main is wrapped in KeptOnBannerRefresh, which registers the page here (its path, as
 * the server rendered it, and the patient its toolbar names). Before the refresh the hold writes that registration into
 * a short-lived cookie; the paper page, seeing a marker for its own path, reads no paper and sends its main empty, and
 * KeptOnBannerRefresh keeps the main it already shows. The shell re-reads `me` and the banner as on every render, so the
 * banner is drawn again from what Java now says. The hold clears the cookie once the refresh has landed. A page that
 * registered nothing refreshes as before, re-reading everything.
 *
 * No directive and no copy: the server reads the cookie with readBannerOnly, the client islands write and clear it.
 */

export const BANNER_ONLY_COOKIE = "medioo_banner_only";
/** Seconds the marker lives if the hold never clears it (a tab closed mid-refresh): long enough for one refresh. */
const MAX_AGE = 10;

/** What a held page registers: its path as the server rendered it, and the patient its toolbar names (or nobody). */
export type HeldPage = { path: string; patient: string | null };

let held: HeldPage | null = null;

/** Registers the page as held until the returned function runs (KeptOnBannerRefresh's effect and its cleanup). */
export function holdOnBannerRefresh(page: HeldPage): () => void {
  held = page;
  return () => {
    if (held === page) held = null;
  };
}

/** The cookie that makes the next refresh banner-only for the held page, or false when no page is held. */
export function markBannerOnly(): boolean {
  if (held === null) return false;
  document.cookie = `${BANNER_ONLY_COOKIE}=${encodeURIComponent(JSON.stringify(held))}; Path=/app; Max-Age=${MAX_AGE}; SameSite=Strict`;
  return true;
}

export function clearBannerOnly(): void {
  document.cookie = `${BANNER_ONLY_COOKIE}=; Path=/app; Max-Age=0; SameSite=Strict`;
}

/**
 * The held page a request's marker names, when it names `path` (the request's own pathname); null for no marker, a
 * marker for another page (the family moved on before it was cleared), or one that is not a marker at all.
 */
export function readBannerOnly(value: string | undefined, path: string): HeldPage | null {
  if (value === undefined || value === "") return null;
  try {
    const page: unknown = JSON.parse(decodeURIComponent(value));
    if (typeof page !== "object" || page === null) return null;
    const { path: p, patient } = page as Record<string, unknown>;
    if (typeof p !== "string" || p !== path) return null;
    if (patient !== null && typeof patient !== "string") return null;
    return { path: p, patient };
  } catch {
    return null;
  }
}
