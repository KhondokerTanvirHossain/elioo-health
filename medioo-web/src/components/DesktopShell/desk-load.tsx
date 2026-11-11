"use client";

import dynamic from "next/dynamic";

/*
 * The desktop shell's loader (owner decision 2026-11-07, option a; DR-42). The desktop layout renders only where
 * desktopLayout(ctx) says so (a preview request, until the flip), yet shell.tsx imports DesktopShell on every signed-in
 * page. Its stylesheet therefore comes through next/dynamic: Next links DesktopShell.desk's files (react-loadable-
 * manifest) only on a page that renders the shell, never on a live page. desktop-chunks.built.test.ts holds that.
 */
export const DesktopShellStyles = dynamic(() => import("./DesktopShell.desk"));
/** The divider between the paper and the conversation: a client island, in the same desk-only chunk. */
export const ColumnResizer = dynamic(() => import("./DesktopShell.desk").then((m) => m.ColumnResizer));
/** A paper dropped anywhere opens the add dialog (plan desktop-parity U1): in the same desk-only chunk. */
export const DropAnywhere = dynamic(() => import("./DesktopShell.desk").then((m) => m.DropAnywhere));
