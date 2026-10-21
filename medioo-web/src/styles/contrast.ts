/**
 * WCAG 2.x contrast over the resolved tokens (plan W2a). The pairs checked are the ones each token declares in its
 * `on` list; a text, non-text or fill token that declares none is itself a failure (coverageProblems), so a check can
 * never be skipped by leaving the list out. Which check runs is decided by the token's role, and the role is pinned
 * to the plan's table by contrast.test.ts, so relabelling a token is not a way round it either.
 */
import { LAYOUTS, THEMES, formatColour, parseColour, resolveTokens, type Role, type TokenFile } from "./build-tokens";

/** The only tokens allowed to opt out of a contrast check: decorative under WCAG 1.4.11. */
export const EXEMPTABLE = ["sep", "hover", "selbg", "scrim"] as const;

/** In an `on` list: every `role: "surface"` token, except one whose `notEverySurface` says why it is not. */
export const EVERY_SURFACE = "every-surface";

/** The roles whose tokens are contrast-checked against the surfaces in their `on`. */
const CHECKED: readonly Role[] = ["text", "non-text", "fill"];

const channel = (c: number) => {
  const s = c / 255;
  return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
};

function luminance(colour: string): number {
  const { r, g, b, a } = parseColour(colour);
  if (a < 1) throw new Error(`luminance needs an opaque colour, got ${colour}`);
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** Source-over in sRGB: `over` painted on `under`. Opaque when `under` is opaque. */
export function composite(over: string, under: string): string {
  const o = parseColour(over);
  const u = parseColour(under);
  const a = o.a + u.a * (1 - o.a);
  if (a === 0) return formatColour({ r: 0, g: 0, b: 0, a: 0 });
  const mix = (x: number, y: number) => (x * o.a + y * u.a * (1 - o.a)) / a;
  return formatColour({ r: mix(o.r, u.r), g: mix(o.g, u.g), b: mix(o.b, u.b), a });
}

/** The WCAG contrast ratio; a translucent foreground is composited over the (opaque) background first. */
export function contrastRatio(fg: string, bg: string): number {
  const ground = parseColour(bg);
  if (ground.a < 1) throw new Error(`contrastRatio needs an opaque background, got ${bg}`);
  const l1 = luminance(composite(fg, bg));
  const l2 = luminance(bg);
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

/** What `every-surface` stands for: derived from the file, so a surface added later is checked without an edit. */
export function everySurface(src: TokenFile): string[] {
  return Object.entries(src.color)
    .filter(([, token]) => token.role === "surface" && !token.notEverySurface)
    .map(([name]) => name);
}

const surfacesFor = (src: TokenFile, on: string[] | undefined): string[] => [
  ...new Set((on ?? []).flatMap((s) => (s === EVERY_SURFACE ? everySurface(src) : [s]))),
];

/**
 * Every declared pair of every token with `role`, in every theme and layout: `theme/layout fg on bg = ratio`. A
 * failure is a pair under `minimum`. A translucent surface is composited over every opaque surface of the same theme
 * and layout it can sit on (the every-surface set), and the lowest ratio is the one reported, as
 * `theme/layout fg on bg over underlay = ratio`.
 */
export function contrastChecks(src: TokenFile, role: Extract<Role, "text" | "non-text" | "fill">, minimum: number) {
  const resolved = resolveTokens(src);
  const pairs: string[] = [];
  const failures: string[] = [];
  const underlays = everySurface(src);
  for (const theme of THEMES) {
    for (const layout of LAYOUTS) {
      const values = resolved[theme][layout];
      const opaque = underlays.filter((u) => values[u] !== undefined && parseColour(values[u]!).a >= 1);
      for (const [name, token] of Object.entries(src.color)) {
        if (token.role !== role || token.exempt) continue;
        for (const surface of surfacesFor(src, token.on)) {
          const under = values[surface];
          if (under === undefined) {
            failures.push(`${theme}/${layout} ${name} on ${surface}: no such token`);
            continue;
          }
          let line: string;
          let ratio: number;
          if (parseColour(under).a >= 1) {
            ratio = contrastRatio(values[name]!, under);
            line = `${theme}/${layout} ${name} on ${surface} = ${ratio.toFixed(2)}`;
          } else {
            if (opaque.length === 0) throw new Error(`${theme}/${layout}: no opaque surface to composite ${surface} over`);
            const each = opaque.map((u) => ({ u, r: contrastRatio(values[name]!, composite(under, values[u]!)) }));
            const worst = each.reduce((a, b) => (b.r < a.r ? b : a));
            ratio = worst.r;
            line = `${theme}/${layout} ${name} on ${surface} over ${worst.u} = ${ratio.toFixed(2)}`;
          }
          pairs.push(line);
          if (ratio < minimum) failures.push(line);
        }
      }
    }
  }
  return { pairs, failures };
}

/** A text, non-text or fill token with no `on` and no `exempt`, or an `exempt` on a token that may not have one. */
export function coverageProblems(src: TokenFile): string[] {
  const problems: string[] = [];
  if (everySurface(src).length === 0) problems.push(`${EVERY_SURFACE} stands for no token: every on-list using it is empty`);
  for (const [name, token] of Object.entries(src.color)) {
    if (token.exempt !== undefined && !(EXEMPTABLE as readonly string[]).includes(name)) {
      problems.push(`${name}: exempt is allowed only on ${EXEMPTABLE.join(", ")}`);
      continue;
    }
    if (token.notEverySurface !== undefined && token.role !== "surface") {
      problems.push(`${name}: notEverySurface is only for a surface`);
      continue;
    }
    if (!CHECKED.includes(token.role)) continue;
    const checked = surfacesFor(src, token.on).length > 0;
    const exempt = typeof token.exempt === "string" && token.exempt.trim().length > 0;
    if (!checked && !exempt) problems.push(`${name}: a ${token.role} token with no surface to check it on and no exemption`);
  }
  return problems;
}
