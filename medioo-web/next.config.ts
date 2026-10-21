import type { NextConfig } from "next";
import { SECURITY_HEADERS } from "./src/security/headers";

// The component catalog (/app/catalog-test, plan W2c) exists only in a build made with MEDIOO_CATALOG=1. The value is
// inlined at build time (src/catalog/flag.ts reads it), so a production build serves the route as a 404 whatever its
// runtime environment holds; and the catalog build writes to its own directory, so it can never be the .next that
// the Dockerfile copies into the image.
const catalog = process.env.MEDIOO_CATALOG === "1";

const nextConfig: NextConfig = {
  output: "standalone",
  distDir: catalog ? ".next-catalog" : ".next",
  env: { MEDIOO_CATALOG: catalog ? "1" : "0" },
  poweredByHeader: false,
  // `/app` and `/app/` are both the sign-in page (WhatsApp links and Java's redirects use both spellings), so neither
  // may 308 to the other: Next would otherwise strip the slash with a redirect before the page renders (plan W3).
  skipTrailingSlashRedirect: true,
  reactStrictMode: true,
  // The image optimizer (/_next/image) is never needed — next/image is banned by lint and crops are served by Java —
  // and it is sharp work plus a recurring CVE surface in a 320m container on a shared box. Unoptimized, it answers
  // 404 under the middleware's policy (csp.spec.ts asserts it).
  images: { unoptimized: true },
  async headers() {
    // The middleware skips /_next/static (hashed, immutable assets) so each asset request does not mint a nonce, but
    // those responses still must not be sniffed or framed. Everything else gets the headers from the middleware.
    return [{ source: "/_next/static/:path*", headers: [...SECURITY_HEADERS] }];
  },
};

export default nextConfig;
