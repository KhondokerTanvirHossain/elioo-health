import { expect, type Page } from "@playwright/test";

/*
 * What the desktop layout's specs share (talk-and-papers.spec.ts, desk-calendar.spec.ts): the widths and schemes they
 * judge it at, the overflow measure, and opening the sidebar where it hides behind the menu button.
 */

export const WIDTHS = {
  w1440: { width: 1440, height: 900 },
  w1280: { width: 1280, height: 800 },
  w1024: { width: 1024, height: 768 },
  w900: { width: 900, height: 800 },
} as const;
export const SCHEMES = ["light", "dark"] as const;
/** From this width the sidebar is a column of its own; below it, behind the paper column's button (DesktopShell.css). */
export const SIDEBAR_COLUMN = 1200;

/**
 * Every element of the desktop shell whose content is wider than its box, but for three that are not overflow:
 * - a scroller (overflow-x auto or scroll: the strip, the page canvas), which is meant to scroll sideways;
 * - a line cut with an ellipsis (text-overflow: ellipsis on one line), which desktop.html declares for the viewer's title
 *   and whose-paper line (`.tb .t b`, `.tb .t small`) and the strip's labels: returned as `truncated`, so a caller can
 *   require that none of them is a place a name must wrap;
 * - text read out and not drawn (a 1 px box clipped to nothing: the screen's h1, the strip's words).
 * A box that clips without saying so, or spills, is reported. Also returned: the scrollers that do scroll, how many
 * elements were measured, and the page's own width.
 */
export function deskOverflow(page: Page) {
  return page.evaluate(() => {
    const over: string[] = [];
    const scrolling: string[] = [];
    const truncated: Array<{ label: string; where: string[] }> = [];
    const NAMED = [".desktop-sidebar", ".conversation__head", ".now-banner--bar"];
    let measured = 0;
    for (const el of document.querySelectorAll<HTMLElement>(".desktop-shell, .desktop-shell *")) {
      if (el.getClientRects().length === 0) continue;
      measured++;
      if (el.scrollWidth <= el.clientWidth + 1) continue;
      const style = getComputedStyle(el);
      const label = `<${el.tagName.toLowerCase()} class="${el.getAttribute("class") ?? ""}"> ${el.scrollWidth} > ${el.clientWidth}`;
      const box = el.getBoundingClientRect();
      if (style.overflowX === "auto" || style.overflowX === "scroll") scrolling.push(el.className);
      else if (style.clipPath === "inset(50%)" && box.width <= 1 && box.height <= 1) continue;
      else if (style.textOverflow === "ellipsis" && style.whiteSpace === "nowrap") {
        truncated.push({ label, where: NAMED.filter((s) => el.closest(s) !== null) });
      } else over.push(label);
    }
    const root = document.documentElement;
    return { over, scrolling, truncated, measured, page: [root.scrollWidth, root.clientWidth] as [number, number] };
  });
}

/** Opens the sidebar where it lies behind the paper column's button (900 to 1199 px), so its names are measured too. */
export async function showSidebar(page: Page, width: number) {
  if (width >= SIDEBAR_COLUMN) return;
  await page.locator(".desktop-shell__menu").click();
  await expect(page.locator(".desktop-shell__sidebar[data-open]")).toBeVisible();
}
