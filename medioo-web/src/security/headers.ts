/**
 * The non-CSP security headers on every response Next serves (plan Global Constraints → Web platform).
 *
 * Referrer-Policy is strict-origin-when-cross-origin, the value Caddy and Java already send (DR-40 A1): Caddy's
 * medioo header block runs at response-write time and overwrites the upstream value on every path, so any other
 * value here would be tested locally and never reach a browser.
 */
export const SECURITY_HEADERS = [
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
] as const;
