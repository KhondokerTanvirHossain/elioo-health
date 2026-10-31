import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { t, type CopyKey, type Lang } from "@/copy";
import { isReadingPaper, type PaperJson, type PaperResultJson, type ValueSheetJson } from "@/api/types";
import { CHIP_KEY } from "@/components/StatusChip/StatusChip";
import { MEDICINE_FIELDS } from "./PaperParts";
import { PaperAll } from "./PaperAll";
import { type PaperWords } from "./PaperShared";
import { PaperSummary } from "./PaperSummary";
import { allRows, summaryRows } from "./rows";
import { ValueSheet } from "./ValueSheet";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

afterEach(cleanup);

/*
 * The paper (plan W6) over every fixture Java's PaperApiContractTest wrote (fixtures/api/documents) and every sheet
 * ValueSheetApiContractTest wrote (fixtures/api/value-sheet): each check is judged on what Java actually sends, and
 * each that walks the fixtures guards against having walked nothing.
 */
const api = join(__dirname, "..", "..", "..", "fixtures", "api");

function bodies<T>(area: string): Array<{ file: string; status: number; body: T }> {
  return readdirSync(join(api, area))
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((file) => {
      const json = JSON.parse(readFileSync(join(api, area, file), "utf8")) as { status: number; body: T };
      return { file, status: json.status, body: json.body };
    });
}

const papers = () => bodies<PaperJson>("documents").filter((f) => f.status === 200);
const results = () =>
  papers().flatMap((f) => (isReadingPaper(f.body) ? [] : [{ file: f.file, body: f.body as PaperResultJson }]));

function result(file: string): PaperResultJson {
  const found = results().find((f) => f.file === file);
  if (!found) throw new Error(`no finished paper fixture ${file}`);
  return structuredClone(found.body);
}

const words: PaperWords = {
  delete: { open: "open", question: "q", warn: "w", yes: "y", no: "n", gone: "g", refused: "r", failed: "f" },
  page: (n, label) => ({ zoom: label ?? `page ${n}`, title: `page ${n}`, closeLabel: "close" }),
  reading: { settled: { DONE: "read" }, again: "again" },
};

const summary = (paper: PaperJson, lang: Lang = "bn") =>
  render(<PaperSummary paper={paper} lang={lang} preview={false} words={words} />).container;

/** Whether some element's own text is exactly `text` (React escapes, so a string is rendered as one text node). */
const hasText = (root: HTMLElement, text: string) =>
  [...root.querySelectorAll("*")].some((el) => [...el.childNodes].some((c) => c.nodeType === 3 && c.textContent === text));

