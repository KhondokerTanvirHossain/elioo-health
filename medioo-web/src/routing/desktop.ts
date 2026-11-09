/**
 * The gate for the desktop layout (plan 2026-11-07 desktop parity, Task 2; ruling P19). From 900 px the new
 * DesktopShell (the sidebar, the paper and the conversation) rendered first only on preview requests (`/app/preview/...`
 * for families in BAYMAX_WEB_PREVIEW_FAMILIES), where the PO tried each screen.
 *
 * DESKTOP_DEFAULT makes the desktop layout the default for every request. Task 10, the flip: the PO turned it on on
 * 2026-11-09 (DR-51), with the calendar, lab values, medicines and family pages built; desktop.test.ts holds it true.
 * Turning the desktop off for everyone again is this one constant set back to false.
 */
export const DESKTOP_DEFAULT = true;

/** Whether this request renders the desktop layout at widths of 900 px or more: always in preview, else the default. */
export function desktopLayout(ctx: { preview: boolean }): boolean {
  return ctx.preview || DESKTOP_DEFAULT;
}
