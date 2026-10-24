/*
 * A return path a page may link to (`back`): "/" or a page under /app, checked raw and decoded; anything else is the
 * fallback. The same rule as Java's LocalPath (baymax web.adapter.in.ui.LocalPath), held to its cases by
 * local-path.test.ts: a browser reads "/\host" (and "/%5Chost") as "//host", another site, so a backslash anywhere is
 * refused; it resolves "." and ".." segments (and "%2e" spellings) before it requests, so "/app/../api" is not under
 * /app and is refused too. A malformed escape is the fallback, never an exception. No copy here: the browser bundle
 * may import this module.
 */

const SAFE = /^(?:\/|\/app(?:\/[A-Za-z0-9._~%/-]*)?(?:\?[A-Za-z0-9._~%=&/-]*)?)$/;

/** A whole path segment that is "." or ".." (the query is not resolved by the browser, so it is not looked at). */
function hasDotSegment(path: string): boolean {
  const query = path.indexOf("?");
  return (query < 0 ? path : path.slice(0, query)).split("/").some((segment) => segment === "." || segment === "..");
}

/** `raw` when it is a page of this app (or the landing), as written; otherwise `fallback`. */
export function localPathOr(raw: string | null, fallback: string): string {
  if (raw === null || raw.includes("\\") || !SAFE.test(raw)) return fallback;
  let decoded: string;
  try {
    decoded = decodeURIComponent(raw);
  } catch {
    return fallback;
  }
  return decoded.includes("\\") || decoded.startsWith("//") || !SAFE.test(decoded) || hasDotSegment(raw) || hasDotSegment(decoded)
    ? fallback
    : raw;
}
