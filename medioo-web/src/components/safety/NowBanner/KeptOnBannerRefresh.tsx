"use client";

import { useEffect, useState, type ReactNode } from "react";
import { clearBannerOnly, holdOnBannerRefresh } from "./banner-only";

/**
 * A paper page's main, kept across a banner-only refresh (banner-only.ts): registers the page while it is mounted, and
 * when the server sends it no children (the refresh read no paper) keeps showing the children it last had. With
 * children it shows them, so every real render (an open, a navigation, ReadingProgress's refresh) is the server's.
 * Mounted with nothing to keep (a marker that outlived its refresh), it clears the marker and loads the page again.
 */
export function KeptOnBannerRefresh({ path, patient, children }: { path: string; patient: string | null; children?: ReactNode }) {
  const [kept, setKept] = useState<ReactNode>(children);
  if (children !== undefined && children !== kept) setKept(children);
  const empty = children === undefined && kept === undefined;

  useEffect(() => holdOnBannerRefresh({ path, patient }), [path, patient]);
  useEffect(() => {
    if (!empty) return;
    clearBannerOnly();
    location.reload();
  }, [empty]);

  return children ?? kept;
}
