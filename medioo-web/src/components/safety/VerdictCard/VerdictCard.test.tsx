import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type CopyKey, type Lang } from "@/copy";
import { GATES, isReadingPaper, type Gate, type PaperJson, type PaperResultJson, type PaperStatus } from "@/api/types";
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

  it("noGateButReleasedShowsWording", () => {
    // Derived from the type's own list of gates, not from the ones the fixtures happen to carry: REJECTED and
    // FAILED_SAFETY (wording a reviewer or the safety check refused) are where showing it would be worst.
    const marker = "a withheld explanation 7f3c";
    const shown: string[] = [];
    const exercised: Gate[] = [];
    for (const gate of GATES.filter((g) => g !== "RELEASED")) {
      for (const lang of ["bn", "en"] as const) {
        const held = result("routine-released.json");
        held.gate = gate;
        held.wording = marker;
        const el = card(held, lang);
        if (el.querySelector(".verdict-card__wording")) shown.push(`${gate} (${lang}): a .verdict-card__wording`);
        if (el.textContent?.includes(marker)) shown.push(`${gate} (${lang}): the wording as text`);
        cleanup();
      }
      exercised.push(gate);
    }
    expect(shown, "gates other than RELEASED that show wording").toEqual([]);
    // Guard: every gate but RELEASED ran, the two refused ones by name.
    expect(exercised.length).toBe(GATES.length - 1);
    expect(exercised).toEqual(expect.arrayContaining(["NONE", "PENDING", "REJECTED", "FAILED_SAFETY"]));
    // And the same clone with gate RELEASED does show the marker, so the loop's "none shown" is about the gate.
    const released = result("routine-released.json");
    released.wording = marker;
    expect(card(released).querySelector(".verdict-card__wording")?.textContent).toBe(marker);
  });

  it("aCardWithoutCountsIsTitledByItsStatus", () => {
    // The spec, restated here rather than read from the component: the word the HTML pages show for each status.
    const expected: Record<PaperStatus, CopyKey> = {
      RECEIVED: "progress.step.received",
      PROCESSING: "progress.step.processing",
      DONE: "status.DONE",
      NEEDS_RETAKE: "retake.title",
      FAILED: "failed.title",
    };
    const wrong: string[] = [];
    const seen = new Set<PaperStatus>();
    for (const { file, body } of papers()) {
      if ("counts" in body && body.counts) continue;
      seen.add(body.status);
      for (const lang of ["bn", "en"] as const) {
        const got = card(body, lang).querySelector(".verdict-card__title")?.textContent ?? null;
        const want = t(lang, expected[body.status]);
        if (got !== want) wrong.push(`${file} (${lang}, ${body.status}): "${got ?? ""}" != ${expected[body.status]} "${want}"`);
        cleanup();
      }
    }
    expect(wrong).toEqual([]);
    // Guard: the counts-less papers the fixtures hold, each titled by a different rule, were all judged.
    expect([...seen]).toEqual(expect.arrayContaining(["FAILED", "NEEDS_RETAKE", "PROCESSING"]));
  });

  it("aRetakeShowsItsReason", () => {
    const retake = result("retake-third.json");
    expect(retake.retake, "the retake fixture carries a retake block").toBeTruthy();
    for (const lang of ["bn", "en"] as const) {
      const reason = card(retake, lang).querySelector(".verdict-card__reason");
      expect(reason?.textContent, `retake-third (${lang})`).toBe(t(lang, retake.retake!.reason_key));
      cleanup();
    }
    // And no other paper renders a reason line.
    const others: string[] = [];
    for (const { file, body } of papers()) {
      if (file === "retake-third.json") continue;
      if (card(body).querySelector(".verdict-card__reason")) others.push(file);
      cleanup();
    }
    expect(others).toEqual([]);
    expect(papers().length, "other papers were judged").toBeGreaterThan(1);
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
