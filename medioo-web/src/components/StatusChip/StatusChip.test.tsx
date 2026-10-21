import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import postcss, { type Rule } from "postcss";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import { NOW_ALLOWED } from "@/styles/now-usage";
import { CHIP_KEY, CHIP_KINDS, CHIP_SHAPE, chipKindOf, StatusChip, type ChipKind } from "./StatusChip";

afterEach(cleanup);

const web = join(__dirname, "..", "..", "..");
const documentsDir = join(web, "fixtures", "api", "documents");
const healthDir = join(web, "fixtures", "api", "health");

type Value = { id: string; display_kind: "inside" | "unknown" | "outside" | "critical"; side: "high" | "low" | null; chip_key: string };

/** Every value object of every paper fixture B2's contract test wrote, with the file it came from. */
function fixtureValues(): Array<{ file: string; value: Value }> {
  const out: Array<{ file: string; value: Value }> = [];
  for (const file of readdirSync(documentsDir).filter((f) => f.endsWith(".json")).sort()) {
    const body = JSON.parse(readFileSync(join(documentsDir, file), "utf8")).body as { values?: Value[] };
    for (const value of body.values ?? []) out.push({ file, value });
  }
  return out;
}

/** A value Java sent beside a chip key, normalised to the two inputs chipKindOf takes, with where it came from. */
type Sent = { where: string; kind: string; side: string | null; chip_key: string };

/** Every paper value (`display_kind`) of fixtures/api/documents. */
function paperValues(): Sent[] {
  return fixtureValues().map(({ file, value }) => ({
    where: `${file} ${value.id}`,
    kind: value.display_kind,
    side: value.side,
    chip_key: value.chip_key,
  }));
}

/**
 * Every lab point (`kind`) of fixtures/api/health: each object anywhere in a body that carries a `chip_key`, so
 * series[].points[] and bp.points[].systolic/diastolic are both read and a later shape cannot be skipped. An object
 * with a chip key but no `kind` is reported, not silently dropped.
 */
function healthPoints(): Sent[] {
  const out: Sent[] = [];
  const walk = (file: string, node: unknown) => {
    if (Array.isArray(node)) return node.forEach((n) => walk(file, n));
    if (node === null || typeof node !== "object") return;
    const o = node as Record<string, unknown>;
    if (typeof o.chip_key === "string") {
      out.push({
        where: `${file} ${String(o.observation_id ?? "?")}`,
        kind: typeof o.kind === "string" ? o.kind : "<no kind>",
        side: typeof o.side === "string" ? o.side : null,
        chip_key: o.chip_key,
      });
    }
    for (const v of Object.values(o)) walk(file, v);
  };
  for (const file of readdirSync(healthDir).filter((f) => f.endsWith(".json")).sort()) {
    walk(file, JSON.parse(readFileSync(join(healthDir, file), "utf8")).body);
  }
  return out;
}

/** The chip keys the generated copy holds (read as a file: copy is imported only through @/copy). */
function copyChipKeys(): string[] {
  const copy = JSON.parse(readFileSync(join(web, "src", "copy", "generated", "copy.bn.json"), "utf8")) as Record<string, unknown>;
  return Object.keys(copy).filter((k) => k.startsWith("value.chip."));
}

const chip = (kind: ChipKind, lang: Lang) => {
  const el = render(<StatusChip kind={kind} lang={lang} />).container.firstElementChild;
  expect(el, `StatusChip ${kind} renders`).not.toBeNull();
  return el!;
};

/** NOW_ALLOWED matches a selector; a class list matches when one of its classes, as a selector, does. */
const isNowClass = (el: Element) => [...el.classList].some((c) => NOW_ALLOWED.test(`.${c}`));

