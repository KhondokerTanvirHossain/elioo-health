import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LabsJson, TrendPointJson } from "@/api/types";
import { formatDate, t, type Lang } from "@/copy";
import { CHIP_KEY, chipKindOf } from "@/components/StatusChip/StatusChip";
import bp from "../../../fixtures/api/health/bp.json";
import bpFlagged from "../../../fixtures/api/health/bp-flagged.json";
import labsNonNumeric from "../../../fixtures/api/health/labs-non-numeric.json";
import labsNone from "../../../fixtures/api/health/labs-none.json";
import labsPrintedRange from "../../../fixtures/api/health/labs-printed-range.json";
import labsSome from "../../../fixtures/api/health/labs-some.json";
import labsTwoUnits from "../../../fixtures/api/health/labs-two-units.json";
import labsWithUnknown from "../../../fixtures/api/health/labs-with-unknown.json";
import { LabList } from "./LabList";

// Each value's crop is a client island that asks Java about the session only after a failed retry; nothing here fails.
vi.mock("@/api/browser", () => ({ apiGet: vi.fn() }));

afterEach(cleanup);

const LANGS: readonly Lang[] = ["bn", "en"];

/** Every generated labs body, as Java's HealthApiContractTest wrote it. */
const BODIES: Array<[string, LabsJson]> = [
  ["labs-some", labsSome.body as LabsJson],
  ["labs-with-unknown", labsWithUnknown.body as LabsJson],
  ["labs-non-numeric", labsNonNumeric.body as LabsJson],
  ["labs-printed-range", labsPrintedRange.body as LabsJson],
  ["labs-two-units", labsTwoUnits.body as LabsJson],
  ["labs-none", labsNone.body as LabsJson],
  ["bp", bp.body as LabsJson],
  ["bp-flagged", bpFlagged.body as LabsJson],
];

/** Every reading of a labs body (each series' points, then each pair's two halves), as Java sent them. */
const pointsOf = (labs: LabsJson): TrendPointJson[] => [
  ...labs.series.flatMap((s) => s.points),
  ...labs.bp.points.flatMap((pair) => [pair.systolic, pair.diastolic]),
];

/** The accessible name of a list: its aria-labelledby targets' text, or its aria-label. */
function listName(el: Element): string {
  const by = el.getAttribute("aria-labelledby");
  if (by) return by.split(" ").map((id) => el.ownerDocument.getElementById(id)?.textContent ?? "").join(" ");
  return el.getAttribute("aria-label") ?? "";
}

/** A reading's date as the paper is dated, with the trend's two marks: written here from the copy, not from LabList. */
function dated(lang: Lang, p: Pick<TrendPointJson, "date" | "date_is_upload_day" | "date_uncertain">): string {
  const day = formatDate(lang, p.date);
  if (p.date_is_upload_day) return t(lang, "trend.date.upload", day);
  if (p.date_uncertain) return t(lang, "trend.date.uncertain", day);
  return day;
}

const sections = (el: HTMLElement) => [...el.querySelectorAll<HTMLElement>("[data-lab-series]")];
const ids = (el: Element, selector: string) => [...el.querySelectorAll(selector)].map((r) => r.getAttribute(selector.slice(1, -1)));