describe("PaperSummary", () => {
  it("wordingOnlyWhenReleased", () => {
    let released = 0;
    let withheld = 0;
    for (const { file, body } of results()) {
      for (const lang of ["bn", "en"] as const) {
        const el = summary(body, lang);
        const wording = el.querySelectorAll("[data-wording]");
        if (body.gate === "RELEASED" && body.wording) {
          expect(wording.length, `${file} (${lang}): the released wording, once`).toBe(1);
          expect(wording[0]!.textContent, file).toBe(body.wording);
          released++;
        } else {
          expect(wording.length, `${file} (${lang}) with gate ${body.gate}: no wording`).toBe(0);
          withheld++;
        }
        cleanup();
      }
    }
    // A wording Java sent with any other gate is never shown, whatever the body holds.
    const pending = { ...result("this-week-pending.json"), wording: "This must never be shown." };
    expect(pending.gate).toBe("PENDING");
    expect(summary(pending).querySelectorAll("[data-wording]").length, `gate ${pending.gate}`).toBe(0);
    expect(released, "released papers rendered").toBeGreaterThan(0);
    expect(withheld, "papers with no released wording rendered").toBeGreaterThan(0);
  });

  it("pendingSaysWhoAndHowLong", () => {
    const pending = result("this-week-pending.json");
    expect(pending.review_hours).toBeTypeOf("number");
    const el = summary(pending);
    expect(hasText(el, t("bn", "result.gate.pending", pending.review_hours!))).toBe(true);
    expect(hasText(el, t("bn", "result.review.chip"))).toBe(true);
  });

  it("aWithheldPaperShowsNoWording", () => {
    for (const file of ["withheld-rejected.json", "failed-safety.json"]) {
      const paper = result(file);
      expect(["REJECTED", "FAILED_SAFETY"], file).toContain(paper.gate);
      const el = summary(paper);
      expect(el.querySelectorAll("[data-wording]").length, file).toBe(0);
      expect(hasText(el, t("bn", "result.gate.withheld")), file).toBe(true);
      cleanup();
    }
  });

  it("aRoutinePaperIsFiledAndUnchipped", () => {
    const routine = result("routine-released.json");
    expect(routine.banner).toBeNull();
    const el = summary(routine);
    expect(hasText(el, t("bn", "result.filed"))).toBe(true);
    // No band: the card has neither modifier, and no urgency line is drawn.
    expect(el.querySelector(".verdict-card")!.className).toBe("verdict-card");
    expect(el.querySelector("[data-now-block]")).toBeNull();
  });

  it("theCountsLineIsJavas", () => {
    let compared = 0;
    for (const { file, body } of results()) {
      if (body.counts === null) continue;
      for (const lang of ["bn", "en"] as const) {
        const el = summary(body, lang);
        const title = el.querySelector(".verdict-card__title");
        expect(title?.textContent, `${file} (${lang})`).toBe(t(lang, body.counts.line_key, body.counts.values, body.counts.outside));
        compared++;
        cleanup();
      }
    }
    expect(compared, "counts lines compared").toBeGreaterThan(0);
  });

  it("aSidelessOrOneSidedValueIsUnknown", () => {
    const now = result("now-bd.json");
    const unknown = now.values.filter((v) => v.display_kind === "unknown");
    expect(unknown.length, "the fixture holds a one-sided value").toBeGreaterThan(0);
    const el = summary(now);
    for (const v of unknown) {
      const row = el.querySelector<HTMLElement>(`[id="v-${v.id}"]`)!;
      expect(row.querySelector(".status-chip--unk"), v.name!).not.toBeNull();
      expect(row.textContent, v.name!).not.toContain(t("bn", "value.chip.inside"));
      expect(row.textContent).toContain(t("bn", "value.chip.unknown"));
    }
    expect(hasText(el, t("bn", "value.group.unknown.why"))).toBe(true);
    cleanup();
    // The sheet of a one-sided value says the "could not match" sentence (sheet.unknown.why), never inside.
    const sheet = bodies<ValueSheetJson>("value-sheet").find((f) => f.file === "unknown-one-sided.json")!.body;
    expect(sheet.value.display_kind).toBe("unknown");
    const s = render(<ValueSheet sheet={sheet} lang="bn" preview={false} from="summary" />).container;
    expect(hasText(s, t("bn", "sheet.unknown.why"))).toBe(true);
    expect(s.querySelector(".status-chip--unk")).not.toBeNull();
    expect(s.textContent).not.toContain(t("bn", "value.chip.inside"));
  });

  it("everyChipIsTheKeyJavaSent", () => {
    let chips = 0;
    for (const { file, body } of results()) {
      const el = render(<PaperAll paper={body} lang="bn" preview={false} words={words} />).container;
      for (const v of body.values) {
        if (v.id === null) continue;
        const word = el.querySelector(`[id="v-${v.id}"] .status-chip__word`)?.textContent;
        expect(word, `${file} ${v.name}`).toBe(t("bn", v.chip_key));
        expect(Object.values(CHIP_KEY)).toContain(v.chip_key);
        chips++;
      }
      cleanup();
    }
    expect(chips, "chips compared").toBeGreaterThan(0);
  });

  it("medicinesAreTheirWrittenFieldsVerbatim", () => {
    let fields = 0;
    for (const { file, body } of results()) {
      if (body.medicines.length === 0) continue;
      const el = summary(body);
      const rows = [...el.querySelectorAll<HTMLElement>("[data-medicine-row]")];
      expect(rows.length, file).toBe(body.medicines.length);
      body.medicines.forEach((m, i) => {
        const row = rows[i]!;
        const written = MEDICINE_FIELDS.filter((f) => m[f] !== undefined);
        const rendered = [...row.querySelectorAll<HTMLElement>("[data-field]")];
        expect(rendered.map((e) => e.dataset.field), `${file} medicine ${i}: the fields, in order`).toEqual(written);
        rendered.forEach((e) => {
          const field = e.dataset.field as (typeof MEDICINE_FIELDS)[number];
          expect(e.textContent, `${file} medicine ${i} ${field}`).toBe(m[field]);
          fields++;
        });
        expect(row.querySelector("img")?.getAttribute("src"), `${file} medicine ${i}: its crop`).toBe(m.crop.src);
      });
      cleanup();
    }
    expect(fields, "medicine fields compared").toBeGreaterThan(0);
  });

  it("theSummaryDrawsTheRowsItSaysItDraws", () => {
    // summaryRows is the order the e2e crop check pairs images by: it must be the DOM's own order of rows.
    let checked = 0;
    for (const { file, body } of results()) {
      const el = summary(body);
      const ids = [...el.querySelectorAll<HTMLElement>("[data-value-row]")].map((r) => r.id);
      expect(ids, file).toEqual(summaryRows(body).map((v) => (v.id === null ? "" : `v-${v.id}`)));
      checked += ids.length;
      cleanup();
      const all = render(<PaperAll paper={body} lang="bn" preview={false} words={words} />).container;
      const allIds = [...all.querySelectorAll<HTMLElement>("[data-value-row]")].map((r) => r.id);
      expect(allIds, `${file} /all`).toEqual(allRows(body).map((v) => (v.id === null ? "" : `v-${v.id}`)));
      expect(allIds.length, `${file} /all draws every value`).toBe(body.values.length);
      cleanup();
    }
    expect(checked, "rows checked").toBeGreaterThan(0);
  });
});

