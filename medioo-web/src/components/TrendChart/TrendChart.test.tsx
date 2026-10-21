import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { LabsJson, TrendPointJson } from "@/api/types";
import { CHIP_SHAPE, chipKindOf } from "@/components/StatusChip/StatusChip";
import labsSome from "../../../fixtures/api/health/labs-some.json";
import labsWithUnknown from "../../../fixtures/api/health/labs-with-unknown.json";
import { TrendChart } from "./TrendChart";

afterEach(cleanup);

/** One series of B6's generated labs body, chosen by its canonical name, as the health screen will pass it. */
function series(labs: LabsJson, canonical: string): TrendPointJson[] {
  const s = labs.series.find((x) => x.canonical_name === canonical);
  if (!s) throw new Error(`no series ${canonical} in the fixture`);
  return s.points;
}

const potassium = series(labsSome.body, "potassium");
const creatinine = series(labsSome.body, "creatinine");
const hba1c = series(labsWithUnknown.body, "hba1c");

const draw = (points: TrendPointJson[]) => render(<TrendChart points={points} lang="bn" />).container;

describe("TrendChart", () => {
  it("oneReadingIsNoChart", () => {
    expect(potassium, "potassium in labs-some.json is one reading").toHaveLength(1);
    const root = draw(potassium);
    expect(root.innerHTML, "one reading renders nothing (it is a sentence on the screen, W9)").toBe("");
    // Nor do no readings.
    expect(draw([]).innerHTML).toBe("");
  });

  it("twoReadingsDrawOneLine", () => {
    for (const points of [creatinine.slice(0, 2), creatinine]) {
      const root = draw(points);
      const lines = root.querySelectorAll("polyline");
      expect(lines, `${points.length} readings: one line`).toHaveLength(1);
      const vertices = lines[0]!.getAttribute("points")!.trim().split(/\s+/);
      expect(vertices, "one vertex per reading").toHaveLength(points.length);
      // Oldest first, left to right: the read port serves the newest first.
      const xs = vertices.map((v) => Number(v.split(",")[0]));
      expect([...xs].sort((a, b) => a - b), "vertices run left to right").toEqual(xs);
      const markers = [...root.querySelectorAll("[data-observation-id]")].map((m) => m.getAttribute("data-observation-id"));
      expect(markers, "markers oldest first").toEqual([...points].sort((a, b) => a.date.localeCompare(b.date)).map((p) => p.observation_id));
      cleanup();
    }
  });

  it("eachMarkerIsItsChipShape", () => {
    const root = draw(hba1c);
    for (const p of hba1c) {
      const marker = root.querySelector(`[data-observation-id="${p.observation_id}"]`);
      expect(marker?.getAttribute("data-shape"), p.observation_id).toBe(CHIP_SHAPE[chipKindOf(p.kind, p.side)]);
    }
  });

  it("anUnknownPointHasNoBand", () => {
    const unknown = hba1c.filter((p) => p.kind === "unknown");
    const judged = hba1c.filter((p) => p.kind !== "unknown");
    // Guard: the series holds both, and enough points to be drawn at all.
    expect(unknown.length, "unknown points in labs-with-unknown.json").toBeGreaterThan(0);
    expect(judged.length, "judged points in labs-with-unknown.json").toBeGreaterThan(0);
    const root = draw(hba1c);
    expect(root.querySelectorAll("polyline"), "the chart is drawn").toHaveLength(1);
    const bands = [...root.querySelectorAll(".trend-chart__band")].map((b) => b.getAttribute("data-band-for"));
    expect(bands.sort(), "a band for every judged point and none for an unknown one").toEqual(judged.map((p) => p.observation_id).sort());
    for (const p of unknown) expect(root.querySelector(`[data-observation-id="${p.observation_id}"]`), "its marker is drawn").not.toBeNull();
  });

  it("theChartIsNamedByItsReadings", () => {
    const svg = draw(creatinine).querySelector("svg")!;
    expect(svg.getAttribute("role")).toBe("img");
    const label = svg.getAttribute("aria-label")!;
    for (const p of creatinine) expect(label, p.observation_id).toContain(p.value);
    expect(svg.getAttribute("style"), "no inline style").toBeNull();
  });
});
