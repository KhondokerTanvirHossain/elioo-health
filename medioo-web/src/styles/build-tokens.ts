/**
 * The token renderer (plan W2a). `medioo.tokens.json` is the one source of every colour, font and size the web app
 * paints with; this module resolves it and writes `tokens.css`, the only stylesheet allowed to hold a colour literal
 * (stylelint). TypeScript outside src/styles/ and outside test files is scanned by src/lint/colour-literal.test.ts;
 * src/styles/ itself (this renderer, the checkers, the JSON) and the tests hold literals on purpose. Derived values (`mix`, `alpha`) are resolved here and written as literals, so the CSS never needs color-mix().
 *
 * tokens.test.ts renders the CSS on every run and byte-compares it with the committed file.
 */

export const THEMES = ["light", "dark"] as const;
export const LAYOUTS = ["phone", "desktop"] as const;
export type Theme = (typeof THEMES)[number];
export type Layout = (typeof LAYOUTS)[number];

/** A hex `#rrggbb`, an `rgba(r, g, b, a)`, a mix toward a hex (sRGB interpolation), or a token at an alpha. */
export type ColourValue = string | { mix: [string, string, number] } | { alpha: [string, number] };

export type Role = "surface" | "text" | "non-text" | "fill";

export interface ColourToken {
  light: ColourValue;
  dark: ColourValue;
  /** Applies at `@media (min-width: 900px)`; phone values apply below. */
  desktop?: { light?: ColourValue; dark?: ColourValue };
  role: Role;
  /**
   * The surfaces this token is checked against (contrast.test.ts): 4.5:1 for text, 3:1 for non-text and fill.
   * `"every-surface"` stands for every `role: "surface"` token without a `notEverySurface`, derived at check time.
   */
  on?: string[];
  /** Only for the tokens in contrast.ts's EXEMPTABLE: the WCAG clause that does not require a contrast, and why. */
  exempt?: string;
  /** Only on a surface: why `every-surface` does not include it (paper: only paper-ink is painted on it). */
  notEverySurface?: string;
  note?: string;
}

export interface TokenFile {
  _meta: Record<string, string>;
  color: Record<string, ColourToken>;
  /** `--font-<name>` */
  font: Record<string, string>;
  /** `--text-<name>` */
  text: Record<string, string>;
  /** `--radius-<name>` */
  radius: Record<string, string>;
  /** `--space-<name>` */
  space: Record<string, string>;
  /** `--<name>`, unprefixed */
  layout: Record<string, string>;
  /** A prototype custom property → the token that replaces it, or `dropped: <why>`. */
  _renamed: Record<string, string>;
  /** A prototype literal, or `<file>:<theme>:--<property>: <value>`, → the token chosen and why. */
  _absorbed: Record<string, string>;
}

type Rgba = { r: number; g: number; b: number; a: number };

export function parseColour(value: string): Rgba {
  const v = value.trim().toLowerCase();
  const hex = /^#([0-9a-f]{3,8})$/.exec(v);
  if (hex) {
    let h = hex[1]!;
    if (h.length === 3 || h.length === 4) h = [...h].map((c) => c + c).join("");
    if (h.length !== 6 && h.length !== 8) throw new Error(`not a colour: ${value}`);
    const n = (i: number) => parseInt(h.slice(i, i + 2), 16);
    return { r: n(0), g: n(2), b: n(4), a: h.length === 8 ? Math.round((n(6) / 255) * 1000) / 1000 : 1 };
  }
  const fn = /^rgba?\(([^)]*)\)$/.exec(v);
  if (fn) {
    const parts = fn[1]!.split(/[\s,/]+/).filter(Boolean).map(Number);
    if (parts.length < 3 || parts.some((p) => Number.isNaN(p))) throw new Error(`not a colour: ${value}`);
    return { r: parts[0]!, g: parts[1]!, b: parts[2]!, a: parts[3] ?? 1 };
  }
  throw new Error(`not a colour: ${value}`);
}

const hex2 = (n: number) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, "0");

/** `#rrggbb` when opaque, `rgba(r, g, b, a)` otherwise. */
export function formatColour({ r, g, b, a }: Rgba): string {
  if (a >= 1) return `#${hex2(r)}${hex2(g)}${hex2(b)}`;
  return `rgba(${Math.round(r)}, ${Math.round(g)}, ${Math.round(b)}, ${Number(a.toFixed(3))})`;
}

/**
 * One spelling per value, so a prototype's `#fff`, `rgba(0,0,0,.36)` or `"SF Pro Text",Roboto` compares equal to the
 * token's `#ffffff`, `rgba(0, 0, 0, 0.36)` or `"SF Pro Text", Roboto`. Anything that is not a colour has its
 * whitespace and comma spacing normalised and is lower-cased.
 */
export function normaliseColour(value: string): string {
  const v = value.trim().toLowerCase().replace(/\s+/g, " ");
  if (/^#[0-9a-f]{3,8}$/.test(v) || /^rgba?\([^)]*\)$/.test(v)) {
    try {
      return formatColour(parseColour(v));
    } catch {
      return v;
    }
  }
  return v.replace(/\s*,\s*/g, ", ");
}

