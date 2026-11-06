import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { isReadingPaper, type PaperJson, type PaperResultJson } from "@/api/types";
import { t, type CopyKey } from "@/copy";
import { paperWords } from "@/app/app/(signed-in)/documents/[id]/paper-words";
import { PaperSummary } from "@/components/Paper/PaperSummary";
import { PaperPanel } from "./PaperPanel";

/*
 * A paper's details over the desktop conversation (plan desktop-parity Task 8; ruling P19: `desk.panel.open` opens a side
 * panel holding everything today's paper page holds, and no safety block is dropped). Walked over every paper Java's
 * contract tests wrote (fixtures/api/documents), and the list of safety blocks is read off today's paper page
 * (PaperSummary) on the same fixture, never typed here: a block added to the page later is a block the panel must
 * carry, with no edit to this file.
 */

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));
vi.mock("@/api/browser", () => ({ apiGet: vi.fn(async () => ({ kind: "ok", status: 200, body: { status: "PROCESSING" } })), apiSend: vi.fn() }));

afterEach(cleanup);

const dir = join(__dirname, "..", "..", "..", "fixtures", "api", "documents");
const PAPERS: Array<{ file: string; paper: PaperJson }> = readdirSync(dir)
  .filter((f) => f.endsWith(".json"))
  .sort()
  .flatMap((file) => {
    const json = JSON.parse(readFileSync(join(dir, file), "utf8")) as { status: number; body: PaperJson };
    return json.status === 200 ? [{ file, paper: json.body }] : [];
  });

const byFile = (file: string) => {
  const found = PAPERS.find((p) => p.file === file);
  if (!found) throw new Error(`no paper fixture ${file}`);
  return structuredClone(found.paper);
};

const CLOSE = "/app/home?patient=p&paper=d";

const page = (paper: PaperJson) =>
  render(<PaperSummary paper={paper} lang="bn" preview={false} words={paperWords("bn")} />).container;
const panel = (paper: PaperJson, closeHref = CLOSE) =>
  render(<PaperPanel paper={paper} lang="bn" preview={false} closeHref={closeHref} />).container;

/** The elements whose whole text is `sentence`: a warning line, not a message that quotes it among other lines. */
const warningLines = (root: ParentNode, sentence: string) =>
  [...root.querySelectorAll<HTMLElement>("*")].filter((e) => e.textContent?.trim() === sentence && ![...e.children].some((c) => c.textContent?.trim() === sentence));

/** What `a` holds that `b` does not, counting repeats: a block drawn twice on the page and once in the panel is missing. */
function difference(a: string[], b: string[]): string[] {
  const left = [...b];
  return a.filter((name) => {
    const at = left.indexOf(name);
    if (at < 0) return true;
    left.splice(at, 1);
    return false;
  });
}

/** Every safety block an element holds, by its `data-safety` name, as many times as it is drawn, in a stable order. */
const safetyOf = (root: ParentNode) => [...root.querySelectorAll("[data-safety]")].map((e) => e.getAttribute("data-safety")!).sort();

