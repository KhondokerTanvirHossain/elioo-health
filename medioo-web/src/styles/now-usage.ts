/**
 * Which selectors paint with the NOW (or THIS_WEEK) tokens, and which paint text with a token that is not a text
 * colour (plan W2a). Run over the built CSS by built/now-usage.built.test.ts, so what is judged is what ships.
 *
 * A use is any declaration whose value names `var(--now)`, `var(--now-*)` or `var(--on-now)` (fallbacks included).
 * Inside a `:root` rule every such use is reported, as an alias (`--danger: var(--now)`) would let any selector paint
 * the red under another name; a `:root` definition with a literal value names no var() and is never seen here.
 *
 * A selector may use them only when the element it paints is inside the allowed component: its leftmost compound
 * starts with an allowed class, every class in it belongs to that class's BEM block, and it has no sibling
 * combinator (`+`, `~`) and no `:has()`, either of which makes the subject an element outside the component.
 */
import postcss, { type AtRule, type Declaration, type Rule } from "postcss";
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

const isKeyframes = (node: postcss.Container | postcss.Document | undefined): node is AtRule =>
  node?.type === "atrule" && /keyframes$/i.test((node as AtRule).name);

function selectorsOf(decl: Declaration): string[] {
  const parent = decl.parent;
  if (parent?.type === "rule") {
    if (isKeyframes(parent.parent)) return [`@keyframes ${(parent.parent as AtRule).params.trim()}`];
    return (parent as Rule).selectors.map((s) => s.trim());
  }
  if (parent?.type === "atrule") return [`@${(parent as AtRule).name}`];
  return ["(top level)"];
}

const varsIn = (value: string) => [...value.matchAll(VAR)].map((m) => m[1]!);

/** The selector with attribute values, strings and :nth-*() arguments blanked, so only real combinators are left. */
const skeleton = (selector: string) =>
  selector
    .replace(/"[^"]*"|'[^']*'/g, '""')
    .replace(/\[[^\]]*\]/g, "[]")
    .replace(/:nth-[\w-]+\([^)]*\)/gi, ":nth()");

/** The BEM block of a class: `verdict-card--now` → `verdict-card`, `now-banner__call` → `now-banner`. */
const blockOf = (cls: string) => cls.split(/__|--/)[0]!;

