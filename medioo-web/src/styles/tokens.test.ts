import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  LAYOUTS,
  THEMES,
  normaliseColour,
  parseColour,
  renderTokensCss,
  resolveTokens,
  valueFor,
  type TokenFile,
} from "./build-tokens";
import json from "./medioo.tokens.json";

// The one token source (plan W2a). Every test below reads medioo.tokens.json and the two approved prototypes; nothing
// here restates a colour the file already holds, so a reconciliation that drifts from the prototypes is caught by
// reading the prototypes, not by a list the author remembered.
const src = json as unknown as TokenFile;
const here = dirname(fileURLToPath(import.meta.url));
const web = join(here, "..", "..");
const approved = join(web, "..", "docs", "design", "2026-10-21-approved");
const PROTOTYPES = ["phone.html", "desktop.html"] as const;
const layoutOf = (file: string) => (file === "desktop.html" ? "desktop" : "phone");

const copy = (): TokenFile => JSON.parse(JSON.stringify(src)) as TokenFile;
const colourNames = (file: TokenFile) => Object.keys(file.color);
const isDerivedFrom = (value: unknown, from: string[]) => {
  if (typeof value !== "object" || value === null) return false;
  const op = (value as Record<string, unknown>).mix ?? (value as Record<string, unknown>).alpha;
  return Array.isArray(op) && from.includes(op[0] as string);
};
const isDerivedFromNow = (value: unknown) => isDerivedFrom(value, ["now"]);

/** Every :root block of a prototype, with its theme: dark when under prefers-color-scheme: dark or [data-theme="dark"]. */
function rootBlocks(html: string): { theme: "light" | "dark"; props: [string, string][] }[] {
  const blocks: { theme: "light" | "dark"; props: [string, string][] }[] = [];
  const re = /:root([^{]*)\{([^}]*)\}/g;
  for (let m = re.exec(html); m; m = re.exec(html)) {
    const before = html.slice(Math.max(0, m.index - 60), m.index);
    const dark = /data-theme="dark"/.test(m[1] ?? "") || /prefers-color-scheme:\s*dark\)\s*\{\s*$/.test(before);
    const props: [string, string][] = [];
    for (const decl of (m[2] ?? "").split(";")) {
      const at = decl.indexOf(":");
      const prop = decl.slice(0, at).trim();
      if (!prop.startsWith("--")) continue;
      props.push([prop, decl.slice(at + 1).trim()]);
    }
    blocks.push({ theme: dark ? "dark" : "light", props });
  }
  return blocks;
}

const stripRootBlocks = (html: string) => html.replace(/:root[^{]*\{[^}]*\}/g, "");

/** Hue in degrees and chroma (max − min, 0..1) of a resolved colour, with its alpha. */
function hueOf(value: string): { hue: number; chroma: number; alpha: number } {
  const { r, g, b, a } = parseColour(value);
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let hue = 0;
  if (d > 0) {
    if (max === r) hue = 60 * (((g - b) / d) % 6);
    else if (max === g) hue = 60 * ((b - r) / d + 2);
    else hue = 60 * ((r - g) / d + 4);
  }
  return { hue: (hue + 360) % 360, chroma: d / 255, alpha: a };
}
// Chroma, not HSL saturation: a near-white like paper #fffdf8 has HSL saturation 1 and no colour to speak of.
const visible = (c: { chroma: number; alpha: number }) => c.chroma > 0.25 && c.alpha > 0.05;
/** The NOW red's family: hue 345°–20°, anything an eye would call red. */
const inRedBand = (value: string) => {
  const c = hueOf(value);
  return visible(c) && (c.hue >= 345 || c.hue <= 20);
};
/** THIS_WEEK's amber: hue above 20° up to 50°. */
const inAmberBand = (value: string) => {
  const c = hueOf(value);
  return visible(c) && c.hue > 20 && c.hue <= 50;
};

/**
 * Every colour token whose resolved value, in any theme and layout, falls in `band` but is neither one of `owners`
 * nor written (in that theme and layout, desktop override included) as a mix or alpha of one of them.
 */
function huedOutside(file: TokenFile, band: (value: string) => boolean, owners: string[]): string[] {
  const resolved = resolveTokens(file);
  const offenders = new Set<string>();
  for (const theme of THEMES) {
    for (const layout of LAYOUTS) {
      for (const name of colourNames(file)) {
        if (owners.includes(name) || !band(resolved[theme][layout][name]!)) continue;
        if (!isDerivedFrom(valueFor(file.color[name]!, theme, layout), owners)) offenders.add(name);
      }
    }
  }
  return [...offenders];
}

