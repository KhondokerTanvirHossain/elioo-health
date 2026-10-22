import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { LAYOUTS, THEMES, resolveTokens, type Role, type TokenFile } from "./build-tokens";
import { EXEMPTABLE, contrastChecks, contrastRatio, coverageProblems, everySurface } from "./contrast";
import json from "./medioo.tokens.json";

// Every pair a token declares (its `on`) is computed from the resolved values, both themes × both layouts. A
// translucent surface is composited over every opaque surface it can sit on, and the lowest ratio is the one judged;
// a translucent ink is then composited over that.
const src = json as unknown as TokenFile;
const copy = (): TokenFile => JSON.parse(JSON.stringify(src)) as TokenFile;
const here = dirname(fileURLToPath(import.meta.url));
const PLAN = join(here, "..", "..", "..", "docs", "superpowers", "plans", "2026-10-22-medioo-wave2b-3-backend.md");

/**
 * The role each colour token has in the plan's reconciled-token table, read from the plan itself. A token's role
 * decides which contrast check runs on it, so a role is never changed in the token file alone: a different role
 * means a different row in the plan, which is a decision someone reads, not an edit nobody sees.
 */
function planRoles(markdown: string): Map<string, Role> {
  const start = markdown.indexOf("**The reconciled colour tokens.**");
  if (start < 0) throw new Error("the plan has no reconciled-token table");
  const roles = new Map<string, Role>();
  const roleOf = (text: string): Role | undefined => {
    const t = text.replace(/\*\*/g, "").trim();
    if (/^non-text\b/.test(t)) return "non-text";
    if (/^text\b/.test(t)) return "text";
    if (/^surface\b/.test(t)) return "surface";
    if (/^fill\b/.test(t)) return "fill";
    return undefined;
  };
  for (const line of markdown.slice(start).split("\n").slice(1)) {
    if (line.trim() === "") {
      if (roles.size > 0) break;
      continue;
    }
    if (!line.startsWith("|") || /^\|\s*-/.test(line) || /^\|\s*Token\b/.test(line)) continue;
    const cells = line.split("|").slice(1, -1);
    const names = [...(cells[0] ?? "").matchAll(/`([\w-]+)`/g)].map((m) => m[1]!);
    const last = cells[cells.length - 1] ?? "";
    const parts = names.length > 1 ? last.split(/\s+\/\s+|;\s+/) : [last];
    names.forEach((name, i) => {
      const role = roleOf(parts.length === names.length ? parts[i]! : parts[0]!);
      if (role) roles.set(name, role);
    });
  }
  return roles;
}

const roleMismatches = (file: TokenFile, roles: Map<string, Role>): string[] =>
  Object.entries(file.color).flatMap(([name, token]) => {
    const planned = roles.get(name);
    if (planned === undefined) return [`${name}: not in the plan's reconciled-token table`];
    return planned === token.role ? [] : [`${name}: role ${token.role}, the plan says ${planned}`];
  });

/**
 * The pairs the MonthCalendar's urgency marks rely on (R2, DR-48): now and week on bg, bg2 and bg3, every theme and
 * layout, at 3:1. A pair fails unless its ratio is a number at or above 3, so an unresolved token (a rename, a typo)
 * fails by name instead of passing as NaN.
 */
