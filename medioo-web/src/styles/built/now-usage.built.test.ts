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
        if (/^\.status-chip--crit-(high|low)$/.test(selector.trim())) allowedNowUses.push(`${selector.trim()} ${decl.prop}`);
      }
    });
    expect(allowedNowUses, "StatusChip's critical states paint with the NOW tokens in the built CSS").toEqual(
      expect.arrayContaining([expect.stringMatching(/^\.status-chip--crit-high /)]),
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
    expect(fillBoundaryViolations(css)).toEqual([]);
    expect(controlBoundaryViolations(css)).toEqual([]);
    // What the two checks judged. W2a ships no field and no track, so both lists are empty and both checks above
    // are vacuous today. This pin says so instead of letting a green run read as coverage: W2e-a, the first PR that
    // ships a field, replaces it with its non-empty guard (the field and track classes are among the painters and
    // the controls) and adds the rendered computed-style check (plan W2e-a Step 3).
    const painters = fillPainters(css);
    const controls = controlSelectors(css);
    console.info(`--fill painters in the built CSS (${painters.length}): ${painters.join(", ") || "none"}`);
    console.info(`controls in the built CSS (${controls.length}): ${controls.join(", ") || "none"}`);
    expect(painters, "W2a ships no field or track; W2e-a replaces this pin with its non-empty guard").toEqual([]);
    expect(controls, "W2a ships no field or track; W2e-a replaces this pin with its non-empty guard").toEqual([]);
  });
});
