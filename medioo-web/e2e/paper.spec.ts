import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import { t } from "../src/copy";
import type { PaperResultJson, ValueSheetJson } from "../src/api/types";
import { allRows, summaryRows } from "../src/components/Paper/rows";
import { BANNER_ONLY_COOKIE } from "../src/components/safety/NowBanner/banner-only";
import { basicSignInPath, signInPath } from "../src/routing/paths";
import { useScenario, type CropRow, type Recorder } from "./support/browser-api";
import { expectedWidth } from "./support/png.mjs";
import { FIXTURES, PAPER_ID, SHEET_OBS, withStaleNowName } from "./support/scenarios.mjs";
import { cropRowsReport, imagesReport, screenChecks } from "./support/screen-checks";

/*
 * The paper (plan W6): its summary, /all and one value's sheet, against stub Java serving the fixtures Java's contract
 * tests wrote. Owns master Review Focus 2 (a crop that fails, a session that ended, a paper link opened without
 * JavaScript) and 5 (a share member), and this plan's Review Focus 4 (one open is one read of the paper).
 */

const body = <T>(file: string): T => (JSON.parse(readFileSync(join(FIXTURES, file), "utf8")) as { body: T }).body;
const doc = (file: string) => body<PaperResultJson>(`documents/${file}`);
const sheetOf = (file: string) => body<ValueSheetJson>(`value-sheet/${file}`);

const PAPER = `/app/documents/${PAPER_ID}`;
const ALL = `${PAPER}/all`;
const SHEET = `${PAPER}/values/${SHEET_OBS}`;

/** The paper each scenario serves (scenarios.mjs), so a check pairs images with that paper's own rows. */
const SCENARIO_PAPER: Record<string, string> = {
  "paper-now-bd": "now-bd.json",
  "paper-now-abroad": "now-abroad.json",
  "paper-routine": "routine-released.json",
  "paper-retake-third": "retake-third.json",
  "paper-58": "fifty-eight-values-multipage.json",
  "paper-58-long": "fifty-eight-values-multipage.json",
};
const paperOf = (scenario: string) => doc(SCENARIO_PAPER[scenario]!);

/** The images a paper shows beside its values: each medicine, follow-up and doctor's line, and (on /all) each page. */
function otherImages(p: PaperResultJson, pages: boolean): CropRow[] {
  return [
    ...p.medicines,
    ...p.follow_up,
    ...Object.values(p.doctor).flat(),
    ...(pages ? p.pages.map((pg) => ({ crop: { src: pg.src, width: null, height: null } })) : []),
  ];
}

/** The paper's GET (never its status, sheet or delete token): what records a DOCUMENT_VIEW. */
const PAPER_READ = /^\/app\/api\/documents\/[^/]+$/;

/** Every read of the paper this test caused: stub Java's (the Next server's) and the browser's own. */
async function paperReads(recorder: Recorder): Promise<number> {
  const java = (await recorder.java()).filter((r) => r.method === "GET" && PAPER_READ.test(r.path));
  const browser = recorder.requests().filter((r) => r.method === "GET" && PAPER_READ.test(r.path));
  return java.length + browser.length;
}

async function open(page: Page, scenario: string, path = PAPER, rows: CropRow[] = [], images: Record<string, number[]> = {}) {
  const recorder = await useScenario(page, scenario, { rows, images });
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  return recorder;
}

/** Switches this test's stub scenario without a new session (what happened on Java's side between two reads). */
async function switchScenario(page: Page, name: string) {
  const url = new URL(page.url());
  await page.context().addCookies([{ name: "e2e_scenario", value: name, domain: url.hostname, path: "/app" }]);
}

/** The decoded crop key of a row, as the stub's image routes match it. */
const keyOf = (row: CropRow) => new URL(row.crop.src, "http://e2e.invalid").searchParams.get("key")!;

