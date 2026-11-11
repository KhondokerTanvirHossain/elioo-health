import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { LabsJson } from "../src/api/types";
import { t } from "../src/copy";
import { useScenario } from "./support/browser-api";
import { SCHEMES, WIDTHS, hydrated } from "./support/desk-checks";
import { PROTO_PAPERS, PROTO_PATIENTS } from "./support/scenarios.mjs";
import { axeReport, cspViolations, expectSchemeIsPainted, installCspHook } from "./support/screen-checks";

/*
 * The desktop search (plan desktop-parity Phase 2, row S4; desktop.html's search): ⌘K focuses the sidebar's field; typing
 * lists the patient in view's matching papers, lab values and medicines, each opening its screen; Enter opens the
 * first; nothing matched says so. Over the prototype family (the mother in view), against the production build and stub
 * Java.
 */

const AMMA = PROTO_PATIENTS.amma;
const LABS = JSON.parse(readFileSync(join(__dirname, "..", "fixtures", "api", "health", "proto-labs-amma.json"), "utf8")).body as LabsJson;
const CREATININE = LABS.series.find((s) => s.canonical_name === "creatinine")!;

async function open(page: Page) {
  await useScenario(page, "proto");
  await installCspHook(page);
  const response = await page.goto(`/app/home?patient=${AMMA}`);
  expect(response?.status()).toBe(200);
  await expect(page.locator(".desktop-shell")).toBeVisible();
  await hydrated(page);
}

const field = (page: Page) => page.locator(".desktop-shell .desk-search__field");
const hits = (page: Page) => page.locator(".desktop-shell .desk-search__hit");

test.describe("the desktop search", () => {
  test.skip(({ isMobile }) => isMobile, "the desktop layout's");
  test.use({ viewport: WIDTHS.w1440 });

  test("cmdKFocusesTheFieldAndAValueOpensTheLabValuesOnIt", async ({ page }) => {
    await open(page);
    await page.keyboard.press("ControlOrMeta+k");
    await expect(field(page)).toBeFocused();
    await page.keyboard.type(CREATININE.name);
    await expect(hits(page).first()).toContainText(CREATININE.name);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(new RegExp(`/app/health\\?patient=${AMMA}&lab=${CREATININE.points[0]!.observation_id}$`));
    await expect(page.locator(".desktop-shell .desk-labs__detail h2")).toContainText(CREATININE.name);
  });

  test("aPapersTypeFindsItAndOpensItOnTheTalkAndPapersScreen", async ({ page }) => {
    await open(page);
    await field(page).fill(t("bn", "type.prescription"));
    const rx = page.locator(`.desktop-shell .desk-search__hit[href*="paper=${PROTO_PAPERS.rx}"]`);
    await expect(rx.first()).toBeVisible();
    await rx.first().click();
    await expect(page).toHaveURL(new RegExp(`paper=${PROTO_PAPERS.rx}`));
    await expect(page.locator(".desktop-shell .paper-viewer")).toHaveAttribute("data-paper", PROTO_PAPERS.rx);
  });

  test("nothingFoundSaysSoAndEscapeClears", async ({ page }) => {
    await open(page);
    await field(page).fill("zzz");
    await expect(page.locator(".desktop-shell .desk-search__none")).toHaveText(t("bn", "desk.search.none").replace("{0}", "zzz"));
    await field(page).press("Escape");
    await expect(field(page)).toHaveValue("");
    await expect(page.locator(".desktop-shell .desk-search__pop")).toHaveCount(0);
  });

  for (const colorScheme of SCHEMES) {
    test(`axeAndCspWithResultsOpen (${colorScheme})`, async ({ page }) => {
      await page.emulateMedia({ colorScheme });
      await open(page);
      await field(page).fill(CREATININE.name);
      await expect(hits(page).first()).toBeVisible();
      await expectSchemeIsPainted(page, colorScheme);
      const report = await axeReport(page);
      expect(report.violations).toEqual([]);
      expect(await cspViolations(page)).toEqual([]);
    });
  }
});