/** Why a rule selector may not use the tokens, or null when it may. */
function selectorRefusal(selector: string, allow: RegExp): string | null {
  const s = skeleton(selector.trim());
  if (/:has\(/i.test(s)) return ":has()";
  if (/[+~]/.test(s)) return "a sibling combinator";
  if (!allow.test(selector.trim())) return "not the component";
  const classes = [...s.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map((m) => m[1]!);
  const block = blockOf(classes[0]!);
  const own = (c: string) => c === block || c.startsWith(`${block}__`) || c.startsWith(`${block}--`);
  return classes.every(own) ? null : "a class of another component";
}

/** A keyframes name belongs to a component when it is an allowed class, or one followed by `-<suffix>`. */
function keyframesAllowed(name: string, allow: RegExp): boolean {
  const parts = name.split("-");
  for (let i = parts.length; i > 0; i--) if (allow.test(`.${parts.slice(0, i).join("-")}`)) return true;
  return false;
}

export function tokenUsageViolations(css: string, prefix: "now" | "week", allow: RegExp): string[] {
  const out = new Set<string>();
  const root = postcss.parse(css);
  // Keyframes that use the tokens, by name → the tokens they use.
  const frames = new Map<string, Set<string>>();
  root.walkDecls((decl) => {
    const used = varsIn(decl.value).filter((n) => isPrefixToken(n, prefix));
    if (used.length === 0) return;
    const frame = decl.parent?.type === "rule" && isKeyframes(decl.parent.parent) ? (decl.parent.parent as AtRule).params.trim() : null;
    if (frame !== null) {
      const set = frames.get(frame) ?? new Set<string>();
      for (const t of used) set.add(t);
      frames.set(frame, set);
      if (!keyframesAllowed(frame, allow)) for (const t of used) out.add(`@keyframes ${frame} uses --${t}`);
      return;
    }
    const selectors = selectorsOf(decl);
    for (const token of used) {
      if (selectors.length > 0 && selectors.every(isRoot)) {
        out.add(`:root uses --${token} (as ${decl.prop})`);
        continue;
      }
      for (const selector of selectors) if (selectorRefusal(selector, allow)) out.add(`${selector} uses --${token}`);
    }
  });
  if (frames.size > 0) {
    root.walkDecls(/^(-webkit-)?animation(-name)?$/i, (decl) => {
      if (decl.parent?.type !== "rule" || isKeyframes(decl.parent.parent)) return;
      const words = new Set(decl.value.split(/[\s,]+/));
      for (const [frame, used] of frames) {
        if (!words.has(frame)) continue;
        for (const selector of selectorsOf(decl)) {
          if (!selectorRefusal(selector, allow)) continue;
          for (const t of used) out.add(`${selector} uses --${t} (through @keyframes ${frame})`);
        }
      }
    });
  }
  return [...out];
}

export const nowUsageViolations = (css: string, allow: RegExp) => tokenUsageViolations(css, "now", allow);

// Properties that paint text (or the caret, or a text decoration), whatever element they are on.
const TEXT_PROPS = new Set([
  "color",
  "-webkit-text-fill-color",
  "caret-color",
  "text-decoration",
  "text-decoration-color",
  "-webkit-text-stroke",
  "-webkit-text-stroke-color",
  "text-emphasis",
  "text-emphasis-color",
]);
// Properties that paint text when the subject is SVG text.
const SVG_TEXT_PROPS = new Set(["fill", "stroke"]);
const SVG_TEXT = /^(text|tspan|textpath)(?![\w-])/i;
// Properties that draw something that has to be seen against what is behind it: a border, an outline, a glyph.
const INDICATOR_PROP =
  /^(border(-(top|right|bottom|left|block|inline)(-(start|end))?)?(-color)?|outline(-color)?|column-rule(-color)?|stroke|fill)$/i;

/** The subject compound of a selector (after its last combinator), without pseudo-elements. */
const subjectOf = (selector: string) => {
  const compounds = skeleton(selector).trim().split(/\s*[>+~]\s*|\s+/);
  return compounds[compounds.length - 1] ?? "";
};

/** Custom property → every custom property it forwards, transitively, from every rule in the CSS. */
function aliasesIn(root: postcss.Root): (name: string) => Set<string> {
  const direct = new Map<string, Set<string>>();
  root.walkDecls(/^--/, (decl) => {
    const set = direct.get(decl.prop.slice(2)) ?? new Set<string>();
    for (const v of varsIn(decl.value)) set.add(v);
    direct.set(decl.prop.slice(2), set);
  });
  return (name) => {
    const seen = new Set<string>();
    const walk = (n: string) => {
      for (const next of direct.get(n) ?? []) {
        if (seen.has(next)) continue;
        seen.add(next);
        walk(next);
      }
    };
    walk(name);
    return seen;
  };
}

/**
 * Text painted with a colour token whose role is not `text` (in a text property, anything inside a `::placeholder`
 * rule, or fill/stroke on SVG text), and a `surface` token drawing a border, an outline or a glyph. A custom property
 * that forwards a token (`--hint: var(--label3)`) is that token wherever it is used.
 */
export function roleUsageViolations(css: string, tokens: TokenFile): string[] {
  const out = new Set<string>();
  const root = postcss.parse(css);
  const aliases = aliasesIn(root);
  const resolve = (name: string): [string, string | null][] => [
    [name, null],
    ...[...aliases(name)].map((t): [string, string | null] => [t, name]),
  ];
  const via = (alias: string | null) => (alias ? ` (via --${alias})` : "");
  root.walkDecls((decl) => {
    if (decl.prop.startsWith("--")) return;
    const prop = decl.prop.toLowerCase();
    const all = selectorsOf(decl);
    const text = all.filter(
      (s) => TEXT_PROPS.has(prop) || /placeholder/i.test(s) || (SVG_TEXT_PROPS.has(prop) && SVG_TEXT.test(subjectOf(s))),
    );
    const indicator = INDICATOR_PROP.test(prop) ? all.filter((s) => !text.includes(s)) : [];
    if (text.length === 0 && indicator.length === 0) return;
    for (const used of varsIn(decl.value)) {
      for (const [name, alias] of resolve(used)) {
        const token = tokens.color[name];
        if (!token) continue;
        if (token.role !== "text") for (const s of text) out.add(`${s} paints text with non-text --${name}${via(alias)}`);
        if (token.role === "surface") for (const s of indicator) out.add(`${s} draws an indicator with surface --${name}${via(alias)}`);
      }
    }
  });
  return [...out];
}

/** A control's wash, and the edge every control painted with it carries (Q1 (A), PR #120). */
export const CONTROL_WASH = "fill";
export const CONTROL_BOUNDARY = "boundary";

const WASH_PROP = /^background(-color|-image)?$/i;
const SIDES = "(-(top|right|bottom|left|block|inline)(-(start|end))?)?";
// Where a boundary is carried: a border, an outline or a box-shadow ring (the usual inset 0 0 0 1px).
const CARRIER_PROP = new RegExp(`^(border${SIDES}(-color)?|outline(-color)?|box-shadow)$`, "i");
// What takes a carried boundary away again: a border, outline or shadow set to none, hidden or a zero width.
const REMOVER_PROP = new RegExp(`^(border${SIDES}(-(width|style))?|outline(-(width|style))?|box-shadow)$`, "i");
const REMOVED = /^(none|hidden|0[a-z%]*)$/i;
const familyOf = (prop: string) => (/^border/i.test(prop) ? "border" : /^outline/i.test(prop) ? "outline" : "box-shadow");

/** The at-rules a declaration sits inside (`@media (min-width: 900px)`), outermost first; "" at the top level. */
function contextOf(node: postcss.Node): string {
  const chain: string[] = [];
  for (let p = node.parent; p && p.type !== "root"; p = p.parent as postcss.Container) {
    if (p.type === "atrule") chain.unshift(`@${(p as AtRule).name} ${(p as AtRule).params.trim()}`);
  }
  return chain.join(" ");
}

/** The selector without the pseudo-classes and attribute selectors at its end: `.field:hover` → `.field`. */
function baseOf(selector: string): string {
  let s = selector.trim();
  for (;;) {
    const next = s.replace(/(?<!:)(:[\w-]+(\((?:[^()]|\([^()]*\))*\))?|\[[^\]]*\])$/, "").trim();
    if (next === s || next === "" || /[\s>+~]$/.test(next)) return s;
    s = next;
  }
}

/**
 * Every selector that paints a control's wash (`--fill` in a background) and carries no `--boundary` in a border, an
 * outline or a box-shadow, on itself or on the selector it is a state of (`.field:hover` is `.field`), at the top level
 * or inside the same at-rule; and every declaration on such a selector that takes a carried boundary away again
 * (`border: none`, `border-width: 0`) when nothing else carries it. A forwarding custom property is the token it
 * forwards. A later rule that recolours the border is not judged here.
 */
export function fillBoundaryViolations(css: string): string[] {
  const out = new Set<string>();
  const root = postcss.parse(css);
  const aliases = aliasesIn(root);
  const names = (value: string, token: string): string[] =>
    varsIn(value).filter((v) => v === token || aliases(v).has(token));
  const key = (context: string, selector: string) => `${context}\u0000${selector}`;
  const uses: { selector: string; context: string; via: string | null }[] = [];
  const carriers = new Map<string, Set<string>>();
  const removals = new Map<string, { family: string; text: string }[]>();
  root.walkDecls((decl) => {
    if (decl.prop.startsWith("--") || decl.parent?.type !== "rule" || isKeyframes(decl.parent.parent)) return;
    const selectors = (decl.parent as Rule).selectors.map((s) => s.trim());
    const context = contextOf(decl);
    const prop = decl.prop.toLowerCase();
    if (WASH_PROP.test(prop)) {
      for (const used of names(decl.value, CONTROL_WASH)) {
        for (const selector of selectors) uses.push({ selector, context, via: used === CONTROL_WASH ? null : used });
      }
    }
    if (CARRIER_PROP.test(prop) && names(decl.value, CONTROL_BOUNDARY).length > 0) {
      for (const selector of selectors) {
        const set = carriers.get(key(context, selector)) ?? new Set<string>();
        set.add(familyOf(prop));
        carriers.set(key(context, selector), set);
      }
    }
    if (REMOVER_PROP.test(prop) && REMOVED.test(decl.value.trim())) {
      for (const selector of selectors) {
        const list = removals.get(selector) ?? [];
        list.push({ family: familyOf(prop), text: `${decl.prop}: ${decl.value.trim()}` });
        removals.set(selector, list);
      }
    }
  });
  for (const { selector, context, via } of uses) {
    const own = [...new Set([selector, baseOf(selector)])];
    const carried = new Set<string>();
    for (const s of own) {
      for (const c of new Set([context, ""])) for (const f of carriers.get(key(c, s)) ?? []) carried.add(f);
    }
    if (carried.size === 0) {
      out.add(`${selector} paints --${CONTROL_WASH}${via ? ` (via --${via})` : ""} and carries no --${CONTROL_BOUNDARY}`);
      continue;
    }
    const removed = own.flatMap((s) => removals.get(s) ?? []).filter((r) => carried.has(r.family));
    if ([...carried].every((f) => removed.some((r) => r.family === f))) {
      for (const r of removed) out.add(`${selector} removes its --${CONTROL_BOUNDARY} (${r.text})`);
    }
  }
  return [...out];
}
