import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Page, type Response, type TestInfo } from "@playwright/test";
import { NEXT_PAGES } from "../src/routing/paths";
import { ceilingMessage, concretePath, fontProblems, isJavaImage, readCeilings, routeTotal } from "./support/budget";
import { useScenario } from "./support/browser-api";
import { PAGE_MARKERS, PAGE_SCENARIOS } from "./support/scenarios.mjs";

type Kind = "document" | "script" | "stylesheet" | "font" | "rsc" | "other";
type Row = {
  kind: Kind;
  url: string;
  status: number;
  sameOrigin: boolean;
  encoding: string;
  wire: number;
  decoded: number;
  javaImage: boolean;
};

/**
 * The DR-42 first-load wire budget, enforced (W1d).
 *
 * Every load is measured exactly as the W1a measurement measured /app/probe: a cold first load (a fresh context per
 * test), both self-hosted Noto subsets fetched and counted (forced in coldLoad, required of every load, the probe and
 * each routed page alike, by fontProblems), the encoded body bytes from the browser's network stack
 * (`responseBodySize`, not a content-length header, which a streamed HTML response does not have), headers not
 * counted. The ceilings are read from e2e/budget-ceilings.json and nowhere else, never from a probe measured in this
 * run, which would move with the framework. They are defined on the phone profile (Pixel 7, 390x844), so they are
 * enforced on the phone project once that project is shown to be that profile (enforcedHere); the desktop project
 * measures and records.
 *
 * The defining run is behind a Caddy replica of docs/infra/Caddyfile.medioo (scripts/budget-behind-caddy.sh), with
 * E2E_VIA_CADDY=1: every measured document must then carry Strict-Transport-Security, which only that Caddy block
 * sets, so a run that bypassed Caddy cannot pass. The suite's run against `next start` applies the same ceilings and
 * can only be stricter (Caddy encodes only what Next sent unencoded).
 */
const VIA_CADDY = process.env.E2E_VIA_CADDY === "1";
const ENFORCED_ON = "phone";

function kindOf(response: Response): Kind {
  const request = response.request();
  if (new URL(request.url()).searchParams.has("_rsc") || request.headers()["rsc"] === "1") return "rsc";
  const type = request.resourceType();
  if (type === "document" || type === "script" || type === "stylesheet" || type === "font") return type;
  return "other";
}

type Load = {
  rows: Row[];
  totals: Record<string, { count: number; wire: number; decoded: number }>;
  all: { wire: number; decoded: number };
  scriptRequests: string[];
  document: Response | null;
};

/** One cold load of `path`, every response measured. */
async function coldLoad(page: Page, origin: string, path: string): Promise<Load> {
  const responses: Response[] = [];
  // Every script the page TRIED to load, from the request side: a foreign script that never answers (DNS failure,
  // blocked, offline CI) produces no response, and a response-only check would never see it.
  const scriptRequests: string[] = [];
  page.on("request", (r) => {
    if (r.resourceType() === "script") scriptRequests.push(r.url());
  });
  page.on("response", (r) => {
    if (!r.url().startsWith("data:")) responses.push(r);
  });

  const doc = await page.goto(path);
  await page.waitForLoadState("networkidle");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  // The worst case the ceilings are defined on (a phone without Noto, DR-42 item 2) fetches both subsets, whatever
  // this page happens to lay out: globals.css loads them by unicode-range, so a page with no Bangla on its first render
  // (the `en` cookie, a Latin-only error state) would otherwise skip 69,784 B. Each load asks for one face through the
  // page's own @font-face rules, so a face that is broken or blocked still fetches nothing and fontProblems says so.
  await page.evaluate(() =>
    Promise.all([
      // U+0985 BENGALI LETTER A, by code point: copy lives in the catalog, never as a literal (bangla-literal.test.ts).
      document.fonts.load('400 16px "Noto Sans Bengali"', String.fromCodePoint(0x0985)),
      document.fonts.load('400 16px "Noto Sans"', "A"),
    ]).then(() => undefined),
  );
  await page.waitForLoadState("networkidle");

  const rows: Row[] = [];
  for (const r of responses) {
    if (r.status() >= 300 && r.status() < 400) continue;
    const body = await r.body();
    const sizes = await r.request().sizes();
    rows.push({
      kind: kindOf(r),
      url: r.url().replace(origin, ""),
      status: r.status(),
      sameOrigin: new URL(r.url()).origin === origin,
      encoding: r.headers()["content-encoding"] ?? "identity",
      wire: sizes.responseBodySize,
      decoded: body.length,
      javaImage: isJavaImage(r.url()),
    });
  }

  const totals: Load["totals"] = {};
  for (const row of rows) {
    const t = (totals[row.kind] ??= { count: 0, wire: 0, decoded: 0 });
    t.count += 1;
    t.wire += row.wire;
    t.decoded += row.decoded;
  }
  const all = rows.reduce((a, r) => ({ wire: a.wire + r.wire, decoded: a.decoded + r.decoded }), { wire: 0, decoded: 0 });
  return { rows, totals, all, scriptRequests, document: doc };
}

function record(testInfo: TestInfo, name: string, baseURL: string, load: Load, extra: object = {}) {
  const out = join(__dirname, "..", "test-results");
  mkdirSync(out, { recursive: true });
  const file = testInfo.project.name === "phone" ? `${name}.json` : `${name}-${testInfo.project.name}.json`;
  const { rows, totals, all } = load;
  writeFileSync(
    join(out, file),
    JSON.stringify({ project: testInfo.project.name, baseURL, viaCaddy: VIA_CADDY, ...extra, totals, all, rows }, null, 2) +
      "\n",
  );
}

