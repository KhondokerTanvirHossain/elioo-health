import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import type { PaperResultJson } from "@/api/types";
import { NowBlock, type NowProof } from "./NowBlock";

afterEach(cleanup);

/* The NOW part of a paper over the two NOW papers Java's PaperApiContractTest wrote: one seen from Bangladesh, one abroad. */
const documents = join(__dirname, "..", "..", "..", "..", "fixtures", "api", "documents");
const paper = (file: string) => (JSON.parse(readFileSync(join(documents, file), "utf8")) as { body: PaperResultJson }).body;

/** Each proof marks what it was asked to draw, so the test sees which rows the block chose and in what order. */
const proof: NowProof = {
  row: (v) => <div data-proof-row={v.id ?? ""} />,
  followUp: (f) => <div data-proof-followup={f.instruction ?? ""} />,
  quote: (l) => <div data-proof-quote={l.text} />,
  pages: <div data-proof-pages />,
  outsideHref: "#outside",
};

const block = (p: PaperResultJson, lang: Lang = "bn", over: Partial<NowProof> = {}) =>
  render(<NowBlock paper={p} now={p.now!} lang={lang} proof={{ ...proof, ...over }} />).container.querySelector<HTMLElement>(
    "[data-now-block]",
  )!;

const ownTexts = (root: HTMLElement) =>
  [...root.querySelectorAll("*")].flatMap((el) => [...el.childNodes].filter((c) => c.nodeType === 3).map((c) => c.textContent));

describe("NowBlock", () => {
  it("theNowBlockSaysNotDoctorItself", () => {
    for (const file of ["now-bd.json", "now-abroad.json"]) {
      const p = paper(file);
      for (const lang of ["bn", "en"] as const) {
        const el = block(p, lang);
        expect(ownTexts(el), `${file} (${lang})`).toContain(t(lang, p.now!.notdoctor_key));
        cleanup();
      }
    }
  });

  it("abroadGetsTheRemoteLinesAndNo999", () => {
    const abroad = paper("now-abroad.json");
    expect(abroad.now!.call_999).toBe(false);
    expect(abroad.now!.remote_share).toBe(true);
    const el = block(abroad);
    const texts = ownTexts(el);
    expect(texts).toContain(t("bn", "now.remote.flags.go"));
    expect(texts).toContain(t("bn", "now.bring.remote"));
    const share = el.querySelector<HTMLAnchorElement>(".now-block__share");
    expect(share?.textContent).toBe(t("bn", "now.remote.share"));
    // The share text names the patient and carries no value (DocumentPages.remoteAction).
    const text = decodeURIComponent(new URL(share!.href).searchParams.get("text")!);
    expect(text).toBe(t("bn", "now.remote.share.text", abroad.patient.name));
    for (const v of abroad.values) if (v.value) expect(text, v.name!).not.toContain(v.value);
    expect([...el.querySelectorAll("a")].filter((a) => a.getAttribute("href")!.startsWith("tel:"))).toEqual([]);
    cleanup();
    // The other direction: from Bangladesh, the home lines and no share (the one tel:999 is VerdictCard's).
    const home = block(paper("now-bd.json"));
    expect(ownTexts(home)).toContain(t("bn", "now.flags.go"));
    expect(ownTexts(home)).toContain(t("bn", "now.bring"));
    expect(home.querySelector(".now-block__share")).toBeNull();
  });

  it("theBlockRowsAreThoseJavaFlagged", () => {
    const p = paper("now-bd.json");
    const flagged = p.values.filter((v) => v.in_now_block).map((v) => v.id);
    expect(flagged.length, "the fixture flags a row").toBeGreaterThan(0);
    expect(p.values.some((v) => !v.in_now_block), "and leaves one unflagged").toBe(true);
    const el = block(p);
    expect([...el.querySelectorAll<HTMLElement>("[data-proof-row]")].map((e) => e.dataset.proofRow)).toEqual(flagged);
    // With two rows flagged, the block follows the body's order, and reversed in the body, reversed on the page.
    const two = { ...p, values: p.values.map((v) => (v.display_kind === "outside" ? { ...v, in_now_block: true } : v)) };
    const both = two.values.filter((v) => v.in_now_block).map((v) => v.id);
    expect(both.length, "two flagged rows").toBe(2);
    cleanup();
    expect([...block(two).querySelectorAll<HTMLElement>("[data-proof-row]")].map((e) => e.dataset.proofRow)).toEqual(both);
    cleanup();
    const reversed = { ...two, values: [...two.values].reverse() };
    expect([...block(reversed).querySelectorAll<HTMLElement>("[data-proof-row]")].map((e) => e.dataset.proofRow)).toEqual(
      [...both].reverse(),
    );
  });

  it("reasonsAreInJavasOrder", () => {
    const p = paper("now-bd.json");
    expect(p.reasons.length, "the fixture has reasons").toBeGreaterThan(1);
    const el = block(p);
    expect([...el.querySelectorAll<HTMLElement>("[data-reason]")].map((e) => e.dataset.reason)).toEqual(p.reasons);
    cleanup();
    const shuffled = { ...p, reasons: [...p.reasons].reverse() };
    expect([...block(shuffled).querySelectorAll<HTMLElement>("[data-reason]")].map((e) => e.dataset.reason)).toEqual(shuffled.reasons);
  });

  it("aReasonWithNoProofOfItsOwnShowsTheWholePages", () => {
    const p = { ...paper("now-bd.json"), reasons: ["text_emergency_phrase", "other"] };
    const el = block(p);
    const reasons = [...el.querySelectorAll<HTMLElement>("[data-reason]")];
    expect(reasons.map((r) => r.querySelector("[data-proof-pages]") !== null)).toEqual([true, true]);
    expect(ownTexts(el)).toEqual(expect.arrayContaining([t("bn", "now.why.phrase"), t("bn", "now.why.other")]));
  });
});