describe("StatusChip", () => {
  it("everyKindIsColourShapeAndWord", () => {
    const missing: string[] = [];
    for (const lang of ["bn", "en"] as const) {
      for (const kind of CHIP_KINDS) {
        const el = chip(kind, lang);
        if (!el.classList.contains(`status-chip--${kind}`)) missing.push(`${kind} (${lang}): no class status-chip--${kind}`);
        const svg = el.querySelector('svg[aria-hidden="true"]');
        if (!svg) missing.push(`${kind} (${lang}): no svg[aria-hidden="true"]`);
        else if (svg.getAttribute("data-shape") !== CHIP_SHAPE[kind]) {
          missing.push(`${kind} (${lang}): shape ${svg.getAttribute("data-shape")}, expected ${CHIP_SHAPE[kind]}`);
        } else if (svg.children.length === 0) missing.push(`${kind} (${lang}): the svg draws nothing`);
        if (el.textContent !== t(lang, CHIP_KEY[kind])) missing.push(`${kind} (${lang}): text "${el.textContent}"`);
        cleanup();
      }
    }
    expect(missing).toEqual([]);
    // Six kinds, six different shapes: the shape alone tells them apart.
    expect(new Set(CHIP_KINDS.map((k) => CHIP_SHAPE[k])).size).toBe(CHIP_KINDS.length);
  });

  it("theSixKindsAreTheKindsTheApiSends", () => {
    const byKey = new Map(Object.entries(CHIP_KEY).map(([kind, key]) => [key as string, kind as ChipKind]));
    const fromFixtures = [...paperValues(), ...healthPoints()].map((v) => v.chip_key);
    const fromCopy = copyChipKeys();
    // Guards: both sources were read, so the set below is derived and not vacuous.
    expect(fromFixtures.length, "chip keys in fixtures/api/documents").toBeGreaterThan(0);
    expect(fromCopy.length, "value.chip.* keys in the generated copy").toBeGreaterThan(0);

    const keys = [...new Set([...fromFixtures, ...fromCopy])].sort();
    expect(keys.filter((k) => !byKey.has(k)), "chip keys no kind maps to").toEqual([]);
    expect(new Set(keys.map((k) => byKey.get(k)))).toEqual(new Set(CHIP_KINDS));
  });

  it("redIfAndOnlyIfCritical", () => {
    const papers = paperValues();
    const points = healthPoints();
    // Guards: the walks saw a critical and an outside paper value, and lab points including an outside/high one, so
    // both directions below are exercised on what Java actually wrote.
    expect(papers.some((v) => v.kind === "critical"), "a critical value in fixtures/api/documents").toBe(true);
    expect(papers.some((v) => v.kind === "outside"), "an outside value in fixtures/api/documents").toBe(true);
    expect(points.length, "lab points in fixtures/api/health").toBeGreaterThan(0);
    expect(points.some((v) => v.kind === "outside" && v.side === "high"), "an outside/high lab point").toBe(true);
    const values = [...papers, ...points];

    // Every kind and side the API can send is walked too, read off the chip keys Java's copy carries
    // (`value.chip.<kind>[.<side>]`), plus the sideless outside and critical PaperDisplay.kindOf turns into unknown.
    const vocabulary: Array<{ where: string; kind: string; side: string | null }> = copyChipKeys().map((key) => {
      const [kind, side] = key.slice("value.chip.".length).split(".") as [string, string | undefined];
      return { where: `copy ${key}`, kind, side: side ?? null };
    });
    vocabulary.push({ where: "sideless", kind: "outside", side: null }, { where: "sideless", kind: "critical", side: null });
    expect(vocabulary.some((v) => v.kind === "outside" && v.side === "high"), "outside/high is in the vocabulary").toBe(true);

    const wrong: string[] = [];
    const judge = (where: string, kind: string, side: string | null) => {
      const el = chip(chipKindOf(kind, side), "bn");
      const red = isNowClass(el);
      const critical = kind === "critical" && (side === "high" || side === "low");
      if (red !== critical) wrong.push(`${where}: ${kind}/${side} red=${red}`);
      cleanup();
    };
    for (const { where, kind, side } of values) judge(where, kind, side);
    for (const { where, kind, side } of vocabulary) judge(where, kind, side);
    expect(wrong).toEqual([]);
  });

  it("theChipSaysTheKeyJavaSent", () => {
    // The kind chipKindOf picks names exactly the chip key Java wrote beside the same value: PaperDisplay.chipKey on a
    // paper (`display_kind`), the B6 health JSON on a lab or blood-pressure point (`kind`).
    const papers = paperValues();
    const points = healthPoints();
    expect(papers.length, "values in fixtures/api/documents").toBeGreaterThan(0);
    expect(points.length, "points in fixtures/api/health").toBeGreaterThan(0);
    const wrong = [...papers, ...points]
      .filter((v) => CHIP_KEY[chipKindOf(v.kind, v.side)] !== v.chip_key)
      .map((v) => `${v.where}: ${v.kind}/${v.side} -> ${CHIP_KEY[chipKindOf(v.kind, v.side)]} != ${v.chip_key}`);
    expect(wrong).toEqual([]);
  });

  it("aSidelessOutsideOrCriticalIsUnknown", () => {
    expect(chipKindOf("critical", null)).toBe("unk");
    expect(chipKindOf("outside", null)).toBe("unk");
    expect(chipKindOf("critical", "low")).toBe("crit-low");
    expect(chipKindOf("outside", "high")).toBe("out-high");
    expect(chipKindOf("inside", null)).toBe("in");
    expect(chipKindOf("unknown", null)).toBe("unk");
  });

  it("everyKindPaintsItsOwnInkOnItsOwnWash", () => {
    // The colour in "colour + shape + word", read off what the class paints and not off the class being present.
    const expected: Record<ChipKind, { color: string; background: string }> = {
      in: { color: "--tint-ink", background: "--tint-soft" },
      "out-high": { color: "--label", background: "--unk-soft" },
      "out-low": { color: "--label", background: "--unk-soft" },
      "crit-high": { color: "--now-ink", background: "--now-soft" },
      "crit-low": { color: "--now-ink", background: "--now-soft" },
      unk: { color: "--label2", background: "--unk-soft" },
    };
    const painted = new Map<string, { color?: string; background?: string }>();
    postcss.parse(readFileSync(join(__dirname, "StatusChip.css"), "utf8")).walkRules((rule: Rule) => {
      if (rule.parent?.type !== "root") return; // a kind's paint is unconditional, never only inside a media block
      for (const selector of rule.selectors) {
        const m = /^\.status-chip--([\w-]+)$/.exec(selector.trim());
        if (!m) continue;
        const entry = painted.get(m[1]!) ?? {};
        rule.walkDecls((decl) => {
          if (decl.prop === "color") entry.color = decl.value.trim();
          if (decl.prop === "background" || decl.prop === "background-color") entry.background = decl.value.trim();
        });
        painted.set(m[1]!, entry);
      }
    });
    const wrong: string[] = [];
    for (const kind of CHIP_KINDS) {
      const got = painted.get(kind);
      if (!got) {
        wrong.push(`.status-chip--${kind}: no rule`);
        continue;
      }
      if (got.color !== `var(${expected[kind].color})`) wrong.push(`.status-chip--${kind}: color ${got.color ?? "unset"}`);
      if (got.background !== `var(${expected[kind].background})`) {
        wrong.push(`.status-chip--${kind}: background ${got.background ?? "unset"}`);
      }
    }
    expect(wrong).toEqual([]);
  });

  it("anUnrecognisedKindIsUnknownNeverADirection", () => {
    // Fail closed, as PaperDisplay.kindOf does: a kind this table does not know (a later additive verdict, a casing
    // drift, a lab point spelled differently) is unknown. It never claims a direction, and never loses or gains red.
    expect(chipKindOf("CRITICAL", "high")).toBe("unk");
    expect(chipKindOf("borderline", "low")).toBe("unk");
    expect(chipKindOf("", "high")).toBe("unk");
    expect(chipKindOf("Outside", "low")).toBe("unk");
    expect(chipKindOf("inside", "sideways")).toBe("in");
    expect(chipKindOf("critical", "HIGH")).toBe("unk");
  });
});
