import { describe, expect, it } from "vitest";
import ownerOne from "../../../fixtures/api/upload-options/owner-one.json";
import type { UploadOptionsJson } from "@/api/types";
import { downscaleOf, isKnown, pageName, plan, preparePage } from "./prepare";

/*
 * What a page is before it leaves the phone, as Java's own sheet makes it (upload.js `known`, `plan`, `pageName`):
 * the vectors are upload.js's own rules, written out, so a port that drifts from them fails here.
 */

const on = { edge: 1600, quality: 0.85, passBytes: 1_500_000 };

describe("a page before it is sent", () => {
  it("onlyTheKindsJavaReadsAreTaken", () => {
    const file = (type: string) => new File([new Uint8Array([1])], "x", { type });
    for (const type of ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "application/pdf", ""]) {
      expect(isKnown(file(type)), type || "(no type)").toBe(true);
    }
    for (const type of ["application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain", "image/gif", "video/mp4"]) {
      expect(isKnown(file(type)), type).toBe(false);
    }
  });

  it("aPageGoesUnderItsNumberNeverItsOwnName", () => {
    expect([pageName(0, "jpg"), pageName(1, "pdf"), pageName(9, "png")]).toEqual(["page-1.jpg", "page-2.pdf", "page-10.png"]);
  });

  it("theDownscaleIsUploadJsPlan", () => {
    // Off while the server's edge is 0 (the shipped default).
    expect(plan(4000, 3000, 9_000_000, "image/jpeg", { ...on, edge: 0 })).toBeNull();
    // Only the kinds a canvas re-encodes.
    expect(plan(4000, 3000, 9_000_000, "image/heic", on)).toBeNull();
    expect(plan(4000, 3000, 9_000_000, "application/pdf", on)).toBeNull();
    // A JPEG already within the edge and under the pass-through bytes is never re-compressed.
    expect(plan(1600, 1200, 1_500_000, "image/jpeg", on)).toBeNull();
    // Within the edge but heavy: re-encoded at its own size.
    expect(plan(1600, 1200, 1_500_001, "image/jpeg", on)).toEqual({ width: 1600, height: 1200, quality: 0.85 });
    // Bigger than the edge: the longest side to the edge, the other in proportion.
    expect(plan(4000, 3000, 900_000, "image/jpeg", on)).toEqual({ width: 1600, height: 1200, quality: 0.85 });
    expect(plan(3000, 4000, 900_000, "image/png", on)).toEqual({ width: 1200, height: 1600, quality: 0.85 });
  });

  it("withDownscalingOffAPageIsSentAsItIs", async () => {
    const limits = (ownerOne.body as UploadOptionsJson).limits;
    expect(limits.downscale_max_edge, "the fixture is the shipped default: off").toBe(0);
    const file = new File([new Uint8Array([0xff, 0xd8, 0xff, 7])], "Rahima Begum CBC.jpg", { type: "image/jpeg" });
    const page = await preparePage(file, 2, downscaleOf(limits));
    expect(page.blob, "the very bytes the family chose").toBe(file);
    expect(page.name).toBe("page-3.jpg");
  });
});
