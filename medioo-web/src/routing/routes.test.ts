import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { JAVA_PAGES } from "./paths";

/** The `GET /app…` page patterns of Java's generated route snapshot, `/app/api/**` left out. */
export function snapshotPages(text: string): string[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.startsWith("GET /app"))
    .map((line) => line.slice("GET ".length))
    .filter((path) => path !== "/app/api" && !path.startsWith("/app/api/"));
}

describe("routes", () => {
  it("javaPagesAreTheSnapshotsGetRoutes", () => {
    const snapshot = readFileSync(join(__dirname, "..", "..", "..", "docs", "infra", "app-routes.txt"), "utf8");
    const pages = snapshotPages(snapshot);
    // Guard: the parse read the snapshot's pages, so equality is not two empty lists.
    expect(pages.length, "GET /app pages parsed from app-routes.txt").toBeGreaterThan(10);
    expect(pages).toContain("/app/documents/{id}");
    expect([...JAVA_PAGES].sort()).toEqual([...pages].sort());
  });
});
