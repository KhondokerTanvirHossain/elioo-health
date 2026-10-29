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
// urgent-mark: the patient's red mark in the switcher and the chooser (src/components/safety/UrgentMark, plan W4).
// MonthCalendar's NOW and THIS_WEEK marks, in the cell and in the legend: PO ruling R2 (DR-48).
// A paper's NOW and THIS_WEEK tag on the papers list and the attention strip (safety/UrgencyTag): PO ruling P5.
export const NOW_ALLOWED = new RegExp(
  `^\\.(now-banner|urgent-mark|verdict-card--now|status-chip--crit-(high|low)|month-calendar__(alert|key)--now|urgency-tag--now)${BOUNDARY}`,
);
export const WEEK_ALLOWED = new RegExp(`^\\.(verdict-card--week|month-calendar__(alert|key)--week|urgency-tag--week)${BOUNDARY}`);

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

/** A control's wash, and the edge every control carries (Q1 (A), PR #120). */
export const CONTROL_WASH = "fill";
export const CONTROL_BOUNDARY = "boundary";

const WASH_PROP = /^background(-color|-image)?$/i;

// ---- Selectors: which rules reach the element a selector paints --------------------------------------------------

type Parsed = {
  text: string;
  /** Everything before the subject compound, combinators normalised ("" when the selector is one compound). */
  ancestors: string;
  /** The subject compound's simple selectors (`.field`, `input`, `[role=radiogroup]`, `:hover`); `*` is left out. */
  subject: Set<string>;
  pseudoElement: boolean;
  specificity: number;
};

