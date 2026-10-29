import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import { NOW_ALLOWED, WEEK_ALLOWED } from "@/styles/now-usage";
import { UrgencyTag } from "./UrgencyTag";

afterEach(cleanup);

/*
 * A paper's stored urgency on a row (plan W8: the papers list and the attention strip): Java's `chip` or `level` as the
 * shape and the short word WhatsApp's bundle holds, for NOW and THIS_WEEK only. Anything else, routine and unknown
 * values included, is nothing at all (D27), never a guess. PO ruling P5 (2026-10-28): the NOW tag is the prototype's
 * `chip crit` (the NOW wash and the NOW ink) and the THIS_WEEK tag its `chip out` (the amber wash and the amber), shape
 * and word kept; red only for NOW.
 */

const LANGS: readonly Lang[] = ["bn", "en"];

describe("UrgencyTag", () => {
  it("nowAndThisWeekAreShapeAndWord", () => {
    for (const lang of LANGS) {
      const now = render(<UrgencyTag level="now" lang={lang} />).container.querySelector("[data-urgency]")!;
      expect(now.getAttribute("data-urgency")).toBe("now");
      expect(now.querySelector("svg[data-shape='octagon']"), "the NOW octagon").not.toBeNull();
      expect(now.textContent).toBe(t(lang, "urgency.now.short"));
      cleanup();
      const week = render(<UrgencyTag level="this_week" lang={lang} />).container.querySelector("[data-urgency]")!;
      expect(week.getAttribute("data-urgency")).toBe("this_week");
      expect(week.querySelector("svg[data-shape='calendar-week']"), "the this-week calendar").not.toBeNull();
      expect(week.textContent).toBe(t(lang, "urgency.this_week.short"));
      cleanup();
    }
  });

  it("anythingElseIsNothing", () => {
    for (const level of [null, "routine", "NOW", "", "critical"]) {
      expect(render(<UrgencyTag level={level} lang="bn" />).container.innerHTML, String(level)).toBe("");
      cleanup();
    }
  });

  it("theNowTagIsTheNowRedAndTheThisWeekTagTheAmberShapeAndWordKept", () => {
    // The class each level carries: NOW's is a NOW_ALLOWED class and never a WEEK_ALLOWED one, THIS_WEEK's the other
    // way round, so the built-CSS guard judges each tag by its own level.
    const classesOf = (level: string) => {
      const tag = render(<UrgencyTag level={level} lang="bn" />).container.querySelector("[data-urgency]")!;
      const classes = [...tag.classList];
      cleanup();
      return classes;
    };
    const now = classesOf("now");
    const week = classesOf("this_week");
    expect(now.filter((c) => NOW_ALLOWED.test(`.${c}`)), "the NOW tag's NOW class").toEqual(["urgency-tag--now"]);
    expect(now.filter((c) => WEEK_ALLOWED.test(`.${c}`)), "the NOW tag carries no amber class").toEqual([]);
    expect(week.filter((c) => WEEK_ALLOWED.test(`.${c}`)), "the THIS_WEEK tag's amber class").toEqual(["urgency-tag--week"]);
    expect(week.filter((c) => NOW_ALLOWED.test(`.${c}`)), "the THIS_WEEK tag carries no NOW class").toEqual([]);
    // What each class paints, read from the stylesheet: the prototype's chip crit and chip out. The shape strokes in
    // currentColor, so it takes the word's colour. (What the browser paints is papers.spec.ts's to judge.)
    expect(declarations(".urgency-tag--now")).toEqual({ background: "var(--now-soft)", color: "var(--now-ink)" });
    expect(declarations(".urgency-tag--week")).toEqual({ background: "var(--week-soft)", color: "var(--week)" });
    expect(declarations(".urgency-tag__shape").stroke).toBe("currentColor");
    // The dangerous direction: the base rule every tag carries paints neither the red nor the amber, so nothing but the
    // two modifiers can carry either.
    expect(declarations(".urgency-tag")).toEqual({ background: "var(--unk-soft)", color: "var(--label)" });
    const urgencyToken = /var\(\s*--(on-)?(now|week)(?![A-Za-z0-9_])/;
    for (const rule of [".urgency-tag", ".urgency-tag__shape", ".urgency-tag__word"]) {
      for (const [prop, value] of Object.entries(declarations(rule))) expect(value, `${rule} ${prop}`).not.toMatch(urgencyToken);
    }
  });
});

const URGENCY_TAG_CSS = readFileSync(join(dirname(fileURLToPath(import.meta.url)), "UrgencyTag.css"), "utf8");

/** The colour declarations of the rule that lists exactly `selector`. */
function declarations(selector: string): Record<string, string> {
  const blocks = [...URGENCY_TAG_CSS.replace(/\/\*[\s\S]*?\*\//g, "").matchAll(/([^{}]+)\{([^{}]*)\}/g)];
  const rule = blocks.find(([, sel]) => sel!.split(",").map((s) => s.trim()).includes(selector));
  if (!rule) throw new Error(`no rule for ${selector}`);
  const out: Record<string, string> = {};
  for (const decl of rule[2]!.split(";")) {
    const [prop, ...rest] = decl.split(":");
    const name = prop?.trim() ?? "";
    if (["background", "color", "fill", "stroke", "border-color"].includes(name)) out[name] = rest.join(":").trim();
  }
  return out;
}