function calendarMarkFailures(resolved: ReturnType<typeof resolveTokens>): { failures: string[]; judged: number } {
  const failures: string[] = [];
  let judged = 0;
  for (const theme of THEMES) {
    for (const layout of LAYOUTS) {
      for (const mark of ["now", "week"]) {
        for (const surface of ["bg", "bg2", "bg3"]) {
          judged++;
          const fg = resolved[theme][layout][mark];
          const bg = resolved[theme][layout][surface];
          const ratio = fg === undefined || bg === undefined ? Number.NaN : contrastRatio(fg, bg);
          if (!(ratio >= 3)) {
            failures.push(`${theme}/${layout} ${mark} on ${surface} = ${Number.isNaN(ratio) ? "unresolved" : ratio.toFixed(2)}`);
          }
        }
      }
    }
  }
  return { failures, judged };
}

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
    for (const pair of [
      "light/phone green on bg",
      "light/phone label3 on bg3",
      "dark/phone green on bg",
      "dark/phone label3 on bg3",
      "light/desktop focus on bg2",
      "dark/desktop focus on bg3",
      "light/phone boundary on bg",
      "light/desktop boundary on bg2",
      "dark/phone boundary on bg3",
      "dark/desktop boundary on bg3",
    ]) {
      expect(pairs.some((p) => p.startsWith(`${pair} = `)), pair).toBe(true);
    }
  });

  it("everyFillTokenClearsThreeOnTheSurfacesItSitsOn", () => {
    const { pairs, failures } = contrastChecks(src, "fill", 3);
    expect(failures).toEqual([]);
    for (const pair of ["dark/desktop now on bg3", "light/phone now on bg", "light/phone tint on bg", "dark/desktop sent on bg"]) {
      expect(pairs.some((p) => p.startsWith(`${pair} = `)), pair).toBe(true);
    }
    // A NOW band a shade darker than decision 7 fails against the card it sits on, not only against on-now.
    const darker = copy();
    darker.color.now!.dark = "#b02a22";
    expect(contrastChecks(darker, "fill", 3).failures).toContainEqual(expect.stringMatching(/^dark\/desktop now on bg3 = /));
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
    // The exemptions are these and no others: Q1 (A) gave fill a boundary token, not an exemption (PR #120).
    expect([...EXEMPTABLE]).toEqual(["sep", "hover", "selbg", "scrim"]);
    expect(src.color.fill!.exempt).toBeUndefined();
    const fillExempt = copy();
    fillExempt.color.fill!.exempt = "WCAG 1.4.11: a wash";
    expect(coverageProblems(fillExempt)).toEqual(["fill: exempt is allowed only on sep, hover, selbg, scrim"]);
    // A fill (the NOW band, a button) is a shape that must be seen against its surface, so it needs an on too.
    const noNowOn = copy();
    delete noNowOn.color.now!.on;
    expect(coverageProblems(noNowOn)).toEqual([expect.stringContaining("now")]);
  });

  it("everyTokenHasThePlansRole", () => {
    const roles = planRoles(readFileSync(PLAN, "utf8"));
    // The parse read the table it claims to: the roles the checks hinge on.
    expect(roles.size).toBeGreaterThanOrEqual(30);
    for (const name of ["label3", "green", "boundary"]) expect(roles.get(name), name).toBe("non-text");
    // Q1 (A): fill is the control's wash, a surface like the other washes; its boundary is the boundary token.
    expect(roles.get("fill"), "fill").toBe("surface");
    for (const name of ["now", "tint", "sent"]) expect(roles.get(name), name).toBe("fill");
    expect(roleMismatches(src, roles)).toEqual([]);
    // Relabelling a token is how a check is switched off; it is refused unless the plan's row changes too.
    const relabelled = copy();
    relabelled.color.green!.role = "surface";
    expect(roleMismatches(relabelled, roles)).toEqual(["green: role surface, the plan says non-text"]);
  });

  it("theFieldBoundaryIsLabel3OnBgBg2Bg3AtThreeToOne", () => {
    // Q1 (A), PR #120: every input and segmented track carries this boundary (fillBoundaryViolations), because its
    // fill is a wash of about 1.15:1. Checked as non-text at 3:1 on the surfaces a field sits on.
    const boundary = src.color.boundary!;
    expect(boundary.role).toBe("non-text");
    expect(boundary.on).toEqual(["bg", "bg2", "bg3"]);
    expect(boundary.exempt).toBeUndefined();
    const resolved = resolveTokens(src);
    for (const theme of THEMES) {
      for (const layout of LAYOUTS) {
        expect(resolved[theme][layout].boundary, `${theme}/${layout} boundary is label3`).toBe(resolved[theme][layout].label3);
      }
    }
    // Derived, so it follows label3: lower label3 and the boundary goes with it, and the check reports both.
    const paler = copy();
    paler.color.label3!.light = "rgba(60, 60, 67, 0.3)";
    expect(resolveTokens(paler).light.phone.boundary).toBe("rgba(60, 60, 67, 0.3)");
    expect(contrastChecks(paler, "non-text", 3).failures).toContainEqual(expect.stringMatching(/^light\/phone boundary on bg = /));
    // A boundary that is the wash itself is no boundary.
    const wash = copy();
    wash.color.boundary!.light = "rgba(120, 120, 128, 0.12)";
    expect(contrastChecks(wash, "non-text", 3).failures).toContain("light/phone boundary on bg = 1.15");
  });

  it("theCalendarsUrgencyMarksClearThreeOnEveryCardSurface", () => {
    // PO ruling R2 (DR-48): the MonthCalendar's NOW mark is drawn in now and its THIS_WEEK mark in week (the amber the
    // prototype's calendar dot uses), each a meaningful shape, so WCAG 1.4.11's 3:1 applies on bg, bg2 and bg3, in
    // both themes and both layouts. Both are already declared there (now as a fill, week as text at 4.5); this says
    // which pairs the calendar relies on, so a token that drops one of them fails here by name.
    const { failures, judged } = calendarMarkFailures(resolveTokens(src));
    expect(failures).toEqual([]);
    expect(judged).toBe(2 * 2 * 2 * 3);
    // The check itself can fail: the same function, given the prototype's lighter dark amber in the light theme,
    // names the low pairs by value.
    const pale = copy();
    pale.color.week!.light = "#ffb340";
    const paleFailures = calendarMarkFailures(resolveTokens(pale)).failures;
    expect(paleFailures).toEqual([
      "light/phone week on bg = 1.78",
      "light/phone week on bg2 = 1.60",
      "light/phone week on bg3 = 1.78",
      "light/desktop week on bg = 1.78",
      "light/desktop week on bg2 = 1.64",
      "light/desktop week on bg3 = 1.78",
    ]);
    // A token that does not resolve fails by name rather than passing as NaN (NaN < 3 is false).
    const missing = resolveTokens(src);
    delete missing.dark.desktop.week;
    expect(calendarMarkFailures(missing).failures).toEqual(["bg", "bg2", "bg3"].map((s) => `dark/desktop week on ${s} = unresolved`));
  });

  it("everySurfaceMeansEverySurfaceTokenIncludingOnesAddedLater", () => {
    const surfaces = everySurface(src);
    expect(surfaces.length).toBeGreaterThanOrEqual(10);
    expect(surfaces).toContain("bg");
    expect(surfaces).toContain("week-soft");
    // paper is a surface only paper-ink is painted on, and says so on the token.
    expect(surfaces).not.toContain("paper");
    expect(src.color.paper!.notEverySurface).toMatch(/paper-ink/);
    for (const name of ["label", "label2", "now-ink", "week"]) expect(src.color[name]!.on, name).toContain("every-surface");

    const grown = copy();
    grown.color["chip-bg"] = { light: "#ffffff", dark: "#000000", role: "surface" };
    const { pairs } = contrastChecks(grown, "text", 4.5);
    for (const name of ["label", "label2", "now-ink", "week"]) {
      expect(pairs.some((p) => p.startsWith(`light/phone ${name} on chip-bg = `)), name).toBe(true);
    }
    const unreadable = copy();
    unreadable.color["chip-bg"] = { light: "#111111", dark: "#000000", role: "surface" };
    expect(contrastChecks(unreadable, "text", 4.5).failures).toContainEqual(expect.stringMatching(/^light\/phone label on chip-bg = /));
  });

  it("aTranslucentSurfaceIsJudgedOverEveryOpaqueSurfaceItCanSitOn", () => {
    // Over bg alone this passes; over a light bg3 card it does not, and the line names the card.
    const lightCard = copy();
    lightCard.color.bg3!.desktop = { dark: "#d0d0d0" };
    expect(contrastChecks(lightCard, "text", 4.5).failures).toContainEqual(
      expect.stringMatching(/^dark\/desktop label on week-soft over bg3 = /),
    );
    expect(contrastChecks(src, "text", 4.5).pairs.some((p) => / on now-soft over [\w-]+ = /.test(p))).toBe(true);
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

  it("aNonTextColourThatFailsIsReported", () => {
    const pale = copy();
    pale.color.green!.light = "#34c759";
    expect(contrastChecks(pale, "non-text", 3).failures).toContain("light/phone green on bg = 2.22");
  });
});
