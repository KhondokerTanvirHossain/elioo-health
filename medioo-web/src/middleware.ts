import { NextResponse, type NextRequest } from "next/server";
import { buildCsp, newNonce } from "./security/csp";

/**
 * Every page and route handler gets a fresh nonce and the CSP built from it.
 *
 * The nonce travels to the render on two request headers: `x-nonce` for our own code (the root layout refuses to render
 * without it), and `content-security-policy`, from which Next reads the nonce and stamps it on its own scripts.
 */
export function middleware(request: NextRequest) {
  const nonce = newNonce();
  const csp = buildCsp(nonce);

  const requestHeaders = new Headers(request.headers);
  requestHeaders.set("x-nonce", nonce);
  requestHeaders.set("content-security-policy", csp);

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set("content-security-policy", csp);
  response.headers.set("x-frame-options", "DENY");
  response.headers.set("x-content-type-options", "nosniff");
  response.headers.set("referrer-policy", "same-origin");
  return response;
}

export const config = {
  // Hashed static assets and the fonts carry their headers from next.config.ts instead (no nonce needed there).
  matcher: ["/((?!_next/static|_next/image|fonts/).*)"],
};
