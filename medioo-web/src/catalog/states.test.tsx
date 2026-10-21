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

const ids = () => STATES.map(({ component, state }) => `${component}/${state}`);

describe("catalog states", () => {
  it("theRegistryHoldsExactlyTheShippedStates", () => {
    expect(ids()).toEqual(SHIPPED);
  });

  it("onlyTheHiddenBannerIsEmpty", () => {
    // catalog.spec.ts holds an empty state to painting nothing and excuses it from rendering something, so marking any
    // other state empty would hide a state that renders nothing.
    expect(STATES.filter((s) => s.empty).map(({ component, state }) => `${component}/${state}`)).toEqual(["NowBanner/hidden"]);
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