/** The value a token is written with in one theme and layout: the desktop override where there is one. */
export function valueFor(token: ColourToken, theme: Theme, layout: Layout): ColourValue {
  return (layout === "desktop" ? token.desktop?.[theme] : undefined) ?? token[theme];
}

function resolveColour(src: TokenFile, name: string, theme: Theme, layout: Layout, stack: string[] = []): string {
  const token = src.color[name];
  if (!token) throw new Error(`unknown colour token: ${name}${stack.length ? ` (from ${stack.join(" → ")})` : ""}`);
  if (stack.includes(name)) throw new Error(`token cycle: ${[...stack, name].join(" → ")}`);
  const value = valueFor(token, theme, layout);
  if (typeof value === "string") return value;
  const next = [...stack, name];
  if ("mix" in value) {
    const [ref, toward, amount] = value.mix;
    const from = parseColour(resolveColour(src, ref, theme, layout, next));
    const to = parseColour(toward);
    if (from.a !== 1 || to.a !== 1) throw new Error(`${name}: mix needs two opaque colours`);
    const lerp = (x: number, y: number) => x + (y - x) * amount;
    return formatColour({ r: lerp(from.r, to.r), g: lerp(from.g, to.g), b: lerp(from.b, to.b), a: 1 });
  }
  const [ref, alpha] = value.alpha;
  const base = parseColour(resolveColour(src, ref, theme, layout, next));
  if (base.a !== 1) throw new Error(`${name}: alpha needs an opaque colour`);
  return formatColour({ ...base, a: alpha });
}

/** Every non-colour token, by its CSS name without the leading `--`. Identical in every theme and layout. */
function otherTokens(src: TokenFile): [string, string][] {
  return [
    ...Object.entries(src.font).map(([k, v]): [string, string] => [`font-${k}`, v]),
    ...Object.entries(src.text).map(([k, v]): [string, string] => [`text-${k}`, v]),
    ...Object.entries(src.radius).map(([k, v]): [string, string] => [`radius-${k}`, v]),
    ...Object.entries(src.space).map(([k, v]): [string, string] => [`space-${k}`, v]),
    ...Object.entries(src.layout),
  ];
}

/** Every token's value per theme and layout, by its CSS name without the leading `--`; colours are literals. */
export function resolveTokens(src: TokenFile): Record<Theme, Record<Layout, Record<string, string>>> {
  const out = {} as Record<Theme, Record<Layout, Record<string, string>>>;
  for (const theme of THEMES) {
    out[theme] = {} as Record<Layout, Record<string, string>>;
    for (const layout of LAYOUTS) {
      const values: Record<string, string> = {};
      for (const name of Object.keys(src.color)) values[name] = resolveColour(src, name, theme, layout);
      for (const [name, value] of otherTokens(src)) values[name] = value;
      out[theme][layout] = values;
    }
  }
  return out;
}

const HEADER = `/* GENERATED from medioo.tokens.json by build-tokens.ts (plan W2a). Do not edit: change the JSON and run
   src/styles/tokens.test.ts, which writes build/tokens.css and prints the cp line. This is the only stylesheet that
   may hold a colour literal (stylelint); TypeScript outside src/styles/ and test files is scanned by
   colour-literal.test.ts. Phone values apply below 900 px; the desktop blocks override them from 900 px up. */
`;

/** tokens.css: light phone on :root, then the desktop overrides, then both dark selectors, each with its overrides. */
export function renderTokensCss(src: TokenFile): string {
  const resolved = resolveTokens(src);
  const colours = Object.keys(src.color);
  const decls = (names: string[], values: Record<string, string>, indent: string) =>
    names.map((n) => `${indent}--${n}: ${values[n]};\n`).join("");
  const overrides = (theme: Theme) =>
    colours.filter((n) => resolved[theme].desktop[n] !== resolved[theme].phone[n]);

  let css = HEADER;
  css += ":root {\n  color-scheme: light;\n";
  css += decls(colours, resolved.light.phone, "  ");
  css += otherTokens(src).map(([n, v]) => `  --${n}: ${v};\n`).join("");
  css += "}\n";
  css += "@media (min-width: 900px) {\n  :root {\n" + decls(overrides("light"), resolved.light.desktop, "    ") + "  }\n}\n";

  const dark = resolved.dark;
  const darkBody = (indent: string) => `${indent}color-scheme: dark;\n` + decls(colours, dark.phone, indent);
  css += '@media (prefers-color-scheme: dark) {\n  :root:not([data-theme="light"]) {\n' + darkBody("    ") + "  }\n}\n";
  css +=
    '@media (prefers-color-scheme: dark) and (min-width: 900px) {\n  :root:not([data-theme="light"]) {\n' +
    decls(overrides("dark"), dark.desktop, "    ") +
    "  }\n}\n";
  css += ':root[data-theme="dark"] {\n' + darkBody("  ") + "}\n";
  css += '@media (min-width: 900px) {\n  :root[data-theme="dark"] {\n' + decls(overrides("dark"), dark.desktop, "    ") + "  }\n}\n";
  return css;
}
