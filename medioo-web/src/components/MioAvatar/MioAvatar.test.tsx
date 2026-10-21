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

describe("MioAvatar", () => {
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
    root.walkDecls(/^animation(-iteration-count)?$/, (decl) => {
      if (/^none$/i.test(decl.value.trim())) return;
      const words = decl.value.split(/[\s,]+/);
      const count = decl.prop === "animation-iteration-count" ? words[0]! : words.find((w) => /^(infinite|\d+(\.\d+)?)$/.test(w));
      counts.push(`${(decl.parent as Rule).selector}: ${count ?? "1"}`);
      expect(count === undefined || (count !== "infinite" && Number(count) <= 2), `${decl.parent?.toString()}`).toBe(true);
    });
    expect(counts.length, "the stylesheet's animations were read").toBeGreaterThan(0);

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
