import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { AskJson } from "@/api/types";
import { t, type Lang } from "@/copy";
import { AskAnswer } from "./AskAnswer";
import { call999Words } from "./call999";

afterEach(cleanup);

const LANGS: readonly Lang[] = ["bn", "en"];
const ANSWER = "first line\n\nsecond line";
const CROP = { alt: "crop-alt", retry: "crop-retry" };
const DOSE = (
  JSON.parse(readFileSync(join(__dirname, "..", "..", "..", "..", "fixtures", "api", "ask", "answer-dose.json"), "utf8")) as {
    body: AskJson;
  }
).body;

describe("AskAnswer", () => {
  it("theCallWordsAreTheShippedKey", () => {
    for (const lang of LANGS) expect(call999Words(lang), lang).toBe(t(lang, "now.call999"));
  });

  it("theAnswerIsTheBubblesOnlyText", () => {
    const root = render(<AskAnswer answer={ANSWER} call999={null} medicines={[]} crop={CROP} />).container;
    const bodies = [...root.querySelectorAll(".message-bubble--recv .message-bubble__body")];
    expect(bodies.map((b) => b.textContent)).toEqual([ANSWER]);
    expect(bodies[0]!.children.length, "nothing inside the bubble but the text").toBe(0);
    expect(root.querySelectorAll("img").length, "no line quoted, no crop").toBe(0);
  });

  it("aQuotedLineIsDrawnWithItsCropBeforeTheBubble", () => {
    // Review of PR 166, finding 5 (DR-12, DR-16): Java's dose answer quotes the prescription's line; its crop, the paper's
    // own image of the line, is drawn first, as MedicinesAsWritten draws a line, then the bubble with the words.
    expect(DOSE.medicines.length, "answer-dose quotes a line").toBe(1);
    const root = render(<AskAnswer answer={DOSE.answer} call999={null} medicines={DOSE.medicines} crop={CROP} />).container;
    const img = root.querySelector<HTMLImageElement>(".ask-answer__crops img");
    expect(img?.getAttribute("src"), "the crop Java named").toBe(DOSE.medicines[0]!.crop.src);
    expect(img?.getAttribute("alt"), "the crop says what it is, never the name read off it").toBe(CROP.alt);
    const bubble = root.querySelector(".message-bubble--recv")!;
    expect(img!.compareDocumentPosition(bubble) & Node.DOCUMENT_POSITION_FOLLOWING, "the crop comes before the bubble").toBeTruthy();
    expect(bubble.querySelector("img"), "and is not inside it").toBeNull();
  });

  it("the999LinkOnlyWhenAsked", () => {
    for (const lang of LANGS) {
      const words = call999Words(lang);
      const home = render(<AskAnswer answer={ANSWER} call999={words} medicines={[]} crop={CROP} />).container;
      expect([...home.querySelectorAll("a")].map((a) => [a.getAttribute("href"), a.textContent])).toEqual([["tel:999", words]]);
      // Beside the bubble, never inside it.
      expect(home.querySelector(".message-bubble a")).toBeNull();
      cleanup();
      const abroad = render(<AskAnswer answer={ANSWER} call999={null} medicines={[]} crop={CROP} />).container;
      expect(abroad.querySelectorAll("a").length).toBe(0);
      expect(abroad.textContent).not.toContain(words);
      cleanup();
    }
  });
});
