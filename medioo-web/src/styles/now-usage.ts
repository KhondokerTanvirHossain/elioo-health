/**
 * Which selectors paint with the NOW (or THIS_WEEK) tokens, and which paint text with a token that is not a text
 * colour (plan W2a). Run over the built CSS by built/now-usage.built.test.ts, so what is judged is what ships.
 *
 * A use is any declaration whose value names `var(--now)`, `var(--now-*)` or `var(--on-now)` (fallbacks included).
 * Inside a `:root` rule every such use is reported, as an alias (`--danger: var(--now)`) would let any selector paint
 * the red under another name; a `:root` definition with a literal value names no var() and is never seen here.
 */
import postcss, { type Declaration, type Rule } from "postcss";
import type { TokenFile } from "./build-tokens";

// A component's own classes: the block, its elements (`__x`) and its modifiers (`--x`), then the end of the compound
// selector. The boundary is what stops `.now-bannerx` or `.status-chip--critique` from passing.
const BOUNDARY = "(?=$|[\\s.:#\\[,>+~)]|__|--)";
export const NOW_ALLOWED = new RegExp(`^\\.(now-banner|verdict-card--now|status-chip--crit-(high|low))${BOUNDARY}`);
export const WEEK_ALLOWED = new RegExp(`^\\.verdict-card--week${BOUNDARY}`);

const VAR = /var\(\s*--([\w-]+)/g;

const isPrefixToken = (name: string, prefix: string) =>
  name === prefix || name.startsWith(`${prefix}-`) || name === `on-${prefix}`;

const isRoot = (selector: string) => /^:root(?![\w-])/.test(selector.trim());

function selectorsOf(decl: Declaration): string[] {
  const parent = decl.parent;
  if (parent?.type === "rule") return (parent as Rule).selectors.map((s) => s.trim());
  if (parent?.type === "atrule") return [`@${(parent as postcss.AtRule).name}`];
  return ["(top level)"];
}

const varsIn = (value: string) => [...value.matchAll(VAR)].map((m) => m[1]!);

export function tokenUsageViolations(css: string, prefix: "now" | "week", allow: RegExp): string[] {
  const out = new Set<string>();
  postcss.parse(css).walkDecls((decl) => {
    const used = varsIn(decl.value).filter((n) => isPrefixToken(n, prefix));
    if (used.length === 0) return;
    const selectors = selectorsOf(decl);
    for (const token of used) {
      if (selectors.length > 0 && selectors.every(isRoot)) {
        out.add(`:root uses --${token} (as ${decl.prop})`);
        continue;
      }
      for (const selector of selectors) {
        if (!allow.test(selector)) out.add(`${selector} uses --${token}`);
      }
    }
  });
  return [...out];
}

export const nowUsageViolations = (css: string, allow: RegExp) => tokenUsageViolations(css, "now", allow);

const TEXT_PROPS = new Set(["color", "-webkit-text-fill-color"]);

/** `color:` (or anything inside a `::placeholder` rule) painted with a colour token whose role is not `text`. */
export function roleUsageViolations(css: string, tokens: TokenFile): string[] {
  const out = new Set<string>();
  postcss.parse(css).walkDecls((decl) => {
    const selectors = selectorsOf(decl).filter((s) => TEXT_PROPS.has(decl.prop) || /placeholder/i.test(s));
    if (selectors.length === 0) return;
    for (const name of varsIn(decl.value)) {
      const token = tokens.color[name];
      if (!token || token.role === "text") continue;
      for (const selector of selectors) out.add(`${selector} paints text with non-text --${name}`);
    }
  });
  return [...out];
}