describe("medioo.tokens.json and tokens.css", () => {
  it("theCommittedTokensCssIsTheRendererOutput", () => {
    const committed = readFileSync(join(here, "tokens.css"), "utf8");
    const rendered = renderTokensCss(src);
    if (committed !== rendered) {
      mkdirSync(join(web, "build"), { recursive: true });
      writeFileSync(join(web, "build", "tokens.css"), rendered);
    }
    expect(committed === rendered, "regenerate: cp medioo-web/build/tokens.css medioo-web/src/styles/tokens.css").toBe(true);
  });

  it("theNowRedIsOneTokenAndEveryNowTokenDerivesFromIt", () => {
    const literalNow = Object.entries(src.color)
      .filter(([name]) => name.startsWith("now") || name === "on-now")
      .filter(([, token]) => [token.light, token.dark].every((v) => typeof v === "string"))
      .map(([name]) => name);
    expect(literalNow.sort()).toEqual(["now", "on-now"]);
    const derived = Object.entries(src.color).filter(([name]) => name.startsWith("now-"));
    expect(derived.length, "now-ink and now-soft exist").toBeGreaterThanOrEqual(2);
    for (const [name, token] of derived) {
      expect(isDerivedFromNow(token.light), `${name}.light derives from now`).toBe(true);
      expect(isDerivedFromNow(token.dark), `${name}.dark derives from now`).toBe(true);
      expect(token.desktop, `${name} has no desktop override of its own`).toBeUndefined();
    }

    // The derived direction: change the one red and every now-* follows.
    const blue = copy();
    blue.color.now!.light = "#0000ff";
    const before = resolveTokens(src);
    const after = resolveTokens(blue);
    for (const layout of LAYOUTS) {
      for (const [name] of derived) {
        expect(after.light[layout][name], `${layout} ${name} follows now`).not.toBe(before.light[layout][name]);
      }
    }
    expect(after.light.phone["now-ink"]).toBe("#0000c7");
  });

  it("decisionSevenIsTheNowRed", () => {
    const resolved = resolveTokens(src);
    for (const layout of LAYOUTS) {
      expect(resolved.light[layout].now, `light/${layout}`).toBe("#d12c24");
      expect(resolved.dark[layout].now, `dark/${layout}`).toBe("#d93229");
    }
    expect(src.color.now!.note).toMatch(/Part B decision 7/);
  });

  it("everyLiteralInThePrototypesIsAbsorbed", () => {
    const resolved = resolveTokens(src);
    const values = new Set<string>();
    for (const theme of THEMES) for (const layout of LAYOUTS) for (const v of Object.values(resolved[theme][layout])) values.add(normaliseColour(v));
    const absorbed = new Set(Object.keys(src._absorbed).map(normaliseColour));
    const collected: string[] = [];
    const missing: string[] = [];
    for (const file of PROTOTYPES) {
      const body = stripRootBlocks(readFileSync(join(approved, file), "utf8"));
      // color-mix() derives a colour as surely as a literal does (a wash, a ring), so it is collected whole.
      for (const literal of body.match(/color-mix\((?:[^()]|\([^()]*\))*\)|#[0-9a-fA-F]{3,8}\b|rgba?\([^)]*\)/g) ?? []) {
        collected.push(literal);
        const n = normaliseColour(literal);
        if (!values.has(n) && !absorbed.has(n)) missing.push(`${file}: ${literal}`);
      }
    }
    // The reader really read the prototypes.
    expect(collected.length).toBeGreaterThan(0);
    expect(collected).toContain("#25d366");
    expect(collected).toContain("color-mix(in srgb, var(--tint) 60%, transparent)");
    expect([...new Set(missing)]).toEqual([]);
  });

  it("everyPrototypeRootPropertyIsReconciled", () => {
    const resolved = resolveTokens(src);
    const tokenNames = new Set(Object.keys(resolved.light.phone));
    const renamed = src._renamed;
    const absorbed = new Set(Object.keys(src._absorbed).map((k) => k.replace(/: (.*)$/, (_, v: string) => `: ${normaliseColour(v)}`)));
    const problems: string[] = [];
    const seen: string[] = [];
    for (const file of PROTOTYPES) {
      const blocks = rootBlocks(readFileSync(join(approved, file), "utf8"));
      const light = new Map(blocks.filter((b) => b.theme === "light").flatMap((b) => b.props));
      let count = 0;
      for (const block of blocks) {
        const own = new Map(block.props);
        const lookup = (prop: string): string | undefined => own.get(prop) ?? light.get(prop);
        const deref = (value: string, depth = 0): string =>
          depth > 5 ? value : value.replace(/var\(\s*(--[\w-]+)\s*\)/g, (whole, p: string) => deref(lookup(p) ?? whole, depth + 1));
        for (const [prop, raw] of block.props) {
          count++;
          seen.push(`${file}:${block.theme}:${prop}: ${raw}`);
          const bare = prop.slice(2);
          const target = renamed[prop] ?? bare;
          if (!tokenNames.has(bare) && !(prop in renamed)) {
            problems.push(`${file}: ${prop} is neither a token nor in _renamed`);
            continue;
          }
          if (target.startsWith("dropped:")) continue;
          const token = resolved[block.theme][layoutOf(file)][target];
          if (token === undefined) {
            problems.push(`${file}: ${prop} is renamed to ${target}, which is not a token`);
            continue;
          }
          if (normaliseColour(deref(raw)) === normaliseColour(token)) continue;
          const key = `${file}:${block.theme}:${prop}: ${normaliseColour(raw)}`;
          if (!absorbed.has(key)) problems.push(`${key} (token ${target} is ${token}) has no _absorbed entry`);
        }
      }
      expect(count, `${file}: at least 40 :root definitions were read`).toBeGreaterThanOrEqual(40);
    }
    // The parse read the blocks it claims to: the prototypes' fill name and their dark ink.
    expect(seen.some((s) => s.includes(":--now-fill:"))).toBe(true);
    expect(seen.some((s) => s.endsWith("--now: #ff8a80"))).toBe(true);
    expect(problems).toEqual([]);
  });

  it("noSecondRedUnderAnotherName", () => {
    expect(huedOutside(src, inRedBand, ["now"])).toEqual([]);
    // One hex digit off is the same red to an eye, and the prototypes' own reds are reds.
    for (const red of ["#d12c25", "#e0352b", "#ff3b30", "#ff2d55"]) {
      const planted = copy();
      planted.color.danger = { light: red, dark: red, role: "fill" };
      expect(huedOutside(planted, inRedBand, ["now"]), red).toEqual(["danger"]);
    }
    // A desktop override is a value too: a now-derived phone value with a literal red on desktop is a second red.
    const desktopOnly = copy();
    desktopOnly.color.crit = { light: { alpha: ["now", 0.5] }, dark: { alpha: ["now", 0.5] }, desktop: { light: "#d12c24" }, role: "fill" };
    expect(huedOutside(desktopOnly, inRedBand, ["now"])).toEqual(["crit"]);
    // THIS_WEEK's amber is not a red.
    expect(huedOutside(src, inRedBand, ["now"])).not.toContain("week");
    const resolved = resolveTokens(src);
    expect(inRedBand(resolved.light.phone.now!)).toBe(true);
    expect(inRedBand(resolved.light.phone.week!)).toBe(false);
  });

  it("noSecondAmberUnderAnotherName", () => {
    expect(huedOutside(src, inAmberBand, ["week", "week-soft"])).toEqual([]);
    const resolved = resolveTokens(src);
    for (const theme of THEMES) expect(inAmberBand(resolved[theme].phone.week!), `${theme} week is amber`).toBe(true);
    const planted = copy();
    planted.color.alert = { light: "#f29100", dark: "#ff9500", role: "fill" };
    expect(huedOutside(planted, inAmberBand, ["week", "week-soft"])).toEqual(["alert"]);
  });

  it("everyFontSizeTokenIsAtLeastTwelvePixels", () => {
    const sizes = [...renderTokensCss(src).matchAll(/--text-[\w-]+:\s*([\d.]+)px;/g)].map((m) => Number(m[1]));
    expect(sizes.length).toBeGreaterThanOrEqual(4);
    expect(sizes.filter((s) => s < 12)).toEqual([]);
    // Every --text-* is in px, so none escaped the parse above.
    const all = [...renderTokensCss(src).matchAll(/--text-[\w-]+:/g)].length;
    expect(all).toBe(sizes.length);
  });

  it("bothThemesAndBothLayoutsAreEmitted", () => {
    const css = renderTokensCss(src);
    const block = (opening: string): string => {
      const at = css.indexOf(opening);
      expect(at, `the CSS has ${opening}`).toBeGreaterThanOrEqual(0);
      return css.slice(at + opening.length, css.indexOf("}", at));
    };
    expect(css.startsWith(":root {") || /\n:root \{/.test(css)).toBe(true);
    const mediaDark = block('@media (prefers-color-scheme: dark) {\n  :root:not([data-theme="light"]) {');
    const dataDark = block(':root[data-theme="dark"] {');
    for (const name of colourNames(src)) {
      expect(mediaDark, `the prefers-color-scheme dark block defines --${name}`).toContain(`--${name}:`);
      expect(dataDark, `the [data-theme="dark"] block defines --${name}`).toContain(`--${name}:`);
    }
    expect(css).toContain("@media (min-width: 900px) {\n  :root {");
    expect(css).toContain('@media (prefers-color-scheme: dark) and (min-width: 900px) {\n  :root:not([data-theme="light"]) {');
    expect(css).toContain('@media (min-width: 900px) {\n  :root[data-theme="dark"] {');
  });
});
