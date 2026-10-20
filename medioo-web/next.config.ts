import type { NextConfig } from "next";

// The security headers that the middleware cannot reach: it skips /_next/static (hashed, immutable assets) so each
// asset request does not mint a nonce, but those responses still must not be sniffed or framed.
const STATIC_HEADERS = [
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "same-origin" },
];

const nextConfig: NextConfig = {
  output: "standalone",
  poweredByHeader: false,
  reactStrictMode: true,
  async headers() {
    return [
      { source: "/_next/static/:path*", headers: STATIC_HEADERS },
      { source: "/fonts/:path*", headers: STATIC_HEADERS },
    ];
  },
};

export default nextConfig;
