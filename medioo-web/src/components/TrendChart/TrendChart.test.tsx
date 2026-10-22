import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { LabSeriesJson, LabsJson, TrendPointJson } from "@/api/types";
import { formatDate, formatDayMonth, t, type CopyKey, type Lang } from "@/copy";
import { CHIP_SHAPE, StatusChip, chipKindOf, type ChipKind } from "@/components/StatusChip/StatusChip";
import bp from "../../../fixtures/api/health/bp.json";
import labsNonNumeric from "../../../fixtures/api/health/labs-non-numeric.json";
import labsNone from "../../../fixtures/api/health/labs-none.json";
import labsPrintedRange from "../../../fixtures/api/health/labs-printed-range.json";
import labsSome from "../../../fixtures/api/health/labs-some.json";
import labsWithUnknown from "../../../fixtures/api/health/labs-with-unknown.json";
import { TrendChart, chartable } from "./TrendChart";

afterEach(cleanup);

const LANGS: readonly Lang[] = ["bn", "en"];

/** One series of B6's generated labs body, chosen by its canonical name, as the health screen will pass it. */
function series(labs: LabsJson, canonical: string): LabSeriesJson {
  const s = labs.series.find((x) => x.canonical_name === canonical);
  if (!s) throw new Error(`no series ${canonical} in the fixture`);
  return s;
}

const potassium = series(labsSome.body, "potassium");
const creatinine = series(labsSome.body, "creatinine");
const hba1c = series(labsWithUnknown.body, "hba1c");
const potassiumLow = series(labsWithUnknown.body, "potassium");
/** Every series of every generated labs body, so a rule checked over "all" is checked over what Java writes. */
const ALL: Array<[string, LabSeriesJson]> = [
  ["labs-some", labsSome.body],
  ["labs-with-unknown", labsWithUnknown.body],
  ["labs-non-numeric", labsNonNumeric.body],
  ["labs-none", labsNone.body],
  ["labs-printed-range", labsPrintedRange.body],
  ["bp", bp.body],
].flatMap(([name, body]) => (body as LabsJson).series.map((s): [string, LabSeriesJson] => [`${name}/${s.canonical_name}`, s]));

const draw = (s: LabSeriesJson, lang: Lang = "bn") => render(<TrendChart series={s} lang={lang} />).container;

/** The points oldest first, as the chart lays them out (the API sends the newest first). */
const oldestFirst = (points: TrendPointJson[]) =>
  points.map((p, i) => ({ p, i })).sort((a, b) => a.p.date.localeCompare(b.p.date) || b.i - a.i).map(({ p }) => p);

/** The drawn marker of a point: the shape svg inside the group the chart drew for it. */
function markerOf(root: HTMLElement, p: TrendPointJson): SVGSVGElement {
  const group = root.querySelector(`g[data-observation-id="${p.observation_id}"]`);
  const shape = group?.querySelector<SVGSVGElement>("svg[data-shape]");
  if (!shape) throw new Error(`no drawn marker for ${p.observation_id}`);
  return shape;
}

/** The shape a StatusChip of this kind draws, as markup: the path itself, not only its name. */
function chipShapeMarkup(kind: ChipKind): string {
  const { container } = render(<StatusChip kind={kind} lang="bn" />);
  const html = container.querySelector(".status-chip__shape")!.innerHTML;
  container.remove();
  return html;
}

/** What a screen reader hears for one reading, built from the bundle keys only. */
function expectedSpoken(lang: Lang, s: LabSeriesJson, p: TrendPointJson): string {
  const years = new Set(s.points.map((x) => x.date.slice(0, 4))).size > 1;
  const day = years ? formatDate(lang, p.date) : formatDayMonth(lang, p.date);
  const date = p.date_is_upload_day ? t(lang, "trend.date.upload", day) : p.date_uncertain ? t(lang, "trend.date.uncertain", day) : day;
  const word = t(lang, p.chip_key as CopyKey);
  return s.unit ? t(lang, "trend.point", date, p.value, s.unit, word) : t(lang, "trend.point.nounit", date, p.value, word);
}

