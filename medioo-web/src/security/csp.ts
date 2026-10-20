/**
 * The Content-Security-Policy for every Next page (plan 2026-10-21, Global Constraints → Web platform).
 *
 * - script-src: this origin plus the per-request nonce; Next stamps the nonce on its own inline scripts, reading it
 *   from the CSP request header the middleware forwards.
 * - style-src 'self': no inline styles anywhere, so no `style={}` in TSX (lint) and no next/image or next/font.
 * - img-src is copied verbatim from the Java pages' BaymaxSecurityHeadersFilter.CSP so the two policies cannot drift:
 *   crops 302 to S3 (https:), and the upload tray previews blob: URLs.
 */
const NONCE = /^[A-Za-z0-9+/]+={0,2}$/;

export function buildCsp(nonce: string): string {
  // The nonce is interpolated into a header: anything but base64 could close the source list and add a directive.
  if (!NONCE.test(nonce)) throw new Error("CSP nonce must be non-empty base64");
  return [
    "default-src 'none'",
    `script-src 'self' 'nonce-${nonce}'`,
    "style-src 'self'",
    "img-src 'self' https: http: blob:",
    "font-src 'self'",
    "connect-src 'self'",
    "frame-ancestors 'none'",
    "form-action 'self'",
    "base-uri 'none'",
  ].join("; ");
}

/** 16 random bytes, base64: a fresh nonce for one request. Web Crypto, so it runs in the Edge middleware runtime. */
export function newNonce(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}
