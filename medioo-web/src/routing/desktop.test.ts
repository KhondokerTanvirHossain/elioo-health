import { describe, expect, it } from "vitest";
import { DESKTOP_DEFAULT, desktopLayout } from "./desktop";

describe("desktopLayout, the preview gate for the desktop layout (plan 2026-11-07 Task 2)", () => {
  it("a preview request gets the desktop layout", () => {
    expect(desktopLayout({ preview: true })).toBe(true);
  });

  it("a live request gets whatever DESKTOP_DEFAULT says", () => {
    expect(desktopLayout({ preview: false })).toBe(DESKTOP_DEFAULT);
  });

  // Task 10 (the flip, only after the PO approves Phase 1 in preview) changes this test to `true` first and watches it
  // fail; until then live users see no change.
  it("DESKTOP_DEFAULT is false, so a live request does not get the desktop layout", () => {
    expect(DESKTOP_DEFAULT).toBe(false);
    expect(desktopLayout({ preview: false })).toBe(false);
  });
});
