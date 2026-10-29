import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { TimelineEntryJson } from "@/api/types";
import { formatDayMonth, formatMonthYear, t, type Lang } from "@/copy";
import firstPage from "../../../fixtures/api/timeline/first-page.json";
import fortyFive from "../../../fixtures/api/timeline/forty-five-first-page.json";
import inFlight from "../../../fixtures/api/timeline/in-flight.json";
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

  it("theCountsLineIsJavasTimelineSummary", () => {
    // Java's rules (TimelineSummary.line), written out here so the line is judged against them and not against itself:
    // a lab or imaging paper counts its values and, only when there are any, how many are outside; a prescription its
    // medicines; any other paper what it has; an upload day adds `timeline.sum.nodate`; the parts are joined with " · ".
    const javas = (lang: Lang, e: TimelineEntryJson): string => {
      const c = e.counts;
      const values = () => t(lang, "timeline.sum.values", c.values) + (c.outside > 0 ? `, ${t(lang, "timeline.sum.outside", c.outside)}` : "");
      const medicines = () => t(lang, "timeline.sum.medicines", c.medicines);
      const parts: string[] = [];
      if (e.document_type === "lab_report" || e.document_type === "imaging_report") parts.push(values());
      else if (e.document_type === "prescription") parts.push(medicines());
      else {
        if (c.values > 0) parts.push(values());
        if (c.medicines > 0) parts.push(medicines());
      }
      if (e.date_is_upload_day) parts.push(t(lang, "timeline.sum.nodate"));
      return parts.join(" · ");
    };
    const pages = [firstPage.body.entries, fortyFive.body.entries, inFlight.body.entries] as TimelineEntryJson[][];
    const done = pages.flat().filter((e) => e.status === "DONE");
    // Guards: the line was judged on a lab with values outside, a prescription and an upload day, all of them read.
    expect(done.some((e) => e.document_type === "lab_report" && e.counts.outside > 0), "a read lab with values outside").toBe(true);
    expect(done.some((e) => e.document_type === "prescription" && e.counts.medicines > 0), "a read prescription").toBe(true);
    expect(done.some((e) => e.date_is_upload_day), "a read paper filed under its upload day").toBe(true);
    let judged = 0;
    for (const lang of LANGS) {
      for (const entries of pages) {
        const root = draw(entries, lang);
        for (const e of entries.filter((x) => x.status === "DONE")) {
          const want = javas(lang, e);
          const line = rowOf(root, e.document_id)!.querySelector(".month-list__summary");
          expect(line?.textContent ?? "", `${lang} ${e.document_id}`).toBe(want);
          judged++;
        }
        cleanup();
      }
    }
    expect(judged, "rows judged").toBe(done.length * LANGS.length);
  });

  it("theReviewChipIsNeverTheInsideColour", () => {
    // A paper waiting for review is not "within range": its chip takes the unknown look, as Java's `.chips .chip.review`
    // (the unknown status colour) and the prototype's `.chip.unk`, never `.status-chip--in`'s tint, which means INSIDE.
    const pending = inFlight.body.entries.find((e) => e.review_pending);
    expect(pending, "in-flight.json holds a paper waiting for review").toBeDefined();
    const chip = rowOf(draw(inFlight.body.entries as TimelineEntryJson[]), pending!.document_id)!.querySelector(".month-list__review");
    expect(chip?.textContent).toBe(t("bn", "timeline.review.pending"));
    const review = declarations(MONTH_LIST_CSS, ".month-list__review");
    const inside = declarations(STATUS_CHIP_CSS, ".status-chip--in");
    expect(inside, "the inside chip's colours were read").toEqual({ background: "var(--tint-soft)", color: "var(--tint-ink)" });
    expect(review).toEqual({ background: "var(--unk-soft)", color: "var(--label2)" });
    expect(review.background).not.toBe(inside.background);
    expect(review.color).not.toBe(inside.color);
  });
});

const MONTH_LIST_CSS = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "MonthList.css"), "utf8");
const STATUS_CHIP_CSS = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "../StatusChip/StatusChip.css"), "utf8");

/** The background and color one rule declares for exactly `selector` (a rule listing only that selector). */
function declarations(css: string, selector: string): { background?: string; color?: string } {
  const blocks = [...css.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  const rule = blocks.find(([, sel]) => sel!.split(",").map((s) => s.trim()).includes(selector));
  if (!rule) throw new Error(`no rule for ${selector}`);
  const out: { background?: string; color?: string } = {};
  for (const decl of rule[2]!.split(";")) {
    const [prop, ...rest] = decl.split(":");
    const name = prop?.trim();
    if (name === "background" || name === "color") out[name] = rest.join(":").trim();
  }
  return out;
}