describe("ValueSheet", () => {
  const sheets = () => bodies<ValueSheetJson>("value-sheet").filter((f) => f.status === 200);

  it("theEarlierLineIsTheKeyJavaSentAndNothingElse", () => {
    const seen = new Set<string | null>();
    for (const { file, body } of sheets()) {
      const el = render(<ValueSheet sheet={body} lang="bn" preview={false} from="summary" />).container;
      const lines = [...el.querySelectorAll("[data-earlier-line]")].map((e) => e.textContent);
      const first = t("bn", "sheet.earlier.none");
      if (body.earlier_line_key === null) {
        // R1: no line at all, never "the first time", even with no earlier reading in this unit.
        expect(lines, file).toEqual([]);
        expect(el.textContent, file).not.toContain(first);
      } else {
        const key = body.earlier_line_key as CopyKey;
        expect(lines, file).toEqual([key === "sheet.earlier" ? t("bn", key, body.value.unit ?? "") : t("bn", key)]);
      }
      expect(el.querySelectorAll("[data-value-row]").length, `${file}: the value and each earlier reading`).toBe(1 + body.earlier.length);
      seen.add(body.earlier_line_key);
      cleanup();
    }
    expect([...seen]).toEqual(expect.arrayContaining([null, "sheet.earlier.none", "sheet.earlier"]));
  });

  it("eachEarlierReadingIsBesideItsOwnPapersCrop", () => {
    const three = sheets().find((f) => f.file === "earlier-three.json")!.body;
    expect(three.earlier.length).toBe(3);
    const el = render(<ValueSheet sheet={three} lang="bn" preview={false} from="all" />).container;
    const rows = [...el.querySelectorAll<HTMLElement>("[data-value-row]")].slice(1);
    rows.forEach((row, i) => {
      const e = three.earlier[i]!;
      expect(row.querySelector("img")?.getAttribute("src"), `earlier ${i}`).toBe(e.crop.src);
      expect(row.querySelector("a")?.getAttribute("href"), `earlier ${i}`).toBe(
        `/app/documents/${e.document_id}/values/${e.observation_id}?from=all`,
      );
    });
    expect(el.querySelector(".paper__close")?.getAttribute("href")).toBe(`/app/documents/${three.paper.id}/all#v-${three.value.id}`);
  });
});
