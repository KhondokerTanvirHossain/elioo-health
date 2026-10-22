import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import postcss from "postcss";
import { describe, expect, it } from "vitest";
import type { TokenFile } from "../build-tokens";
import json from "../medioo.tokens.json";
import {
  NOW_ALLOWED,
  WEEK_ALLOWED,
  controlBoundaryViolations,
  controlSelectors,
  fillBoundaryViolations,
  fillPainters,
  nowUsageViolations,
  roleUsageViolations,
  tokenUsageViolations,
} from "../now-usage";

// Runs on the build (pnpm build && pnpm run test:built): what is judged is the CSS that ships, after Next has bundled
// every component's stylesheet, so a use the source scans cannot attribute is still seen here.
const web = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const cssDir = join(web, ".next", "static", "css");

function builtCss(): string {
  const files: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) walk(path);
      else if (entry.endsWith(".css")) files.push(path);
    }
  };
  if (existsSync(cssDir)) walk(cssDir);
  if (files.length === 0) throw new Error("run pnpm build first: no CSS under .next/static/css");
  return files.map((f) => readFileSync(f, "utf8")).join("\n");
}

describe("the built CSS", () => {
  it("theBuiltCssUsesNowOnlyInNowCritAndEmergencyComponents", () => {
    const css = builtCss();

    // Guard: the files read are the app's, with the tokens in them (the definitions sit in a :root block).
    const rootProps = new Set<string>();
    postcss.parse(css).walkDecls((decl) => {
      const parent = decl.parent;
      if (parent?.type === "rule" && /^:root(?![\w-])/.test((parent as postcss.Rule).selector)) rootProps.add(decl.prop);
    });
    expect(rootProps.has("--now"), "the built CSS defines --now in :root").toBe(true);
    expect(rootProps.has("--week"), "the built CSS defines --week in :root").toBe(true);

    // The second guard (W2c): at least one allowed use ships, StatusChip's critical states, so the check below judged
    // a real use of the red and not only its definitions.
    const allowedNowUses: string[] = [];
    postcss.parse(css).walkDecls((decl) => {
      if (!/var\(\s*--(now|on-now)\b/.test(decl.value) || decl.parent?.type !== "rule") return;
      for (const selector of (decl.parent as postcss.Rule).selectors) {
        if (/^\.(status-chip--crit-(high|low)|now-banner)$/.test(selector.trim())) allowedNowUses.push(`${selector.trim()} ${decl.prop}`);
      }
    });
    expect(allowedNowUses, "StatusChip's critical states paint with the NOW tokens in the built CSS").toEqual(
      expect.arrayContaining([expect.stringMatching(/^\.status-chip--crit-high /)]),
    );
    // W2d-b: the NOW banner ships, its ground the NOW red and its text --on-now, and is judged below as an allowed use.
    expect(allowedNowUses, "the NOW banner paints with the NOW tokens in the built CSS").toEqual(
      expect.arrayContaining([".now-banner background", ".now-banner color"]),
    );

    // W2d-a: the NOW card's band and call link are allowed NOW uses, and the THIS_WEEK card's band is the one allowed
    // --week use, so the --week check below judged a real use of the amber and not only its definitions.
    const usesOf = (token: RegExp, selectorPattern: RegExp) => {
      const out: string[] = [];
      postcss.parse(css).walkDecls((decl) => {
        if (!token.test(decl.value) || decl.parent?.type !== "rule") return;
        for (const selector of (decl.parent as postcss.Rule).selectors) {
          if (selectorPattern.test(selector.trim())) out.push(`${selector.trim()} ${decl.prop}`);
        }
      });
      return out;
    };
    expect(usesOf(/var\(\s*--(now|on-now)\b/, /^\.verdict-card--now\b/), "VerdictCard's NOW band paints with the NOW tokens").toEqual(
      expect.arrayContaining([".verdict-card--now .verdict-card__band background", ".verdict-card--now .verdict-card__band color"]),
    );
    expect(usesOf(/var\(\s*--week\b/, /./), "every --week use in the built CSS").toEqual(
      expect.arrayContaining([".verdict-card--week .verdict-card__band background", ".verdict-card--week .verdict-card__band color"]),
    );
    // R2 (DR-48): the calendar's NOW mark (cell and legend) is the NOW red and its THIS_WEEK mark the amber, shape and
    // word, so both ship as allowed uses and are judged below.
    // Judged per token: the shape is the NOW fill red (--now, 3:1) and the word the NOW ink (--now-ink, 4.5:1), so a
    // shape stroked in --now-ink or a word coloured --now is reported here and not passed as "a NOW token".
    expect(usesOf(/var\(\s*--now\s*[,)]/, /^\.month-calendar__/), "MonthCalendar's NOW shapes are stroked in --now").toEqual(
      expect.arrayContaining([
        ".month-calendar__alert--now .month-calendar__mark--urgency stroke",
        ".month-calendar__key--now .month-calendar__mark--urgency stroke",
      ]),
    );
    expect(usesOf(/var\(\s*--now-ink\b/, /^\.month-calendar__/), "MonthCalendar's NOW word is coloured --now-ink").toEqual(
      expect.arrayContaining([".month-calendar__alert--now .month-calendar__alert-word color"]),
    );
    expect(usesOf(/var\(\s*--week\b/, /^\.month-calendar__/), "MonthCalendar's THIS_WEEK marks paint with --week").toEqual(
      expect.arrayContaining([
        ".month-calendar__alert--week .month-calendar__mark--urgency stroke",
        ".month-calendar__alert--week .month-calendar__alert-word color",
        ".month-calendar__key--week .month-calendar__mark--urgency stroke",
      ]),
    );
    expect(
      usesOf(/var\(\s*--week\b/, /./).filter((u) => !/^\.(verdict-card--week|month-calendar__(alert|key)--week) /.test(u)),
      "--week used outside .verdict-card--week and the calendar's THIS_WEEK marks",
    ).toEqual([]);

    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
    expect(tokenUsageViolations(css, "week", WEEK_ALLOWED)).toEqual([]);
    expect(roleUsageViolations(css, json as unknown as TokenFile)).toEqual([]);
  });

  it("everyFillPainterAndEveryControlCarriesADrawnBoundary", () => {
    const css = builtCss();
    // Guard: the wash and its boundary are both shipped, so a field can carry what the check asks for.
    const rootProps = new Set<string>();
    postcss.parse(css).walkDecls((decl) => {
      const parent = decl.parent;
      if (parent?.type === "rule" && /^:root(?![\w-])/.test((parent as postcss.Rule).selector)) rootProps.add(decl.prop);
    });
    expect(rootProps.has("--fill"), "the built CSS defines --fill in :root").toBe(true);
    expect(rootProps.has("--boundary"), "the built CSS defines --boundary in :root").toBe(true);
    // Q1 (A), PR #120: no input or segmented track relies on the fill wash alone, and every control carries a drawn
    // boundary whatever its background.
    // Soft, so one run reports both: a field that drops its boundary is a --fill painter and a control at once.
    expect.soft(fillBoundaryViolations(css), "fillBoundaryViolations").toEqual([]);
    expect.soft(controlBoundaryViolations(css), "controlBoundaryViolations").toEqual([]);
    // What the two checks judged, so the empty lists above are not vacuous: the W2e-a fields (OtpInput's and
    // PhoneField's inputs) are among both the --fill painters and the controls. A later PR that ships a field or a
    // segmented track adds its class here, and to catalog.spec.ts everyFieldAndTrackDrawsTheBoundary's guard, which
    // checks what the browser paints (order, a recolouring rule, a class put on by markup).
    const painters = fillPainters(css);
    const controls = controlSelectors(css);
    console.info(`--fill painters in the built CSS (${painters.length}): ${painters.join(", ") || "none"}`);
    console.info(`controls in the built CSS (${controls.length}): ${controls.join(", ") || "none"}`);
    expect(painters, "--fill painters in the built CSS").toEqual(expect.arrayContaining([".otp-input", ".phone-field__input"]));
    expect(controls, "controls in the built CSS").toEqual(expect.arrayContaining([".otp-input", ".phone-field__input"]));
  });
});
