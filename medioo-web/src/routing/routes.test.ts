import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { JAVA_PAGES } from "./paths";

/**
 * The page patterns of Java's generated route snapshot that answer GET: `GET /app…` lines and `* /app…` lines ("*" is
 * any method, GET included), `/app/api/**` left out.
 */
export function snapshotPages(text: string): string[] {
  return text
    .split("\n")
    .map((line) => line.trim())
    .map((line) => /^(?:GET|\*) (\/app(?:\/\S*)?)$/.exec(line)?.[1])
    .filter((path): path is string => path !== undefined)
    .filter((path) => path !== "/app/api" && !path.startsWith("/app/api/"));
}

describe("routes", () => {
  it("anyMethodPageLinesAreGetPagesToo", () => {
    // "*" is any method in the snapshot: a page Java serves under "*" answers GET, so it is a Java page; "* /app/api/**"
    // is the API's catch-all and is not.
    const text = ["# comment", "* /app/api/**", "* /app/x", "GET /app/home", "POST /app/login", "GET /app/api/me", ""].join("\n");
    expect(snapshotPages(text).sort()).toEqual(["/app/home", "/app/x"]);
  });


  it("javaPagesAreTheSnapshotsGetRoutes", () => {
    const snapshot = readFileSync(join(__dirname, "..", "..", "..", "docs", "infra", "app-routes.txt"), "utf8");
    const pages = snapshotPages(snapshot);
    // Guard: the parse read the snapshot's pages, so equality is not two empty lists.
    expect(pages.length, "GET /app pages parsed from app-routes.txt").toBeGreaterThan(10);
    expect(pages).toContain("/app/documents/{id}");
    expect([...JAVA_PAGES].sort()).toEqual([...pages].sort());
  });
});
