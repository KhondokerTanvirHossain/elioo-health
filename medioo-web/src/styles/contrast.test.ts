import { describe, expect, it } from "vitest";
import type { TokenFile } from "./build-tokens";
import { contrastChecks, contrastRatio, coverageProblems } from "./contrast";
import json from "./medioo.tokens.json";

// Every pair a token declares (its `on`) is computed from the resolved values, both themes × both layouts. A
// translucent surface is composited over bg of the same theme and layout first; a translucent ink over that surface.
const src = json as unknown as TokenFile;
const copy = (): TokenFile => JSON.parse(JSON.stringify(src)) as TokenFile;

describe("contrast of the declared pairs", () => {
  it("everyTextTokenClearsFourAndAHalfOnEverySurfaceItIsDeclaredOn", () => {
    const { pairs, failures } = contrastChecks(src, "text", 4.5);
    expect(failures).toEqual([]);
    // The checker really ran over the file, including the one pair decision 7 exists for.
    expect(pairs.length).toBeGreaterThanOrEqual(40);
    expect(pairs.some((p) => p.startsWith("light/phone on-now on now = "))).toBe(true);
    expect(pairs.some((p) => p.startsWith("dark/phone on-now on now = "))).toBe(true);
  });

  it("everyNonTextTokenClearsThreeWhereItIsDeclared", () => {
    const { pairs, failures } = contrastChecks(src, "non-text", 3);
    expect(failures).toEqual([]);
    for (const pair of ["light/phone green on bg", "light/phone label3 on bg3", "dark/phone green on bg", "dark/phone label3 on bg3"]) {
      expect(pairs.some((p) => p.startsWith(`${pair} = `)), pair).toBe(true);
    }
  });

  it("everyTextAndNonTextTokenIsCheckedOrExemptByName", () => {
    expect(coverageProblems(src)).toEqual([]);
    // Leaving `on` out can never skip a check silently, and exempt is not a way round it either.
    const noOn = copy();
    delete noOn.color.green!.on;
    expect(coverageProblems(noOn)).toEqual([expect.stringContaining("green")]);
    const exempted = copy();
    exempted.color.green!.exempt = "WCAG 1.4.11: decorative";
    expect(coverageProblems(exempted)).toEqual([expect.stringContaining("green")]);
  });

  it("contrastRatioMatchesWcagReferenceValues", () => {
    expect(contrastRatio("#d12c24", "#ffffff")).toBeCloseTo(5.13, 2);
    expect(Math.abs(contrastRatio("#d12c24", "#ffffff") - 5.13)).toBeLessThanOrEqual(0.01);
    expect(Math.abs(contrastRatio("#d93229", "#ffffff") - 4.73)).toBeLessThanOrEqual(0.01);
    expect(contrastRatio("#000000", "#ffffff")).toBe(21);
    expect(contrastRatio("#ffffff", "#000000")).toBe(21);
  });

  it("aColourThatFailsIsReported", () => {
    const pale = copy();
    pale.color.now!.light = "#ff6f61";
    const { failures } = contrastChecks(pale, "text", 4.5);
    expect(failures).toContainEqual(expect.stringMatching(/^light\/phone on-now on now = /));
  });
});
