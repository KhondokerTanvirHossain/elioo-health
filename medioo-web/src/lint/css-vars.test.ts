import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import postcss from "postcss";
import { describe, expect, it } from "vitest";

// Every var(--x) the app uses names a custom property that exists (PR #120 review). A misspelt or leftover name
// (var(--lable2), var(--c-line)) resolves to the property's initial value: text inherits, a border paints nothing,
// and no contrast or axe check is guaranteed to notice. Defined means: in src/styles/tokens.css, or as a custom
// property in the same file (a stylesheet's own --tabbar-h, a style object's "--x" key).
const src = join(dirname(fileURLToPath(import.meta.url)), "..");
const web = join(src, "..");
const VAR = /var\(\s*--([\w-]+)/g;

function files(dir: string, ext: RegExp): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...files(path, ext));
    else if (ext.test(entry)) out.push(path);
  }
  return out;
}
const rel = (path: string) => relative(web, path).split(sep).join("/");

function definedInCss(css: string): Set<string> {
  const names = new Set<string>();
  postcss.parse(css).walkDecls(/^--/, (decl) => {
    names.add(decl.prop.slice(2));
  });
  return names;
}

const tokenNames = () => definedInCss(readFileSync(join(src, "styles", "tokens.css"), "utf8"));

/** Each var() in one stylesheet that names nothing, as `<file>:<line> var(--x)`; and how many var() it checked. */
function scanCss(file: string, css: string, tokens: Set<string>): { undefined: string[]; checked: number } {
  const own = definedInCss(css);
  const out: string[] = [];
  let checked = 0;
  postcss.parse(css).walkDecls((decl) => {
    for (const m of decl.value.matchAll(VAR)) {
      checked++;
      if (!tokens.has(m[1]!) && !own.has(m[1]!)) out.push(`${file}:${decl.source?.start?.line ?? 0} var(--${m[1]})`);
    }
  });
  return { undefined: out, checked };
}

/** The same for a TypeScript source: var() inside any string, and "--x" keys of style objects as its own. */
function scanTs(file: string, text: string, tokens: Set<string>): string[] {
  const own = new Set([...text.matchAll(/["'`]--([\w-]+)["'`]\s*:/g)].map((m) => m[1]!));
  const lineOf = (index: number) => text.slice(0, index).split("\n").length;
  return [...text.matchAll(VAR)]
    .filter((m) => !tokens.has(m[1]!) && !own.has(m[1]!))
    .map((m) => `${file}:${lineOf(m.index!)} var(--${m[1]})`);
}

describe("every var() names a defined custom property", () => {
  it("everyVarInTheStylesheetsAndComponentsIsDefined", () => {
    const tokens = tokenNames();
    expect(tokens.has("label2") && tokens.has("text-lg") && tokens.has("space-4"), "tokens.css was read").toBe(true);

    const sheets = files(src, /\.css$/);
    let checked = 0;
    const missing: string[] = [];
    for (const path of sheets) {
      const result = scanCss(rel(path), readFileSync(path, "utf8"), tokens);
      checked += result.checked;
      missing.push(...result.undefined);
    }
    const sources = files(src, /\.(ts|tsx)$/)
      .map(rel)
      .filter((r) => !r.startsWith("src/styles/") && !/\.test\.(ts|tsx)$/.test(r));
    for (const r of sources) missing.push(...scanTs(r, readFileSync(join(web, r), "utf8"), tokens));

    expect(missing).toEqual([]);
    // The scan read what it claims to: globals.css and its uses.
    expect(sheets.map(rel)).toContain("src/app/globals.css");
    expect(checked).toBeGreaterThanOrEqual(10);
    expect(sources).toContain("src/app/layout.tsx");
  });

  it("aNameThatIsNotDefinedIsReported", () => {
    const tokens = tokenNames();
    expect(scanCss("X.css", ".x { color: var(--c-bg); border-top: 1px solid var(--sep) }", tokens).undefined).toEqual([
      "X.css:1 var(--c-bg)",
    ]);
    expect(scanCss("X.css", ".x { color: var(--x, var(--lable2)) }", tokens).undefined).toEqual(["X.css:1 var(--x)", "X.css:1 var(--lable2)"]);
    // A custom property the same file defines is defined.
    expect(scanCss("X.css", ":root { --tabbar-h: 64px } .x { height: var(--tabbar-h) }", tokens).undefined).toEqual([]);
    expect(scanTs("P.tsx", 'const s = { color: "var(--c-line)" };', tokens)).toEqual(["P.tsx:1 var(--c-line)"]);
    expect(scanTs("P.tsx", 'const s = { "--w": "4px", width: "var(--w)", color: "var(--label)" };', tokens)).toEqual([]);
  });
});
