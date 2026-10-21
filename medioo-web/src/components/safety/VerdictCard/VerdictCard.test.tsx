import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import { isReadingPaper, type PaperJson, type PaperResultJson } from "@/api/types";
import { VerdictCard } from "./VerdictCard";

afterEach(cleanup);

/*
 * Every case here is a fixture B2's PaperApiContractTest wrote from Java's PaperJson, so the card is judged on what
 * Java actually sends. types.test.ts holds the fixtures to PaperJson; here a 200 body is a paper.
 */
const documentsDir = join(__dirname, "..", "..", "..", "..", "fixtures", "api", "documents");

type Fixture = { file: string; status: number; body: PaperJson };

function fixtures(): Fixture[] {
  return readdirSync(documentsDir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((file) => {
      const json = JSON.parse(readFileSync(join(documentsDir, file), "utf8")) as { status: number; body: PaperJson };
      return { file, status: json.status, body: json.body };
    });
}

/** Every paper fixture (a 200 body), by file name. */
function papers(): Fixture[] {
  return fixtures().filter((f) => f.status === 200);
}

function paper(file: string): PaperJson {
  const found = papers().find((f) => f.file === file);
  if (!found) throw new Error(`no paper fixture ${file} in fixtures/api/documents`);
  return structuredClone(found.body);
}

/** A finished paper fixture (DONE, NEEDS_RETAKE, FAILED), typed as one. */
function result(file: string): PaperResultJson {
  const p = paper(file);
  if (isReadingPaper(p)) throw new Error(`${file} is a paper still being read`);
  return p;
}

const card = (p: PaperJson, lang: Lang = "bn") => {
  const el = render(<VerdictCard paper={p} lang={lang} />).container.firstElementChild;
  if (!el) throw new Error("VerdictCard rendered nothing");
  return el as HTMLElement;
};

const level = (p: PaperJson) => ("banner" in p && p.banner ? p.banner.level : null);

describe("VerdictCard", () => {
  it("wordingOnlyWhenReleased", () => {
    const released = result("routine-released.json");
    expect(released.wording, "the released fixture carries wording").toBeTruthy();
    for (const lang of ["bn", "en"] as const) {
      const el = card(released, lang);
      expect(el.querySelector(".verdict-card__wording")?.textContent, `routine-released (${lang})`).toBe(released.wording);
      cleanup();
    }

    // Every paper whose gate is not RELEASED shows no wording element, whatever its body holds.
    const shown: string[] = [];
    for (const { file, body } of papers()) {
      if ("gate" in body && body.gate === "RELEASED") continue;
      // The dangerous direction: a held paper that somehow carries wording must still not show it.
      const held = structuredClone(body) as PaperJson & { wording?: string };
      held.wording = "a held explanation";
      const el = card(held);
      if (el.querySelector(".verdict-card__wording")) shown.push(file);
      if (el.textContent?.includes("a held explanation")) shown.push(`${file} (as text)`);
      cleanup();
    }
    expect(shown, "papers not RELEASED that show wording").toEqual([]);
    // Guard: the held cases include this-week-pending, the one PENDING fixture.
    expect(papers().some(({ file, body }) => file === "this-week-pending.json" && "gate" in body && body.gate === "PENDING")).toBe(true);
  });

  it("noNineNineNineAbroad", () => {
    const tel = (el: HTMLElement) => [...el.querySelectorAll<HTMLAnchorElement>('a[href^="tel:"]')];
    const abroad = tel(card(paper("now-abroad.json")));
    expect(abroad.length, `expected 0 tel: links, found ${abroad.length}`).toBe(0);
    cleanup();
    const bd = tel(card(paper("now-bd.json")));
    expect(bd.map((a) => a.getAttribute("href"))).toEqual(["tel:999"]);
    expect(bd[0]!.classList.contains("verdict-card__call")).toBe(true);
    expect(bd[0]!.textContent).toBe(t("bn", "now.call999"));
    cleanup();

    // Derived over every paper: a tel: link exactly when Java set now.call_999.
    const wrong: string[] = [];
    for (const { file, body } of papers()) {
      const want = "now" in body && body.now?.call_999 === true ? 1 : 0;
      const got = tel(card(body)).length;
      if (got !== want) wrong.push(`${file}: expected ${want} tel: links, found ${got}`);
      cleanup();
    }
    expect(wrong).toEqual([]);
  });

  it("routineHasNoBand", () => {
    const el = card(paper("routine-released.json"));
    expect(el.classList.contains("verdict-card--now")).toBe(false);
    expect(el.classList.contains("verdict-card--week")).toBe(false);
    expect(el.querySelector(".verdict-card--now, .verdict-card--week, .verdict-card__band")).toBeNull();
  });

  it("aNowCardCarriesNotDoctor", () => {
    for (const file of ["now-bd.json", "now-abroad.json"]) {
      for (const lang of ["bn", "en"] as const) {
        const el = card(paper(file), lang);
        expect(el.querySelector(".verdict-card__notdoctor")?.textContent, `${file} (${lang})`).toBe(t(lang, "now.notdoctor"));
        cleanup();
      }
    }
  });

  it("theBandIsTheBannerLevelExactly", () => {
    const wrong: string[] = [];
    const seen = { now: 0, this_week: 0, none: 0 };
    for (const { file, body } of papers()) {
      const want = level(body);
      seen[want ?? "none"]++;
      for (const lang of ["bn", "en"] as const) {
        const el = card(body, lang);
        const now = el.classList.contains("verdict-card--now");
        const week = el.classList.contains("verdict-card--week");
        if (now !== (want === "now")) wrong.push(`${file} (${lang}): verdict-card--now ${now}, banner.level ${want}`);
        if (week !== (want === "this_week")) wrong.push(`${file} (${lang}): verdict-card--week ${week}, banner.level ${want}`);
        // The band says the line Java named, and only on a banner.
        const band = el.querySelector(".verdict-card__band");
        const line = "banner" in body && body.banner ? t(lang, body.banner.line_key) : null;
        if ((band?.textContent ?? null) !== line) wrong.push(`${file} (${lang}): band "${band?.textContent ?? ""}", line "${line ?? ""}"`);
        cleanup();
      }
    }
    expect(wrong).toEqual([]);
    // Guard: at least one fixture of each level, and one with no banner, was read.
    expect(seen.now, "NOW papers").toBeGreaterThan(0);
    expect(seen.this_week, "THIS_WEEK papers").toBeGreaterThan(0);
    expect(seen.none, "papers with no banner").toBeGreaterThan(0);
  });

  it("theCardNeverDerivesUrgency", () => {
    // Every value made critical, with the chip key Java would send beside it: the band still reads banner.level only.
    const routine = result("routine-released.json");
    expect(routine.values.length, "values to make critical").toBeGreaterThan(0);
    for (const v of routine.values) {
      v.display_kind = "critical";
      v.side = "high";
      v.chip_key = "value.chip.critical.high";
      v.in_now_block = true;
    }
    routine.reasons = ["value_critical"];
    const el = card(routine);
    expect(el.classList.contains("verdict-card--now")).toBe(false);
    expect(el.classList.contains("verdict-card--week")).toBe(false);
    expect(el.querySelector(".verdict-card__band, .verdict-card__notdoctor, a[href^='tel:']")).toBeNull();
    cleanup();

    // And the other way: a NOW banner over values that are all inside still bands NOW.
    const now = result("now-bd.json");
    for (const v of now.values) {
      v.display_kind = "inside";
      v.side = null;
      v.chip_key = "value.chip.inside";
      v.in_now_block = false;
    }
    expect(card(now).classList.contains("verdict-card--now")).toBe(true);
  });

  it("theCountsLineIsTheKeyJavaSent", () => {
    const wrong: string[] = [];
    let counted = 0;
    for (const { file, body } of papers()) {
      const counts = "counts" in body ? body.counts : null;
      const el = card(body, "en");
      const line = el.querySelector(".verdict-card__title")?.textContent ?? null;
      if (counts) {
        counted++;
        const want = t("en", counts.line_key, counts.values, counts.outside);
        if (line !== want) wrong.push(`${file}: "${line ?? ""}" != "${want}"`);
      }
      // Every card has a title line, so no state of it renders an empty bubble.
      if (!line) wrong.push(`${file}: no title line`);
      cleanup();
    }
    expect(wrong).toEqual([]);
    expect(counted, "papers with a counts line").toBeGreaterThan(0);
  });
});
