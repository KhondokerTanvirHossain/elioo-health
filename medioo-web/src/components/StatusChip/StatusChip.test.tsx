import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import { NOW_ALLOWED } from "@/styles/now-usage";
import { CHIP_KEY, CHIP_KINDS, CHIP_SHAPE, chipKindOf, StatusChip, type ChipKind } from "./StatusChip";

afterEach(cleanup);

const web = join(__dirname, "..", "..", "..");
const documentsDir = join(web, "fixtures", "api", "documents");

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
    const fromFixtures = fixtureValues().map(({ value }) => value.chip_key);
    const fromCopy = copyChipKeys();
    // Guards: both sources were read, so the set below is derived and not vacuous.
    expect(fromFixtures.length, "chip keys in fixtures/api/documents").toBeGreaterThan(0);
    expect(fromCopy.length, "value.chip.* keys in the generated copy").toBeGreaterThan(0);

    const keys = [...new Set([...fromFixtures, ...fromCopy])].sort();
    expect(keys.filter((k) => !byKey.has(k)), "chip keys no kind maps to").toEqual([]);
    expect(new Set(keys.map((k) => byKey.get(k)))).toEqual(new Set(CHIP_KINDS));
  });

  it("redIfAndOnlyIfCritical", () => {
    const values = fixtureValues();
    // Guard: the walk saw both a critical and an outside value, so both directions below are exercised.
    expect(values.some(({ value }) => value.display_kind === "critical"), "a critical value in the fixtures").toBe(true);
    expect(values.some(({ value }) => value.display_kind === "outside"), "an outside value in the fixtures").toBe(true);

    // The fixtures hold only the values B2's cases happen to have (no outside/high today), so every kind and side the
    // API can send is walked too, read off the chip keys Java's copy carries (`value.chip.<kind>[.<side>]`), plus the
    // sideless outside and critical PaperDisplay.kindOf already turns into unknown.
    const vocabulary: Array<{ where: string; kind: Value["display_kind"]; side: Value["side"] }> = copyChipKeys().map((key) => {
      const [kind, side] = key.slice("value.chip.".length).split(".") as [Value["display_kind"], "high" | "low" | undefined];
      return { where: `copy ${key}`, kind, side: side ?? null };
    });
    vocabulary.push({ where: "sideless", kind: "outside", side: null }, { where: "sideless", kind: "critical", side: null });
    expect(vocabulary.some((v) => v.kind === "outside" && v.side === "high"), "outside/high is in the vocabulary").toBe(true);

    const wrong: string[] = [];
    const judge = (where: string, kind: Value["display_kind"], side: Value["side"]) => {
      const el = chip(chipKindOf(kind, side), "bn");
      const red = isNowClass(el);
      const critical = kind === "critical" && side !== null;
      if (red !== critical) wrong.push(`${where}: ${kind}/${side} red=${red}`);
      cleanup();
    };
    for (const { file, value } of values) judge(`${file} ${value.id}`, value.display_kind, value.side);
    for (const { where, kind, side } of vocabulary) judge(where, kind, side);
    expect(wrong).toEqual([]);
  });

  it("theChipSaysTheKeyJavaSent", () => {
    // The kind chipKindOf picks names exactly the chip key PaperDisplay.chipKey wrote beside the same value.
    const values = fixtureValues();
    expect(values.length).toBeGreaterThan(0);
    const wrong = values
      .filter(({ value }) => CHIP_KEY[chipKindOf(value.display_kind, value.side)] !== value.chip_key)
      .map(({ file, value }) => `${file} ${value.id}: ${CHIP_KEY[chipKindOf(value.display_kind, value.side)]} != ${value.chip_key}`);
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
});