describe("TrendChart", () => {
  it("oneReadingIsNoChart", () => {
    expect(potassium.points, "potassium in labs-some.json is one reading").toHaveLength(1);
    expect(chartable(potassium.points), "one reading is not chartable").toBe(false);
    expect(draw(potassium).innerHTML, "one reading renders nothing (it is a sentence on the screen, W9)").toBe("");
    expect(draw({ ...potassium, points: [] }).innerHTML).toBe("");
  });

  it("aReadingPrintedAsABoundMakesTheSeriesNotChartable", () => {
    // labs-non-numeric: creatinine's newest reading is ">500" (critical high), CRP's is "<0.5". Neither has a place on
    // an axis, so the whole series is not chartable: W9 lists it as written. Never a trend ending on the older reading.
    for (const s of labsNonNumeric.body.series) {
      expect(s.points.length, `${s.canonical_name}: two or more readings`).toBeGreaterThanOrEqual(2);
      expect(s.points.some((p) => /^\d+(\.\d+)?$/.test(p.value)), `${s.canonical_name}: one reading is a number`).toBe(true);
      expect(chartable(s.points), s.canonical_name).toBe(false);
      expect(draw(s).innerHTML, `${s.canonical_name} draws nothing, never a chart without its ${s.points[0]!.value}`).toBe("");
      cleanup();
    }
    expect(series(labsNonNumeric.body, "creatinine").points[0]!.value, "the newest creatinine reading").toBe(">500");
  });

  it("chartableIsTheOnlyRuleForDrawing", () => {
    // The predicate W9 calls is the chart's own rule, over every series Java writes: drawn exactly when chartable.
    const drawn = ALL.map(([name, s]) => {
      const html = draw(s).innerHTML;
      cleanup();
      return { name, chartable: chartable(s.points), drawn: html !== "" };
    });
    expect(drawn.filter((d) => d.chartable !== d.drawn).map((d) => d.name)).toEqual([]);
    // Guard: both outcomes were met.
    expect(drawn.some((d) => d.drawn)).toBe(true);
    expect(drawn.some((d) => !d.drawn)).toBe(true);
  });

  it("twoReadingsDrawOneLineThroughTheValuesOldestFirst", () => {
    for (const s of [{ ...creatinine, points: creatinine.points.slice(0, 2) }, creatinine, hba1c, potassiumLow]) {
      const root = draw(s);
      const lines = root.querySelectorAll("polyline");
      expect(lines, `${s.points.length} readings: one line`).toHaveLength(1);
      const vertices = lines[0]!.getAttribute("points")!.trim().split(/\s+/).map((v) => v.split(",").map(Number) as [number, number]);
      const ordered = oldestFirst(s.points);
      expect(vertices, "one vertex per reading").toHaveLength(ordered.length);
      const groups = [...root.querySelectorAll("g[data-observation-id]")].map((m) => m.getAttribute("data-observation-id"));
      expect(groups, "markers oldest first").toEqual(ordered.map((p) => p.observation_id));
      ordered.forEach((p, i) => {
        // Each marker is centred on its own vertex.
        const m = markerOf(root, p);
        const size = Number(m.getAttribute("width"));
        expect([Number(m.getAttribute("x")) + size / 2, Number(m.getAttribute("y")) + size / 2], `${p.value}: marker on its vertex`).toEqual(vertices[i]);
        // A higher value is drawn higher (smaller y), a lower one lower, an equal one level: y follows the values.
        for (let j = i + 1; j < ordered.length; j++) {
          const dv = Number(ordered[j]!.value) - Number(p.value);
          const dy = vertices[j]![1] - vertices[i]![1];
          expect(Math.sign(dy), `${p.value} → ${ordered[j]!.value}`).toBe(-Math.sign(dv) || 0);
        }
      });
      cleanup();
    }
    // Creatinine 90 → 98 → 132 rises, so its y strictly falls; HbA1c 9.8 → 3.6 does not rise.
    const ys = (s: LabSeriesJson) => {
      const root = draw(s);
      const out = root.querySelector("polyline")!.getAttribute("points")!.trim().split(/\s+/).map((v) => Number(v.split(",")[1]));
      cleanup();
      return out;
    };
    expect(oldestFirst(creatinine.points).map((p) => p.value)).toEqual(["90", "98", "132"]);
    const c = ys(creatinine);
    expect(c[0]! > c[1]! && c[1]! > c[2]!, `creatinine y ${c.join(",")}`).toBe(true);
    const h = ys(hba1c);
    expect(h[0]! < h[1]!, `hba1c 9.8 → 3.6 falls: y ${h.join(",")}`).toBe(true);
  });

  it("eachMarkerIsItsChipShape", () => {
    const met = new Set<ChipKind>();
    for (const s of [...labsWithUnknown.body.series, creatinine]) {
      const root = draw(s);
      for (const p of s.points) {
        const kind = chipKindOf(p.kind, p.side);
        met.add(kind);
        const marker = markerOf(root, p);
        expect(marker.getAttribute("data-shape"), p.observation_id).toBe(CHIP_SHAPE[kind]);
        expect(marker.innerHTML, `${p.observation_id}: the drawn path is the ${kind} chip's`).toBe(chipShapeMarkup(kind));
      }
      cleanup();
    }
    // Guard: the generated fixtures hold every kind a chip can be, critical high and low included.
    expect([...met].sort()).toEqual(["crit-high", "crit-low", "in", "out-high", "out-low", "unk"]);
  });

  it("noReadingStandsOnABand", () => {
    // B6's point carries no printed range, so a band could only be a shape that looks like a range and encodes nothing:
    // an out-of-range reading inside it reads as within range. No band is drawn until the point carries the range.
    const banded: string[] = [];
    for (const [name, s] of ALL) {
      const root = draw(s);
      for (const el of root.querySelectorAll("rect, [data-band-for], .trend-chart__band")) {
        banded.push(`${name}: ${el.getAttribute("data-band-for") ?? el.outerHTML.slice(0, 40)}`);
      }
      cleanup();
    }
    expect(banded.length, `readings drawn on a band: ${banded.join("; ")}`).toBe(0);
  });

  it("aReadingTheCodeCannotJudgeIsDrawnUnknown", () => {
    // The dangerous direction: an outside or critical without a side, or a kind this code does not know, has no direction
    // to show. It is drawn and worded unknown, as chipKindOf makes it, never as a judged reading.
    const [first, second] = hba1c.points.filter((p) => p.kind === "outside" || p.kind === "inside");
    const odd: LabSeriesJson = {
      ...hba1c,
      points: hba1c.points.map((p) =>
        p === first ? { ...p, kind: "outside", side: null } : p === second ? { ...p, kind: "CRITICAL", side: "high" } : p,
      ),
    };
    const root = draw(odd);
    for (const p of [first!, second!]) {
      expect(markerOf(root, p).getAttribute("data-shape"), `${p.observation_id}: the unknown shape`).toBe(CHIP_SHAPE.unk);
      const reading = root.querySelector(`[data-reading-for="${p.observation_id}"] .trend-chart__word`);
      expect(reading?.textContent, `${p.observation_id}: the unknown word`).toBe(t("bn", "value.chip.unknown"));
    }
    const bands = [...root.querySelectorAll("rect, [data-band-for]")].map((b) => b.getAttribute("data-band-for"));
    expect(bands.length, `bands under readings the code cannot judge: ${bands.join("; ")}`).toBe(0);
  });

  it("everyReadingIsSaidWithItsDateValueUnitAndWord", () => {
    let judged = 0;
    for (const lang of LANGS) {
      for (const s of [creatinine, hba1c, potassiumLow]) {
        const root = draw(s, lang);
        const list = root.querySelector("ol.trend-chart__readings")!;
        expect(list.getAttribute("aria-label"), "the list is named by the series").toBe(s.name);
        const items = [...list.querySelectorAll(":scope > li")];
        const ordered = oldestFirst(s.points);
        expect(items.map((li) => li.getAttribute("data-reading-for")), "one reading per point, oldest first").toEqual(ordered.map((p) => p.observation_id));
        items.forEach((li, i) => {
          const p = ordered[i]!;
          const spoken = li.querySelector(".trend-chart__spoken")!;
          expect(spoken.textContent, `${lang} ${p.observation_id}`).toBe(expectedSpoken(lang, s, p));
          for (let e: Element | null = spoken; e && e !== root; e = e.parentElement) {
            expect(e.getAttribute("aria-hidden"), `${lang} ${p.observation_id}: the spoken text is not hidden from a reader`).not.toBe("true");
          }
          // And on screen: the status is a shape and a word, the word the chip has (B6's chip_key).
          expect(li.querySelector(".trend-chart__word")?.textContent, `${lang} ${p.observation_id}: the visible word`).toBe(t(lang, p.chip_key as CopyKey));
          if (p.kind === "outside" || p.kind === "critical") judged++;
        });
        cleanup();
      }
    }
    // Guard: out-of-range and critical readings were among them, so the word was checked where it matters.
    expect(judged).toBeGreaterThanOrEqual(8);
  });

  it("aDateTheTrendCannotBeSureOfIsMarked", () => {
    const uncertain = creatinine.points.filter((p) => p.date_uncertain);
    const upload = creatinine.points.filter((p) => p.date_is_upload_day);
    // Guard: labs-some's creatinine holds one of each and an ordinary date.
    expect(uncertain.length).toBeGreaterThan(0);
    expect(upload.length).toBeGreaterThan(0);
    expect(creatinine.points.some((p) => !p.date_uncertain && !p.date_is_upload_day)).toBe(true);
    for (const lang of LANGS) {
      const root = draw(creatinine, lang);
      for (const p of creatinine.points) {
        const day = formatDayMonth(lang, p.date);
        const want = p.date_is_upload_day ? t(lang, "trend.date.upload", day) : p.date_uncertain ? t(lang, "trend.date.uncertain", day) : day;
        expect(root.querySelector(`[data-reading-for="${p.observation_id}"] .trend-chart__date`)?.textContent, `${lang} ${p.observation_id}`).toBe(want);
      }
      cleanup();
    }
  });

  it("everyPointHasADateWithTheYearWhenTheSeriesSpansYears", () => {
    expect(new Set(hba1c.points.map((p) => p.date.slice(0, 4))).size, "hba1c spans two years").toBe(2);
    for (const [s, format] of [
      [hba1c, formatDate],
      [potassiumLow, formatDayMonth],
    ] as const) {
      const root = draw(s, "bn");
      const dates = s.points.map((p) => root.querySelector(`[data-reading-for="${p.observation_id}"] .trend-chart__date`)?.textContent);
      expect(dates, s.canonical_name).toEqual(s.points.map((p) => format("bn", p.date)));
      cleanup();
    }
  });

  it("noInlineStyle", () => {
    const root = draw(hba1c);
    expect([...root.querySelectorAll("[style]")].map((e) => e.outerHTML.slice(0, 40))).toEqual([]);
    expect(root.querySelector("svg.trend-chart__plot")?.getAttribute("aria-hidden"), "the drawing is decorative; the list says it").toBe("true");
  });
});
