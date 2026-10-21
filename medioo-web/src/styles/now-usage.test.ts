import { describe, expect, it } from "vitest";
import type { TokenFile } from "./build-tokens";
import json from "./medioo.tokens.json";
import { NOW_ALLOWED, WEEK_ALLOWED, nowUsageViolations, roleUsageViolations, tokenUsageViolations } from "./now-usage";

// The NOW red is painted only by the components that show a stored NOW (plan W2a): the checker below runs over the
// built CSS in built/now-usage.built.test.ts. These are its own tests, in both directions.
const tokens = json as unknown as TokenFile;

describe("tokenUsageViolations", () => {
  it("aNowVarOutsideTheAllowedComponentsIsReported", () => {
    expect(nowUsageViolations(".day-cell { color: var(--now-ink) }", NOW_ALLOWED)).toEqual([".day-cell uses --now-ink"]);
  });

  it("aNowVarInsideTheAllowedComponentsIsAccepted", () => {
    const css = `
      .now-banner { background: var(--now); color: var(--on-now); }
      .now-banner__call { color: var(--now-ink); }
      .now-banner--abroad { background: var(--now-soft); }
      .verdict-card--now .verdict-card__band { background: var(--now); color: var(--on-now); }
      .status-chip--crit-high { background: var(--now); }
      .status-chip--crit-low:hover { color: var(--on-now); }
    `;
    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
  });

  it("theTokenDefinitionsThemselvesAreNotUses", () => {
    const css = `
      :root { --now: #d12c24; --on-now: #ffffff; --now-ink: #a3221c; }
      :root[data-theme="dark"] { --now-soft: rgba(217, 50, 41, 0.18); }
      @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) { --now: #d93229; } }
    `;
    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
  });

  it("aSelectorListIsJudgedPerSelector", () => {
    expect(nowUsageViolations(".now-banner, .tab-bar { background: var(--now) }", NOW_ALLOWED)).toEqual([".tab-bar uses --now"]);
  });

  it("aCustomPropertyThatForwardsNowIsAUse", () => {
    expect(nowUsageViolations(".tab-bar { --accent: var(--now) }", NOW_ALLOWED)).toEqual([".tab-bar uses --now"]);
  });

  it("aRootAliasOfNowIsAUse", () => {
    expect(nowUsageViolations(":root { --danger: var(--now) } .tab-bar { color: var(--danger) }", NOW_ALLOWED)).toEqual([
      ":root uses --now (as --danger)",
    ]);
    // A var() fallback is a use too, wherever it sits in the value.
    expect(nowUsageViolations(".tab-bar { color: var(--x, var(--now-ink)) }", NOW_ALLOWED)).toEqual([".tab-bar uses --now-ink"]);
  });

  it("anAllowedPrefixIsNotEnough", () => {
    expect(nowUsageViolations(".now-bannerx { color: var(--now) }", NOW_ALLOWED)).toEqual([".now-bannerx uses --now"]);
    expect(nowUsageViolations(".status-chip--critique { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".status-chip--critique uses --now",
    ]);
    // A descendant of another component is that component's use, not the banner's.
    expect(nowUsageViolations(".tab-bar .now-banner { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".tab-bar .now-banner uses --now",
    ]);
  });

  it("aSiblingOrHasSelectorPaintsOutsideTheComponentAndIsReported", () => {
    // The element painted is the subject: a sibling of the banner is not the banner.
    expect(nowUsageViolations(".now-banner ~ .day-cell { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner ~ .day-cell uses --now",
    ]);
    expect(nowUsageViolations(".now-banner + .tab-bar { background: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner + .tab-bar uses --now",
    ]);
    expect(nowUsageViolations(".status-chip--crit-high ~ * { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".status-chip--crit-high ~ * uses --now",
    ]);
    expect(nowUsageViolations(".now-banner:has(~ .x) .x { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner:has(~ .x) .x uses --now",
    ]);
    // Inside the component, in either combinator, is the component's own use.
    expect(nowUsageViolations(".now-banner .now-banner__call { color: var(--now-ink) }", NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(".now-banner > span { color: var(--on-now) }", NOW_ALLOWED)).toEqual([]);
    // A + inside :nth-child() is not a combinator, and ~= inside an attribute selector is not one either.
    expect(nowUsageViolations(".now-banner__row:nth-child(2n+1) { background: var(--now-soft) }", NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations('.now-banner[class~="now-banner--abroad"] { background: var(--now-soft) }', NOW_ALLOWED)).toEqual([]);
  });

  it("everyClassInTheSelectorBelongsToTheAllowedComponent", () => {
    expect(nowUsageViolations(".now-banner .tab-bar { color: var(--now) }", NOW_ALLOWED)).toEqual([".now-banner .tab-bar uses --now"]);
    expect(nowUsageViolations(".now-banner:is(.tab-bar) { color: var(--now) }", NOW_ALLOWED)).toEqual([
      ".now-banner:is(.tab-bar) uses --now",
    ]);
    expect(nowUsageViolations(".verdict-card--now .verdict-card__band { color: var(--on-now) }", NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(".status-chip--crit-low .status-chip__arrow { fill: var(--now) }", NOW_ALLOWED)).toEqual([]);
  });

  it("aKeyframesBlockIsJudgedByItsNameAndByWhoAnimatesWithIt", () => {
    const pulse = "@keyframes now-banner-pulse { 0% { box-shadow: 0 0 0 0 var(--now-soft) } to { box-shadow: 0 0 0 12px var(--now-soft) } }";
    expect(nowUsageViolations(pulse, NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(`${pulse} .now-banner { animation: now-banner-pulse 1s infinite }`, NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations("@keyframes tab-pulse { to { color: var(--now) } }", NOW_ALLOWED)).toEqual([
      "@keyframes tab-pulse uses --now",
    ]);
    expect(nowUsageViolations("@keyframes now-bannerx { to { color: var(--now) } }", NOW_ALLOWED)).toEqual([
      "@keyframes now-bannerx uses --now",
    ]);
    // A NOW keyframe borrowed by another component paints that component red.
    expect(nowUsageViolations(`${pulse} .tab-bar { animation: now-banner-pulse 1s }`, NOW_ALLOWED)).toEqual([
      ".tab-bar uses --now-soft (through @keyframes now-banner-pulse)",
    ]);
  });

  it("aTokenNamedLikeNowIsNotNow", () => {
    // --nowrap is not --now: only --now, --now-* and --on-now are the NOW tokens.
    expect(nowUsageViolations(".x { --nowrap: 1; white-space: var(--nowrap) }", NOW_ALLOWED)).toEqual([]);
  });

  it("weekIsCheckedTheSameWay", () => {
    expect(tokenUsageViolations(".status-chip--out-high { background: var(--week-soft) }", "week", WEEK_ALLOWED)).toEqual([
      ".status-chip--out-high uses --week-soft",
    ]);
    expect(
      tokenUsageViolations(".verdict-card--week .verdict-card__band { background: var(--week-soft) }", "week", WEEK_ALLOWED),
    ).toEqual([]);
  });
});

describe("roleUsageViolations", () => {
  it("aNonTextTokenNeverPaintsText", () => {
    expect(roleUsageViolations(".hint { color: var(--label3) }", tokens)).toEqual([".hint paints text with non-text --label3"]);
    expect(roleUsageViolations("input::placeholder { color: var(--label3) }", tokens)).toEqual([
      "input::placeholder paints text with non-text --label3",
    ]);
    expect(roleUsageViolations(".hint { color: var(--label2) }", tokens)).toEqual([]);
    expect(roleUsageViolations("input::placeholder { color: var(--label2) }", tokens)).toEqual([]);
    // A non-text token where it belongs, as a glyph's fill, is not text.
    expect(roleUsageViolations(".icon { fill: var(--label3) }", tokens)).toEqual([]);
  });

  it("everyPropertyThatPaintsTextIsText", () => {
    for (const prop of ["caret-color", "text-decoration-color", "-webkit-text-stroke-color", "text-emphasis-color"]) {
      expect(roleUsageViolations(`.hint { ${prop}: var(--label3) }`, tokens), prop).toEqual([".hint paints text with non-text --label3"]);
    }
    // SVG text is painted with fill and stroke.
    expect(roleUsageViolations("svg text { fill: var(--label3) }", tokens)).toEqual(["svg text paints text with non-text --label3"]);
    expect(roleUsageViolations(".chart tspan { stroke: var(--label3) }", tokens)).toEqual([
      ".chart tspan paints text with non-text --label3",
    ]);
  });

  it("aForwardedAliasIsTheTokenItForwards", () => {
    expect(roleUsageViolations(".x { --hint: var(--label3) } .x p { color: var(--hint) }", tokens)).toEqual([
      ".x p paints text with non-text --label3 (via --hint)",
    ]);
    expect(roleUsageViolations(".x { --a: var(--label3) } .y { --b: var(--a) } .y p { color: var(--b) }", tokens)).toEqual([
      ".y p paints text with non-text --label3 (via --b)",
    ]);
    expect(roleUsageViolations(".x { --hint: var(--label2) } .x p { color: var(--hint) }", tokens)).toEqual([]);
  });

  it("aSurfaceIsNeverAnIndicator", () => {
    // A surface is checked as something text sits on, never at 3:1 against another surface, so it cannot draw a
    // border, an outline or a glyph that has to be seen.
    expect(roleUsageViolations(".card { border-color: var(--bg3) }", tokens)).toEqual([".card draws an indicator with surface --bg3"]);
    expect(roleUsageViolations(".field { border: 1px solid var(--bg) }", tokens)).toEqual([".field draws an indicator with surface --bg"]);
    expect(roleUsageViolations(".x { outline-color: var(--tint-soft) }", tokens)).toEqual([".x draws an indicator with surface --tint-soft"]);
    expect(roleUsageViolations(".icon { stroke: var(--paper) }", tokens)).toEqual([".icon draws an indicator with surface --paper"]);
    expect(roleUsageViolations(".icon { fill: var(--week-soft) }", tokens)).toEqual([".icon draws an indicator with surface --week-soft"]);
    // A surface as a background, a separator as a border and a checked fill as a ring are where they belong.
    expect(roleUsageViolations(".card { background: var(--bg3); border-top: 1px solid var(--sep) }", tokens)).toEqual([]);
    expect(roleUsageViolations(".btn { border-color: var(--tint); outline: 3px solid var(--tint) }", tokens)).toEqual([]);
  });
});
