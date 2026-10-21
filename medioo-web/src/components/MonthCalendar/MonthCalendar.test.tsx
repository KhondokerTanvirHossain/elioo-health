import { cleanup } from "@testing-library/react";
import { afterEach, describe } from "vitest";
import { monthCalendarDateTests, monthCalendarTests } from "./MonthCalendar.cases";

afterEach(cleanup);

/*
 * MonthCalendar over B3's generated calendar fixtures, in the machine's own time zone. MonthCalendar.tz.test.tsx runs
 * the date tests again west of UTC, where a date computed with local getters lands on the day before.
 */
describe("MonthCalendar", () => {
  monthCalendarDateTests();
  monthCalendarTests();
});