test.describe("each value beside its own crop", () => {
  for (const [scenario, path, rows] of [
    ["paper-now-bd", PAPER, summaryRows(doc("now-bd.json"))],
    ["paper-routine", PAPER, summaryRows(doc("routine-released.json"))],
    ["paper-58", ALL, allRows(doc("fifty-eight-values-multipage.json"))],
  ] as const) {
    test(`everyValuesImageIsItsOwnRowsCrop ${scenario} ${path}`, async ({ page }) => {
      await open(page, scenario, path, [...rows, ...otherImages(paperOf(scenario), path === ALL)]);
      expect(await cropRowsReport(page, rows)).toEqual([]);
    });
  }

  test("anExpiredCropShowsRetry", async ({ page }) => {
    const p = doc("now-bd.json");
    const rows = summaryRows(p);
    const broken = rows[1]!;
    await open(page, "paper-now-bd", PAPER, [...rows, ...otherImages(p, false)], { [keyOf(broken)]: [403] });
    const cells = page.locator("[data-value-row]");
    await expect(cells).toHaveCount(rows.length);
    await cells.nth(1).scrollIntoViewIfNeeded();
    await expect(cells.nth(1).getByRole("button", { name: t("bn", "crop.retry") })).toBeVisible();
    // Every other row decodes at its own width.
    await imagesReport(page, "[data-value-row]");
    for (const [i, row] of rows.entries()) {
      if (row === broken) continue;
      const natural = await cells.nth(i).locator("img").evaluate((img: HTMLImageElement) => img.naturalWidth);
      expect(natural, `row ${i + 1}`).toBe(expectedWidth(row));
    }
  });

  test("theRetryFetchesTheImageAgain", async ({ page }) => {
    const p = doc("now-bd.json");
    const rows = summaryRows(p);
    const broken = rows[0]!;
    const key = keyOf(broken);
    // Refused until the retry is offered, then served: how many loads the page made before the press is the browser's
    // and React's (an image that failed before hydration is reloaded once by React), so the press is judged on its own.
    let refuse = true;
    let asked = 0;
    await useScenario(page, "paper-now-bd", { rows: [...rows, ...otherImages(p, false)] });
    await page.route(
      (url) => url.pathname.endsWith("/image") && url.searchParams.get("key") === key,
      (route) => {
        asked++;
        return refuse ? route.fulfill({ status: 403 }) : route.fallback();
      },
    );
    expect((await page.goto(PAPER))?.status()).toBe(200);
    const row = page.locator("[data-value-row]").first();
    await row.scrollIntoViewIfNeeded();
    const retry = row.getByRole("button", { name: t("bn", "crop.retry") });
    await retry.waitFor();
    const before = asked;
    expect(before, "the crop was asked for and refused").toBeGreaterThan(0);
    refuse = false;
    await retry.click();
    await expect.poll(() => asked, { message: "requests for the crop's key: the press made one more" }).toBe(before + 1);
    await expect(row.locator("img")).toBeVisible();
    await expect.poll(() => row.locator("img").evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth)).toBe(
      expectedWidth(broken),
    );
    await page.waitForTimeout(500);
    expect(asked, "and only one").toBe(before + 1);
  });

  test("aCropAfterTheSessionEndedLeadsToSignIn", async ({ page }) => {
    const p = doc("routine-released.json");
    const rows = summaryRows(p);
    await open(page, "paper-crop-signed-out", PAPER, rows, { [keyOf(rows[0]!)]: [303, 303] });
    const row = page.locator("[data-value-row]").first();
    await row.getByRole("button", { name: t("bn", "crop.retry") }).click();
    await page.waitForURL((u) => u.pathname === "/app/");
    expect(new URL(page.url()).pathname + new URL(page.url()).search).toBe(signInPath(PAPER));
  });
});

