process.env.TZ = "America/New_York";

import { describe, expect, it } from "vitest";
import { banglaVectorTests } from "./bangla.cases";

/*
 * Java's golden vectors again, west of UTC (plan W2e-b review): MonthCalendar's and TrendChart's dates come from these
 * formatters, and bangla.test.ts runs only in the machine's zone, which is UTC on CI. A formatter that read a date
 * through `new Date(iso)` and local getters would print 30 September for 2026-10-01 here, and only here. Run by
 * Vitest's `tz` project; the assignment above is the file's first statement, so it runs before any Date is made.
 */
describe("Bangla golden vectors west of UTC", () => {
  it("runsWestOfUtc", () => {
    // Guard: the run really is west of UTC, or the vectors below prove nothing the UTC run did not.
    expect(new Date(Date.UTC(2026, 9, 1)).getTimezoneOffset(), "minutes behind UTC").toBeGreaterThan(0);
  });
  banglaVectorTests();
});
