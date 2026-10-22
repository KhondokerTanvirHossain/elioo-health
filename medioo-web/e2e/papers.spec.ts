import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { FollowUpsJson, MeJson, TimelineJson } from "../src/api/types";
import { formatMonthYear, t } from "../src/copy";
import { useScenario, type CropRow } from "./support/browser-api";
import { FIXTURES, longNameOf } from "./support/scenarios.mjs";
import { cropRowsReport, overflowReport, rendersOwnText, screenChecks } from "./support/screen-checks";

/*
 * The papers page (plan W8) at /app/timeline, against the production build and stub Java: the wall calendar, the doctor's dates
 * and the month list, every control a plain link, so the page reads and pages without JavaScript. Every answer is a
 * fixture Java's PatientTimelineApiContractTest, AttentionApiContractTest or MeApiContractTest wrote.
 */

const body = <T>(fixture: string): T => (JSON.parse(readFileSync(join(FIXTURES, fixture), "utf8")) as { body: T }).body;
const FIRST = body<TimelineJson>("timeline/first-page.json");
const LAST = body<TimelineJson>("timeline/last-page.json");
const LAB = body<TimelineJson>("timeline/lab-only.json");
const RX = body<TimelineJson>("timeline/rx-only.json");
const FORTY_FIVE = body<TimelineJson>("timeline/forty-five-first-page.json");
const FOLLOWS = body<FollowUpsJson>("follow-ups/some.json");
const ONE = body<MeJson>("me/one-patient.json");
const TWO = body<MeJson>("me/two-patients.json");

/** The follow-up rows, as the crop checks read them: each its own crop, in Java's order. */
const FOLLOW_ROWS: CropRow[] = FOLLOWS.items.map((i) => ({ crop: { src: i.crop.src, width: null, height: null } }));

const TIMELINE = "/app/api/patients/{id}/timeline";
const CALENDAR = "/app/api/patients/{id}/calendar";
const isRead = (path: string, route: string) => new RegExp(`^${route.replace("{id}", "[^/]+")}$`).test(path);

async function open(page: Page, scenario: string, path = "/app/timeline") {
  const recorder = await useScenario(page, scenario);
  const response = await page.goto(path);
  expect(response?.status(), `${path} under ${scenario}`).toBe(200);
  await page.waitForLoadState("load");
  return recorder;
}

/** The papers the list shows, by the one link each row is. */
const listed = (page: Page) =>
  page.locator(".month-list a[href^='/app/documents/']").evaluateAll((as) => as.map((a) => a.getAttribute("href")!.split("/")[3]!));

test.describe("forty-five papers and the longest name (master Review Focus 1)", () => {
  for (const width of [390, 1440]) {
    test.describe(`at ${width} px`, () => {
      test.use({ viewport: { width, height: 900 } });

      test("fortyFivePapersAndTheLongestNameDoNotOverflow", async ({ page }) => {
        await open(page, "papers-forty-five-long");
        const longName = longNameOf("papers-forty-five-long");
        // The page was judged on the long name and the forty-five papers, not on short strings or a quiet month.
        expect(await rendersOwnText(page, longName), `the long name "${longName}" is rendered`).toBe(true);
        expect(await listed(page)).toEqual(FORTY_FIVE.entries.map((e) => e.document_id));
        const paperDays = await page.locator(".month-calendar__day .month-calendar__mark--dot").count();
        expect(paperDays, "October's days with a paper").toBeGreaterThanOrEqual(21);
        const report = await overflowReport(page, "body");
        expect(report.over).toEqual([]);
        expect(report.page[0], "the page itself scrolls sideways").toBeLessThanOrEqual(report.page[1] + 1);
        const grid = await page.locator(".month-calendar__grid").evaluate((g) => [g.scrollWidth, g.clientWidth]);
        expect(grid[0], "the calendar grid").toBeLessThanOrEqual(grid[1]!);
      });
    });
  }
});

test("theFilterIsALink", async ({ page }) => {
  const recorder = await open(page, "papers-busy");
  const patient = ONE.patients[0]!.id;
  // A plain link, whose href carries the filter (no script decides it).
  const lab = page.locator(".papers__filters a", { hasText: t("bn", "timeline.filter.lab") });
  await expect(lab).toHaveAttribute("href", `/app/timeline?patient=${patient}&type=lab`);
  await lab.click();
  await page.waitForURL((url) => url.searchParams.get("type") === "lab");
  // Guard: the fixture is lab reports only, and differs from the unfiltered page.
  expect(LAB.entries.every((e) => e.document_type === "lab_report"), "lab-only.json is lab reports").toBe(true);
  expect(await listed(page)).toEqual(LAB.entries.map((e) => e.document_id));
  expect(await listed(page)).not.toEqual(FIRST.entries.map((e) => e.document_id));
  await expect(page.locator(".papers__filters a[aria-current='true']")).toHaveText(t("bn", "timeline.filter.lab"));
  const reads = (await recorder.java()).filter((r) => isRead(r.path, TIMELINE));
  expect(reads.map((r) => new URLSearchParams(r.query).get("type")), "the timeline reads, in order").toEqual([null, "lab"]);
  // And prescriptions, the other way.
  await page.locator(".papers__filters a", { hasText: t("bn", "timeline.filter.rx") }).click();
  await page.waitForURL((url) => url.searchParams.get("type") === "rx");
  expect(await listed(page)).toEqual(RX.entries.map((e) => e.document_id));
});

