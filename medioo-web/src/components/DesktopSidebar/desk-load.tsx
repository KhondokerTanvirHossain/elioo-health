"use client";

import dynamic from "next/dynamic";

/*
 * The desktop sidebar's loader (owner decision 2026-11-07, option a; DR-42): its stylesheet comes through next/dynamic,
 * so Next links it only on a page that renders the sidebar (the desktop layout), never on a live page.
 * desktop-chunks.built.test.ts holds that.
 */
export const DesktopSidebarStyles = dynamic(() => import("./DesktopSidebar.desk"));
