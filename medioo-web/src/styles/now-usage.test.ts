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
});