/** What every measured load must satisfy before its total means anything. */
function expectAWholeMeasurement(load: Load, origin: string, path: string) {
  // The page itself was measured: a 404, an error page or a redirect to sign-in is a different (smaller) page.
  expect(load.document, `${path}: the navigation produced a document response`).not.toBeNull();
  expect(load.document!.status(), `${path}: the document answered 200`).toBe(200);
  expect(new URL(load.document!.url()).pathname, `${path}: the document is the page asked for`).toBe(path);
  if (VIA_CADDY) {
    // Only the Caddy block sets HSTS (Next does not, DR-40 A1): without it this run did not go through Caddy.
    expect(
      load.document!.headers()["strict-transport-security"],
      `${path}: E2E_VIA_CADDY=1 but the document has no Strict-Transport-Security, so the run bypassed Caddy`,
    ).toBe("max-age=31536000");
  }

  // Every script is our own.
  expect(load.scriptRequests.length, `${path}: script requests seen`).toBeGreaterThan(0);
  const foreign = load.scriptRequests.filter((url) => new URL(url).origin !== origin);
  expect(foreign, `${path}: third-party scripts`).toEqual([]);

  // A measurement that missed a class is flattering, not small.
  expect(load.totals.document?.count, `${path}: one HTML document`).toBe(1);
  expect(load.totals.script?.count ?? 0, `${path}: Next's client scripts`).toBeGreaterThan(0);
  expect(load.totals.stylesheet?.count ?? 0, `${path}: the stylesheet`).toBeGreaterThan(0);
  for (const r of load.rows) expect(r.wire, `wire bytes measured for ${r.url}`).toBeGreaterThan(0);
  // Both Noto subsets, on every measured load and not only the probe (DR-42 item 2): globals.css loads them by
  // unicode-range, so a load without one is up to 97,648 B under the worst case the ceilings are defined on.
  expect(fontProblems(load.rows), `${path}: both Noto subsets crossed the wire, once each`).toEqual([]);
}

/**
 * Whether this test enforces the ceilings. They are defined on a profile (Pixel 7, 390x844, DR-42 item 2), not on a
 * project's name, so the enforcing project's profile is asserted before a number is compared; and a config without a
 * project of that name fails here, so a rename cannot switch enforcement off in the `next start` run.
 */
async function enforcedHere(page: Page, testInfo: TestInfo): Promise<boolean> {
  expect(
    testInfo.config.projects.map((p) => p.name),
    `a project named "${ENFORCED_ON}" exists, or no run would enforce the ceilings`,
  ).toContain(ENFORCED_ON);
  if (testInfo.project.name !== ENFORCED_ON) return false;
  expect(page.viewportSize(), `the ${ENFORCED_ON} project measures at DR-42's 390x844`).toEqual({ width: 390, height: 844 });
  expect(testInfo.project.use.isMobile, `the ${ENFORCED_ON} project is a mobile profile`).toBe(true);
  expect(await page.evaluate(() => navigator.userAgent), `the ${ENFORCED_ON} project is the Pixel 7 profile`).toMatch(
    /\bPixel 7\b/,
  );
  return true;
}

test("the framework floor: a cold first load of the probe, within the probe ceiling", async ({ page, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const load = await coldLoad(page, origin, "/app/probe");
  record(testInfo, "budget", baseURL!, load);

  expectAWholeMeasurement(load, origin, "/app/probe");

  // The probe's own total, every response (it loads no Java image, and none is excused from the floor).
  const { probe } = readCeilings();
  if (await enforcedHere(page, testInfo)) {
    expect(load.all.wire, ceilingMessage("probe", "/app/probe", load.all.wire, probe)).toBeLessThanOrEqual(probe);
  }
});

// A page with no scenario would be measured against whatever stub Java answers by default (an error page, a redirect),
// and an empty list would measure nothing at all and pass: both fail here, not silently.
test("every routed page has a scenario and a marker to be measured under", () => {
  expect(NEXT_PAGES.length, "NEXT_PAGES lists the routed pages").toBeGreaterThan(0);
  expect(NEXT_PAGES.filter((p) => !Object.hasOwn(PAGE_SCENARIOS, p)), "pages with no PAGE_SCENARIOS entry").toEqual([]);
  expect(NEXT_PAGES.filter((p) => !Object.hasOwn(PAGE_MARKERS, p)), "pages with no PAGE_MARKERS entry").toEqual([]);
});

// Every routed page (NEXT_PAGES, held equal to the build's pages by src/routing/built/routes.built.test.ts) against the
// route ceiling, each under its PAGE_SCENARIOS scenario (W3). Its total is every response of the cold load except
// Java's crop and page images (S9).
for (const pattern of NEXT_PAGES) {
  test(`routed page ${pattern}: a cold first load within the route ceiling`, async ({ page, baseURL }, testInfo) => {
    const origin = new URL(baseURL!).origin;
    const scenario = PAGE_SCENARIOS[pattern];
    expect(scenario, `PAGE_SCENARIOS[${pattern}]`).toBeDefined();
    await useScenario(page, scenario!);
    const path = concretePath(pattern);
    const load = await coldLoad(page, origin, path);
    const total = routeTotal(load.rows);
    record(testInfo, `budget-route${path.replace(/[^a-zA-Z0-9]+/g, "-")}`, baseURL!, load, { path, scenario, routeTotal: total });

    expectAWholeMeasurement(load, origin, path);
    // The page itself, not an error page that also answered 200: its own marker is in the document Java's stub fed.
    const marker = PAGE_MARKERS[pattern]!;
    expect(await load.document!.text(), `${path}: the document holds ${marker}, so it is the page and not an error page`).toContain(
      marker,
    );
    const { route } = readCeilings();
    if (await enforcedHere(page, testInfo)) {
      expect(total, ceilingMessage("route", path, total, route)).toBeLessThanOrEqual(route);
    }
  });
}