test.describe("a paper that is gone", () => {
  test("aDeletedPaperSaysItIsGone", async ({ page }) => {
    await open(page, "paper-gone");
    await expect(page.getByRole("heading", { name: t("bn", "doc.gone") })).toBeVisible();
    await expect(page.getByRole("link", { name: t("bn", "app.back.papers") })).toHaveAttribute("href", "/app/timeline");
  });

  test("aDeleteRacedByAnotherMemberSaysGone", async ({ page }) => {
    await open(page, "paper-delete-raced");
    await page.getByRole("button", { name: t("bn", "detail.delete") }).click();
    await page.getByRole("button", { name: t("bn", "doc.delete.yes") }).click();
    await expect(page.locator(".toast__text")).toHaveText(t("bn", "doc.gone"));
  });

  test("aDeleteThatSucceedsLeavesThePaperGone", async ({ page }) => {
    const recorder = await open(page, "paper-routine");
    await page.getByRole("button", { name: t("bn", "detail.delete") }).click();
    await expect(page.getByRole("dialog")).toBeVisible();
    // From here Java no longer shows the paper: the delete itself is answered under the same scenario's shape.
    await switchScenario(page, "paper-deleted");
    await page.getByRole("button", { name: t("bn", "doc.delete.yes") }).click();
    await page.waitForURL((u) => u.searchParams.get("deleted") === "1");
    await expect(page.getByRole("heading", { name: t("bn", "doc.gone") })).toBeVisible();
    await expect(page.getByText(t("bn", "doc.deleted"))).toBeVisible();
    const sent = recorder.requests();
    const del = sent.find((r) => r.method === "DELETE");
    expect(del?.body, "the confirm token Java minted").toEqual({ confirm_token: "e2e-confirm-token" });
    const tokenAt = sent.findIndex((r) => r.path.endsWith("/delete-token"));
    expect(tokenAt).toBeGreaterThanOrEqual(0);
    expect(tokenAt).toBeLessThan(sent.indexOf(del!));
  });
});

test.describe("one open is one read of the paper (Review Focus 4)", () => {
  test("oneOpenIsOneDocumentRead", async ({ page }) => {
    test.setTimeout(60_000);
    const recorder = await open(page, "paper-58", ALL);
    expect(await paperReads(recorder), "opening /all").toBe(1);
    // Hovering and scrolling past every link that opens the paper or a sheet reads nothing.
    const back = page.locator(".paper__back");
    await back.hover();
    for (const link of await page.locator("[data-value-row] a").all()) await link.hover();
    await page.mouse.wheel(0, 20_000);
    await page.waitForTimeout(1_500);
    expect(await paperReads(recorder), "after hovering and scrolling").toBe(1);
    await back.click();
    await page.locator("[data-now-block]").waitFor();
    expect(await paperReads(recorder), "back to the paper").toBe(2);
    await page.locator(".paper__action").first().hover();
    await page.waitForTimeout(1_500);
    expect(await paperReads(recorder), "after hovering see-all").toBe(2);
    await page.locator(".paper__action").first().click();
    await page.locator("#values").waitFor();
    expect(await paperReads(recorder), "to /all again").toBe(3);

    // A paper still being read polls its status, which records nothing, until it is read: then one read, the result's.
    await switchScenario(page, "paper-processing");
    await page.goto(PAPER);
    await page.locator("#progress").waitFor();
    expect(await paperReads(recorder), "opening the paper being read").toBe(4);
    await page.waitForTimeout(6_500);
    expect(await paperReads(recorder), "after a poll").toBe(4);
    expect(recorder.requests().filter((r) => r.path.endsWith("/status")).length, "the poll asked the status").toBeGreaterThan(0);
    await switchScenario(page, "paper-processing-done");
    await page.locator("[data-value-row]").first().waitFor({ timeout: 15_000 });
    expect(await paperReads(recorder), "the result, once").toBe(5);
    await page.waitForTimeout(6_000);
    expect(await paperReads(recorder), "and the poll stopped").toBe(5);
  });

  test("aStaleSeenKeepsThePaperAndReadsItNoMore", async ({ page }) => {
    // A stale `now.banner.seen` tap (Java answers `show: true`) refreshes the banner only (banner-only.ts): no read of
    // the paper or the sheet, the page still shows itself, the marker is gone once the refresh landed, and the next open
    // reads again.
    const READ = /^\/app\/api\/documents\//;
    const reads = async (r: Recorder) => (await r.java()).filter((x) => x.method === "GET" && READ.test(x.path)).length;
    const cases: Array<[string, string, string]> = [
      [PAPER, "paper-now-bd", "[data-now-block]"],
      [ALL, "paper-58", "#values"],
      [SHEET, "sheet-earlier-three", "#value-sheet-title"],
    ];
    for (const [path, own, mark] of cases) {
      const recorder = await open(page, withStaleNowName(own), path);
      expect(await reads(recorder), `${path}: the open`).toBe(1);
      const refreshed = page.waitForRequest((r) => r.headers()["rsc"] === "1" && new URL(r.url()).pathname === path);
      const banner = page.locator('section[role="alert"]');
      await banner.getByRole("button", { name: t("bn", "now.banner.seen") }).click();
      await refreshed;
      await page.waitForLoadState("networkidle");
      // Guard: the refresh reached Java (it re-read the banner), or this saw no refresh at all.
      expect((await recorder.java()).map((r) => r.path), `${path}: the banner re-read`).toContain("/app/api/now-banner");
      expect(await reads(recorder), `${path}: read by the refresh`).toBe(1);
      await expect(banner, `${path}: the stale banner stays`).toHaveCount(1);
      await expect(page.locator(mark), `${path}: the page still shows itself`).toHaveCount(1);
      const left = (await page.context().cookies()).filter((c) => c.name === BANNER_ONLY_COOKIE);
      expect(left, `${path}: the marker served one refresh`).toEqual([]);
      await page.reload();
      await page.locator(mark).waitFor();
      expect(await reads(recorder), `${path}: the next open reads it`).toBe(2);
    }
  });

  test("aNonPreviewFamilyOnAPreviewPaperCausesNoPaperRead", async ({ page }) => {
    const recorder = await useScenario(page, "paper-preview-off");
    const response = await page.goto(`/app/preview/documents/${PAPER_ID}`);
    expect(response?.status()).toBe(404);
    const java = await recorder.java();
    expect(java.some((r) => r.path === "/app/api/me"), "the gate read me").toBe(true);
    expect(java.filter((r) => r.path.startsWith("/app/api/documents/")).map((r) => `${r.method} ${r.path}`)).toEqual([]);
  });

  test("aPreviewFamilyStaysInsideThePreview", async ({ page }) => {
    const recorder = await useScenario(page, "paper-58");
    const response = await page.goto(`/app/preview/documents/${PAPER_ID}/all`);
    expect(response?.status()).toBe(200);
    await expect(page.locator(".paper__back")).toHaveAttribute("href", `/app/preview/documents/${PAPER_ID}`);
    expect(await paperReads(recorder)).toBe(1);
  });
});

