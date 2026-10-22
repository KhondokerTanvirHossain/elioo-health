import { describe, expect, it } from "vitest";
import { t } from "@/copy";
import refusals from "../../../fixtures/api/uploads/refusals.json";
import { UPLOAD_REASON_COPY } from "./upload-reasons";

/*
 * Every refusal the upload path can make has copy (plan W7). The names are read from the fixture Java writes
 * (baymax UploadApiContractTest serves every reason UploadRefusalSweep finds in the upload path's source, through the
 * guard), never listed here: a refusal intake learns later fails this test until the sheet can say it.
 */
describe("the upload refusals", () => {
  it("everyUploadRefusalHasCopy", () => {
    const names = Object.keys(refusals.body).sort();
    // Guard: the list judged is Java's whole sweep, not an empty or a truncated file.
    expect(names.length, "refusal names in fixtures/api/uploads/refusals.json").toBeGreaterThanOrEqual(10);
    for (const name of names) {
      const key = UPLOAD_REASON_COPY[name];
      expect(key, `${name} has a copy key`).toBeDefined();
      for (const lang of ["bn", "en"] as const) {
        // The numbers a line takes (the page and file caps, the allowance and its reset) are filled from the options.
        const text = t(lang, key!, 10, "2026-11-01");
        expect(text.trim(), `${name} in ${lang}`).not.toBe("");
        expect(text, `${name} in ${lang} has no slot left unfilled`).not.toMatch(/\{\d\}/);
      }
    }
    // The other direction: the map names no refusal Java does not make (a stale entry would hide a renamed one).
    expect(Object.keys(UPLOAD_REASON_COPY).sort()).toEqual(names);
  });

  it("eachFixtureEntryIsTheAnswerItNames", () => {
    for (const [name, answer] of Object.entries(refusals.body)) {
      expect(answer.body.reason, name).toBe(name);
      expect(answer.status, name).toBeGreaterThanOrEqual(400);
    }
  });
});
