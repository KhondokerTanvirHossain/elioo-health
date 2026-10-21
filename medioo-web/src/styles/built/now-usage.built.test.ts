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