test.describe("a share member (Review Focus 5)", () => {
  test("aShareMemberSeesNoDeleteAndNoRetakeUpload", async ({ page }) => {
    await open(page, "paper-retake-share");
    await expect(page.getByText(t("bn", "retake.viewonly"))).toBeVisible();
    await expect(page.getByRole("link", { name: t("bn", "retake.again") })).toHaveCount(0);
    await expect(page.getByRole("link", { name: t("bn", "retake.again.same") })).toHaveCount(0);
    await expect(page.getByRole("link", { name: t("bn", "retake.other") })).toHaveCount(0);
    await expect(page.getByRole("button", { name: t("bn", "detail.delete") })).toHaveCount(0);
    // The other direction: the owner of the same paper is offered both.
    await open(page, "paper-retake-third");
    await expect(page.getByRole("link", { name: t("bn", "retake.again.same") })).toHaveCount(1);
    await expect(page.getByRole("button", { name: t("bn", "detail.delete") })).toHaveCount(1);
    // A share member on a read paper: no delete either.
    await open(page, "paper-routine-share");
    await expect(page.locator("[data-value-row]").first()).toBeVisible();
    await expect(page.getByRole("button", { name: t("bn", "detail.delete") })).toHaveCount(0);
  });
});

test.describe("without JavaScript (DR-42 item 1)", () => {
  test.use({ javaScriptEnabled: false });

  test("aPaperIsReadableWithoutJavaScript", async ({ page }) => {
    const p = doc("now-bd.json");
    const rows = summaryRows(p);
    await open(page, "paper-now-bd", PAPER, [...rows, ...otherImages(p, false)]);
    await expect(page.locator("[data-now-block]")).toBeVisible();
    await expect(page.locator("[data-value-row]")).toHaveCount(rows.length);
    await expect(page.locator("[data-value-row] .status-chip")).toHaveCount(rows.length);
    expect(await cropRowsReport(page, rows)).toEqual([]);
    await expect(page.getByRole("button", { name: t("bn", "detail.delete") })).toHaveCount(0);
    expect(await page.locator("form").count(), "no form that posts somewhere").toBe(0);
  });

  test("allAndTheSheetAreReadableWithoutJavaScript", async ({ page }) => {
    await open(page, "paper-58", ALL);
    await expect(page.locator("[data-value-row]")).toHaveCount(doc("fifty-eight-values-multipage.json").values.length);
    await open(page, "sheet-earlier-three", SHEET);
    await expect(page.locator("[data-value-row]")).toHaveCount(1 + sheetOf("earlier-three.json").earlier.length);
  });

  test("anExpiredSessionOnAPaperWithoutJavaScriptReachesTheFallback", async ({ page }) => {
    await useScenario(page, "signed-out-paper");
    const answer = await page.request.get(PAPER, { maxRedirects: 0 });
    expect(answer.status()).toBe(307);
    const location = answer.headers()["location"]!;
    expect(location).toBe(signInPath(PAPER));
    await page.goto(location);
    await expect(page.locator("noscript a")).toHaveAttribute("href", basicSignInPath(PAPER));
  });
});

