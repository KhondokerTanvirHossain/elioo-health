import { readFileSync } from "node:fs";
import { join } from "node:path";
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t } from "@/copy";
import { NOW_ALLOWED, nowUsageViolations } from "@/styles/now-usage";
import { UrgentMark } from "./UrgentMark";

afterEach(cleanup);

describe("UrgentMark", () => {
  it("anUrgentMarkIsDotShapeAndWord", () => {
    // Every status is colour + shape + word (Global Constraints): the NOW dot, drawn as an octagon so it is not a
    // colour-only dot, and the attention strip's word as real text a screen reader reads.
    for (const lang of ["bn", "en"] as const) {
      const word = t(lang, "attn.strip");
      const { container } = render(<UrgentMark word={word} />);
      const mark = container.querySelector(".urgent-mark");
      expect(mark, "the mark's root").not.toBeNull();
      const shape = mark!.querySelector("svg.urgent-mark__dot");
      expect(shape?.getAttribute("data-shape")).toBe("octagon");
      expect(shape?.getAttribute("aria-hidden"), "the shape is drawing; the word says it").toBe("true");
      const text = mark!.querySelector(".urgent-mark__word");
      expect(text?.textContent).toBe(word);
      expect(text?.closest("[aria-hidden]"), "the word is not hidden from a screen reader").toBeNull();
      cleanup();
    }
  });

  it("theMarksStylesheetIsTheOnlyNowUseAndAnAllowedOne", () => {
    // The NOW red here is allowed by the built-CSS rule because the mark lives under src/components/safety and its
    // selectors are its own block; a selector of another component that borrowed it would be refused.
    const css = readFileSync(join(__dirname, "UrgentMark.css"), "utf8");
    expect(css, "the mark paints with the NOW token").toMatch(/var\(--now\)/);
    expect(nowUsageViolations(css, NOW_ALLOWED)).toEqual([]);
    expect(nowUsageViolations(".patient-switcher .urgent-mark__dot { fill: var(--now) }", NOW_ALLOWED)).toEqual([
      ".patient-switcher .urgent-mark__dot uses --now",
    ]);
  });
});
