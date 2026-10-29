import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { LabsJson, MedicinesJson } from "../src/api/types";
import { t } from "../src/copy";
import { CHIP_KEY, chipKindOf } from "../src/components/StatusChip/StatusChip";
import { FLIPPED } from "../src/routing/flipped";
import { useScenario, type CropRow } from "./support/browser-api";
import { expectedWidth } from "./support/png.mjs";
import { FIXTURES } from "./support/scenarios.mjs";
import { cropRowsReport, screenChecks } from "./support/screen-checks";

/*
 * The health tab (plan W9) on /app/health against the production build and stub Java answering the bodies
 * HealthApiContractTest wrote: each medicine line beside its own crop, the tab only where its page answers (the preview
 * until F9), the patient never decided by the page, and the shared screen checks over the lab values and the latest
 * prescription, with the longest name.
 */

const body = <T>(file: string): T => (JSON.parse(readFileSync(join(FIXTURES, file), "utf8")) as { body: T }).body;
const LATEST = body<MedicinesJson>("health/medicines-latest.json");
const NEWER = body<MedicinesJson>("health/medicines-newer-unread.json");
const EMPTY = body<MedicinesJson>("health/medicines-empty-latest.json");
const NONE_NEWER = body<MedicinesJson>("health/medicines-none-newer-unread.json");
const LABS = body<LabsJson>("health/labs-some.json");
const labs = (name: string) => body<LabsJson>(`health/${name}.json`);

/** A prescription's lines as the crop checks take them: each its crop's href, no stored size (Java sends none here). */
const rowsOf = (m: MedicinesJson): CropRow[] => m.medicines.map((line) => ({ crop: { src: line.crop.src, width: null, height: null } }));

/**
 * The lab values' crop rows in page order: every series' readings as Java sent them (a charted series' papers under
 * its chart, newest first, as Java sent them), then each pair's systolic and diastolic halves. Each its own crop.
 */
const labRowsOf = (l: LabsJson): CropRow[] =>
  [...l.series.flatMap((s) => s.points), ...l.bp.points.flatMap((p) => [p.systolic, p.diastolic])].map((p) => ({
    crop: { src: p.crop.src, width: null, height: null },
  }));

/** Every crop row of a page: its lab values', then its prescription's lines. */
const pageRows = (l: LabsJson, m: MedicinesJson): CropRow[] => [...labRowsOf(l), ...rowsOf(m)];

async function open(page: Page, scenario: string, path = "/app/health") {
  const recorder = await useScenario(page, scenario);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  return recorder;
}

/** Every Java read the Next server made in this test, as `METHOD path`. */
const javaReads = async (recorder: Awaited<ReturnType<typeof useScenario>>) => (await recorder.java()).map((r) => `${r.method} ${r.path}`);

