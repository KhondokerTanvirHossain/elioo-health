import { describe, expect, it } from "vitest";
import { announcement, judge, MAX_CHECKS, MAX_WAIT_MS, RELOAD_SECONDS, STEADY_CHECKS, waitBefore } from "./reading-check";

const WORDS = { DONE: "Read", NEEDS_RETAKE: "Retake the photo", FAILED: "Could not be read" };

describe("the quiet check's schedule (PO ruling P6)", () => {
  it("checksAtJavasPaceFirstThenBacksOffToACeiling", () => {
    const waits = Array.from({ length: MAX_CHECKS }, (_, i) => waitBefore(i));
    // Java's 5 s for the first half minute.
    expect(waits.slice(0, STEADY_CHECKS)).toEqual(Array(STEADY_CHECKS).fill(RELOAD_SECONDS * 1000));
    // Then each wait longer than the last until the ceiling, never past it, never shorter than the one before.
    expect(waits.slice(STEADY_CHECKS, STEADY_CHECKS + 4)).toEqual([7500, 11250, 16875, 25313]);
    for (let i = 1; i < waits.length; i++) expect(waits[i]!, `wait ${i}`).toBeGreaterThanOrEqual(waits[i - 1]!);
    expect(Math.max(...waits)).toBe(MAX_WAIT_MS);
    expect(waitBefore(1000), "far past the cap").toBe(MAX_WAIT_MS);
  });

  it("theCapIsPastJavasSlowLineAndNotForever", () => {
    // DocumentPages.SLOW is 5 min: the page gives up only after Java itself would say "taking longer than usual".
    const total = Array.from({ length: MAX_CHECKS }, (_, i) => waitBefore(i)).reduce((a, b) => a + b, 0);
    expect(total).toBeGreaterThan(5 * 60_000);
    expect(total).toBeLessThan(10 * 60_000);
  });
});

describe("a status answer, judged", () => {
  it("receivedAndProcessingAreStillReading", () => {
    expect(judge({ kind: "ok", status: 200, body: { status: "RECEIVED" } })).toEqual({ kind: "reading" });
    expect(judge({ kind: "ok", status: 200, body: { status: "PROCESSING" } })).toEqual({ kind: "reading" });
  });

  it("everyOtherStatusIsSettled", () => {
    for (const status of ["DONE", "NEEDS_RETAKE", "FAILED"]) {
      expect(judge({ kind: "ok", status: 200, body: { status } })).toEqual({ kind: "settled", status });
    }
  });

  it("aPaperThatIsNoLongerThereIsGoneAndATroubledAnswerIsNoChange", () => {
    expect(judge({ kind: "refused", status: 404 })).toEqual({ kind: "gone" });
    // The dangerous direction: a server in trouble or a phone offline must never read as a paper that settled.
    expect(judge({ kind: "refused", status: 503 })).toEqual({ kind: "unknown" });
    expect(judge({ kind: "offline" })).toEqual({ kind: "unknown" });
    expect(judge({ kind: "ok", status: 200, body: null })).toEqual({ kind: "unknown" });
    expect(judge({ kind: "ok", status: 200, body: { status: 3 } })).toEqual({ kind: "unknown" });
  });
});

describe("what the live region says", () => {
  it("eachSettledStatusOnceInOrder", () => {
    const settled = (status: string) => ({ kind: "settled", status }) as const;
    expect(announcement([settled("DONE"), settled("DONE")], WORDS)).toBe("Read");
    expect(announcement([settled("FAILED"), { kind: "gone" }, settled("DONE")], WORDS)).toBe("Could not be read · Read");
  });

  it("nothingSettledSaysNothing", () => {
    expect(announcement([{ kind: "reading" }, { kind: "unknown" }, { kind: "gone" }], WORDS)).toBe("");
    expect(announcement([{ kind: "settled", status: "SOMETHING_NEW" }], WORDS), "a status the bundle has no words for").toBe("");
  });
});