test.describe("without JavaScript", () => {
  test.use({ javaScriptEnabled: false });

  test("moreIsALinkThatWorksWithoutJavaScript", async ({ page }) => {
    const recorder = await open(page, "papers-busy");
    expect(await listed(page)).toEqual(FIRST.entries.map((e) => e.document_id));
    const more = page.getByRole("link", { name: t("bn", "timeline.more") });
    const cursor = FIRST.next_cursor!;
    await expect(more).toHaveAttribute("href", `/app/timeline?patient=${ONE.patients[0]!.id}&cursor=${encodeURIComponent(cursor)}`);
    await more.click();
    await page.waitForURL((url) => url.searchParams.get("cursor") === cursor);
    expect(await listed(page), "the next page, from Java's cursor").toEqual(LAST.entries.map((e) => e.document_id));
    // The last page has no next cursor, so no more link.
    await expect(page.getByRole("link", { name: t("bn", "timeline.more") })).toHaveCount(0);
    const reads = (await recorder.java()).filter((r) => isRead(r.path, TIMELINE));
    expect(reads.map((r) => new URLSearchParams(r.query).get("cursor"))).toEqual([null, cursor]);
  });

  test("theMonthMovesByLink", async ({ page }) => {
    const recorder = await open(page, "papers-busy");
    const caption = page.locator(".month-calendar__caption");
    await expect(caption).toHaveText(`${ONE.patients[0]!.name} · ${formatMonthYear("bn", "2026-10-01")}`);
    const previous = page.getByRole("link", { name: formatMonthYear("bn", "2026-09-01") });
    await expect(previous).toHaveAttribute("href", `/app/timeline?patient=${ONE.patients[0]!.id}&month=2026-09`);
    await expect(page.getByRole("link", { name: formatMonthYear("bn", "2026-11-01") })).toHaveAttribute(
      "href",
      `/app/timeline?patient=${ONE.patients[0]!.id}&month=2026-11`,
    );
    await previous.click();
    await page.waitForURL((url) => url.searchParams.get("month") === "2026-09");
    // The month on the page is the one Java answered (calendar/september.json), never the one the link asked for.
    await expect(caption).toHaveText(`${ONE.patients[0]!.name} · ${formatMonthYear("bn", "2026-09-01")}`);
    const reads = (await recorder.java()).filter((r) => isRead(r.path, CALENDAR));
    expect(reads.map((r) => new URLSearchParams(r.query).get("month"))).toEqual([null, "2026-09"]);
  });
});

test.describe("an empty patient", () => {
  test("anEmptyPatientGetsTheEmptyState", async ({ page }) => {
    const owner = ONE.patients[0]!;
    await open(page, "papers-empty");
    await expect(page.getByRole("heading", { name: t("bn", "timeline.empty.title", owner.name_possessive) })).toBeVisible();
    await expect(page.getByText(t("bn", "timeline.empty.body"))).toBeVisible();
    await expect(page.getByText(t("bn", "timeline.empty.foot"))).toBeVisible();
    // One add link, to the add sheet for this patient, and nothing else to read.
    const add = page.locator("main a[href^='/app/add']");
    await expect(add).toHaveCount(1);
    await expect(add).toHaveAttribute("href", new RegExp(`^/app/add\\?patient=${owner.id}&back=`));
    await expect(page.locator(".month-list")).toHaveCount(0);
  });

  test("aShareMemberGetsNoAddLink", async ({ page }) => {
    const member = TWO.patients.find((p) => !p.owner)!;
    expect(member, "two-patients.json holds a patient this family does not own").toBeDefined();
    await open(page, "papers-empty-member", `/app/timeline?patient=${member.id}`);
    await expect(page.getByRole("heading", { name: t("bn", "timeline.empty.title", member.name_possessive) })).toBeVisible();
    await expect(page.locator("main a[href^='/app/add']")).toHaveCount(0);
  });
});

test("everyFollowUpCropIsItsOwn", async ({ page }) => {
  await open(page, "papers-busy");
  // Each doctor's date shows its own crop, decoded at its own width; a swapped or missing crop fails by row.
  expect(await cropRowsReport(page, FOLLOW_ROWS)).toEqual([]);
  // And the instruction beside it is the one Java sent for that row.
  const instructions = await page.locator("[data-value-row] .due-card__instruction").allTextContents();
  expect(instructions).toEqual(FOLLOWS.items.map((i) => i.instruction));
});

test("oneOpenIsOneTimelineView", async ({ page }) => {
  const recorder = await open(page, "papers-busy");
  const count = async () => {
    const java = await recorder.java();
    return { timeline: java.filter((r) => r.method === "GET" && isRead(r.path, TIMELINE)).length, calendar: java.filter((r) => r.method === "GET" && isRead(r.path, CALENDAR)).length };
  };
  // One open: one timeline read (Java records the one TIMELINE_VIEW, TimelineAuditTest) and one calendar read (none).
  expect(await count()).toEqual({ timeline: 1, calendar: 1 });
  // A month link is a new render, so one more of each: the views per month link are known, never assumed to be zero.
  await page.getByRole("link", { name: formatMonthYear("bn", "2026-09-01") }).click();
  await page.waitForURL((url) => url.searchParams.get("month") === "2026-09");
  await page.waitForLoadState("load");
  expect(await count()).toEqual({ timeline: 2, calendar: 2 });
  // The browser itself read nothing: every read is the server's, once.
  expect(recorder.requests().filter((r) => isRead(r.path, TIMELINE) || isRead(r.path, CALENDAR))).toEqual([]);
});

test("aNonPreviewFamilyOnThePreviewPathReadsNothingButMe", async ({ page }) => {
  const recorder = await useScenario(page, "me-preview-off");
  const response = await page.goto("/app/preview/timeline");
  expect(response?.status()).toBe(404);
  expect((await recorder.java()).map((r) => `${r.method} ${r.path}`)).toEqual(["GET /app/api/me"]);
});

screenChecks("/app/timeline", "papers-busy", { crops: FOLLOW_ROWS, longest: true });
