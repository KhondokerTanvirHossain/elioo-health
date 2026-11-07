"use client";

import dynamic from "next/dynamic";

/*
 * The paper viewer's loader (owner decision 2026-11-07, option a; DR-42): its stylesheet and its one client island,
 * Zoom, come through next/dynamic from PaperViewer.desk, so Next links them only on a page that renders the viewer (the
 * desktop layout), never on a live page. Both are one chunk (one module). desktop-chunks.built.test.ts holds that.
 */
export const PaperViewerStyles = dynamic(() => import("./PaperViewer.desk"));
export const Zoom = dynamic(() => import("./PaperViewer.desk").then((m) => m.Zoom));
