import { describe } from "vitest";
import { banglaVectorTests } from "./bangla.cases";

// Java's golden vectors in the machine's own time zone (UTC on CI); bangla.tz.test.tsx replays them west of UTC.
describe("Bangla golden vectors", () => {
  banglaVectorTests();
});
