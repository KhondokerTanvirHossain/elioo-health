import type { NextConfig } from "next";
import { SECURITY_HEADERS } from "./src/security/headers";

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
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
