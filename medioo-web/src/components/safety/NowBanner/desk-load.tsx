"use client";

import dynamic from "next/dynamic";

/*
 * The NOW bar's loader (owner decision 2026-11-07, option a; DR-42): the bar variant's stylesheet (NowBanner.desk.css)
 * comes through next/dynamic, so Next links it only on a page that renders the bar (the desktop layout, a NOW paper
 * unseen), never on a live page, where the banner is the block and NowBanner.css alone. DesktopShell renders it in its
 * bar slot: NowBanner is on every signed-in page, and a loader there would put next/dynamic on all of them.
 * desktop-chunks.built.test.ts holds that.
 */
export const NowBarStyles = dynamic(() => import("./NowBanner.desk"));
