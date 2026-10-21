import { describe, expect, it } from "vitest";
import { CHIP_KINDS } from "@/components/StatusChip/StatusChip";
import { STATES } from "./states";

/*
 * The registry, as an explicit list (plan W2c). catalog.spec.ts reads the registry's length off the page that renders
 * it, so on its own it would accept any registry; this list is independent of the page. A later component PR (W2d,
 * W2e) appends its states here, and removing a state already shipped fails.
 */
const W2C: string[] = [
  "Wordmark/default",
  "MioAvatar/32",
  "MioAvatar/48-still",
  "MioAvatar/96",
  "MessageBubble/recv",
  "MessageBubble/sent",
  "MessageBubble/long-unbroken",
  // One chip state per kind, derived from the kinds the component knows, so a kind added there needs a state here.
  ...CHIP_KINDS.map((kind) => `StatusChip/${kind}`),
];

const ids = () => STATES.map(({ component, state }) => `${component}/${state}`);

describe("catalog states", () => {
  it("theRegistryHoldsExactlyTheShippedStates", () => {
    expect(ids()).toEqual(W2C);
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
