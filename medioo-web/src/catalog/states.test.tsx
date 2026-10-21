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

const ids = () => STATES.map(({ component, state }) => `${component}/${state}`);

describe("catalog states", () => {
  it("theRegistryHoldsExactlyTheShippedStates", () => {
    expect(ids()).toEqual([...SHIPPED, ...W2D_A()]);
  });

  it("onlyTheHiddenBannerIsEmpty", () => {
    // catalog.spec.ts holds an empty state to painting nothing and excuses it from rendering something, so marking any
    // other state empty would hide a state that renders nothing.
    expect(STATES.filter((s) => s.empty).map(({ component, state }) => `${component}/${state}`)).toEqual(["NowBanner/hidden"]);
  });

  it("everyPaperFixtureHasAVerdictCardState", () => {
    // Guard: the directory held papers of each kind the card draws differently, so the derived list is not vacuous.
    const names = paperFixtureNames();
    expect(names).toEqual(expect.arrayContaining(["now-bd", "now-abroad", "this-week-pending", "routine-released"]));
    // The documents fixtures left out are exactly the ones that are not papers (a non-200 refusal).
    const all = readdirSync(documentsDir).filter((f) => f.endsWith(".json"));
    expect(all.length - names.length, "non-paper documents fixtures").toBe(1);
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
