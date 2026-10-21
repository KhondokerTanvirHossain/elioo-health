import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// The brand files the app serves are the PO's approved masters, byte for byte (master plan, Brand): Mio "as drawn",
// the wordmark from medioo-light.svg / medioo-dark.svg. A re-export, a recompression or a swapped file fails here.
const web = join(__dirname, "..", "..");
const served = join(web, "public", "brand");
const approved = join(web, "..", "docs", "design", "2026-10-21-approved");

const sha256 = (path: string) => createHash("sha256").update(readFileSync(path)).digest("hex");

describe("brand assets", () => {
  it("theServedBrandFilesAreTheApprovedOnesByteForByte", () => {
    const files = readdirSync(served).sort();
    expect(files).toEqual(["medioo-dark.svg", "medioo-light.svg", "mio-head.webp", "mio.webp"]);
    const differ = files.filter((f) => sha256(join(served, f)) !== sha256(join(approved, f)));
    expect(differ, "served files that are not the approved master of the same name").toEqual([]);
  });
});
