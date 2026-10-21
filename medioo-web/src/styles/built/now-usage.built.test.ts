import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import postcss from "postcss";
import { describe, expect, it } from "vitest";
import type { TokenFile } from "../build-tokens";
import json from "../medioo.tokens.json";
import { NOW_ALLOWED, WEEK_ALLOWED, nowUsageViolations, roleUsageViolations, tokenUsageViolations } from "../now-usage";

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

    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
    expect(tokenUsageViolations(css, "week", WEEK_ALLOWED)).toEqual([]);
    expect(roleUsageViolations(css, json as unknown as TokenFile)).toEqual([]);
  });
});
