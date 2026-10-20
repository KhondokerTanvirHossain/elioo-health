import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
import type { ReactNode } from "react";
import "./globals.css";

export const metadata: Metadata = {
  title: "Medioo",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

/**
 * Reading the request headers makes every page dynamic, which the nonce requires: a prerendered page would carry a
 * build-time nonce that no request's CSP allows. A request that reached a page without the middleware has no nonce and
 * no CSP, so the layout refuses it rather than serve a page outside the policy.
 */
export default async function RootLayout({ children }: { children: ReactNode }) {
  const nonce = (await headers()).get("x-nonce");
  if (!nonce) throw new Error("medioo-web: no CSP nonce on this request (the middleware did not run)");
  // bn is the default and Accept-Language never changes it; only the baymax_lang cookie will (W2b).
  return (
    <html lang="bn">
      <body>{children}</body>
    </html>
  );
}
