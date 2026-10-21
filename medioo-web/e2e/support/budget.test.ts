import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { CEILINGS_FILE, ceilingMessage, concretePath, isJavaImage, readCeilings, routeTotal } from "./budget";

// The rules budget.spec.ts enforces with, judged here without a browser (DR-42 items 2-4, screens plan S9).
const dir = mkdtempSync(join(tmpdir(), "budget-ceilings-"));
const file = (name: string, text: string) => {
  const path = join(dir, name);
  writeFileSync(path, text);
  return path;
};

describe("readCeilings", () => {
  it("reads the committed file, and it holds exactly the two ceilings", () => {
    const c = readCeilings(CEILINGS_FILE);
    expect(Object.keys(c).sort()).toEqual(["probe", "route"]);
    expect(Number.isSafeInteger(c.probe) && c.probe > 0).toBe(true);
    expect(Number.isSafeInteger(c.route) && c.route > 0).toBe(true);
  });

  it("refuses a missing file by name", () => {
    expect(() => readCeilings(join(dir, "absent.json"))).toThrow(/budget-ceilings: cannot read .*absent\.json/);
  });

  it("refuses a file that is not JSON", () => {
    expect(() => readCeilings(file("bad.json", "{ probe: 1 "))).toThrow(/budget-ceilings: .*bad\.json is not JSON/);
  });

  it("refuses a missing ceiling", () => {
    expect(() => readCeilings(file("one.json", '{"probe": 214167}'))).toThrow(
      /must hold exactly "probe" and "route", found \["probe"\]/,
    );
  });

  it("refuses an extra key, such as a page list (S9: the page list is NEXT_PAGES)", () => {
    expect(() => readCeilings(file("pages.json", '{"probe": 1, "route": 2, "pages": ["/app"]}'))).toThrow(
      /must hold exactly "probe" and "route", found \["pages","probe","route"\]/,
    );
  });

  it.each([["0"], ["-1"], ["1.5"], ['"265409"'], ["null"]])("refuses a ceiling of %s", (value) => {
    expect(() => readCeilings(file("value.json", `{"probe": 214167, "route": ${value}}`))).toThrow(
      /"route" must be a positive whole number of bytes/,
    );
  });
});

describe("isJavaImage (S9: Java's crop and page images are not part of a page's route total)", () => {
  it("excludes exactly the two image routes Java serves, as docs/infra/app-routes.txt spells them", () => {
    const routes = readFileSync(join(__dirname, "..", "..", "..", "docs", "infra", "app-routes.txt"), "utf8")
      .split("\n")
      .map((l) => l.trim());
    expect(routes, "the route snapshot was read").toContain("GET /app/documents/{id}");
    expect(routes).toContain("GET /app/documents/{id}/image");
    expect(routes).toContain("GET /app/documents/{id}/page/{n}");
    // Every other GET route of Java's, with its segments filled, is counted.
    const counted = routes
      .filter((l) => l.startsWith("GET /app/") && !/\/documents\/\{id\}\/(image|page\/\{n\})$/.test(l))
      .map((l) => l.slice("GET ".length).replace(/\{[^}]+\}/g, "x1"));
    expect(counted.length).toBeGreaterThan(0);
    for (const path of counted) expect(isJavaImage(path), path).toBe(false);
  });

  it.each([
    "/app/documents/0b3f9c8e-1111-4222-8333-944455556666/image?key=v1-abc",
    "/app/documents/0b3f9c8e-1111-4222-8333-944455556666/image",
    "/app/documents/0b3f9c8e-1111-4222-8333-944455556666/page/1",
    "https://medioo.example/app/documents/abc/page/12",
  ])("excludes %s", (url) => expect(isJavaImage(url)).toBe(true));

  it.each([
    "/app/documents/0b3f9c8e-1111-4222-8333-944455556666",
    "/app/documents/abc/all",
    "/app/documents/abc/image/extra",
    "/app/documents/abc/images",
    "/app/documents/abc/page",
    "/app/documents/abc/page/1/more",
    "/app/documents//image",
    "/brand/mio.webp",
    "/fonts/noto-sans-bengali.woff2",
    "/_next/static/chunks/main-app.js",
    "/app/probe?next=/app/documents/abc/image",
  ])("counts %s", (url) => expect(isJavaImage(url)).toBe(false));
});

describe("routeTotal", () => {
  it("sums every row's wire bytes except Java's images", () => {
    const rows = [
      { url: "/app/documents/abc", wire: 4_000 },
      { url: "/_next/static/chunks/a.js", wire: 100_000 },
      { url: "/fonts/noto-sans-bengali.woff2", wire: 69_784 },
      { url: "/app/documents/abc/image?key=k1", wire: 50_000 },
      { url: "/app/documents/abc/page/1", wire: 70_000 },
    ];
    expect(routeTotal(rows)).toBe(173_784);
  });

  it("counts an image of any other origin or path (only Java's two image routes are left out)", () => {
    expect(routeTotal([{ url: "/brand/mio.webp", wire: 20_000 }, { url: "/app/documents/abc/images", wire: 5 }])).toBe(
      20_005,
    );
  });
});

describe("ceilingMessage", () => {
  it("names the ceiling, the page, and the measured and allowed bytes", () => {
    expect(ceilingMessage("route", "/app/verify", 270_001, 265_409)).toBe(
      'route ceiling (budget-ceilings.json "route"): /app/verify is 270,001 B on the wire, allowed 265,409 B (4,592 B over)',
    );
  });
});

describe("concretePath", () => {
  it("keeps a static page", () => {
    expect(concretePath("/app")).toBe("/app");
    expect(concretePath("/app/verify")).toBe("/app/verify");
  });

  it("refuses a dynamic segment it has no fixture for, rather than measuring a 404", () => {
    expect(() => concretePath("/app/documents/[id]")).toThrow(/\/app\/documents\/\[id\] has a dynamic segment/);
  });
});
