import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  CADDY_MIN_LENGTH,
  CEILINGS_FILE,
  NOTO_SUBSETS,
  ceilingMessage,
  concretePath,
  fontProblems,
  isJavaImage,
  readCeilings,
  routeTotal,
  unencodedByCaddy,
} from "./budget";

// The rules budget.spec.ts enforces with, judged here without a browser (DR-42 items 2-4, screens plan S9).
const dir = mkdtempSync(join(tmpdir(), "budget-ceilings-"));
const file = (name: string, text: string) => {
  const path = join(dir, name);
  writeFileSync(path, text);
  return path;
};

describe("readCeilings", () => {
  it("reads the committed file, and it holds exactly DR-42's two ceilings, as docs/DECISIONS.md records them", () => {
    // The numbers are the PO's record (DR-42 items 2 and 3), parsed from it rather than restated here: a ceilings change
    // fails this test unless DECISIONS.md, which changes only with the PO, changes with it.
    const decisions = readFileSync(join(__dirname, "..", "..", "..", "docs", "DECISIONS.md"), "utf8");
    const start = decisions.indexOf("\n## DR-42 |");
    expect(start, "the DR-42 section was found in docs/DECISIONS.md").toBeGreaterThanOrEqual(0);
    const end = decisions.indexOf("\n## ", start + 1);
    const dr42 = decisions.slice(start, end < 0 ? undefined : end);
    const number = (re: RegExp, what: string) => {
      const m = re.exec(dr42);
      expect(m, `DR-42 states ${what}`).not.toBeNull();
      return Number((m?.[1] ?? "").replace(/,/g, ""));
    };
    const route = number(/^2\. \*\*First-load wire budget per routed page: ≤ ([\d,]+) B/m, "item 2's route ceiling");
    const probe = number(/^3\. \*\*Framework floor ceiling: the probe ≤ ([\d,]+) B/m, "item 3's probe ceiling");
    expect(Number.isSafeInteger(probe) && probe > 0 && Number.isSafeInteger(route) && route > 0).toBe(true);
    expect(readCeilings(CEILINGS_FILE)).toEqual({ probe, route });
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

describe("fontProblems (DR-42 item 2: both self-hosted Noto subsets fetched and counted, on every measured load)", () => {
  const BN = "/fonts/noto-sans-bengali.woff2";
  const LATIN = "/fonts/noto-sans-latin.woff2";
  const font = (url: string, over: Partial<{ status: number; wire: number; kind: string }> = {}) => ({
    kind: "font",
    url,
    status: 200,
    wire: url === BN ? 69_784 : 27_864,
    ...over,
  });
  const page = [
    { kind: "document", url: "/app/verify", status: 200, wire: 3_465 },
    { kind: "script", url: "/_next/static/chunks/main-app.js", status: 200, wire: 50_000 },
  ];

  it("names the two subsets the W1a probe measured, and nothing else", () => {
    expect([...NOTO_SUBSETS]).toEqual([BN, LATIN]);
  });

  it("passes a load on which both subsets crossed the wire, once each", () => {
    expect(fontProblems([...page, font(LATIN), font(BN)])).toEqual([]);
  });

  it("refuses a load missing the Bengali subset (a page that laid out no Bangla, or whose face failed)", () => {
    expect(fontProblems([...page, font(LATIN)])).toEqual([`missing ${BN}`]);
  });

  it("refuses a load missing the Latin subset", () => {
    expect(fontProblems([...page, font(BN)])).toEqual([`missing ${LATIN}`]);
  });

  it("refuses a load missing both", () => {
    expect(fontProblems(page)).toEqual([`missing ${BN}`, `missing ${LATIN}`]);
  });

  it("refuses a subset that answered other than 200 (a broken path measures a small error body)", () => {
    expect(fontProblems([...page, font(BN, { status: 404, wire: 120 }), font(LATIN)])).toEqual([`${BN} answered 404`]);
  });

  it("refuses a subset with no wire bytes measured", () => {
    expect(fontProblems([...page, font(BN), font(LATIN, { wire: 0 })])).toEqual([`${LATIN} has 0 B on the wire`]);
  });

  it("refuses a subset fetched twice (the measurement is no longer one cold load)", () => {
    expect(fontProblems([...page, font(BN), font(BN), font(LATIN)])).toEqual([`${BN} fetched 2 times`]);
  });

  it("refuses a font that is neither subset, so a third face cannot ride along unexamined", () => {
    expect(fontProblems([...page, font(BN), font(LATIN), font("/fonts/other.woff2")])).toEqual([
      "unexpected font /fonts/other.woff2",
    ]);
  });

  it("does not count a non-font response at a subset's path as the font", () => {
    expect(fontProblems([...page, font(BN, { kind: "other" }), font(LATIN)])).toEqual([`missing ${BN}`]);
  });

  it("matches a subset by path, whatever the origin or query", () => {
    expect(fontProblems([...page, font(`https://medioo.example${BN}?v=1`), font(LATIN)])).toEqual([]);
  });
});

describe("unencodedByCaddy (PO ruling P3: what was measured is what Caddy sends a phone)", () => {
  type Row = { kind: string; url: string; status: number; encoding: string; decoded: number };
  const row = (over: Partial<Row> = {}): Row => ({
    kind: "document",
    url: "/app/probe",
    status: 200,
    encoding: "zstd",
    decoded: 7983,
    ...over,
  });

  it("passes a load whose document, scripts and stylesheet all came zstd-encoded, with fonts left as they are", () => {
    expect(
      unencodedByCaddy([
        row(),
        row({ kind: "script", url: "/_next/static/chunks/a.js", decoded: 173019 }),
        row({ kind: "stylesheet", url: "/_next/static/css/a.css", decoded: 4944 }),
        row({ kind: "font", url: NOTO_SUBSETS[0], encoding: "identity", decoded: 69784 }),
        row({ kind: "other", url: "/brand/mio-head.webp", encoding: "identity", decoded: 4620 }),
      ]),
    ).toEqual([]);
  });

  it("refuses a document measured unencoded: nothing stood in front of Next, and no phone gets that", () => {
    expect(unencodedByCaddy([row({ encoding: "identity" })])).toEqual(["/app/probe (document, 7983 B, content-encoding identity)"]);
  });

  it("refuses a script Next gzipped itself: compress: false undone, the defect P3 fixed", () => {
    expect(unencodedByCaddy([row(), row({ kind: "script", url: "/_next/static/chunks/a.js", encoding: "gzip", decoded: 9000 })])).toEqual([
      "/_next/static/chunks/a.js (script, 9000 B, content-encoding gzip)",
    ]);
  });

  it("refuses an RSC payload and a stylesheet the same way", () => {
    expect(
      unencodedByCaddy([
        row({ kind: "rsc", url: "/app?_rsc=1", encoding: "identity" }),
        row({ kind: "stylesheet", url: "/a.css", encoding: "gzip" }),
      ]),
    ).toEqual(["/app?_rsc=1 (rsc, 7983 B, content-encoding identity)", "/a.css (stylesheet, 7983 B, content-encoding gzip)"]);
  });

  it("leaves a body of 512 B or less alone, as Caddy does, and not one of 513 B", () => {
    expect(unencodedByCaddy([row({ encoding: "identity", decoded: CADDY_MIN_LENGTH })])).toEqual([]);
    expect(unencodedByCaddy([row({ encoding: "identity", decoded: CADDY_MIN_LENGTH + 1 })])).toHaveLength(1);
  });

  it("leaves a response that was not a 200 to the checks that judge it", () => {
    expect(unencodedByCaddy([row({ status: 404, encoding: "identity" })])).toEqual([]);
  });
});
