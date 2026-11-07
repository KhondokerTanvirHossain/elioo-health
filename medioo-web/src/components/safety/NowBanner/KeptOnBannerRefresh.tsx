"use client";

import { useEffect, useState, type ReactNode } from "react";
import { clearBannerOnly, holdOnBannerRefresh, rememberShown, shownPartBefore } from "./banner-only";

/**
 * A paper page's main, kept across a banner-only refresh (banner-only.ts): registers the page while it is mounted, and
 * when the server sends it no children (the refresh read no paper) keeps showing the children it last had. With
 * children it shows them, so every real render (an open, a navigation, ReadingProgress's refresh, which clears the
 * marker first) is the server's. Mounted fresh with no children (back to a page whose cached main is the refresh's
 * empty one), it shows what this tab last showed at that path (shownBefore). Mounted with nothing to keep at all (a
 * marker that outlived its refresh, on a load), it clears the marker and loads the page again.
 *
 * `part` (the desktop layout, plan desktop-parity Task 8): one of several holders on the same page, each keeping its own
 * column (the paper, the conversation, the panel) beside the phone shell's main. They register the same page; what each
 * showed is remembered under the path and its part (rememberShown), so no column is restored with another's content.
 */
export function KeptOnBannerRefresh({
  path,
  patient,
  part,
  children,
}: {
  path: string;
  patient: string | null;
  part?: string;
  children?: ReactNode;
}) {
  const [kept, setKept] = useState<ReactNode>(() => children ?? (shownPartBefore(path, part ?? "") as ReactNode));
  if (children !== undefined && children !== kept) setKept(children);
  const empty = children === undefined && kept === undefined;

  useEffect(() => holdOnBannerRefresh({ path, patient }), [path, patient]);
  useEffect(() => {
    if (children !== undefined) rememberShown(path, children, part ?? "");
  }, [path, part, children]);
  useEffect(() => {
    if (!empty) return;
    clearBannerOnly();
    location.reload();
  }, [empty]);

  return children ?? kept;
}
