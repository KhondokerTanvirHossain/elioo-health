"use client";

import dynamic from "next/dynamic";

/*
 * The details panel's loader (owner decision 2026-11-07, option a; DR-42): its stylesheet comes through next/dynamic,
 * so Next links it only on a page that renders the panel (the desktop layout, a paper's details open), never on a live
 * page. The paper inside keeps today's page styles (PaperSummary's own). desktop-chunks.built.test.ts holds that.
 */
export const PaperPanelStyles = dynamic(() => import("./PaperPanel.desk"));
