"use client";

import dynamic from "next/dynamic";

/*
 * The desktop lab values' loader (owner decision 2026-11-07, option a; DR-42): its stylesheet comes through next/dynamic
 * from DeskLabs.desk, so Next links it only on a page that renders the desktop lab values, never on a live page.
 * desktop-chunks.built.test.ts holds that.
 */
export const DeskLabsStyles = dynamic(() => import("./DeskLabs.desk"));