describe("PaperPanel", () => {
  it("panelCarriesEverySafetyBlockThePaperPageCarries", () => {
    let blocks = 0;
    let carried = 0;
    let weekBandsCompared = 0;
    for (const { file, paper } of PAPERS) {
      const today = safetyOf(page(paper));
      cleanup();
      const shown = safetyOf(panel(paper));
      cleanup();
      // The urgency band (Java's sentence) is left to the card and the NOW bar only on a NOW paper (P19: the warning
      // appears in the bar and on that paper's card, and nowhere else on the screen). Keyed on the fixture's own level,
      // never on a list: a this_week paper has no bar, so its band is compared like every other block.
      const level = isReadingPaper(paper) ? null : (paper.banner?.level ?? null);
      const expected = level === "now" ? today.filter((name) => name !== "band") : today;
      carried += today.length - expected.length;
      if (level === "this_week" && today.includes("band") && shown.includes("band")) weekBandsCompared++;
      expect(difference(expected, shown), `${file}: blocks today's page has and the panel dropped`).toEqual([]);
      expect(difference(shown, expected), `${file}: blocks the panel has and today's page does not`).toEqual([]);
      blocks += expected.length;
    }
    // Not vacuous: the page has safety blocks to compare, the band the card carries was really on some NOW page, and a
    // this_week band was compared, not carried.
    expect(PAPERS.length, "paper fixtures walked").toBeGreaterThan(10);
    expect(blocks, "safety blocks compared").toBeGreaterThan(20);
    expect(carried, "NOW bands left to the card and the bar").toBeGreaterThan(0);
    expect(weekBandsCompared, "this_week bands compared on the page and in the panel").toBeGreaterThan(0);
    const now = safetyOf(page(byFile("now-bd.json")));
    for (const name of ["now-block", "call-999", "not-a-doctor", "band"]) expect(now, `now-bd.json carries ${name}`).toContain(name);
  });

  it("aThisWeekPaperKeepsItsBandInThePanelAndANowPaperLeavesItToTheBar", () => {
    // A this_week paper has no NOW bar, and the panel covers its card: the panel is the one place on the screen its
    // sentence and calendar can be. A NOW paper's sentence is in the bar and on the card, so the panel leaves it out.
    let week = 0;
    for (const { file, paper } of PAPERS) {
      if (isReadingPaper(paper) || paper.banner?.level !== "this_week") continue;
      week++;
      const sentence = t("bn", paper.banner.line_key as CopyKey);
      const band = panel(paper).querySelector("[data-safety=band]");
      expect(band, `${file}: the panel has the this_week band`).not.toBeNull();
      expect(band!.textContent, `${file}: Java's sentence in the band`).toBe(sentence);
      expect(band!.querySelector("svg"), `${file}: the calendar shape, not colour alone`).not.toBeNull();
      cleanup();
    }
    expect(week, "this_week fixtures walked").toBeGreaterThanOrEqual(3);
    let now = 0;
    for (const { file, paper } of PAPERS) {
      if (isReadingPaper(paper) || paper.banner?.level !== "now") continue;
      now++;
      expect(panel(paper).querySelector("[data-safety=band]"), `${file}: a NOW paper's band is the bar's and the card's`).toBeNull();
      cleanup();
    }
    expect(now, "NOW fixtures walked").toBeGreaterThanOrEqual(3);
  });

  it("noRawLinkIsShownInThePanel", () => {
    // Java's released wording is its WhatsApp text, with the timeline link line: the panel shows it as the conversation's
    // card does (webText, Java's web_body rule), so the desktop screen shows no raw link anywhere.
    let linked = 0;
    for (const { file, paper } of PAPERS) {
      const el = panel(paper);
      const text = el.textContent ?? "";
      expect(/https?:\/\/\S*/.exec(text)?.[0] ?? null, `${file}: a raw link is shown in the panel`).toBeNull();
      if (!isReadingPaper(paper) && paper.gate === "RELEASED" && paper.wording) {
        const lines = paper.wording.split("\n");
        if (lines.some((l) => /https?:\/\//.test(l))) linked++;
        const kept = lines.filter((l) => !l.includes("http://") && !l.includes("https://"));
        expect(el.querySelector("[data-wording]")!.textContent, `${file}: every other line of the wording stays`).toBe(kept.join("\n"));
      }
      cleanup();
    }
    expect(linked, "fixtures whose released wording holds a link line").toBeGreaterThan(0);
  });

  it("theBandIsLeftOnlyToTheCard", () => {
    // In the other direction: the panel never draws Java's urgency sentence as a warning line of its own (it would be the
    // third on the screen). Medioo's released message quotes the sentence as one of its lines, and stays: that is the
    // gate's block, which today's page shows too, and dropping it would drop a safety block.
    const potas = byFile("proto-potas.json") as PaperResultJson;
    expect(potas.banner?.level).toBe("now");
    const sentence = t("bn", potas.banner!.line_key as CopyKey);
    const el = panel(potas);
    expect(warningLines(el, sentence), "warning lines in the panel").toEqual([]);
    expect(el.querySelector("[data-safety=band]")).toBeNull();
    // The other direction: today's page draws it as a line of its own, so the check can see one.
    cleanup();
    expect(warningLines(page(potas), sentence).length, "today's page has the band").toBe(1);
    cleanup();
    expect(potas.wording, "the released message quotes the sentence").toContain(sentence);
    expect(panel(potas).querySelector("[data-wording]")!.textContent, "and the panel keeps it").toBe(potas.wording);
    cleanup();
    const again = panel(potas);
    expect(again.querySelector("[data-now-block]"), "the NOW block is there").not.toBeNull();
    expect(again.querySelector('a[href="tel:999"]'), "999 stays reachable while the panel covers the card").not.toBeNull();
  });

  it("theCallIsDrawnOnlyWhereJavaSaysSo", () => {
    const abroad = byFile("now-abroad.json") as PaperResultJson;
    expect(abroad.now?.call_999).toBe(false);
    expect(panel(abroad).querySelector('a[href="tel:999"]')).toBeNull();
  });

  it("thePanelIsTodaysPageWithoutItsBackLink", () => {
    const potas = byFile("proto-potas.json");
    const el = panel(potas);
    expect(el.querySelector(".paper__back"), "no back link").toBeNull();
    expect(el.querySelector("h1"), "the screen's heading is not the panel's").toBeNull();
    const close = el.querySelector<HTMLAnchorElement>("a.paper-panel__close")!;
    expect(close.getAttribute("href")).toBe(CLOSE);
    expect(close.textContent).toBe(t("bn", "sheet.close"));
  });

  it("theOrderIsTheNowBlockThenTheGroupsThenTheDoctorMedicinesNextAndPages", () => {
    const el = panel(byFile("fifty-eight-values-multipage.json"));
    const order = ["[data-now-block]", "[data-panel-part=outside]", "[data-panel-part=pages]"].map((s) => el.querySelector(s));
    expect(order.every((e) => e !== null), "every part present").toBe(true);
    for (let i = 1; i < order.length; i++) {
      expect(order[i - 1]!.compareDocumentPosition(order[i]!) & Node.DOCUMENT_POSITION_FOLLOWING, `part ${i}`).toBeTruthy();
    }
  });

  it("everyValueIsInThePanel", () => {
    // Today's page shows one in-range value and links to the rest; the panel has the room, so it shows every one.
    for (const { file, paper } of PAPERS) {
      if (isReadingPaper(paper) || paper.status !== "DONE") continue;
      expect(panel(paper).querySelectorAll("[data-value-row]").length, file).toBe(paper.values.length);
      cleanup();
    }
    expect(byFile("fifty-eight-values-multipage.json").status).toBe("DONE");
  });

  it("noIdIsSaidTwiceBesideTodaysPage", () => {
    // /app/documents/{id} on the desktop layout holds today's page (below 900 px) and the panel in one document.
    for (const { file, paper } of PAPERS) {
      const { container } = render(
        <>
          <PaperSummary paper={paper} lang="bn" preview={false} words={paperWords("bn")} />
          <PaperPanel paper={paper} lang="bn" preview={false} closeHref={CLOSE} />
        </>,
      );
      const ids = [...container.querySelectorAll("[id]")].map((e) => e.id);
      expect(ids.filter((id, i) => ids.indexOf(id) !== i), `${file}: ids said twice`).toEqual([]);
      cleanup();
    }
  });

  it("aShareMemberHasNoDeleteInThePanel", () => {
    const own = byFile("proto-potas.json");
    expect(panel(own).querySelector(".delete-paper"), "the owner can delete").not.toBeNull();
    cleanup();
    expect(panel({ ...own, owner: false } as PaperJson).querySelector(".delete-paper"), "a share member cannot").toBeNull();
  });
});
