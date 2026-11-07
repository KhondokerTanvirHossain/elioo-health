/**
 * The preview gate for the desktop layout (plan 2026-11-07 desktop parity, Task 2; ruling P19). From 900 px the new
 * DesktopShell (the sidebar, the paper and the conversation) renders only on preview requests (`/app/preview/...` for
 * families in BAYMAX_WEB_PREVIEW_FAMILIES) until the PO approves it there. Merging the desktop work therefore changes
 * nothing for live users.
 *
 * DESKTOP_DEFAULT is the one constant that makes the desktop layout the default for every request. It changes only in
 * Task 10 (the flip), after the PO's approval, and desktop.test.ts holds it false until then.
 */
export const DESKTOP_DEFAULT = false;

/** Whether this request renders the desktop layout at widths of 900 px or more: always in preview, else the default. */
export function desktopLayout(ctx: { preview: boolean }): boolean {
  return ctx.preview || DESKTOP_DEFAULT;
}