test.describe("the pages and the sheet", () => {
  test("zoomOpensThePageImage", async ({ page }) => {
    const p = doc("routine-released.json");
    await open(page, "paper-routine", PAPER, [...summaryRows(p), ...otherImages(p, true)]);
    await expect(page.locator("dialog img")).toHaveCount(0);
    await page.getByRole("link", { name: t("bn", "detail.page", 1) }).click();
    const img = page.locator("dialog[open] img");
    await expect(img).toHaveAttribute("src", p.pages[0]!.src);
    await expect.poll(() => img.evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth > 0)).toBe(true);
    expect(page.url(), "the page stayed on the paper").toContain(PAPER);
  });

  test("aValueSheetListsEarlierReadingsEachWithItsCrop", async ({ page }) => {
    const s = sheetOf("earlier-three.json");
    const rows = [s.value, ...s.earlier];
    await open(page, "sheet-earlier-three", SHEET, rows);
    await expect(page.getByRole("heading", { name: t("bn", "sheet.earlier", s.value.unit!) })).toBeVisible();
    expect(await cropRowsReport(page, rows)).toEqual([]);
    const links = page.locator("[data-value-row] a.value-row__open");
    await expect(links).toHaveCount(s.earlier.length);
    for (const [i, e] of s.earlier.entries()) {
      await expect(links.nth(i)).toHaveAttribute("href", `/app/documents/${e.document_id}/values/${e.observation_id}?from=all`);
    }
  });

  test("aSheetWithNoHistorySaysNothingAboutTheFirstTime", async ({ page }) => {
    for (const scenario of ["sheet-no-history", "sheet-earlier-other-unit"]) {
      await open(page, scenario, SHEET);
      await expect(page.getByText(t("bn", "sheet.earlier.none"))).toHaveCount(0);
    }
    await open(page, "sheet-earlier-none", SHEET);
    await expect(page.getByText(t("bn", "sheet.earlier.none"))).toHaveCount(1);
  });

  test("aSheetJavaRefusesIsNotFound", async ({ page }) => {
    await useScenario(page, "sheet-not-found");
    expect((await page.goto(SHEET))?.status()).toBe(404);
  });
});

// The content checks on every routed state, both schemes, each with its longest twin (the longest name over the
// longest paper), each image judged against its own row.
const summaryCrops = (scenario: string) => summaryRows(paperOf(scenario));
const summaryImages = (scenario: string) => otherImages(paperOf(scenario), false);
screenChecks(PAPER, "paper-now-bd", { crops: summaryCrops, images: summaryImages, longest: true });
// The paper route's one longest twin is registered under now-bd; the other states share it, so they register none.
screenChecks(PAPER, "paper-routine", { crops: summaryCrops, images: summaryImages });
screenChecks(PAPER, "paper-retake-third", { images: summaryImages });
screenChecks(ALL, "paper-58", {
  crops: (s) => allRows(paperOf(s)),
  images: (s) => otherImages(paperOf(s), true),
  longest: true,
});
screenChecks(SHEET, "sheet-earlier-three", {
  crops: () => {
    const s = sheetOf("earlier-three.json");
    return [s.value, ...s.earlier];
  },
  longest: true,
});
