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

/**
 * Opens the sidebar where it lies behind the paper column's button (900 to 1199 px), so its names are measured too. The
 * button is a client island (SidebarToggle): a click that lands before React hydrates it does nothing, which a slow CI
 * runner hit (PR 179). So the click is repeated until the button itself says it is open (`aria-expanded`, which only
 * React sets); a click after hydration opens it at once, so the loop never toggles it shut again.
 */
export async function showSidebar(page: Page, width: number) {
  if (width >= SIDEBAR_COLUMN) return;
  const menu = page.locator(".desktop-shell__menu");
  await expect(async () => {
    if ((await menu.getAttribute("aria-expanded")) !== "true") await menu.click();
    await expect(menu).toHaveAttribute("aria-expanded", "true", { timeout: 1_000 });
  }).toPass({ timeout: 10_000 });
  await expect(page.locator(".desktop-shell__sidebar[data-open]")).toBeVisible();
}

/**
 * Since the flip (DR-51) the talk-and-papers, papers, health and family pages draw the desktop layout from 900 px, which
 * talk-and-papers.spec.ts and the desk-*.spec.ts files test. The specs of today's page (chat, paper, papers, health,
 * family) keep testing that page in the desktop project too, as a desktop browser shows it: just below 900 px. A spec
 * opts in with `test.use(BELOW_DESKTOP)`; the phone project keeps its own viewport.
 */
export const BELOW_DESKTOP_WIDTH = 899;
export const BELOW_DESKTOP = {
  viewport: async (
    { viewport, isMobile }: { viewport: { width: number; height: number } | null; isMobile: boolean },
    use: (v: { width: number; height: number } | null) => Promise<void>,
  ) => use(isMobile || viewport === null || viewport.width < 900 ? viewport : { width: BELOW_DESKTOP_WIDTH, height: viewport.height }),
};

/**
 * Waits until React has hydrated the desktop layout: the sidebar's menu button, a client island on every desktop page,
 * carries React's props. A key or click that lands before then reaches no handler (the paper keys, a soft link), which a
 * slow CI runner hit (PR 182: an arrow key and the panel's link, each waiting for an address that never came).
 */
export async function hydrated(page: Page, island = ".desktop-shell__menu") {
  await expect
    .poll(() => page.locator(island).first().evaluate((el) => Object.keys(el).some((k) => k.startsWith("__reactProps$"))), {
      message: "React has hydrated the desktop layout",
      timeout: 15_000,
    })
    .toBe(true);
}
