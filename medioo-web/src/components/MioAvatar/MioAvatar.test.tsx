import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import postcss, { type AtRule, type Rule } from "postcss";
import { afterEach, describe, expect, it } from "vitest";
import { MioAvatar } from "./MioAvatar";

afterEach(cleanup);

const img = (size: 32 | 48 | 96, still?: boolean) => {
  const el = render(<MioAvatar size={size} still={still} />).container.querySelector("img");
  expect(el, `MioAvatar ${size} renders an <img>`).not.toBeNull();
  return el!;
};

const css = readFileSync(join(__dirname, "MioAvatar.css"), "utf8");

/** The classes in a selector, without the dot. */
const classesOf = (selector: string) => [...selector.matchAll(/\.([\w-]+)/g)].map((m) => m[1]!);

/** Every class that a rule outside a reduced-motion block animates. */
function animatedClasses(): Set<string> {
  const out = new Set<string>();
  postcss.parse(css).walkDecls(/^animation(-name)?$/, (decl) => {
    if (/^none$/i.test(decl.value.trim())) return;
    for (const selector of (decl.parent as Rule).selectors) for (const c of classesOf(selector)) out.add(c);
  });
  return out;
}

/** A comma list split at depth 0 only, so a comma inside cubic-bezier(…) or steps(…) does not split an animation. */
function splitTopLevelCommas(value: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < value.length; i++) {
    const ch = value[i];
    if (ch === "(") depth++;
    else if (ch === ")") depth--;
    else if (ch === "," && depth === 0) {
      parts.push(value.slice(start, i).trim());
      start = i + 1;
    }
  }
  parts.push(value.slice(start).trim());
  return parts.filter((p) => p.length > 0);
}

/**
 * The iteration count of ONE animation (one comma part). Function calls are stripped first: the numbers inside
 * cubic-bezier(0.3, 0.7, 0.4, 1) are the timing function's, not a count. What is left holds at most one unitless
 * number or `infinite`; none means the CSS default, 1.
 */
function iterationCountOf(prop: string, one: string): string {
  const words = one
    .replace(/[\w-]+\([^)]*\)/g, " ")
    .split(/\s+/)
    .filter(Boolean);
  if (prop === "animation-iteration-count") return words[0]?.toLowerCase() ?? "1";
  return words.find((w) => /^(infinite|\d+(\.\d+)?)$/i.test(w))?.toLowerCase() ?? "1";
}

describe("MioAvatar", () => {
  it("theIterationCountParserReadsTheCountNotATimingFunctionArgument", () => {
    // The direction that hid a defect: 0.7 is cubic-bezier's argument, the count is the trailing word.
    expect(splitTopLevelCommas("a 1s cubic-bezier(0.3, 0.7, 0.4, 1) infinite, b 2s steps(4, end) 2")).toEqual([
      "a 1s cubic-bezier(0.3, 0.7, 0.4, 1) infinite",
      "b 2s steps(4, end) 2",
    ]);
    expect(iterationCountOf("animation", "a 700ms cubic-bezier(0.3, 0.7, 0.4, 1) infinite")).toBe("infinite");
    expect(iterationCountOf("animation", "a 700ms cubic-bezier(0.3, 0.7, 0.4, 1) 2")).toBe("2");
    expect(iterationCountOf("animation", "a 2.6s ease-in-out")).toBe("1");
    expect(splitTopLevelCommas("2, infinite").map((p) => iterationCountOf("animation-iteration-count", p))).toEqual([
      "2",
      "infinite",
    ]);
  });

  it("theFigureIsTheApprovedRaster", () => {
    expect(img(32).getAttribute("src")).toBe("/brand/mio-head.webp");
    expect(img(48).getAttribute("src")).toBe("/brand/mio-head.webp");
    expect(img(96).getAttribute("src")).toBe("/brand/mio.webp");
    // Decorative: the name beside Mio carries the meaning.
    expect(img(48).getAttribute("alt")).toBe("");
    expect(img(48).getAttribute("width")).toBe("48");
  });

  it("stillRendersNoAnimation", () => {
    const animated = animatedClasses();
    // Guard: the stylesheet does animate Mio somewhere, so "no animated class" below is not vacuous.
    expect(animated.size, "MioAvatar.css animates a class").toBeGreaterThan(0);
    for (const size of [32, 48, 96] as const) {
      const still = [...img(size, true).classList];
      expect(still.filter((c) => animated.has(c)), `still ${size} carries an animated class`).toEqual([]);
      cleanup();
      // The other direction: without still, Mio moves (as a whole figure, the <img> itself).
      const moving = [...img(size).classList];
      expect(moving.some((c) => animated.has(c) && c !== "mio-avatar"), `moving ${size} carries an animated class`).toBe(
        true,
      );
      cleanup();
    }
  });

  it("everyAnimationPlaysAtMostTwiceAndNoneUnderReducedMotion", () => {
    const root = postcss.parse(css);
    const counts: string[] = [];
    const tooMany: string[] = [];
    root.walkDecls(/^animation(-iteration-count)?$/, (decl) => {
      if (/^none$/i.test(decl.value.trim())) return;
      const selector = (decl.parent as Rule).selector;
      // One entry per animation in the list, each read for its own iteration count.
      splitTopLevelCommas(decl.value).forEach((one, i) => {
        const count = iterationCountOf(decl.prop, one);
        counts.push(`${selector} #${i}: ${count}`);
        if (count === "infinite" || !(Number(count) <= 2)) {
          tooMany.push(`${selector}: ${decl.prop}: ${decl.value} (animation #${i} plays ${count})`);
        }
      });
    });
    expect(counts.length, "the stylesheet's animations were read").toBeGreaterThan(0);
    expect(tooMany, "animations that play more than twice").toEqual([]);

    // Every animated class is stopped under prefers-reduced-motion: reduce.
    const stopped = new Set<string>();
    root.walkAtRules("media", (at: AtRule) => {
      if (!/prefers-reduced-motion:\s*reduce/.test(at.params)) return;
      at.walkDecls(/^animation(-name)?$/, (decl) => {
        if (!/^none$/i.test(decl.value.trim())) return;
        for (const selector of (decl.parent as Rule).selectors) for (const c of classesOf(selector)) stopped.add(c);
      });
    });
    const moving = [...animatedClasses()].filter((c) => c !== "mio-avatar");
    expect(moving.filter((c) => !stopped.has(c)), "animated classes reduced motion does not stop").toEqual([]);
  });
});
