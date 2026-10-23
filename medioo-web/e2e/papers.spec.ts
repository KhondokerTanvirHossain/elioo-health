import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page } from "@playwright/test";
import type { AttentionJson, AttentionUrgentJson, CalendarJson, FollowUpsJson, MeJson, TimelineJson } from "../src/api/types";
import { shift } from "../src/app/app/(signed-in)/timeline/months";
import { formatDate, formatMonthYear, t, type CopyKey, type Lang } from "../src/copy";
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
const IN_FLIGHT = body<TimelineJson>("timeline/in-flight.json");
const ONE = body<MeJson>("me/one-patient.json");
const TWO = body<MeJson>("me/two-patients.json");
const FLAGGED = body<MeJson>("me/flags-set.json");
const STACK = body<AttentionJson>("attention/full-stack.json");
const NO_STACK = body<AttentionJson>("attention/empty.json");
const SEPTEMBER = body<CalendarJson>("calendar/september.json");
const OCTOBER_45 = body<CalendarJson>("calendar/forty-five.json");
const reading = (p: TimelineJson) => p.entries.some((e) => e.status === "RECEIVED" || e.status === "PROCESSING");

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
        // One dot per day Java says holds a paper, no more and no fewer (calendar/forty-five.json).
        const daysWithPapers = OCTOBER_45.days.filter((d) => d.papers.length > 0).length;
        expect(daysWithPapers, "the fixture's days with a paper").toBeGreaterThan(0);
        const paperDays = await page.locator(".month-calendar__day .month-calendar__mark--dot").count();
        expect(paperDays, "October's days with a paper").toBe(daysWithPapers);
        // The facility printed at real length is on a row, whole, before overflow is judged (else only short rows are).
        const facilities = FORTY_FIVE.entries.map((e) => e.facility ?? "");
        const longest = facilities.reduce((a, b) => (b.length > a.length ? b : a), "");
        expect(longest.length, "the fixture's longest facility").toBeGreaterThan(40);
        expect(await rendersOwnText(page, longest), `the facility "${longest}" is rendered`).toBe(true);
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
    // "Java's month, never today's" rests on the first open: no month is asked, so the caption and both links can only
    // come from Java's `month` (calendar/busy.json). While the wall clock is in that month a page reading `new Date()`
    // would pass here too; months.test.ts judges `shift` under a clock years away, which is what tells the two apart.
    const recorder = await open(page, "papers-busy");
    const patient = ONE.patients[0]!;
    const link = (month: string) => page.locator(".papers__months").getByRole("link", { name: formatMonthYear("bn", `${month}-01`), exact: true });
    const caption = page.locator(".month-calendar__caption");
    await expect(caption).toHaveText(`${patient.name} · ${formatMonthYear("bn", "2026-10-01")}`);
    const previous = link("2026-09");
    await expect(previous).toHaveAttribute("href", `/app/timeline?patient=${patient.id}&month=2026-09`);
    await expect(link("2026-11")).toHaveAttribute("href", `/app/timeline?patient=${patient.id}&month=2026-11`);
    await previous.click();
    await page.waitForURL((url) => url.searchParams.get("month") === "2026-09");
    // After the move the page shows Java's answer (calendar/september.json), and both neighbours are that month's,
    // computed from Java's `month`: August and October.
    expect(SEPTEMBER.month, "calendar/september.json answers September").toBe("2026-09");
    await expect(caption).toHaveText(`${patient.name} · ${formatMonthYear("bn", `${SEPTEMBER.month}-01`)}`);
    for (const month of [shift(SEPTEMBER.month, -1), shift(SEPTEMBER.month, 1)]) {
      await expect(link(month)).toHaveAttribute("href", `/app/timeline?patient=${patient.id}&month=${month}`);
    }
    expect([shift(SEPTEMBER.month, -1), shift(SEPTEMBER.month, 1)]).toEqual(["2026-08", "2026-10"]);
    const reads = (await recorder.java()).filter((r) => isRead(r.path, CALENDAR));
    expect(reads.map((r) => new URLSearchParams(r.query).get("month"))).toEqual([null, "2026-09"]);
  });

  test("aPaperBeingReadReloadsThePageWithoutJavaScript", async ({ page }) => {
    // Java's papersBody reloads every 5 s while a listed paper is RECEIVED or PROCESSING; the add sheet comes back here.
    expect(reading(IN_FLIGHT), "in-flight.json lists a paper still being read").toBe(true);
    const recorder = await open(page, "papers-in-flight");
    await expect(page.locator("meta[http-equiv='refresh']")).toHaveAttribute("content", "5");
    // And it does reload, with no script: one more render is one more timeline read (one more TIMELINE_VIEW, as Java's).
    const timelineReads = async () => (await recorder.java()).filter((r) => r.method === "GET" && isRead(r.path, TIMELINE)).length;
    expect(await timelineReads()).toBe(1);
    await expect.poll(timelineReads, { timeout: 9_000, intervals: [500] }).toBe(2);
  });
});

