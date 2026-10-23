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
import { PLOT, TrendChart, chartable } from "./TrendChart";

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

/** The series the chart draws (`chartable`), so a rule about what is drawn is checked over every one Java writes. */
const CHARTED = ALL.filter(([, s]) => chartable(s.points));

const draw = (s: LabSeriesJson, lang: Lang = "bn") => render(<TrendChart series={s} lang={lang} />).container;

/** A point of a series by its observation id. */
function pointOf(s: LabSeriesJson, id: string): TrendPointJson {
  const p = s.points.find((x) => x.observation_id === id);
  if (!p) throw new Error(`no point ${id} in ${s.canonical_name}`);
  return p;
}

/** Every printed range the chart drew, in drawing order: a filled band, or a limit line on one side. */
function rangesOf(root: HTMLElement): Array<{ el: Element; ids: string[]; kind: "band" | "edge"; side: string | null }> {
  return [...root.querySelectorAll("[data-range-for]")].map((el) => ({
    el,
    ids: el.getAttribute("data-range-for")!.split(" "),
    kind: el.classList.contains("trend-chart__band") ? "band" : "edge",
    side: el.getAttribute("data-range-side"),
  }));
}

/** Every element the plot draws other than the trend line and the markers: the ranges and their outlines, whatever
 *  attribute they carry (a sweep that cannot miss an element the code forgot to mark). */
function rangeShapes(root: HTMLElement): Element[] {
  const plot = root.querySelector("svg.trend-chart__plot");
  return plot ? [...plot.children].filter((e) => !e.matches("polyline, g.trend-chart__point")) : [];
}

/** The points whose paper printed a range text that Java sent no number for: the chart may draw nothing for them. */
const TEXT_ONLY = CHARTED.flatMap(([name, s]) =>
  s.points.filter((p) => p.ref_text !== null && p.ref_low === null && p.ref_high === null).map((p) => ({ name, s, p })),
);

/** A series with one point's range replaced, the rest as Java wrote them. */
function withRange(s: LabSeriesJson, target: TrendPointJson, range: Pick<TrendPointJson, "ref_low" | "ref_high" | "ref_text">): LabSeriesJson {
  return { ...s, points: s.points.map((p) => (p === target ? { ...p, ...range } : p)) };
}

/**
 * Every band edge and limit line sits at the numbers its OWN reading carries (each reading under a run, not only the
 * first), and the reading is inside its band or beyond it on its side. The scale is read back from the chart's own
 * markers. Returns the band/edge × chip kinds it met.
 */
function expectOwnBounds(name: string, s: LabSeriesJson, root: HTMLElement): string[] {
  const scale = scaleOf(root, s);
  const met: string[] = [];
  for (const range of rangesOf(root)) {
    for (const id of range.ids) {
      const q = pointOf(s, id);
      if (range.kind === "band") {
        const top = Number(range.el.getAttribute("y"));
        const bottom = top + Number(range.el.getAttribute("height"));
        expect(top, `${name} ${id}: band top at its ${q.ref_high}`).toBeCloseTo(scale(q.ref_high!), 6);
        expect(bottom, `${name} ${id}: band bottom at its ${q.ref_low}`).toBeCloseTo(scale(q.ref_low!), 6);
      } else {
        const bound = range.side === "high" ? q.ref_high! : q.ref_low!;
        expect(Number(range.el.getAttribute("y1")), `${name} ${id}: edge at its ${bound}`).toBeCloseTo(scale(bound), 6);
        expect(range.el.getAttribute("y2")).toBe(range.el.getAttribute("y1"));
      }
      const y = centreOf(root, q)[1];
      const kind = chipKindOf(q.kind, q.side);
      const top = range.kind === "band" ? scale(q.ref_high!) : range.side === "high" ? scale(q.ref_high!) : -Infinity;
      const bottom = range.kind === "band" ? scale(q.ref_low!) : range.side === "low" ? scale(q.ref_low!) : Infinity;
      if (kind === "in") expect(top <= y && y <= bottom, `${name} ${q.value}: inside its band`).toBe(true);
      if (kind.endsWith("high")) expect(y < top, `${name} ${q.value}: above its range`).toBe(true);
      if (kind.endsWith("low")) expect(y > bottom, `${name} ${q.value}: below its range`).toBe(true);
      met.push(`${range.kind}:${kind}`);
    }
  }
  return met;
}

