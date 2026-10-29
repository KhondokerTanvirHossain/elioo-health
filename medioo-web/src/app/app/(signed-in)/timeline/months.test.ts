import { afterEach, describe, expect, it, vi } from "vitest";
import september from "../../../../../fixtures/api/calendar/september.json";
import { shift } from "./months";

afterEach(() => {
  vi.useRealTimers();
});

/*
 * The papers page's month links come from the month Java answered, never from today (plan W8 review). The e2e
 * theMonthMovesByLink cannot tell the two apart while the wall clock sits in the fixtures' month, so the clock here is
 * moved years away from every month judged: a `shift` that read `new Date()` would answer around 2031.
 */
describe("the calendar's neighbouring months", () => {
  it("areJavasMonthPlusOrMinusOneWhateverTodayIs", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2031-03-15T12:00:00Z"));
    const java = september.body.month;
    expect(java, "Java's month in calendar/september.json").toBe("2026-09");
    expect(new Date().getUTCFullYear(), "the fake clock is not in Java's year").not.toBe(2026);
    expect([shift(java, -1), shift(java, 1)]).toEqual(["2026-08", "2026-10"]);
  });

  it("crossesAYearBothWays", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2031-07-01T00:00:00Z"));
    expect(shift("2026-01", -1)).toBe("2025-12");
    expect(shift("2026-12", 1)).toBe("2027-01");
  });
});