test("aSettledPageDoesNotReload", async ({ page }) => {
  // The other direction: nothing being read, no reload (and no TIMELINE_VIEW nobody made).
  expect(reading(FIRST), "first-page.json has nothing being read").toBe(false);
  await open(page, "papers-busy");
  await expect(page.locator("meta[http-equiv='refresh']")).toHaveCount(0);
});

test.describe("the attention strip", () => {
  /** The type and day a strip row says, from Java's fields: the full date (no month header holds the year), its marks. */
  const stripText = (lang: Lang, u: AttentionUrgentJson) => {
    const day = formatDate(lang, u.date);
    const when = u.date_is_upload_day ? `${day} ${t(lang, "timeline.uploaddate")}` : u.date_uncertain ? t(lang, "trend.date.uncertain", day) : day;
    return `${t(lang, `type.${u.document_type}` as CopyKey)} · ${when}`;
  };

  test("theStripIsTheStacksUrgentPapersOnly", async ({ page }) => {
    const urgent = STACK.items.filter((i): i is AttentionUrgentJson => i.kind === "urgent");
    const others = STACK.items.filter((i) => i.kind !== "urgent");
    // Guards: the stack holds both levels and every other kind, so a wrong filter or a dropped level is seen.
    expect(urgent.map((u) => u.level).sort(), "full-stack.json's urgent levels").toEqual(["now", "this_week"]);
    expect(others.map((i) => i.kind).sort(), "full-stack.json's other kinds").toEqual(["follow_up", "reading", "retake"]);
    expect(urgent.some((u) => u.date_uncertain), "an urgent paper with an uncertain date").toBe(true);
    await open(page, "papers-forty-five");
    const rows = await page.locator(".papers__strip a").evaluateAll((as) =>
      as.map((a) => ({
        href: a.getAttribute("href"),
        urgency: a.querySelector("[data-urgency]")?.getAttribute("data-urgency") ?? null,
        text: a.querySelector(".papers__urgent-what")?.textContent ?? null,
      })),
    );
    // Java's urgent items, in Java's order, each its own paper, its stored level and its full date as Java sent it.
    expect(rows).toEqual(urgent.map((u) => ({ href: `/app/documents/${u.document_id}`, urgency: u.level, text: stripText("bn", u) })));
    const hrefs = rows.map((r) => r.href);
    for (const o of others) expect(hrefs, `${o.kind} ${o.document_id}`).not.toContain(`/app/documents/${o.document_id}`);
    await expect(page.getByRole("heading", { name: t("bn", "attn.strip") })).toHaveCount(1);
  });

  test("noUrgentPaperNoStrip", async ({ page }) => {
    expect(NO_STACK.items, "attention/empty.json").toEqual([]);
    await open(page, "papers-one-month");
    await expect(page.getByRole("heading", { name: t("bn", "attn.strip") })).toHaveCount(0);
    await expect(page.locator(".papers__strip")).toHaveCount(0);
    // The page itself rendered: its list is there.
    expect(await listed(page)).toEqual(LAST.entries.map((e) => e.document_id));
  });
});

test.describe("the owner's send link", () => {
  test("theOwnerHasOneSendLinkThatComesBackHere", async ({ page }) => {
    const owner = ONE.patients[0]!;
    expect(owner.owner, "one-patient.json's patient is owned").toBe(true);
    await open(page, "papers-busy");
    const add = page.locator("main a[href^='/app/add']");
    await expect(add).toHaveCount(1);
    const target = new URL((await add.getAttribute("href"))!, "http://medioo.invalid");
    expect(target.pathname).toBe("/app/add");
    expect(target.searchParams.get("patient")).toBe(owner.id);
    expect(target.searchParams.get("back"), "the sheet comes back to this patient's papers").toBe(`/app/timeline?patient=${owner.id}`);
  });

  test("aShareMemberWithPapersGetsNoSendLink", async ({ page }) => {
    const member = TWO.patients.find((p) => !p.owner)!;
    expect(member, "two-patients.json holds a patient this family does not own").toBeDefined();
    await open(page, "papers-member", `/app/timeline?patient=${member.id}`);
    // The page has papers (so the header's send link would show for an owner) and none for this member.
    expect(await listed(page)).toEqual(FIRST.entries.map((e) => e.document_id));
    await expect(page.locator("main a[href^='/app/add']")).toHaveCount(0);
  });
});