/** The centre of a point's drawn marker, in the drawing's units. */
function centreOf(root: HTMLElement, p: TrendPointJson): [number, number] {
  const m = markerOf(root, p);
  const half = Number(m.getAttribute("width")) / 2;
  return [Number(m.getAttribute("x")) + half, Number(m.getAttribute("y")) + half];
}

/** The chart's value-to-y scale, read back from two of its own markers with different values (it is linear). */
function scaleOf(root: HTMLElement, s: LabSeriesJson): (v: number) => number {
  const a = s.points[0]!;
  const b = s.points.find((p) => Number(p.value) !== Number(a.value));
  if (!b) throw new Error(`${s.canonical_name}: every value equal, no scale to read`);
  const [ya, yb] = [centreOf(root, a)[1], centreOf(root, b)[1]];
  const k = (yb - ya) / (Number(b.value) - Number(a.value));
  return (v) => ya + k * (v - Number(a.value));
}

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

  it("aFilledBandIsDrawnOnlyWhereBothNumbersAreSent", () => {
    // PO ruling R3 (DR-48): a pale band behind the line, only from what the reading's own paper printed. Java sends both
    // numbers only for a two-sided range that agrees with the chip, so React draws exactly those and judges nothing.
    let banded = 0;
    let bare = 0;
    for (const [name, s] of CHARTED) {
      const root = draw(s);
      const bands = rangesOf(root).filter((r) => r.kind === "band");
      for (const band of bands) {
        expect(band.el.tagName.toLowerCase(), `${name}: a band is a filled rect`).toBe("rect");
        for (const id of band.ids) {
          const p = pointOf(s, id);
          expect([p.ref_low, p.ref_high].includes(null), `${name} ${id}: banded without both numbers`).toBe(false);
        }
      }
      for (const p of s.points) {
        const both = p.ref_low !== null && p.ref_high !== null;
        const holding = bands.filter((b) => b.ids.includes(p.observation_id));
        expect(holding.length, `${name} ${p.observation_id}: bands behind it`).toBe(both ? 1 : 0);
        if (both) banded++;
        else bare++;
      }
      cleanup();
    }
    // Guard: both directions were met over what Java writes.
    expect(banded, "points sent with both numbers").toBeGreaterThanOrEqual(8);
    expect(bare, "points sent without both numbers").toBeGreaterThanOrEqual(8);
  });

  it("aPointWithNoRangeHasNoBand", () => {
    // labs-printed-range's creatinine of 2026-06-20 (point 179): its paper printed no range, and it is OUTSIDE, so a
    // neighbour's range carried onto it would look as if it agreed. It stands on nothing.
    const creat = series(labsPrintedRange.body, "creatinine");
    const p179 = creat.points.find((p) => p.observation_id.endsWith("179"))!;
    expect([p179.ref_low, p179.ref_high, p179.ref_text, p179.kind]).toEqual([null, null, null, "outside"]);
    const root = draw(creat);
    expect(rangesOf(root).filter((r) => r.ids.includes(p179.observation_id)).map((r) => r.ids.join(" ")), "ranges behind point 179").toEqual([]);
    cleanup();
    // And the whole of it: every chartable series with its ranges taken away draws no band, no edge and no legend.
    let charted = 0;
    for (const [name, s] of CHARTED) {
      const bare = { ...s, points: s.points.map((p) => ({ ...p, ref_low: null, ref_high: null, ref_text: null })) };
      const r = draw(bare);
      expect(r.querySelector("svg.trend-chart__plot"), `${name}: still a chart`).not.toBeNull();
      expect(
        [...r.querySelectorAll("rect, line, .trend-chart__band, .trend-chart__edge, [data-range-for], .trend-chart__legend")].map((e) => e.outerHTML.slice(0, 60)),
        `${name} with no range`,
      ).toEqual([]);
      charted++;
      cleanup();
    }
    expect(charted).toBeGreaterThanOrEqual(8);
    // And with the text kept: every chartable series with only its numbers taken away draws the same nothing, and no
    // reading says a range. "No number → nothing, whatever ref_text says", over every element, not a marked one.
    let texted = 0;
    for (const [name, s] of CHARTED) {
      const textOnly = { ...s, points: s.points.map((p) => ({ ...p, ref_low: null, ref_high: null })) };
      texted += textOnly.points.filter((p) => p.ref_text !== null).length;
      const r = draw(textOnly);
      expect(
        [...r.querySelectorAll("rect, line, .trend-chart__band, .trend-chart__edge, [data-range-for], .trend-chart__legend")].map((e) => e.outerHTML.slice(0, 60)),
        `${name} with its range text and no number`,
      ).toEqual([]);
      for (const p of textOnly.points) {
        expect(r.querySelector(`[data-reading-for="${p.observation_id}"] .trend-chart__spoken`)?.textContent, `${name} ${p.observation_id}`).toBe(
          expectedSpoken("bn", textOnly, p),
        );
      }
      cleanup();
    }
    // Guard: the sweep kept range texts to ignore.
    expect(texted).toBeGreaterThanOrEqual(15);
  });

  it("aRangeTextWithNoNumbersDrawsNothing", () => {
    // Java sends the text but no number where it cannot be placed or would contradict the chip: beside an unknown and
    // beside a critical by threshold inside its printed range. The set is every such point Java writes, not a list.
    expect(TEXT_ONLY.length, "points sent with a range text and no number").toBeGreaterThanOrEqual(7);
    for (const { name, s, p } of TEXT_ONLY) {
      const root = draw(s);
      // Nothing the plot draws besides the line and the markers stands behind this reading's marker, marked or not.
      const [cx] = centreOf(root, p);
      const behind = rangeShapes(root).filter((e) => {
        const [x1, x2] = e.tagName.toLowerCase() === "rect"
          ? [Number(e.getAttribute("x")), Number(e.getAttribute("x")) + Number(e.getAttribute("width"))]
          : [Number(e.getAttribute("x1")), Number(e.getAttribute("x2"))];
        return Math.min(x1, x2) <= cx && cx <= Math.max(x1, x2);
      });
      expect(behind.map((e) => e.outerHTML.slice(0, 60)), `${name} ${p.observation_id}: ${p.ref_text} drawn`).toEqual([]);
      cleanup();
    }
  });

  it("differingRangesAreNeverOneBand", () => {
    // Two labs print two ranges for one marker (59–104, then 62–106): each reading stands on its own short bar, never
    // one band that blends them.
    const creat = series(labsPrintedRange.body, "creatinine");
    const root = draw(creat);
    const bands = rangesOf(root).filter((r) => r.kind === "band");
    expect(bands.map((b) => b.ids.map((id) => id.slice(-3))), "one bar per reading, oldest first").toEqual([["175"], ["171"]]);
    const [a, b] = bands.map((x) => ({ x: Number(x.el.getAttribute("x")), w: Number(x.el.getAttribute("width")) }));
    expect(a!.x + a!.w < b!.x, `the bars do not touch: ${a!.x}+${a!.w} vs ${b!.x}`).toBe(true);
    cleanup();
    // The dangerous direction, built from a series whose three readings share one range: change the middle one's and
    // the band must split into three, none of them across two readings.
    const shared = series(labsSome.body, "creatinine");
    const middle = oldestFirst(shared.points)[1]!;
    // Labs often share one limit, so a range that differs on one side only is the case most likely to be joined:
    // 59–110 (the high differs) and 62–104 (the low differs) split it just the same, each reading on its own numbers.
    for (const [label, range] of [
      ["both ends differ", { ref_low: 62, ref_high: 106, ref_text: "62–106" }],
      ["only the high differs", { ref_low: 59, ref_high: 110, ref_text: "59–110" }],
      ["only the low differs", { ref_low: 62, ref_high: 104, ref_text: "62–104" }],
    ] as const) {
      const split = withRange(shared, middle, range);
      const r = draw(split);
      expect(rangesOf(r).map((x) => x.ids.length), `${label}: three bars, one reading each`).toEqual([1, 1, 1]);
      expect(expectOwnBounds(`${label}`, split, r), `${label}: every reading checked`).toHaveLength(3);
      cleanup();
    }
    // Over everything Java writes: a band's readings are neighbours that carry the very same numbers.
    for (const [name, s] of CHARTED) {
      const order = oldestFirst(s.points).map((p) => p.observation_id);
      for (const band of rangesOf(draw(s))) {
        const at = band.ids.map((id) => order.indexOf(id));
        expect(at, `${name}: neighbours, oldest first`).toEqual(at.map((_, i) => at[0]! + i));
        const ranges = new Set(band.ids.map((id) => `${pointOf(s, id).ref_low}|${pointOf(s, id).ref_high}`));
        expect([...ranges], `${name} ${band.ids.join(" ")}: one range`).toHaveLength(1);
      }
      cleanup();
    }
  });

  it("neighboursSharingARangeShareOneBand", () => {
    const creat = series(labsSome.body, "creatinine");
    const root = draw(creat);
    const bands = rangesOf(root);
    expect(bands.map((b) => b.ids), "one band across the three readings").toEqual([oldestFirst(creat.points).map((p) => p.observation_id)]);
    const x = Number(bands[0]!.el.getAttribute("x"));
    const w = Number(bands[0]!.el.getAttribute("width"));
    for (const p of creat.points) {
      const c = centreOf(root, p);
      expect(x < c[0] && c[0] < x + w, `${p.value}: under the band`).toBe(true);
    }
    cleanup();
    // HbA1c: one reading with no range in the middle breaks the band in two, never bridged.
    const h = draw(hba1c);
    const order = oldestFirst(hba1c.points);
    expect(order.map((p) => p.value)).toEqual(["9.8", "3.6", "6.1", "5.8", "5.4"]);
    expect(rangesOf(h).map((b) => b.ids), "two bands either side of 6.1").toEqual([
      [order[0]!.observation_id, order[1]!.observation_id],
      [order[3]!.observation_id, order[4]!.observation_id],
    ]);
  });

  it("eachRangeIsDrawnAtItsOwnPrintedBounds", () => {
    // The chart's scale read back from two of its own readings: every band's edges and every limit line sit exactly at
    // the numbers its own reading carries, and an inside reading sits in its band, an outside one beyond it on its side.
    let checked = 0;
    const sides = new Set<string>();
    for (const [name, s] of CHARTED) {
      const met = expectOwnBounds(name, s, draw(s));
      met.forEach((m) => sides.add(m));
      checked += met.length;
      cleanup();
    }
    expect(checked).toBeGreaterThanOrEqual(10);
    // Guard: inside and beyond on both sides, on a band and on a limit line.
    expect([...sides].sort()).toEqual(expect.arrayContaining(["band:in", "band:out-high", "band:out-low", "band:crit-high", "band:crit-low", "edge:out-high", "edge:out-low"]));
  });

  it("aOneSidedBoundIsAnEdgeNeverARegion", () => {
    // `<200` beside 240 (point 180) and `>40` beside 35 (point 181): a thin line at the printed bound, on that side only,
    // never a band to an invented other bound.
    for (const [canonical, id, side] of [
      ["total_cholesterol", "180", "high"],
      ["hdl", "181", "low"],
    ] as const) {
      const s = series(labsPrintedRange.body, canonical);
      const p = s.points.find((x) => x.observation_id.endsWith(id))!;
      expect(side === "high" ? [p.ref_low, typeof p.ref_high] : [typeof p.ref_low, p.ref_high], canonical).toEqual(
        side === "high" ? [null, "number"] : ["number", null],
      );
      const root = draw(s);
      const ranges = rangesOf(root);
      expect(ranges.map((r) => [r.kind, r.side, r.ids]), canonical).toEqual([["edge", side, [p.observation_id]]]);
      expect(ranges[0]!.el.tagName.toLowerCase(), "a line, never an area").toBe("line");
      expect(root.querySelectorAll("rect").length, `${canonical}: no filled region`).toBe(0);
      cleanup();
    }
  });

  it("everyRangeLiesInsideThePlot", () => {
    // The scale takes in the drawn bounds, so no band or line runs off the drawing: a bar clipped away would leave a
    // reading standing on a range its sentence still names. Over every element the plot draws, marked or not.
    let shapes = 0;
    const EPS = 1e-6;
    for (const [name, s] of CHARTED) {
      const root = draw(s);
      for (const e of rangeShapes(root)) {
        const ids = e.getAttribute("data-range-for") ?? e.getAttribute("data-bound-for") ?? "";
        const first = s.points.find((p) => ids.split(" ").includes(p.observation_id));
        const what = `${name} ${ids}`;
        if (e.tagName.toLowerCase() === "rect") {
          const y = Number(e.getAttribute("y"));
          const bottom = y + Number(e.getAttribute("height"));
          expect(y, `${what}: band top at its ${first?.ref_high} above the plot`).toBeGreaterThanOrEqual(PLOT.top - EPS);
          expect(bottom, `${what}: band bottom at its ${first?.ref_low} below the plot`).toBeLessThanOrEqual(PLOT.bottom + EPS);
        } else {
          for (const a of ["y1", "y2"]) {
            const y = Number(e.getAttribute(a));
            expect(y >= PLOT.top - EPS && y <= PLOT.bottom + EPS, `${what}: line ${a}=${y} outside the plot ${PLOT.top}..${PLOT.bottom}`).toBe(true);
          }
        }
        shapes++;
      }
      cleanup();
    }
    // Guard: bands, their outlines and limit lines were all swept.
    expect(shapes).toBeGreaterThanOrEqual(15);
  });

  it("aBandIsOutlinedAtItsOwnBounds", () => {
    // The pale fill is decoration; the range is perceived through two thin solid lines at its printed bounds (the
    // edge's token, 3:1 on the page), across the band's own width. So a band is never seen only through its fill.
    let outlined = 0;
    for (const [name, s] of CHARTED) {
      const root = draw(s);
      for (const band of rangesOf(root).filter((r) => r.kind === "band")) {
        const ids = band.ids.join(" ");
        const x = Number(band.el.getAttribute("x"));
        const y = Number(band.el.getAttribute("y"));
        const w = Number(band.el.getAttribute("width"));
        const h = Number(band.el.getAttribute("height"));
        const lines = [...root.querySelectorAll(`line[data-bound-for="${ids}"]`)];
        const at = (v: number) => Math.round(v * 1e6) / 1e6;
        expect(
          lines.map((l) => [l.getAttribute("class"), l.getAttribute("data-range-side"), ...["x1", "x2", "y1", "y2"].map((a) => at(Number(l.getAttribute(a))))]),
          `${name} ${ids}: its two bounds`,
        ).toEqual([
          ["trend-chart__edge trend-chart__edge--bound", "high", ...[x, x + w, y, y].map(at)],
          ["trend-chart__edge trend-chart__edge--bound", "low", ...[x, x + w, y + h, y + h].map(at)],
        ]);
        // Drawn over the fill, so the fill never hides them.
        for (const l of lines) expect(band.el.compareDocumentPosition(l) & Node.DOCUMENT_POSITION_FOLLOWING, `${name}: after its band`).toBeTruthy();
        outlined++;
      }
      // An outline belongs to a band: no line carries a bound for something that is not a drawn band.
      const bands = new Set(rangesOf(root).filter((r) => r.kind === "band").map((r) => r.ids.join(" ")));
      for (const l of root.querySelectorAll("[data-bound-for]")) expect(bands.has(l.getAttribute("data-bound-for")!), `${name}: a stray outline`).toBe(true);
      cleanup();
    }
    expect(outlined).toBeGreaterThanOrEqual(5);
  });

  it("aLoneBoundThatDisagreesWithItsReadingIsNotDrawn", () => {
    // D35: a one-sided range is never an INSIDE, and a limit line stands only beside a reading beyond it on its own
    // side. Java sends no such number (PaperDisplay.edgeAgrees); were one sent, the chart fails closed rather than draw a
    // reading on the "normal" side of a limit. The dangerous direction: a lone <200 beside an inside 150.
    const s = series(labsPrintedRange.body, "total_cholesterol");
    const p180 = s.points.find((p) => p.observation_id.endsWith("180"))!;
    expect([p180.value, p180.kind, p180.side, p180.ref_high]).toEqual(["240", "outside", "high", 200]);
    const cases: Array<[string, LabSeriesJson, TrendPointJson]> = [];
    const put = (label: string, patch: Partial<TrendPointJson>) => {
      const changed = { ...p180, ...patch };
      cases.push([label, { ...s, points: s.points.map((p) => (p === p180 ? changed : p)) }, changed]);
    };
    put("ref_high 200 beside an inside 150", { value: "150", kind: "inside", side: null, chip_key: "value.chip.inside" });
    put("ref_high 200 beside an outside-high 150, under its limit", { value: "150" });
    put("ref_high 200 beside an outside-high 200, on its limit", { value: "200" });
    put("ref_high 200 beside an outside-low 150", { value: "150", side: "low", chip_key: "value.chip.outside.low" });
    put("ref_low 40 beside an outside-low 45, above its limit", { value: "45", side: "low", chip_key: "value.chip.outside.low", ref_low: 40, ref_high: null });
    put("ref_low 40 beside a critical-high 240", { kind: "critical", ref_low: 40, ref_high: null });
    for (const [label, bad, p] of cases) {
      const root = draw(bad);
      expect(rangesOf(root).filter((r) => r.ids.includes(p.observation_id)).map((r) => `${r.kind}:${r.side}`), label).toEqual([]);
      expect(rangeShapes(root).map((e) => e.outerHTML.slice(0, 60)), `${label}: nothing drawn`).toEqual([]);
      cleanup();
    }
    // And the agreeing case still draws: 240 beyond <200 on the high side.
    expect(rangesOf(draw(s)).map((r) => `${r.kind}:${r.side}`)).toEqual(["edge:high"]);
  });

  it("theBandNeverChangesAPointOrItsWord", () => {
    // The band sits behind the line and colours nothing: each reading's marker, its shape and its words are the ones
    // it has with no range at all.
    let compared = 0;
    for (const [name, s] of CHARTED) {
      const bare = { ...s, points: s.points.map((p) => ({ ...p, ref_low: null, ref_high: null, ref_text: null })) };
      const with_ = draw(s);
      const plot = with_.querySelector("svg.trend-chart__plot")!;
      const line = plot.querySelector("polyline")!;
      for (const el of [...rangesOf(with_).map((r) => r.el), ...with_.querySelectorAll("[data-bound-for]")]) {
        expect(el.parentElement, `${name}: a range drawn inside a point`).toBe(plot);
        expect(el.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING, `${name}: drawn behind the line`).toBeTruthy();
      }
      const look = (root: HTMLElement, p: TrendPointJson) => {
        const g = root.querySelector(`g[data-observation-id="${p.observation_id}"]`)!;
        const li = root.querySelector(`[data-reading-for="${p.observation_id}"]`)!;
        return [g.getAttribute("class"), markerOf(root, p).outerHTML.replace(/ (x|y)="[^"]*"/g, ""), li.getAttribute("class"),
          ...[...li.querySelectorAll(":scope > [aria-hidden]")].map((e) => `${e.className}=${e.textContent}`)];
      };
      const withLooks = s.points.map((p) => look(with_, p));
      cleanup();
      const bareRoot = draw(bare);
      s.points.forEach((p, i) => expect(look(bareRoot, p), `${name} ${p.observation_id}: as it looks with no range`).toEqual(withLooks[i]));
      compared += s.points.length;
      cleanup();
    }
    expect(compared).toBeGreaterThanOrEqual(15);
  });

  it("noReaderIsGivenTheRangeTextUntilItsFormIsAccepted", () => {
    // ref_text is Java's join of the stored sides, not the paper's own characters, and the PO has not yet accepted that
    // form for families (PR #143, PO decision 3). Until then it reaches neither reader: a screen reader hears exactly
    // the reading's own sentence and the screen writes exactly its value, unit, word and date, so a sighted reader and a
    // listener are given the same (the band's place, the chip's word), and neither is given the text.
    let compared = 0;
    let withText = 0;
    for (const lang of LANGS) {
      for (const [name, s] of CHARTED) {
        const root = draw(s, lang);
        for (const p of s.points) {
          const li = root.querySelector(`[data-reading-for="${p.observation_id}"]`)!;
          expect(li.querySelector(".trend-chart__spoken")?.textContent, `${lang} ${name} ${p.observation_id} (${p.ref_text})`).toBe(expectedSpoken(lang, s, p));
          // What the reading writes, field by field: nothing beyond the spoken sentence and the four visible parts.
          expect(
            [...li.children].map((e) => e.className),
            `${lang} ${name} ${p.observation_id}: the reading's parts`,
          ).toEqual(["trend-chart__spoken", "trend-chart__value", ...(s.unit ? ["trend-chart__unit"] : []), "trend-chart__word", "trend-chart__date"]);
          compared++;
          if (p.ref_text !== null) withText++;
        }
        // The legend's words are its entries' keys and nothing else: no range text rides in on it.
        const entries = [...root.querySelectorAll(".trend-chart__legend [data-legend]")];
        expect(
          entries.map((e) => e.textContent),
          `${lang} ${name}: the legend's words`,
        ).toEqual(entries.map((e) => t(lang, e.getAttribute("data-legend") === "band" ? "trend.band.legend.band" : "trend.band.legend.edge")));
        cleanup();
      }
    }
    expect(compared).toBeGreaterThanOrEqual(30);
    // Guard: the readings carried range texts that could have been leaked.
    expect(withText).toBeGreaterThanOrEqual(20);
  });

  it("theLegendStandsOnlyUnderADrawnRange", () => {
    // One entry per kind the chart draws, each its key and its own phrase: a band-only chart names no limit line, a
    // limit-only chart names no band, and a chart that draws no range has no legend.
    const met = new Set<string>();
    for (const lang of LANGS) {
      for (const [name, s] of CHARTED) {
        const root = draw(s, lang);
        const legend = root.querySelectorAll(".trend-chart__legend");
        const drawn = new Set(rangesOf(root).map((r) => r.kind));
        if (drawn.size === 0) {
          expect(legend.length, name).toBe(0);
          met.add("none");
          cleanup();
          continue;
        }
        expect(legend.length, name).toBe(1);
        expect(legend[0]!.getAttribute("aria-hidden"), "the legend explains the drawing, which a reader does not hear").toBe("true");
        const entries = [...legend[0]!.querySelectorAll(":scope > [data-legend]")].map((e) => ({
          kind: e.getAttribute("data-legend"),
          key: e.firstElementChild?.className,
          text: e.textContent,
        }));
        const want = (["band", "edge"] as const)
          .filter((k) => drawn.has(k))
          .map((k) => ({
            kind: k,
            key: `trend-chart__key trend-chart__key--${k}`,
            text: t(lang, k === "band" ? "trend.band.legend.band" : "trend.band.legend.edge"),
          }));
        expect(entries, `${lang} ${name}: one entry per drawn kind`).toEqual(want);
        expect(legend[0]!.textContent, `${lang} ${name}: nothing but its entries`).toBe(want.map((w) => w.text).join(""));
        met.add([...drawn].sort().join("+"));
        cleanup();
      }
    }
    // Guard: a band-only chart, an edge-only chart and a chart with no range were all met.
    expect([...met].sort()).toEqual(expect.arrayContaining(["band", "edge", "none"]));
    // The two phrases are distinct, and the limit line's says it is solid, unlike the dashed trend line.
    for (const lang of LANGS) expect(t(lang, "trend.band.legend.band")).not.toBe(t(lang, "trend.band.legend.edge"));
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
    // Java sends no number beside an unknown; were one sent, the chart still fails closed: a reading it draws unknown
    // stands on no range.
    expect([first!, second!].every((p) => p.ref_low !== null && p.ref_high !== null), "the fixture sent both numbers").toBe(true);
    const under = rangesOf(root).filter((r) => r.ids.includes(first!.observation_id) || r.ids.includes(second!.observation_id));
    expect(under.map((r) => r.ids.join(" ")), "ranges under readings the code cannot judge").toEqual([]);
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
          // The reading's own sentence, and no range text (noReaderIsGivenTheRangeTextUntilItsFormIsAccepted).
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
