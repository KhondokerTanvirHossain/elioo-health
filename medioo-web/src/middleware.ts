import { NextResponse, type NextRequest } from "next/server";
import { buildCsp, newNonce } from "./security/csp";
import { SECURITY_HEADERS } from "./security/headers";

/**
 * Every response except the hashed static assets gets a fresh nonce and the CSP built from it: pages, route handlers,
 * /fonts/*, /_next/image and every path nothing serves. The last matters most: a path that skipped the middleware but
 * rendered the not-found page reached the root layout without a nonce and came back as a 500 error page with
 * un-nonced scripts and no CSP. Now it is a 404 under the policy (csp.spec.ts asserts both).
 *
 * How the nonce reaches the HTML (observed with Next 15.5, not just documented):
 * - `x-nonce` on the request is for our own code; the root layout refuses to render without it.
 * - Next stamps the nonce on its own scripts by reading the `content-security-policy` header. Removing the
 *   request-header copy below changed nothing, because Next also reads the nonce from this middleware's response
 *   CSP. The request-header copy is kept as the documented API. Whichever source Next uses, csp.spec.ts fails if it
 *   stops: it requires every <script> on the probe to carry exactly the header's nonce.
 *
 * The preview (plan Wave 3): `/app/preview` and `/app/preview/<rest>` are rewritten to `/app` and `/app/<rest>` and
 * marked `x-medioo-preview: 1`, which src/api/java.ts reads as "read nothing but `me` until `me` says preview". The
 * mark and `x-medioo-path` (the path and query the browser asked for) are the middleware's alone: a client's copies
 * are deleted first, so no request can mark itself a preview or name a place to return to.
 */
const PREVIEW = /^\/app\/preview(?=\/|$)/;

export function middleware(request: NextRequest) {
  const nonce = newNonce();
  const csp = buildCsp(nonce);

  const requestHeaders = new Headers(request.headers);
  requestHeaders.delete("x-medioo-preview");
  requestHeaders.delete("x-medioo-path");
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("content-security-policy", csp);
  const { pathname, search } = request.nextUrl;
  requestHeaders.set("x-medioo-path", pathname + search);

  let response: NextResponse;
  if (PREVIEW.test(pathname)) {
    requestHeaders.set("x-medioo-preview", "1");
    const target = request.nextUrl.clone();
    target.pathname = pathname.replace(PREVIEW, "/app");
    response = NextResponse.rewrite(target, { request: { headers: requestHeaders } });
  } else {
    response = NextResponse.next({ request: { headers: requestHeaders } });
  }
  response.headers.set("content-security-policy", csp);
  for (const { key, value } of SECURITY_HEADERS) response.headers.set(key, value);
  return response;
}

export const config = {
  // Only the hashed, immutable assets skip it (they carry SECURITY_HEADERS from next.config.ts, no nonce needed). The
  // trailing slash matters: without it, look-alikes such as /_next/staticx skipped the middleware too.
  matcher: ["/((?!_next/static/).*)"],
};
