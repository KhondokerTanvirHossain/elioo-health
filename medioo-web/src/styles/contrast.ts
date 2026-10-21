/**
 * WCAG 2.x contrast over the resolved tokens (plan W2a). The pairs checked are the ones each token declares in its
 * `on` list; a token that paints text or meaning and declares none is itself a failure (coverageProblems), so a check
 * can never be skipped by leaving the list out.
 */
import { LAYOUTS, THEMES, formatColour, parseColour, resolveTokens, type Role, type TokenFile } from "./build-tokens";

/** The only tokens allowed to opt out of a contrast check: decorative under WCAG 1.4.11. */
export const EXEMPTABLE = ["sep", "hover", "selbg", "scrim"] as const;

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

/**
 * Every declared pair of every token with `role`, in every theme and layout: `theme/layout fg on bg = ratio`. A
 * failure is a pair under `minimum`. A translucent surface is composited over bg of the same theme and layout.
 */
export function contrastChecks(src: TokenFile, role: Extract<Role, "text" | "non-text">, minimum: number) {
  const resolved = resolveTokens(src);
  const pairs: string[] = [];
  const failures: string[] = [];
  for (const theme of THEMES) {
    for (const layout of LAYOUTS) {
      const values = resolved[theme][layout];
      const base = values.bg!;
      for (const [name, token] of Object.entries(src.color)) {
        if (token.role !== role || token.exempt) continue;
        for (const surface of token.on ?? []) {
          const under = values[surface];
          if (under === undefined) {
            failures.push(`${theme}/${layout} ${name} on ${surface}: no such token`);
            continue;
          }
          const opaque = composite(under, base);
          const ratio = contrastRatio(values[name]!, opaque);
          const line = `${theme}/${layout} ${name} on ${surface} = ${ratio.toFixed(2)}`;
          pairs.push(line);
          if (ratio < minimum) failures.push(line);
        }
      }
    }
  }
  return { pairs, failures };
}

/** A text or non-text token with no `on` and no `exempt`, or an `exempt` on a token that may not have one. */
export function coverageProblems(src: TokenFile): string[] {
  const problems: string[] = [];
  for (const [name, token] of Object.entries(src.color)) {
    if (token.exempt !== undefined && !(EXEMPTABLE as readonly string[]).includes(name)) {
      problems.push(`${name}: exempt is allowed only on ${EXEMPTABLE.join(", ")}`);
      continue;
    }
    if (token.role !== "text" && token.role !== "non-text") continue;
    const checked = (token.on?.length ?? 0) > 0;
    const exempt = typeof token.exempt === "string" && token.exempt.trim().length > 0;
    if (!checked && !exempt) problems.push(`${name}: a ${token.role} token with no surface to check it on and no exemption`);
  }
  return problems;
}
