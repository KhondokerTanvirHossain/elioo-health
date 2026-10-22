import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { TimelineEntryJson } from "@/api/types";
import { formatDayMonth, formatMonthYear, t, type Lang } from "@/copy";
import firstPage from "../../../fixtures/api/timeline/first-page.json";
import { MonthList } from "./MonthList";

afterEach(cleanup);

/*
 * The papers page's list (plan W8), over B3's generated GET /app/api/patients/{id}/timeline bodies: Java's rows in
 * Java's order, under the month Java named for each, each row one link to its paper, its day said as Java filed it.
 */

const LANGS: readonly Lang[] = ["bn", "en"];
const ENTRIES: TimelineEntryJson[] = firstPage.body.entries;

const draw = (entries: TimelineEntryJson[], lang: Lang = "bn") =>
  render(<MonthList entries={entries} lang={lang} preview={false} />).container;

/** The row of one paper, found by its link (the row is the one link to the paper). */
const rowOf = (root: HTMLElement, id: string) => root.querySelector<HTMLElement>(`a[href="/app/documents/${id}"]`);

/** The month section a paper's row sits under, by the section's own `data-month`. */
const sectionMonthOf = (root: HTMLElement, id: string) => rowOf(root, id)?.closest<HTMLElement>("[data-month]")?.dataset.month ?? null;

describe("MonthList", () => {
  it("entriesGroupUnderJavasMonth", () => {
    // A unit-level contract probe: on Java's real output `month` is always `date`'s month (TimelineJson derives it, D2
    // owns the Dhaka-day rule), so only a copy with one entry's month moved can tell grouping by `month` from grouping by
    // a month parsed out of `date`. The mutated entry is dated 1 October and Java says September.
    const moved = structuredClone(ENTRIES);
    const probe = moved[0]!;
    expect(probe.date, "the probe is dated 1 October").toBe("2026-10-01");
    probe.month = "2026-09";
    expect(probe.month, "the probe's month differs from its date's").not.toBe(probe.date.slice(0, 7));
    const root = draw(moved);
    expect(sectionMonthOf(root, probe.document_id), `${probe.document_id} sits under`).toBe("2026-09");
    // Every other row sits under its own `month` too, and each month is one section, headed by that month.
    for (const e of moved) expect(sectionMonthOf(root, e.document_id), `${e.document_id} sits under`).toBe(e.month);
    const sections = [...root.querySelectorAll<HTMLElement>("[data-month]")];
    expect(sections.map((s) => s.dataset.month)).toEqual(["2026-09"]);
    expect(sections[0]!.querySelector("h2")?.textContent).toBe(formatMonthYear("bn", "2026-09-01"));
  });

  it("theRowsAreJavasInJavasOrderUnderOneHeaderPerMonth", () => {
    const root = draw(ENTRIES);
    const sections = [...root.querySelectorAll<HTMLElement>("[data-month]")];
    expect(sections.map((s) => s.dataset.month), "one section per run of a month, newest first").toEqual(["2026-10", "2026-09"]);
    const ids = [...root.querySelectorAll<HTMLAnchorElement>("a[href^='/app/documents/']")].map((a) => a.getAttribute("href"));
    expect(ids).toEqual(ENTRIES.map((e) => `/app/documents/${e.document_id}`));
  });

  it("anUploadDayIsSaidAsSuch", () => {
    const upload = ENTRIES.filter((e) => e.date_is_upload_day);
    expect(upload.length, "the fixture holds an upload day").toBeGreaterThan(0);
    for (const lang of LANGS) {
      const root = draw(ENTRIES, lang);
      for (const e of ENTRIES) {
        const date = rowOf(root, e.document_id)!.querySelector(".month-list__date")?.textContent;
        const day = formatDayMonth(lang, e.date);
        if (e.date_is_upload_day) expect(date, `${lang} ${e.document_id}`).toBe(`${day} ${t(lang, "timeline.uploaddate")}`);
        else expect(date, `${lang} ${e.document_id}`).not.toContain(t(lang, "timeline.uploaddate"));
      }
      cleanup();
    }
  });

  it("anUncertainDateIsMarked", () => {
    const unsure = ENTRIES.filter((e) => e.date_uncertain);
    expect(unsure.length, "the fixture holds an uncertain date").toBeGreaterThan(0);
    for (const lang of LANGS) {
      const root = draw(ENTRIES, lang);
      for (const e of ENTRIES) {
        const date = rowOf(root, e.document_id)!.querySelector(".month-list__date")?.textContent;
        const day = formatDayMonth(lang, e.date);
        const marked = t(lang, "trend.date.uncertain", day);
        if (e.date_uncertain) expect(date, `${lang} ${e.document_id}`).toBe(marked);
        else if (!e.date_is_upload_day) expect(date, `${lang} ${e.document_id}: a certain printed date is the day alone`).toBe(day);
      }
      cleanup();
    }
  });

  it("aChipIsJavasNowOrThisWeekAndNothingElse", () => {
    const root = draw(ENTRIES);
    for (const e of ENTRIES) {
      const tag = rowOf(root, e.document_id)!.querySelector("[data-urgency]");
      const want = e.chip === "now" ? "now" : e.chip === "this_week" ? "this_week" : null;
      expect(tag?.getAttribute("data-urgency") ?? null, `${e.document_id} chip ${e.chip}`).toBe(want);
    }
    // Guard: both chips were met.
    expect(ENTRIES.map((e) => e.chip)).toEqual(expect.arrayContaining(["now", "this_week", null]));
  });

  it("aPaperNotReadSaysWhereItIs", () => {
    const retake = ENTRIES.find((e) => e.status === "NEEDS_RETAKE")!;
    expect(retake.retake_reason_key, "the retake names its line").not.toBeNull();
    for (const lang of LANGS) {
      const row = rowOf(draw(ENTRIES, lang), retake.document_id)!;
      expect(row.querySelector(".month-list__state")?.textContent).toBe(t(lang, "timeline.retake"));
      expect(row.textContent).toContain(t(lang, retake.retake_reason_key as Parameters<typeof t>[1]));
      // A paper that was not read carries no level and no count.
      expect(row.querySelector("[data-urgency]")).toBeNull();
      expect(row.querySelector(".month-list__summary")).toBeNull();
      cleanup();
    }
  });
});
