"use client";

import dynamic from "next/dynamic";

/*
 * The desktop add dialog's loader (owner decision 2026-11-07, option a; DR-42): the sheet and its stylesheets come
 * through next/dynamic, so Next links them only on the screen that opens the dialog (`?add=1`). The drop frame rides in
 * the desktop shell's own chunk (DesktopShell/desk-load.tsx). desktop-chunks.built.test.ts holds that.
 */
export const DeskUploadDialog = dynamic(() => import("./DeskUpload.desk"));