/** Split a selector into compounds at its top-level combinators, keeping `:is(a, b)` and `[x~="y z"]` whole. */
function compoundsOf(selector: string): { compounds: string[]; combinators: string[] } {
  const compounds: string[] = [];
  const combinators: string[] = [];
  let current = "";
  let depth = 0;
  let quote: string | null = null;
  let pending: string | null = null;
  const flush = () => {
    if (current === "") return;
    if (compounds.length > 0) combinators.push(pending ?? " ");
    compounds.push(current);
    current = "";
    pending = null;
  };
  for (const ch of selector.trim()) {
    if (quote) {
      current += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") quote = ch;
    else if (ch === "(" || ch === "[") depth++;
    else if (ch === ")" || ch === "]") depth--;
    if (depth === 0 && (ch === ">" || ch === "+" || ch === "~") && !quote) {
      // `~=` inside an attribute is depth 1, so a `~` here is always the combinator.
      flush();
      pending = ch;
      continue;
    }
    if (depth === 0 && /\s/.test(ch)) {
      flush();
      continue;
    }
    current += ch;
  }
  flush();
  return { compounds, combinators };
}

const SIMPLE = /\.[\w-]+|#[\w-]+|\[[^\]]*\]|::?[\w-]+(?:\((?:[^()]|\([^()]*\))*\))?|\*|[a-zA-Z][\w-]*/g;
const LEGACY_PSEUDO_ELEMENT = /^:(before|after|first-line|first-letter)$/i;

function simplesOf(compound: string): string[] {
  return [...compound.matchAll(SIMPLE)]
    .map((m) => m[0])
    .filter((s) => s !== "*")
    .map((s) => (s.startsWith("[") ? s.replace(/["'\s]/g, "").toLowerCase() : /^[a-zA-Z]/.test(s) ? s.toLowerCase() : s));
}

/** Specificity as one number (ids, then classes, attributes and pseudo-classes, then types and pseudo-elements). */
function specificityOf(compounds: string[]): number {
  let a = 0;
  let b = 0;
  let c = 0;
  for (const simple of compounds.flatMap(simplesOf)) {
    if (simple.startsWith("#")) a++;
    else if (simple.startsWith("::") || LEGACY_PSEUDO_ELEMENT.test(simple)) c++;
    else if (/^:where\(/i.test(simple)) continue;
    else if (/^[.[:]/.test(simple)) b++;
    else c++;
  }
  return a * 10000 + b * 100 + c;
}

function parse(selector: string): Parsed {
  const { compounds, combinators } = compoundsOf(selector);
  const last = compounds[compounds.length - 1] ?? "";
  const subject = simplesOf(last);
  let ancestors = "";
  for (let i = 0; i < compounds.length - 1; i++) ancestors += `${compounds[i]}${combinators[i] === " " ? " " : ` ${combinators[i]} `}`;
  return {
    text: selector.trim(),
    ancestors: ancestors.trim(),
    subject: new Set(subject),
    pseudoElement: subject.some((s) => s.startsWith("::") || LEGACY_PSEUDO_ELEMENT.test(s)),
    specificity: specificityOf(compounds),
  };
}

const subset = (a: Set<string>, b: Set<string>) => [...a].every((x) => b.has(x));
const isStateSimple = (s: string) => s.startsWith(":") || s.startsWith("[");

/**
 * Every element `u` matches is matched by `s` too, so `s`'s declarations reach it: `s`'s subject is a subset of `u`'s
 * and `s` has no ancestors or the same ones. `.field` reaches `.field:hover` and `.form .field`; `.form` never
 * reaches `.form :is(input)`, which is another element.
 */
function reaches(s: Parsed, u: Parsed): boolean {
  if (s.pseudoElement || u.pseudoElement) return s.text === u.text;
  return (s.ancestors === "" || s.ancestors === u.ancestors) && subset(s.subject, u.subject);
}

/**
 * `r` may match an element `u` paints: its subject has every class, id and type of `u`'s subject (its pseudo-classes
 * and attributes are states, `.field:hover`, or variants, `.field.bare`), whatever its ancestors (`.form .field`).
 * A `u` whose subject is only states (`.seg :where(*)`) is matched on all of it.
 */
function mayMatch(r: Parsed, u: Parsed): boolean {
  if (r.pseudoElement || u.pseudoElement) return false;
  const identity = new Set([...u.subject].filter((s) => !isStateSimple(s)));
  return subset(identity.size > 0 ? identity : u.subject, r.subject);
}

// ---- Edges: what a declaration draws, and what it takes away ------------------------------------------------------

type Unit = "border-top" | "border-right" | "border-bottom" | "border-left" | "outline" | "box-shadow";
const BORDER_PROP = /^border(?:-(top|right|bottom|left|block|inline)(?:-(start|end))?)?(?:-(width|style|color))?$/i;
const OUTLINE_PROP = /^outline(?:-(width|style|color))?$/i;
const STYLES_DRAWN = /^(dotted|dashed|solid|double|groove|ridge|inset|outset|auto)$/i;
const STYLES_NONE = /^(none|hidden)$/i;
const WIDTH_WORD = /^(thin|medium|thick)$/i;
const LENGTH = /^([+-]?(?:\d+\.?\d*|\.\d+))([a-z%]*)$/i;

/** The value's top-level words (`var(--x, 1px)` is one word), and its top-level comma-separated parts. */
function wordsOf(value: string): string[] {
  return compoundsOf(value.replace(/,/g, " , ")).compounds.filter((w) => w !== ",");
}
function partsOf(value: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (const ch of value) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    if (ch === "," && depth === 0) {
      parts.push(current);
      current = "";
    } else current += ch;
  }
  parts.push(current);
  return parts.map((p) => p.trim()).filter((p) => p !== "");
}

const isZero = (word: string) => {
  const m = LENGTH.exec(word);
  return m !== null && Number(m[1]) === 0;
};
const isLength = (word: string) => LENGTH.test(word);
/** At least one CSS pixel. */
const atLeastOnePx = (word: string) => {
  const m = LENGTH.exec(word);
  if (!m) return false;
  const n = Number(m[1]);
  const unit = m[2]!.toLowerCase();
  if (unit === "px") return n >= 1;
  if (unit === "rem" || unit === "em") return n * 16 >= 1;
  return n > 0;
};

const SIDE_ORDER = ["border-top", "border-right", "border-bottom", "border-left"] as const;

/** The border edges a property addresses, in the order its 1–4 longhand values map onto them. */
function bordersOf(side: string | undefined, end: string | undefined): Unit[] {
  if (!side) return [...SIDE_ORDER];
  if (side === "block") return end === "start" ? ["border-top"] : end === "end" ? ["border-bottom"] : ["border-top", "border-bottom"];
  if (side === "inline") return end === "start" ? ["border-left"] : end === "end" ? ["border-right"] : ["border-left", "border-right"];
  return [`border-${side}` as Unit];
}

/** Spread 1–4 values over the edges (`0 0 1px` → top 0, right 0, bottom 1px, left 0). */
function perEdge(units: Unit[], words: string[]): Map<Unit, string> {
  const out = new Map<Unit, string>();
  if (units.length === 4) {
    const [t, r = t, b = t, l = r] = words;
    out.set("border-top", t!).set("border-right", r!).set("border-bottom", b!).set("border-left", l!);
  } else units.forEach((u, i) => out.set(u, words[Math.min(i, words.length - 1)]!));
  return out;
}

type Effect = { unit: Unit; colour?: boolean; width?: boolean; style?: boolean; removes?: boolean };

/**
 * What one declaration does to each edge: names the boundary in its colour, gives it a non-zero width, gives it a
 * visible style, or takes it away (a zero width, `none` or `hidden`, a shorthand with no style, `box-shadow: none`).
 * A box-shadow carries the boundary only as a ring: zero offsets, zero blur, a spread of at least 1px.
 */
function effectsOf(prop: string, value: string, namesBoundary: (v: string) => boolean): Effect[] {
  const border = BORDER_PROP.exec(prop);
  const outline = OUTLINE_PROP.exec(prop);
  if (border || outline) {
    const units: Unit[] = border ? bordersOf(border[1]?.toLowerCase(), border[2]?.toLowerCase()) : ["outline"];
    const part = (border ? border[3] : outline![1])?.toLowerCase();
    const words = wordsOf(value);
    if (part === undefined) {
      // A shorthand: one width, one style, one colour, the same for every edge it addresses.
      const style = words.find((w) => STYLES_DRAWN.test(w) || STYLES_NONE.test(w));
      const width = words.find((w) => WIDTH_WORD.test(w) || isLength(w));
      const colour = words.some((w) => w !== style && w !== width && namesBoundary(w));
      const drawnStyle = style !== undefined && STYLES_DRAWN.test(style);
      const drawnWidth = width !== undefined && !isZero(width);
      return units.map((unit) => ({
        unit,
        colour,
        width: drawnWidth,
        style: drawnStyle,
        removes: !drawnStyle || (width !== undefined && isZero(width)),
      }));
    }
    const each = outline ? new Map<Unit, string>([["outline", words[0] ?? ""]]) : perEdge(units, words);
    return [...each].map(([unit, word]) =>
      part === "color"
        ? { unit, colour: namesBoundary(word) }
        : part === "width"
          ? { unit, width: !isZero(word) && (WIDTH_WORD.test(word) || isLength(word)), removes: isZero(word) }
          : { unit, style: STYLES_DRAWN.test(word), removes: STYLES_NONE.test(word) },
    );
  }
  if (prop === "box-shadow") {
    if (/^none$/i.test(value.trim())) return [{ unit: "box-shadow", removes: true }];
    const ring = partsOf(value).some((shadow) => {
      const words = wordsOf(shadow).filter((w) => !/^inset$/i.test(w));
      if (!words.some(namesBoundary)) return false;
      const lengths = words.filter(isLength);
      return lengths.length === 4 && isZero(lengths[0]!) && isZero(lengths[1]!) && isZero(lengths[2]!) && atLeastOnePx(lengths[3]!);
    });
    return ring ? [{ unit: "box-shadow", colour: true, width: true, style: true }] : [];
  }
  return [];
}

// ---- Controls: the elements Q1 (A) is about -----------------------------------------------------------------------

/**
 * Input types whose edge the platform draws, or that are buttons: not a text field. The one list both Q1 (A) guards
 * read: NOT_A_FIELD here (the CSS scan) and FIELD_SELECTOR (catalog.spec.ts everyFieldAndTrackDrawsTheBoundary, the
 * rendered check), so the two judge the same elements (now-usage.fields.test.tsx compares them type by type).
 */
export const NON_FIELD_INPUT_TYPES = ["checkbox", "radio", "hidden", "range", "color", "file", "submit", "button", "reset", "image"] as const;
const NOT_A_FIELD = new RegExp(`^\\[type=(${NON_FIELD_INPUT_TYPES.join("|")})\\]$`);
/** The class a control's own element carries: `.field`, `.segmented`, `.otp-input`, `.phone-field__input`, `.seg__track`. */
export const CONTROL_CLASS = /^\.(field|segmented|[\w-]+-(input|segmented)|[\w-]+__(input|field|track|control))$/;
/** The elements that are controls by what they are, beside `input` (whose non-field types are excluded above). */
const CONTROL_ELEMENTS = ["textarea", "select", "[role=radiogroup]", "[role=tablist]"] as const;
const CONTROL_SIMPLE = new RegExp(`^(input|${CONTROL_ELEMENTS.map((s) => s.replace(/[[\]]/g, "\\$&")).join("|")})$`);
/** The same set as a DOM selector, for the rendered check: what the CSS scan calls a control, the browser check picks. */
export const FIELD_SELECTOR = [
  `input:not(${NON_FIELD_INPUT_TYPES.map((type) => `[type=${type}]`).join(", ")})`,
  ...CONTROL_ELEMENTS,
].join(", ");

function isControl(p: Parsed): boolean {
  if (p.pseudoElement || [...p.subject].some((s) => NOT_A_FIELD.test(s))) return false;
  return [...p.subject].some((s) => CONTROL_SIMPLE.test(s) || CONTROL_CLASS.test(s));
}

// ---- The check ----------------------------------------------------------------------------------------------------

type Edge = { colour: boolean; width: boolean; style: boolean; specificity: number };
type Removal = { selector: Parsed; units: Set<Unit>; text: string };
type Judged = { selector: Parsed; context: string; reason: string };

function edgesIn(css: string) {
  const root = postcss.parse(css);
  const aliases = aliasesIn(root);
  const names = (value: string, token: string): string[] =>
    varsIn(value).filter((v) => v === token || aliases(v).has(token));
  const painters: Judged[] = [];
  const controls: Judged[] = [];
  // context → selector text → (parsed, edge → what is drawn there)
  const carriers = new Map<string, Map<string, { selector: Parsed; edges: Map<Unit, Edge> }>>();
  const removals: Removal[] = [];
  const parsed = new Map<string, Parsed>();
  const parseOnce = (s: string) => parsed.get(s) ?? parsed.set(s, parse(s)).get(s)!;
  const seenControl = new Set<string>();
  root.walkDecls((decl) => {
    if (decl.prop.startsWith("--") || decl.parent?.type !== "rule" || isKeyframes(decl.parent.parent)) return;
    const selectors = (decl.parent as Rule).selectors.map((s) => s.trim()).filter((s) => !isRoot(s));
    const context = contextOf(decl);
    const prop = decl.prop.toLowerCase();
    for (const text of selectors) {
      const selector = parseOnce(text);
      if (isControl(selector) && !seenControl.has(`${context}\u0000${text}`)) {
        seenControl.add(`${context}\u0000${text}`);
        controls.push({ selector, context, reason: "is a control" });
      }
    }
    if (WASH_PROP.test(prop)) {
      for (const used of names(decl.value, CONTROL_WASH)) {
        const via = used === CONTROL_WASH ? "" : ` (via --${used})`;
        for (const text of selectors) painters.push({ selector: parseOnce(text), context, reason: `paints --${CONTROL_WASH}${via}` });
      }
    }
    const effects = effectsOf(prop, decl.value, (word) => names(word, CONTROL_BOUNDARY).length > 0);
    if (effects.length === 0) return;
    for (const text of selectors) {
      const selector = parseOnce(text);
      const byContext = carriers.get(context) ?? carriers.set(context, new Map()).get(context)!;
      const entry = byContext.get(text) ?? byContext.set(text, { selector, edges: new Map() }).get(text)!;
      for (const e of effects) {
        const edge = entry.edges.get(e.unit) ?? { colour: false, width: false, style: false, specificity: selector.specificity };
        edge.colour ||= e.colour ?? false;
        edge.width ||= e.width ?? false;
        edge.style ||= e.style ?? false;
        entry.edges.set(e.unit, edge);
      }
      const removed = new Set(effects.filter((e) => e.removes).map((e) => e.unit));
      if (removed.size > 0) removals.push({ selector, units: removed, text: `${decl.prop}: ${decl.value.trim()}` });
    }
  });

  /** The edges drawn in the boundary on the elements `u` matches, by the rules that reach them, and how specific. */
  const drawnOn = (targets: Parsed[], contexts: string[]): Map<Unit, number> => {
    const merged = new Map<Unit, Edge>();
    for (const context of new Set(contexts)) {
      for (const { selector, edges } of carriers.get(context)?.values() ?? []) {
        if (!targets.some((t) => reaches(selector, t))) continue;
        for (const [unit, edge] of edges) {
          const m = merged.get(unit) ?? { colour: false, width: false, style: false, specificity: 0 };
          m.colour ||= edge.colour;
          m.width ||= edge.width;
          m.style ||= edge.style;
          if (edge.colour) m.specificity = Math.max(m.specificity, edge.specificity);
          merged.set(unit, m);
        }
      }
    }
    return new Map([...merged].filter(([, e]) => e.colour && e.width && e.style).map(([u, e]) => [u, e.specificity]));
  };

  const judge = (items: Judged[], skipRemover: (r: Parsed) => boolean): string[] => {
    const out = new Set<string>();
    for (const { selector: u, context, reason } of items) {
      const atRest = drawnOn([u], [context, ""]);
      if (atRest.size === 0) {
        out.add(`${u.text} ${reason} and carries no --${CONTROL_BOUNDARY}`);
        continue;
      }
      const restRemovals = removals.filter((r) => reaches(r.selector, u));
      const candidates = removals.filter((r) => !skipRemover(r.selector) && (reaches(r.selector, u) || mayMatch(r.selector, u)));
      for (const r of candidates) {
        // In the state (or variant, or descendant) r selects, the edges drawn are those at rest plus r's own; each is
        // taken away by an at-least-as-specific removal at rest or in r.
        const drawn = drawnOn([u, r.selector], [context, ""]);
        const against = [...restRemovals, ...removals.filter((x) => x.selector.text === r.selector.text)];
        const left = [...drawn].filter(([unit, specificity]) => !against.some((x) => x.units.has(unit) && x.selector.specificity >= specificity));
        if (left.length === 0 && [...r.units].some((unit) => drawn.has(unit))) {
          out.add(`${r.selector.text} removes its --${CONTROL_BOUNDARY} (${r.text})`);
        }
      }
    }
    return [...out];
  };

  return { painters, controls, judge };
}

/**
 * Every selector that paints a control's wash (`--fill` in a background), directly or through a forwarding custom
 * property, and carries no `--boundary` drawn as an edge. An edge counts only when it is drawn: a border or outline
 * shorthand with a non-zero width and a visible style, a colour longhand together with a width and a style for the
 * same edge, or a box-shadow ring (offsets and blur 0, spread ≥ 1px). It counts from every rule that reaches the
 * element (`.field` reaches `.field:hover` and `.form .field`, never `.form :is(input)`), at the top level or inside
 * the same at-rule. Any rule that may match the same element, a state (`.field:hover`), a variant (`.field.bare`) or a
 * descendant use (`.form .field`), and takes every drawn edge away there (`border: none`, `border-width: 0`,
 * `box-shadow: none`), at least as specific as what drew it, is `<selector> removes its --boundary (<declaration>)`.
 * Not judged: source order, a later rule that recolours an edge, and a class that only markup puts on the element.
 */
export function fillBoundaryViolations(css: string): string[] {
  const { painters, judge } = edgesIn(css);
  return judge(painters, () => false);
}

/**
 * Every selector whose subject is a control (`input` other than a checkbox, radio or button type, `textarea`,
 * `select`, `[role=radiogroup]`, `[role=tablist]`, or a class matching CONTROL_CLASS) and carries no drawn
 * `--boundary`, whatever its background: Q1 (A) is about the control, not about one variable name. Removals are
 * judged as in fillBoundaryViolations.
 */
export function controlBoundaryViolations(css: string): string[] {
  const { controls, judge } = edgesIn(css);
  return judge(controls, (r) => [...r.subject].some((s) => NOT_A_FIELD.test(s)));
}

/** The selectors that paint `--fill` in a background, as fillBoundaryViolations judges them (the built test prints it). */
export function fillPainters(css: string): string[] {
  const { painters } = edgesIn(css);
  return [...new Set(painters.map((p) => `${p.selector.text}${p.reason.slice(`paints --${CONTROL_WASH}`.length)}`))];
}

/** The selectors controlBoundaryViolations judges as controls (the built test prints it). */
export function controlSelectors(css: string): string[] {
  const { controls } = edgesIn(css);
  return [...new Set(controls.map((c) => c.selector.text))];
}

/** The at-rules a declaration sits inside (`@media (min-width: 900px)`), outermost first; "" at the top level. */
function contextOf(node: postcss.Node): string {
  const chain: string[] = [];
  for (let p = node.parent; p && p.type !== "root"; p = p.parent as postcss.Container) {
    if (p.type === "atrule") chain.unshift(`@${(p as AtRule).name} ${(p as AtRule).params.trim()}`);
  }
  return chain.join(" ");
}
