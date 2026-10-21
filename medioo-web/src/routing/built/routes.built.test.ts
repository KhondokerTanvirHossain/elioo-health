import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { NEXT_PAGES } from "../paths";

/*
 * Runs on the production build (pnpm build && pnpm run test:built). NEXT_PAGES is the list budget.spec.ts measures
 * against the DR-42 route ceiling (S9: the page list is NEXT_PAGES, not the ceilings file). A hand-written list is the
 * check this repo has learned not to trust, so it is held equal to what the build actually serves: every page entry of
 * .next/app-path-routes-manifest.json, minus the pages that are not routed pages. A page added without a NEXT_PAGES
 * entry fails here, so it cannot escape the budget.
 */
const web = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "..");
const root = join(web, "..");

function manifestPages(): string[] {
  let manifest: Record<string, string>;
  try {
    manifest = JSON.parse(readFileSync(join(web, ".next", "app-path-routes-manifest.json"), "utf8"));
  } catch {
    throw new Error("run pnpm build first: no .next/app-path-routes-manifest.json");
  }
  // "/app/probe/page" -> "/app/probe"; route handlers ("/app/healthz/route") are not pages.
  return Object.entries(manifest)
    .filter(([entry]) => entry.endsWith("/page"))
    .map(([, path]) => path)
    .sort();
}

/** The component catalog's pages, from the one place they are named today: the catalog probe in deploy.sh. */
function catalogPages(): string[] {
  const line = readFileSync(join(root, "deploy.sh"), "utf8")
    .split("\n")
    .find((l) => l.startsWith("WEB_CATALOG_PROBE="));
  if (!line) throw new Error("deploy.sh defines no WEB_CATALOG_PROBE");
  return [...line.matchAll(/http:\/\/127\.0\.0\.1:3000(\/app\/[^'"?]+)/g)].map((m) => m[1]!);
}

/** Pages the build has that are not routed pages: Next's 404, the probe (measured against "probe"), the catalog. */
const notRouted = () => ["/_not-found", "/app/probe", ...catalogPages()].sort();

describe("NEXT_PAGES is what the build serves", () => {
  it("the manifest yields pages, so the comparison below did not read nothing", () => {
    const pages = manifestPages();
    console.log(`manifest pages: ${JSON.stringify(pages)}`);
    expect(pages.length).toBeGreaterThan(0);
  });

  it("every page it leaves out is in the build, so the exclusion is not stale", () => {
    const excluded = notRouted();
    expect(excluded, "the catalog probe names its page").toContain("/app/catalog-test");
    for (const page of excluded) expect(manifestPages(), `${page} is in the manifest`).toContain(page);
  });

  it("NEXT_PAGES equals the manifest's pages minus the pages that are not routed", () => {
    const routed = manifestPages().filter((p) => !notRouted().includes(p));
    console.log(`routed pages: ${JSON.stringify(routed)}`);
    expect([...NEXT_PAGES].sort()).toEqual(routed);
  });
});
