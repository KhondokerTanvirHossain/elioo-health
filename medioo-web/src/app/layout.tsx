import type { Metadata, Viewport } from "next";
import { headers } from "next/headers";
import type { ReactNode } from "react";
import { langOf } from "@/copy";
import { dataThemeOf, themeOf } from "./theme";
// The generated tokens first: globals.css and every component stylesheet read their colours, fonts and sizes from it.
import "@/styles/tokens.css";
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
  const requestHeaders = await headers();
  const nonce = requestHeaders.get("x-nonce");
  if (!nonce) throw new Error("medioo-web: no CSP nonce on this request (the middleware did not run)");
  const cookie = requestHeaders.get("cookie") ?? undefined;
  // bn is the default and Accept-Language never changes it; only the baymax_lang cookie does, read as Java's Lang.of.
  // The theme is the baymax_theme cookie Java's PUT /app/api/preferences sets (plan W10): light or dark is written as
  // data-theme, which tokens.css reads; auto, or no cookie, writes nothing, so the phone's own scheme applies.
  return (
    <html lang={langOf(cookie)} data-theme={dataThemeOf(themeOf(cookie))}>
      <body>{children}</body>
    </html>
  );
}