describe("LabList", () => {
  it("oneReadingIsASentenceNotAChart", () => {
    let once = 0;
    for (const [name, labs] of BODIES) {
      for (const lang of LANGS) {
        const { container } = render(<LabList labs={labs} lang={lang} />);
        sections(container).forEach((section, i) => {
          const series = labs.series[i]!;
          if (series.points.length !== 1) return;
          const p = series.points[0]!;
          const where = `${name} ${series.canonical_name} ${lang}`;
          expect(section.querySelector(".trend-chart"), `${where}: no chart`).toBeNull();
          // The only drawing in the section is the chip's own shape.
          for (const svg of section.querySelectorAll("svg")) expect(svg.classList.contains("status-chip__shape"), where).toBe(true);
          const sentence = section.querySelector(".lab-list__once")?.textContent;
          expect(sentence, where).toBe(
            series.unit === null
              ? t(lang, "health.one_reading.nounit", p.value, dated(lang, p))
              : t(lang, "health.one_reading", p.value, series.unit, dated(lang, p)),
          );
          expect(section.querySelector(".status-chip__word")?.textContent, where).toBe(t(lang, CHIP_KEY[chipKindOf(p.kind, p.side)]));
          once++;
        });
        cleanup();
      }
    }
    // Guard: labs-some holds two one-reading series (potassium, total cholesterol), each judged in both languages.
    expect(once, "one-reading series judged").toBeGreaterThanOrEqual(4);
  });

  it("aSeriesIsJavasNotJoinedHere", () => {
    // Two series under one printed name, in two units: two, as Java sent them, each with its own readings.
    const two = labsTwoUnits.body as LabsJson;
    expect(new Set(two.series.map((s) => s.name)).size, "the fixture's series share one name").toBe(1);
    expect(two.series).toHaveLength(2);
    for (const [name, labs] of BODIES) {
      const { container } = render(<LabList labs={labs} lang="en" />);
      const drawn = sections(container);
      expect(drawn, `${name}: one section per series Java sent`).toHaveLength(labs.series.length);
      drawn.forEach((section, i) => {
        const series = labs.series[i]!;
        expect(section.dataset.canonical, name).toBe(series.canonical_name);
        expect(section.dataset.unit, name).toBe(series.unit ?? "");
        expect(section.querySelector(".lab-list__name")?.textContent, name).toBe(series.name);
        if (series.unit !== null) expect(section.querySelector(".lab-list__unit")?.textContent, name).toBe(series.unit);
        // Every list of readings in the section is named by the section's heading, its unit included.
        for (const list of section.querySelectorAll("ol")) {
          expect(listName(list), `${name} ${series.canonical_name} ${series.unit}`).toBe(section.querySelector("h3")?.textContent);
        }
        if (series.points.length > 1) {
          // Each reading is in the section of the series Java put it in, and no other.
          const shown = [...ids(section, "[data-reading-for]")].sort();
          expect(shown, `${name} ${series.canonical_name} ${series.unit}`).toEqual(series.points.map((p) => p.observation_id).sort());
        }
      });
      cleanup();
    }
  });

  it("bloodPressureIsJavasPairs", () => {
    // bp-flagged's halves are judged apart (systolic outside high, diastolic inside), so a chip drawn for the wrong
    // half, or one half's chip drawn twice, shows a word on a half that Java did not send for it. bp's are both unknown.
    const flagged = (bpFlagged.body as LabsJson).bp.points[0]!;
    expect(chipKindOf(flagged.systolic.kind, flagged.systolic.side), "bp-flagged's halves carry two chips").not.toBe(
      chipKindOf(flagged.diastolic.kind, flagged.diastolic.side),
    );
    const cases = [bp, bpFlagged].flatMap((f) => LANGS.map((lang) => [f.body as LabsJson, lang] as const));
    for (const [labs, lang] of cases) {
      expect(labs.bp.points.length, "the fixture has a pair").toBeGreaterThan(0);
      const { container } = render(<LabList labs={labs} lang={lang} />);
      const pairs = [...container.querySelectorAll<HTMLElement>("[data-bp-pair]")];
      expect(pairs).toHaveLength(labs.bp.points.length);
      pairs.forEach((el, i) => {
        const pair = labs.bp.points[i]!;
        for (const half of ["systolic", "diastolic"] as const) {
          const shown = el.querySelector<HTMLElement>(`[data-half="${half}"]`);
          expect(shown?.querySelector(".lab-list__value")?.textContent, `${half} of pair ${i}`).toBe(pair[half].value);
          expect(shown?.dataset.observationId, `${half} of pair ${i}`).toBe(pair[half].observation_id);
          // The half's own chip, inside the half and only there: never the other half's word, never a second chip.
          const words = [...(shown?.querySelectorAll(".status-chip__word") ?? [])].map((w) => w.textContent);
          expect(words, `the ${half} half of pair ${i} (${lang}) shows its own chip`).toEqual([
            t(lang, CHIP_KEY[chipKindOf(pair[half].kind, pair[half].side)]),
          ]);
        }
        expect(el.querySelectorAll(".status-chip__word"), "every chip of the pair is inside its half").toHaveLength(2);
        // The slash is read too, so a screen reader hears the two halves apart.
        expect(el.querySelector(".lab-list__slash")?.getAttribute("aria-hidden")).toBeNull();
        expect(el.querySelector(".lab-list__date")?.textContent).toBe(dated(lang, pair));
      });
      expect(container.querySelector("[data-bp] .trend-chart"), "a pair is never drawn as a series").toBeNull();
      expect(container.querySelector("[data-lab-series]"), "no series of its own for either half").toBeNull();
      expect(container.querySelector("[data-bp] h3")?.textContent).toBe(t(lang, "health.bp"));
      cleanup();
    }
  });

  it("theTwoUnitsListsAreNamedApart", () => {
    // One printed name in two units: a screen-reader user moving by list hears the unit in each list's name.
    const { container } = render(<LabList labs={labsTwoUnits.body as LabsJson} lang="en" />);
    const names = [...container.querySelectorAll("[data-lab-series] ol")].map(listName);
    expect(names, "one list of readings per series").toHaveLength(2);
    expect(new Set(names).size, `the two lists' names: ${names.join(" | ")}`).toBe(2);
    for (const n of names) expect(n.trim(), "a name, not nothing").not.toBe("");
  });

  it("everyValueShowsItsOwnPaper", () => {
    let judged = 0;
    for (const [name, labs] of BODIES) {
      for (const lang of LANGS) {
        const { container } = render(<LabList labs={labs} lang={lang} />);
        const points = pointsOf(labs);
        expect(container.querySelectorAll("[data-value-row]"), `${name}: one crop row per reading`).toHaveLength(points.length);
        for (const p of points) {
          const row = container.querySelector(`[data-crop-for="${p.observation_id}"]`);
          expect(row, `${name} ${p.observation_id}: a row with its crop`).not.toBeNull();
          const images = [...row!.querySelectorAll("img")];
          expect(
            images.map((i) => i.getAttribute("src")),
            `${name} ${p.observation_id}: its own crop, once`,
          ).toEqual([p.crop.src]);
          // The image says what it is, never what was read off it.
          expect(images[0]!.getAttribute("alt"), name).toBe(t(lang, "health.crop.alt"));
          // In the section of its own series (or the blood-pressure section, for a half), never another's.
          const series = labs.series.find((s) => s.points.includes(p));
          const section = row!.closest<HTMLElement>(series ? "[data-lab-series]" : "[data-bp]");
          expect(section, `${name} ${p.observation_id}: inside its section`).not.toBeNull();
          if (series) {
            expect(section!.dataset.canonical, name).toBe(series.canonical_name);
            expect(section!.dataset.unit, name).toBe(series.unit ?? "");
          }
          judged++;
        }
        cleanup();
      }
    }
    expect(judged, "readings judged").toBeGreaterThanOrEqual(40);
  });

  it("aSeriesWithABoundIsListedNotCharted", () => {
    const labs = labsNonNumeric.body as LabsJson;
    const { container } = render(<LabList labs={labs} lang="en" />);
    expect(container.querySelector(".trend-chart"), "no series of labs-non-numeric is drawn").toBeNull();
    let readings = 0;
    for (const series of labs.series) {
      for (const p of series.points) {
        const row = container.querySelector(`[data-reading-for="${p.observation_id}"]`);
        expect(row, `${series.canonical_name} ${p.value}`).not.toBeNull();
        expect(row!.querySelector(".lab-list__value")?.textContent).toBe(p.value);
        expect(row!.querySelector(".lab-list__date")?.textContent).toBe(dated("en", p));
        expect(row!.querySelector(".status-chip__word")?.textContent).toBe(t("en", CHIP_KEY[chipKindOf(p.kind, p.side)]));
        readings++;
      }
    }
    expect(readings, "every reading listed, the bounds included").toBe(labs.series.flatMap((s) => s.points).length);
  });

  it("aSeriesTheChartCanDrawIsTheChart", () => {
    // labs-some's creatinine (three plain numbers) is TrendChart's, which says each reading itself.
    const { container } = render(<LabList labs={labsSome.body as LabsJson} lang="en" />);
    const creatinine = sections(container).find((s) => s.dataset.canonical === "creatinine")!;
    expect(creatinine.querySelector(".trend-chart")).not.toBeNull();
    expect(creatinine.querySelector(".lab-list__readings"), "not listed a second time").toBeNull();
  });

  it("noLabsYetIsSaid", () => {
    for (const lang of LANGS) {
      const { container } = render(<LabList labs={labsNone.body as LabsJson} lang={lang} />);
      expect(container.textContent).toBe(t(lang, "health.labs.none"));
      cleanup();
    }
  });
});
