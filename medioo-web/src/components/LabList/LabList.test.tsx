import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { LabsJson, TrendPointJson } from "@/api/types";
import { formatDate, t, type Lang } from "@/copy";
import { CHIP_KEY, chipKindOf } from "@/components/StatusChip/StatusChip";
import bp from "../../../fixtures/api/health/bp.json";
import labsNonNumeric from "../../../fixtures/api/health/labs-non-numeric.json";
import labsNone from "../../../fixtures/api/health/labs-none.json";
import labsPrintedRange from "../../../fixtures/api/health/labs-printed-range.json";
import labsSome from "../../../fixtures/api/health/labs-some.json";
import labsTwoUnits from "../../../fixtures/api/health/labs-two-units.json";
import labsWithUnknown from "../../../fixtures/api/health/labs-with-unknown.json";
import { LabList } from "./LabList";

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
];

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
    const labs = bp.body as LabsJson;
    expect(labs.bp.points.length, "the fixture has a pair").toBeGreaterThan(0);
    for (const lang of LANGS) {
      const { container } = render(<LabList labs={labs} lang={lang} />);
      const pairs = [...container.querySelectorAll<HTMLElement>("[data-bp-pair]")];
      expect(pairs).toHaveLength(labs.bp.points.length);
      pairs.forEach((el, i) => {
        const pair = labs.bp.points[i]!;
        for (const half of ["systolic", "diastolic"] as const) {
          const shown = el.querySelector<HTMLElement>(`[data-half="${half}"]`);
          expect(shown?.textContent, `${half} of pair ${i}`).toBe(pair[half].value);
          expect(shown?.dataset.observationId, `${half} of pair ${i}`).toBe(pair[half].observation_id);
        }
        expect(el.querySelector(".lab-list__date")?.textContent).toBe(dated(lang, pair));
      });
      expect(container.querySelector("[data-bp] .trend-chart"), "a pair is never drawn as a series").toBeNull();
      expect(container.querySelector("[data-lab-series]"), "no series of its own for either half").toBeNull();
      expect(container.querySelector("[data-bp] h3")?.textContent).toBe(t(lang, "health.bp"));
      cleanup();
    }
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
