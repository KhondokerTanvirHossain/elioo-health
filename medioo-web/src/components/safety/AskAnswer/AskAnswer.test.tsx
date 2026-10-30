import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t, type Lang } from "@/copy";
import { AskAnswer } from "./AskAnswer";
import { call999Words } from "./call999";

afterEach(cleanup);

const LANGS: readonly Lang[] = ["bn", "en"];
const ANSWER = "first line\n\nsecond line";

describe("AskAnswer", () => {
  it("theCallWordsAreTheShippedKey", () => {
    for (const lang of LANGS) expect(call999Words(lang), lang).toBe(t(lang, "now.call999"));
  });

  it("theAnswerIsTheBubblesOnlyText", () => {
    const root = render(<AskAnswer answer={ANSWER} call999={null} />).container;
    const bodies = [...root.querySelectorAll(".message-bubble--recv .message-bubble__body")];
    expect(bodies.map((b) => b.textContent)).toEqual([ANSWER]);
    expect(bodies[0]!.children.length, "nothing inside the bubble but the text").toBe(0);
  });

  it("the999LinkOnlyWhenAsked", () => {
    for (const lang of LANGS) {
      const words = call999Words(lang);
      const home = render(<AskAnswer answer={ANSWER} call999={words} />).container;
      expect([...home.querySelectorAll("a")].map((a) => [a.getAttribute("href"), a.textContent])).toEqual([["tel:999", words]]);
      // Beside the bubble, never inside it.
      expect(home.querySelector(".message-bubble a")).toBeNull();
      cleanup();
      const abroad = render(<AskAnswer answer={ANSWER} call999={null} />).container;
      expect(abroad.querySelectorAll("a").length).toBe(0);
      expect(abroad.textContent).not.toContain(words);
      cleanup();
    }
  });
});