test.describe("the health tab", () => {
  test("everyValueAndMedicineCropIsItsOwn", async ({ page }) => {
    await open(page, "health-some");
    // cropRowsReport counts every [data-value-row] on the page against the fixture's rows (each lab reading, then each
    // medicine line), refuses a fixture whose crops share a width (it could not see a swap), and matches each row's one
    // image by its href and decoded width.
    expect(await cropRowsReport(page, pageRows(LABS, LATEST))).toEqual([]);
  });

  test("eachBloodPressureHalfShowsItsOwnChip", async ({ page }) => {
    await open(page, "health-bp-flagged");
    const pair = labs("bp-flagged").bp.points[0]!;
    // Systolic outside high, diastolic inside: each word inside its own half, never the other's.
    const word = (p: typeof pair.systolic) => t("bn", CHIP_KEY[chipKindOf(p.kind, p.side)]);
    expect(word(pair.systolic), "the fixture's halves carry two chips").not.toBe(word(pair.diastolic));
    for (const half of ["systolic", "diastolic"] as const) {
      await expect(page.locator(`[data-half=${half}] .status-chip__word`), half).toHaveText([word(pair[half])]);
    }
  });

  test("theCropRetryFetchesTheImageAgain", async ({ page }) => {
    // W6's Crop (Review Focus 2) on a medicine line: refused until the retry is offered, then served. How many loads the
    // page made before the press is the browser's and React's (an image that failed before hydration is judged on
    // mount), so the press is judged on its own: one more request, and an image that decoded.
    const rows = pageRows(LABS, LATEST);
    const broken = rowsOf(LATEST)[0]!;
    const key = new URL(broken.crop.src, "http://e2e.invalid").searchParams.get("key");
    let refuse = true;
    let asked = 0;
    await useScenario(page, "health-some", { rows });
    await page.route(
      (url) => url.pathname.endsWith("/image") && url.searchParams.get("key") === key,
      (route) => {
        asked++;
        return refuse ? route.fulfill({ status: 403 }) : route.fallback();
      },
    );
    expect((await page.goto("/app/health"))?.status()).toBe(200);
    const row = page.locator(".medicines [data-value-row]").first();
    await row.scrollIntoViewIfNeeded();
    const retry = row.getByRole("button", { name: t("bn", "crop.retry") });
    await retry.waitFor();
    const before = asked;
    expect(before, "the crop was asked for and refused").toBeGreaterThan(0);
    refuse = false;
    await retry.click();
    await expect.poll(() => asked, { message: "requests for the crop's key: the press made one more" }).toBe(before + 1);
    await expect(row.locator("img")).toBeVisible();
    await expect
      .poll(() => row.locator("img").evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
      .toBe(expectedWidth(broken));
  });

  test("theHealthTabAppearsInAndOutOfThePreview", async ({ page }) => {
    const tab = t("bn", "tab.health");
    // Not a preview page: Caddy sends /app/health to Next (F9), so the tab is drawn, a link to this page, marked as it.
    // (Before F9 it was absent here, its link would have reached Java's 404: TabBar.test.tsx keeps that direction.)
    expect(FLIPPED).toContain("/app/health");
    await open(page, "health-some");
    await expect(page.locator("[data-page=health]")).toHaveCount(1);
    const plain = page.getByRole("link", { name: tab });
    expect(await plain.count(), "the tab is drawn outside the preview").toBeGreaterThan(0);
    for (const link of await plain.all()) {
      await expect(link).toHaveAttribute("href", "/app/health");
      await expect(link).toHaveAttribute("aria-current", "page");
    }
    // A preview page: the tab, inside the preview, marked as this page.
    await open(page, "health-preview", "/app/preview/health");
    const links = page.getByRole("link", { name: tab });
    expect(await links.count(), "the tab is drawn").toBeGreaterThan(0);
    for (const link of await links.all()) {
      await expect(link).toHaveAttribute("href", "/app/preview/health");
      await expect(link).toHaveAttribute("aria-current", "page");
    }
  });

  test("twoPatientsAndNoChoiceShowTheChooserAndReadNoLabs", async ({ page }) => {
    const recorder = await open(page, "health-two-no-choice");
    await expect(page.getByText(t("bn", "switch.title"))).toBeVisible();
    await expect(page.locator("[data-page=health]")).toHaveCount(0);
    const reads = await javaReads(recorder);
    expect(reads.filter((r) => r.endsWith("/labs") || r.endsWith("/medicines")), "the page decided nobody, so read nobody's").toEqual([]);
  });

  test("theReadsAreTheShownPatientsOnly", async ({ page }) => {
    const recorder = await open(page, "health-some");
    const reads = (await javaReads(recorder)).filter((r) => r.includes("/app/api/patients/"));
    // me/one-patient.json's one patient, each route read once.
    const id = body<{ patients: { id: string }[] }>("me/one-patient.json").patients[0]!.id;
    expect(reads.sort()).toEqual([`GET /app/api/patients/${id}/labs`, `GET /app/api/patients/${id}/medicines`]);
  });

  test("theReadsAreTheChosenPatientsNotTheFirst", async ({ page }) => {
    // Two patients, the second asked for: a page that read me.patients[0] would make two reads of the same shape.
    const [first, second] = body<{ patients: { id: string; name: string }[] }>("me/two-patients.json").patients;
    const recorder = await open(page, "health-two-no-choice", `/app/health?patient=${second!.id}`);
    await expect(page.locator("[data-page=health]")).toHaveCount(1);
    const reads = (await javaReads(recorder)).filter((r) => r.endsWith("/labs") || r.endsWith("/medicines"));
    expect(reads.sort(), "the chosen patient's two reads, each once").toEqual([
      `GET /app/api/patients/${second!.id}/labs`,
      `GET /app/api/patients/${second!.id}/medicines`,
    ]);
    expect(reads.filter((r) => r.includes(first!.id)), "nothing of the first patient's is read").toEqual([]);
    await expect(page.locator(".patient-switcher__label")).toHaveText(t("bn", "switch.label", second!.name));
  });

  test("aNewerPrescriptionStillBeingReadIsSaidAndLinked", async ({ page }) => {
    await open(page, "health-newer-unread");
    const link = page.getByRole("link", { name: t("bn", "health.medicines.newer_unread") });
    await expect(link).toHaveAttribute("href", `/app/documents/${NEWER.newer_document_id}`);
  });

  test("aFirstPrescriptionStillBeingReadIsSaidAndLinked", async ({ page }) => {
    await open(page, "health-first-unread");
    const link = page.getByRole("link", { name: t("bn", "health.medicines.first_unread") });
    await expect(link).toHaveAttribute("href", `/app/documents/${NONE_NEWER.newer_document_id}`);
    await expect(page.getByText(t("bn", "health.medicines.newer_unread"))).toHaveCount(0);
    await expect(page.getByText(t("bn", "health.medicines.none"))).toHaveCount(0);
  });

  test("theLinesComeWithThePharmacyRuleAndTheirPrescription", async ({ page }) => {
    await open(page, "health-some");
    const medicines = page.locator("section[aria-labelledby=health-medicines]");
    await expect(medicines.locator(".medicines__pharmacy")).toHaveText(t("bn", "med.pharmacy"));
    await expect(medicines.locator(".medicines__doctor")).toHaveText(t("bn", "health.medicines.doctor_only"));
    await expect(medicines.getByRole("link", { name: t("bn", "health.medicines.open") })).toHaveAttribute(
      "href",
      `/app/documents/${LATEST.document_id}`,
    );
  });

  test("theDisclaimerIsShownWheneverAValueOrAMedicineIs", async ({ page }) => {
    // Both, lab values only, a prescription only: the line, said once. Since W8 the shell says it under `main` on every
    // signed-in page (AppShell, as Java's AppShell footer does), so the page does not say it a second time, and with
    // neither the shell's line is still the only one.
    for (const scenario of ["health-some", "health-labs-only", "health-medicines-only", "health-none"]) {
      await open(page, scenario);
      await expect(page.locator("[data-page=health]"), scenario).toHaveCount(1);
      await expect(page.getByText(t("bn", "disclaimer"), { exact: true }), scenario).toHaveCount(1);
      await expect(page.locator("main .app-shell__foot"), scenario).toHaveText([t("bn", "disclaimer")]);
    }
  });

  test("everySeriesIsShownAsJavaSentIt", async ({ page }) => {
    await open(page, "health-some");
    const sections = page.locator("[data-lab-series]");
    await expect(sections).toHaveCount(LABS.series.length);
    for (const [i, series] of LABS.series.entries()) {
      await expect(sections.nth(i).locator(".lab-list__name")).toHaveText(series.name);
    }
  });
});

// The routed page's content checks, both schemes. health-some is the routed state (a trend, two one-reading sentences,
// the latest prescription's lines beside their crops), with its longest twin.
// Every crop on the page is listed (each lab reading's, then each medicine line's), so everyCropIsItsRowsOwnImage
// judges each value's own crop as well as each line's.
test.describe("health-some", () => screenChecks("/app/health", "health-some", { crops: pageRows(LABS, LATEST), longest: true }));
test.describe("health-newer-unread", () => screenChecks("/app/health", "health-newer-unread", { crops: pageRows(LABS, NEWER) }));
test.describe("health-none", () => screenChecks("/app/health", "health-none"));
test.describe("health-two-units", () => screenChecks("/app/health", "health-two-units", { crops: pageRows(labs("labs-two-units"), LATEST) }));
test.describe("health-bp", () => screenChecks("/app/health", "health-bp", { crops: pageRows(labs("bp"), LATEST) }));
test.describe("health-bp-flagged", () => screenChecks("/app/health", "health-bp-flagged", { crops: pageRows(labs("bp-flagged"), LATEST) }));
test.describe("health-bounds", () => screenChecks("/app/health", "health-bounds", { crops: pageRows(labs("labs-non-numeric"), LATEST) }));
test.describe("health-empty-latest", () => screenChecks("/app/health", "health-empty-latest", { crops: pageRows(LABS, EMPTY) }));
test.describe("health-first-unread", () => screenChecks("/app/health", "health-first-unread"));
