process.env.TZ = "America/New_York";

import { cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { monthCalendarDateTests } from "./MonthCalendar.cases";

afterEach(cleanup);

/*
 * The date tests again, west of UTC (plan W2e-b): an abroad share member is a supported viewer, and there
 * `new Date("2026-10-01").getDay()` is Wednesday, the day before. Run by Vitest's `tz` project; the assignment above is
 * the file's first statement, so it runs before any test body makes a Date.
 */
describe("MonthCalendar west of UTC", () => {
  it("runsWestOfUtc", () => {
    // Guard: the run really is west of UTC, or the tests below prove nothing the UTC run did not.
    expect(new Date(Date.UTC(2026, 9, 1)).getTimezoneOffset(), "minutes behind UTC").toBeGreaterThan(0);
    expect(new Date(0).getTimezoneOffset()).not.toBe(0);
  });
  monthCalendarDateTests();
});
