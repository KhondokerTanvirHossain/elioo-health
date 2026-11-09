import { describe, expect, it } from "vitest";
import { DESKTOP_DEFAULT, desktopLayout } from "./desktop";

describe("desktopLayout, the preview gate for the desktop layout (plan 2026-11-07 Task 2)", () => {
  it("a preview request gets the desktop layout", () => {
    expect(desktopLayout({ preview: true })).toBe(true);
  });

  it("a live request gets whatever DESKTOP_DEFAULT says", () => {
    expect(desktopLayout({ preview: false })).toBe(DESKTOP_DEFAULT);
  });

  // Task 10, the flip (PO, 2026-11-09, DR-51): every family gets the desktop layout from 900 px, not only the preview.
  it("DESKTOP_DEFAULT is true, so a live request gets the desktop layout", () => {
    expect(DESKTOP_DEFAULT).toBe(true);
    expect(desktopLayout({ preview: false })).toBe(true);
  });
});
