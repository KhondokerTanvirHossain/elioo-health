import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { createElement, type ComponentProps, type FunctionComponent } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { ZOOM_MAX, ZOOM_MIN, ZOOM_STEP, Zoom } from "../src/components/PaperViewer/Zoom";

/*
 * The paper viewer's canvas laid out by a real browser (plan desktop-parity Task 6, review finding 1): a page zoomed
 * wider than the canvas must be reachable by scrolling from its left edge, where a lab report prints its test names.
 * A centred flex item wider than its container spills past the start edge, and that part can never be scrolled to; jsdom
 * lays nothing out, so only a browser can see it. The markup is the real Zoom island's (rendered to HTML here), styled by
 * the real tokens.css and PaperViewer.css; nothing is served, so this needs no build and no stub Java.
 */

const SRC = join(__dirname, "..", "src");
const CSS = ["styles/tokens.css", "components/PaperViewer/PaperViewer.css"].map((f) => readFileSync(join(SRC, f), "utf8")).join("\n");
/** A 640 × 900 page (the 100% width), as an image the browser sizes like a stored page. */
const PAGE = `data:image/svg+xml,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="640" height="900"><rect width="640" height="900" fill="white"/></svg>')}`;
/** The paper column's width in the check: narrower than a page at 100%, so every step from 100% up overflows it. */
const COLUMN = 600;

const steps: number[] = [];
for (let z = ZOOM_MIN; z <= ZOOM_MAX + 1e-9; z += ZOOM_STEP) steps.push(Math.round(z * 100) / 100);

async function mount(page: Page, column = COLUMN) {
  const zoom = renderToStaticMarkup(
    createElement(
      // The page goes in as createElement's child argument, which the props type cannot see.
      Zoom as FunctionComponent<Omit<ComponentProps<typeof Zoom>, "children">>,
      { words: { out: "out", in: "in", fit: "fit", digits: "0123456789" }, start: null, end: null, canvasLabel: "paper" },
      createElement("img", { className: "paper-viewer__page", src: PAGE, alt: "page 1" }),
    ),
  );
  await page.setContent(
    `<!doctype html><html><head><style>${CSS}</style></head><body>` +
      `<div id="column" style="width:${column}px;height:700px"><div class="paper-viewer">${zoom}</div></div></body></html>`,
  );
  await page.locator(".paper-viewer__page").evaluate((img: HTMLImageElement) => img.decode());
}

/** Where the page sits in the canvas at a zoom level, scrolled all the way to the start. */
async function at(page: Page, level: string) {
  return page.locator(".paper-viewer__canvas").evaluate((canvas: HTMLElement, z) => {
    canvas.setAttribute("data-zoom", z);
    canvas.scrollLeft = 0;
    const box = canvas.getBoundingClientRect();
    const img = canvas.querySelector("img")!.getBoundingClientRect();
    const style = getComputedStyle(canvas);
    const padLeft = parseFloat(style.paddingLeft);
    const padRight = parseFloat(style.paddingRight);
    const inner = canvas.clientWidth - padLeft - padRight;
    return {
      zoom: canvas.getAttribute("data-zoom"),
      pageWidth: img.width,
      inner,
      padLeft,
      // From the canvas's padding edge to the page's left edge, with the canvas scrolled to its start: never negative.
      leftGap: img.left - (box.left + canvas.clientLeft + padLeft),
      rightGap: box.left + canvas.clientLeft + padLeft + inner - img.right,
      scrollWidth: canvas.scrollWidth,
    };
  }, level);
}

test.describe("the paper viewer's canvas", () => {
  test("aZoomedPageCanBeScrolledToFromItsLeftEdge", async ({ page }) => {
    await mount(page);
    expect(steps, "the zoom steps the check walks").toContain(3);
    const wide = steps.filter((z) => z * 640 > COLUMN);
    expect(wide.length, "some steps make the page wider than the column").toBeGreaterThan(0);
    for (const z of wide) {
      const m = await at(page, String(z));
      expect(m.zoom).toBe(String(z));
      expect(m.pageWidth, `at ${z * 100}% the page is ${z} × 640 px`).toBeCloseTo(640 * z, 0);
      expect(m.leftGap, `at ${z * 100}% the page's left edge is cut off by ${-m.leftGap} px, out of scroll's reach`).toBeGreaterThanOrEqual(-0.5);
      // And the whole page can be scrolled through: the scroll range covers it from its left edge to its right.
      expect(m.scrollWidth, `at ${z * 100}% the canvas scrolls over the whole page`).toBeGreaterThanOrEqual(Math.floor(m.padLeft + m.pageWidth));
    }
  });

  test("aPageNarrowerThanTheCanvasStaysCentred", async ({ page }) => {
    await mount(page);
    const narrow = steps.filter((z) => z * 640 < COLUMN - 100);
    expect(narrow.length, "some steps make the page narrower than the column").toBeGreaterThan(0);
    for (const z of narrow) {
      const m = await at(page, String(z));
      expect(m.leftGap, `at ${z * 100}% the page has room on its left`).toBeGreaterThan(0);
      expect(Math.abs(m.leftGap - m.rightGap), `at ${z * 100}% the page is centred (left ${m.leftGap}, right ${m.rightGap})`).toBeLessThanOrEqual(1);
    }
  });

  test("fitMakesThePageAsWideAsTheCanvasUpToItsOwnWidth", async ({ page }) => {
    // desktop.html's fitZoom(): min(1, canvas / 640). A canvas narrower than the page: the page fills it.
    await mount(page);
    const narrow = await at(page, "fit");
    expect(narrow.inner, "the canvas is narrower than a page at 100%").toBeLessThan(640);
    expect(narrow.leftGap).toBeCloseTo(0, 0);
    expect(narrow.pageWidth, "fit: the page fills the canvas's width").toBeCloseTo(narrow.inner, 0);
    // A canvas wider than the page (a wide window): the page stays at 100%, 640 px, centred; never blown up past it
    // (PO 2026-11-07: "the images are bigger than the original design").
    await mount(page, 1200);
    const wide = await at(page, "fit");
    expect(wide.inner, "the canvas is wider than a page at 100%").toBeGreaterThan(700);
    expect(wide.pageWidth, "fit: the page stops at its own width").toBeCloseTo(640, 0);
    expect(Math.abs(wide.leftGap - wide.rightGap), `centred (left ${wide.leftGap}, right ${wide.rightGap})`).toBeLessThanOrEqual(1);
  });
});
