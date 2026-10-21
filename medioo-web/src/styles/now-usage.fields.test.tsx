import { describe, expect, it } from "vitest";
import { FIELD_SELECTOR, NON_FIELD_INPUT_TYPES, controlSelectors } from "./now-usage";

/*
 * Q1 (A) has two guards over one rule: the CSS scan (controlSelectors / controlBoundaryViolations, judged on the
 * built CSS) and the rendered check (catalog.spec.ts everyFieldAndTrackDrawsTheBoundary, which picks elements with
 * FIELD_SELECTOR). They must judge the same elements, or the first `<input type="file">` fails one and passes the
 * other. This runs in jsdom so FIELD_SELECTOR is matched by a real selector engine, against what the CSS scan says of
 * the same input type.
 */
describe("FIELD_SELECTOR and the CSS scan", () => {
  it("judgeTheSameInputTypes", () => {
    // Every type HTML defines for <input> (the spec's type keywords), so a type neither list names is still compared.
    const types = [
      ...NON_FIELD_INPUT_TYPES,
      "text", "search", "tel", "url", "email", "password", "date", "month", "week", "time", "datetime-local", "number",
    ];
    const verdicts = types.map((type) => {
      const input = document.createElement("input");
      input.setAttribute("type", type);
      return {
        type,
        rendered: input.matches(FIELD_SELECTOR),
        css: controlSelectors(`input[type=${type}] { background: var(--bg3) }`).length > 0,
      };
    });
    expect(verdicts.filter((v) => v.rendered !== v.css), "types the two guards judge differently").toEqual([]);
    // Not vacuous: both sides say yes to a text field and no to a submit button.
    expect(verdicts.find((v) => v.type === "text")).toEqual({ type: "text", rendered: true, css: true });
    expect(verdicts.find((v) => v.type === "submit")).toEqual({ type: "submit", rendered: false, css: false });
  });

  it("judgeTheSameNonInputControls", () => {
    for (const [html, css] of [
      ["<textarea></textarea>", "textarea"],
      ["<select></select>", "select"],
      ['<div role="radiogroup"></div>', "[role=radiogroup]"],
      ['<div role="tablist"></div>', "[role=tablist]"],
    ] as const) {
      const host = document.createElement("div");
      host.innerHTML = html;
      expect(host.firstElementChild!.matches(FIELD_SELECTOR), `${css} rendered`).toBe(true);
      expect(controlSelectors(`${css} { background: var(--bg3) }`), `${css} css`).toEqual([css]);
    }
  });
});
