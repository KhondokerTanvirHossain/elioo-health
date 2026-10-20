import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test, type Response } from "@playwright/test";

type Kind = "document" | "script" | "stylesheet" | "font" | "rsc" | "other";
type Row = { kind: Kind; url: string; sameOrigin: boolean; encoding: string; wire: number; decoded: number };

function kindOf(response: Response): Kind {
  const request = response.request();
  if (new URL(request.url()).searchParams.has("_rsc") || request.headers()["rsc"] === "1") return "rsc";
  const type = request.resourceType();
  if (type === "document" || type === "script" || type === "stylesheet" || type === "font") return type;
  return "other";
}

/**
 * A cold load of /app/probe, measured per resource class: decoded bytes (what the browser parses) and wire bytes (the
 * encoded body actually transferred, from the network stack rather than a content-length header, which a streamed
 * HTML response does not have). The numbers feed the DR-42 proposal; this spec does not enforce a budget yet — the PO
 * sets it (plan Part B, decision 1). It fails only on a third-party script, or on a measurement that missed something.
 */
test("cold first load of the probe, by resource class", async ({ page, baseURL }, testInfo) => {
  const origin = new URL(baseURL!).origin;
  const responses: Response[] = [];
  page.on("response", (r) => {
    if (!r.url().startsWith("data:")) responses.push(r);
  });

  await page.goto("/app/probe");
  await page.waitForLoadState("networkidle");
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.waitForLoadState("networkidle");

  const rows: Row[] = [];
  for (const r of responses) {
    if (r.status() >= 300 && r.status() < 400) continue;
    const body = await r.body();
    const sizes = await r.request().sizes();
    rows.push({
      kind: kindOf(r),
      url: r.url().replace(origin, ""),
      sameOrigin: new URL(r.url()).origin === origin,
      encoding: r.headers()["content-encoding"] ?? "identity",
      wire: sizes.responseBodySize,
      decoded: body.length,
    });
  }

  const totals: Record<string, { count: number; wire: number; decoded: number }> = {};
  for (const row of rows) {
    const t = (totals[row.kind] ??= { count: 0, wire: 0, decoded: 0 });
    t.count += 1;
    t.wire += row.wire;
    t.decoded += row.decoded;
  }
  const all = rows.reduce((a, r) => ({ wire: a.wire + r.wire, decoded: a.decoded + r.decoded }), { wire: 0, decoded: 0 });

  const out = join(__dirname, "..", "test-results");
  mkdirSync(out, { recursive: true });
  const file = testInfo.project.name === "phone" ? "budget.json" : `budget-${testInfo.project.name}.json`;
  writeFileSync(
    join(out, file),
    JSON.stringify({ project: testInfo.project.name, baseURL, totals, all, rows }, null, 2) + "\n",
  );

  // The one rule enforced now: every script is our own.
  const foreign = rows.filter((r) => r.kind === "script" && !r.sameOrigin).map((r) => r.url);
  expect(foreign, "third-party scripts").toEqual([]);

  // A measurement that missed a class is flattering, not small: require each class the probe is built to exercise.
  expect(totals.document?.count, "one HTML document").toBe(1);
  expect(totals.script?.count ?? 0, "Next's client scripts").toBeGreaterThan(0);
  expect(totals.stylesheet?.count ?? 0, "the stylesheet").toBeGreaterThan(0);
  const fonts = rows.filter((r) => r.kind === "font").map((r) => r.url).sort();
  expect(fonts, "both Noto subsets crossed the wire").toEqual([
    "/fonts/noto-sans-bengali.woff2",
    "/fonts/noto-sans-latin.woff2",
  ]);
  for (const r of rows) expect(r.wire, `wire bytes measured for ${r.url}`).toBeGreaterThan(0);
});
