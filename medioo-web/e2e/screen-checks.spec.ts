import { expect, test, type Page } from "@playwright/test";
import type { CropRow } from "./support/browser-api";
import { pngFor } from "./support/png.mjs";
import { cropRowsReport, rendersOwnText, targetReport } from "./support/screen-checks";

/*
 * The shared screen checks, judged on synthetic pages before a screen relies on them (W6's value rows, every screen's
 * longest twin and primary press). Each check is shown passing on a right page and failing on the broken one it exists
 * to catch, so none of them can be green for a reason other than the one it names. No server: the pages are served by
 * the browser's own route handler on an origin nothing else answers.
 */

const ORIGIN = "http://screen-checks.invalid";
const row = (key: string, width: number): CropRow => ({ crop: { src: `/app/documents/d1/image?key=${key}`, width, height: 20 } });
const ROWS = [row("a", 60), row("b", 80), row("c", 100)];

async function serve(page: Page, html: string) {
  const byKey = new Map(ROWS.map((r) => [r.crop.src, r]));
  await page.route(`${ORIGIN}/**`, async (route) => {
    const url = new URL(route.request().url());
    const src = url.pathname + url.search;
    if (url.pathname === "/") {
      return route.fulfill({ status: 200, contentType: "text/html", body: `<!doctype html><html><body>${html}</body></html>` });
    }
    const r = byKey.get(src);
    return r ? route.fulfill({ status: 200, contentType: "image/png", body: pngFor(r) }) : route.fulfill({ status: 404 });
  });
  await page.goto(`${ORIGIN}/`);
}

const rowHtml = (r: CropRow) => `<div data-value-row><span>value</span><img src="${r.crop.src}" alt=""></div>`;

test.describe("cropRowsReport", () => {
  test("eachRowShowingItsOwnCropPasses", async ({ page }) => {
    await serve(page, ROWS.map(rowHtml).join(""));
    expect(await cropRowsReport(page, ROWS)).toEqual([]);
  });

  test("aMissingRowMadeUpByAnExtraImageFails", async ({ page }) => {
    // Two rows rendered, the second holding two images: the image count is still three.
    await serve(page, rowHtml(ROWS[0]!) + `<div data-value-row><img src="${ROWS[1]!.crop.src}" alt=""><img src="${ROWS[2]!.crop.src}" alt=""></div>`);
    expect(await cropRowsReport(page, ROWS)).toEqual(["2 value rows rendered, the fixture has 3", "row 2 shows 2 images, not one"]);
  });

  test("twoSwappedCropsFail", async ({ page }) => {
    await serve(page, [ROWS[0]!, ROWS[2]!, ROWS[1]!].map(rowHtml).join(""));
    const problems = await cropRowsReport(page, ROWS);
    expect(problems).toContain(`row 2 shows ${ROWS[2]!.crop.src}, not its crop ${ROWS[1]!.crop.src}`);
    expect(problems).toContain("row 2 decodes at 100 px, its crop is 80 px");
  });

  test("aFixtureWhoseRowsShareAWidthCannotDetectASwap", async ({ page }) => {
    await serve(page, ROWS.map(rowHtml).join(""));
    expect(await cropRowsReport(page, [ROWS[0]!, row("b", 60), ROWS[2]!])).toContain(
      "this fixture cannot detect a swap: two rows share a width",
    );
  });
});

test.describe("rendersOwnText", () => {
  test("aNameInOneElementIsRendered", async ({ page }) => {
    await serve(page, "<p>Long Name Begum Chowdhury</p>");
    expect(await rendersOwnText(page, "Long Name Begum Chowdhury")).toBe(true);
  });

  test("aNameSplitAcrossElementsOrOnlyInAnAttributeIsNot", async ({ page }) => {
    await serve(page, '<p>Long Name <b>Begum Chowdhury</b></p><img alt="Long Name Begum Chowdhury" src="data:,">');
    expect(await rendersOwnText(page, "Long Name Begum Chowdhury")).toBe(false);
  });
});

test.describe("targetReport's primary count", () => {
  test("countsThePrimaryPress", async ({ page }) => {
    await serve(page, '<button data-primary style="width:200px;height:48px">go</button>');
    expect((await targetReport(page)).primaries).toBe(1);
  });

  test("aDroppedMarkerCountsNone", async ({ page }) => {
    // What `onePrimary` exists to catch: at most one would pass this page, exactly one does not.
    await serve(page, '<button style="width:200px;height:44px">go</button>');
    expect((await targetReport(page)).primaries).toBe(0);
  });
});