test("aFilterThatMatchesNothingSaysSoAndKeepsTheFilters", async ({ page }) => {
  const none = body<TimelineJson>("timeline/rx-none.json");
  expect(none.entries, "rx-none.json: Java's prescription filter over lab reports only").toEqual([]);
  const patient = ONE.patients[0]!;
  await open(page, "papers-filter-none", `/app/timeline?patient=${patient.id}&type=rx`);
  await expect(page.getByText(t("bn", "timeline.filter.none"))).toBeVisible();
  await expect(page.getByText(t("bn", "timeline.end"))).toHaveCount(0);
  // The filters stay, so "all" brings everything back; the chosen one is marked.
  await expect(page.locator(".papers__filters a")).toHaveText([
    t("bn", "timeline.filter.all"),
    t("bn", "timeline.filter.lab"),
    t("bn", "timeline.filter.rx"),
  ]);
  await expect(page.locator(".papers__filters a[aria-current='true']")).toHaveText(t("bn", "timeline.filter.rx"));
  await expect(page.locator(".papers__filters a", { hasText: t("bn", "timeline.filter.all") })).toHaveAttribute(
    "href",
    `/app/timeline?patient=${patient.id}`,
  );
  expect(await listed(page)).toEqual([]);
  await expect(page.getByRole("heading", { name: t("bn", "timeline.empty.title", patient.name_possessive) })).toHaveCount(0);
});

test.describe("the chronic-flags ask (BMX-8)", () => {
  test("aPatientWithNoFlagIsAskedUnderTheList", async ({ page }) => {
    const patient = ONE.patients[0]!;
    expect(patient.chronic_flags_set, "one-patient.json's patient has no flag stored").toBe(false);
    await open(page, "papers-busy");
    const ask = page.locator(".papers__ask");
    await expect(ask.getByRole("heading", { name: t("bn", "timeline.flags.ask") })).toBeVisible();
    await expect(ask.getByText(t("bn", "timeline.flags.why"))).toBeVisible();
    await expect(ask.getByRole("link", { name: t("bn", "timeline.flags.cta") })).toHaveAttribute("href", `/app/family?patient=${patient.id}`);
    // Under the list, as Java places it.
    const after = await page.evaluate(() => {
      const list = document.querySelector(".month-list");
      const card = document.querySelector(".papers__ask");
      return !!list && !!card && (list.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    });
    expect(after, "the ask comes after the list").toBe(true);
  });

  test("aPatientWithAFlagIsNotAsked", async ({ page }) => {
    expect(FLAGGED.patients[0]!.chronic_flags_set, "flags-set.json's patient has a flag").toBe(true);
    await open(page, "papers-flags-set");
    // The page rendered (its list is there), and the ask did not.
    expect(await listed(page)).toEqual(FIRST.entries.map((e) => e.document_id));
    await expect(page.locator(".papers__ask")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: t("bn", "timeline.flags.ask") })).toHaveCount(0);
  });
});

test.describe("the page's title and disclaimer, in both languages", () => {
  for (const lang of ["bn", "en"] as const) {
    test(`thePageIsTitledAndCarriesTheDisclaimer (${lang})`, async ({ page, context, baseURL }) => {
      await context.addCookies([{ name: "baymax_lang", value: lang, url: baseURL! }]);
      await open(page, "papers-busy");
      await expect(page).toHaveTitle(`${t(lang, "tab.papers")} — ${t(lang, "app.title")}`);
      // The not-a-doctor line, once, inside main, on a page that shows stored levels and counts outside.
      await expect(page.locator("main .app-shell__foot")).toHaveText([t(lang, "disclaimer")]);
    });
  }
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
  await page.locator(".papers__months").getByRole("link", { name: formatMonthYear("bn", "2026-09-01"), exact: true }).click();
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

screenChecks("/app/timeline", "papers-busy", { crops: FOLLOW_ROWS, longest: true, disclaimer: true });
