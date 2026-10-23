import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { CHIP_KINDS } from "@/components/StatusChip/StatusChip";
import { STATES } from "./states";

/*
 * The registry, as an explicit list of every state shipped so far (started by plan W2c). catalog.spec.ts reads the
 * registry's length off the page that renders it, so on its own it would accept any registry; this list is independent
 * of the page. Each component PR (W2c, W2e-a, W2d, ...) appends its states here, and removing a state already shipped
 * fails.
 */
const SHIPPED: string[] = [
  "Wordmark/default",
  "MioAvatar/32",
  "MioAvatar/48-still",
  "MioAvatar/96",
  "MessageBubble/recv",
  "MessageBubble/sent",
  "MessageBubble/long-unbroken",
  // One chip state per kind, derived from the kinds the component knows, so a kind added there needs a state here.
  ...CHIP_KINDS.map((kind) => `StatusChip/${kind}`),
  // W2e-a: the two sign-in fields.
  "OtpInput/empty",
  "OtpInput/filled",
  "OtpInput/error",
  "PhoneField/empty",
  "PhoneField/error",
  // W2d-b: the NOW banner over each of B3's generated fixtures (acked.json is the hidden one), and the two long names.
  "NowBanner/one-patient",
  "NowBanner/two-patients",
  "NowBanner/abroad",
  "NowBanner/hidden",
  "NowBanner/long-name",
  "NowBanner/long-unbroken",
];

/**
 * One VerdictCard state per paper B2's contract test wrote: every 200 body in fixtures/api/documents, named by its file,
 * read off the directory (as the StatusChip ids are off CHIP_KINDS), so a new fixture needs a state here.
 */
const documentsDir = join(__dirname, "..", "..", "fixtures", "api", "documents");
const paperFixtureNames = () =>
  readdirSync(documentsDir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .filter((f) => (JSON.parse(readFileSync(join(documentsDir, f), "utf8")) as { status: number }).status === 200)
    .map((f) => f.slice(0, -".json".length));

const W2D_A = (): string[] => [...paperFixtureNames().map((name) => `VerdictCard/${name}`), "Toast/default", "Sheet/open"];

/** Plan W2e-b: the calendar over B3's generated months, the trend chart over B6's generated series. */
const W2E_B: string[] = [
  "MonthCalendar/empty-month",
  "MonthCalendar/busy-month",
  "MonthCalendar/long-name",
  "MonthCalendar/long-unbroken",
  "MonthCalendar/crowded-month",
  "TrendChart/one-point",
  "TrendChart/rising",
  "TrendChart/with-unknown",
  "TrendChart/critical-low",
];

/** Plan RANGE-web: the trend chart's printed range over B6's generated labs-printed-range. */
const RANGE_WEB: string[] = [
  "TrendChart/two-ranges",
  "TrendChart/one-sided-high",
  "TrendChart/one-sided-low",
  "TrendChart/range-withheld",
  "TrendChart/unknown-beside-range",
];

const ids = () => STATES.map(({ component, state }) => `${component}/${state}`);

describe("catalog states", () => {
  it("theRegistryHoldsExactlyTheShippedStates", () => {
    expect(ids()).toEqual([...SHIPPED, ...W2D_A(), ...W2E_B, ...RANGE_WEB]);
  });

  it("onlyTheListedStatesAreEmpty", () => {
    // catalog.spec.ts holds an empty state to painting nothing and excuses it from rendering something, so marking any
    // other state empty would hide a state that renders nothing. The hidden banner (Java's show: false) and one lab
    // reading (no chart: it is a sentence on the health screen, W9) are the only states whose rendering is nothing.
    expect(STATES.filter((s) => s.empty).map(({ component, state }) => `${component}/${state}`)).toEqual([
      "NowBanner/hidden",
      "TrendChart/one-point",
    ]);
  });

  it("everyPaperFixtureHasAVerdictCardState", () => {
    // Asserted directly, not only through the whole-registry list above.
    const names = paperFixtureNames();
    const registered = new Set(ids());
    expect(names.filter((name) => !registered.has(`VerdictCard/${name}`)), "paper fixtures with no VerdictCard state").toEqual([]);
    // Guard: the directory held papers of each kind the card draws differently, so the derived list is not vacuous.
    expect(names).toEqual(expect.arrayContaining(["now-bd", "now-abroad", "this-week-pending", "routine-released"]));
  });

  it("onlyNon200DocumentsFixturesHaveNoState", () => {
    // The fixtures left out are derived, never counted by hand: each one is a refusal (a non-200 envelope whose body is
    // exactly {reason}), so a second refusal B2 adds (a 403 view_only) needs no edit here.
    const names = new Set(paperFixtureNames());
    const excluded = readdirSync(documentsDir)
      .filter((f) => f.endsWith(".json") && !names.has(f.slice(0, -".json".length)))
      .map((f) => ({ f, json: JSON.parse(readFileSync(join(documentsDir, f), "utf8")) as { status: number; body: object } }));
    const notRefusals = excluded
      .filter(({ json }) => json.status === 200 || Object.keys(json.body).join(",") !== "reason")
      .map(({ f, json }) => `${f}: status ${json.status}, body keys ${Object.keys(json.body).join(",")}`);
    expect(notRefusals, "left-out fixtures that are not refusals").toEqual([]);
    // Guard: at least one fixture was left out, so the check above judged something.
    expect(excluded.length, "refusal fixtures").toBeGreaterThan(0);
  });

  it("noStateIsRegisteredTwice", () => {
    expect(new Set(ids()).size).toBe(STATES.length);
  });

  it("everyStateRendersInBothLanguages", () => {
    for (const { component, state, render } of STATES) {
      for (const lang of ["bn", "en"] as const) expect(render(lang), `${component}/${state} (${lang})`).toBeTruthy();
    }
  });
});
