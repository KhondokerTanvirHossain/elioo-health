import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { LabsJson, MedicinesJson } from "../src/api/types";
import { t } from "../src/copy";
import { FLIPPED } from "../src/routing/flipped";
import { useScenario, type CropRow } from "./support/browser-api";
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
const LABS = body<LabsJson>("health/labs-some.json");

/** A prescription's lines as the crop checks take them: each its crop's href, no stored size (Java sends none here). */
const rowsOf = (m: MedicinesJson): CropRow[] => m.medicines.map((line) => ({ crop: { src: line.crop.src, width: null, height: null } }));

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
  test("everyMedicineCropIsItsOwn", async ({ page }) => {
    await open(page, "health-some");
    // cropRowsReport counts every [data-value-row] on the page against the fixture's lines, refuses a fixture whose
    // crops share a width (it could not see a swap), and matches each line's one image by its href and decoded width.
    expect(await cropRowsReport(page, rowsOf(LATEST))).toEqual([]);
  });

  test("theHealthTabAppearsInPreviewOnly", async ({ page }) => {
    const tab = t("bn", "tab.health");
    // Not a preview page, while Caddy does not send /app/health to Next (F9): no tab, since its link would reach Java's 404.
    expect(FLIPPED).not.toContain("/app/health");
    await open(page, "health-some");
    await expect(page.locator("[data-page=health]")).toHaveCount(1);
    await expect(page.getByRole("link", { name: tab })).toHaveCount(0);
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

  test("aNewerPrescriptionStillBeingReadIsSaidAndLinked", async ({ page }) => {
    await open(page, "health-newer-unread");
    const link = page.getByRole("link", { name: t("bn", "health.medicines.newer_unread") });
    await expect(link).toHaveAttribute("href", `/app/documents/${NEWER.newer_document_id}`);
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
test.describe("health-some", () => screenChecks("/app/health", "health-some", { crops: rowsOf(LATEST), longest: true }));
test.describe("health-newer-unread", () => screenChecks("/app/health", "health-newer-unread", { crops: rowsOf(NEWER) }));
test.describe("health-none", () => screenChecks("/app/health", "health-none"));
test.describe("health-two-units", () => screenChecks("/app/health", "health-two-units", { crops: rowsOf(LATEST) }));
test.describe("health-bp", () => screenChecks("/app/health", "health-bp", { crops: rowsOf(LATEST) }));
test.describe("health-bounds", () => screenChecks("/app/health", "health-bounds", { crops: rowsOf(LATEST) }));
test.describe("health-empty-latest", () => screenChecks("/app/health", "health-empty-latest"));
