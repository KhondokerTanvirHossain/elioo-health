import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { NEXT_PAGES, previewPath } from "../paths";

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

/**
 * The catalog-only pages (CATALOG_ONLY_PAGES), from the one list that names them: deploy.sh's WEB_CATALOG_PAGES, which
 * its catalog probe fetches on the shipped container (plan W4). Never restated here.
 */
function catalogPages(): string[] {
  const line = readFileSync(join(root, "deploy.sh"), "utf8")
    .split("\n")
    .find((l) => l.startsWith("WEB_CATALOG_PAGES="));
  const list = /^WEB_CATALOG_PAGES="([^"]*)"$/.exec(line ?? "")?.[1];
  if (list === undefined) throw new Error('deploy.sh defines no WEB_CATALOG_PAGES="…"');
  return list.split(/\s+/).filter(Boolean);
}

/** Pages the build has that are not routed pages: Next's 404, the probe (measured against "probe"), the catalog. */
const notRouted = () => ["/_not-found", "/app/probe", ...catalogPages()].sort();

/**
 * The `not path` arguments of Caddy's preview matcher (docs/infra/Caddyfile.medioo, `@medioo_preview`): the preview
 * paths Caddy keeps on Java although they sit under /app/preview/*.
 */
function previewExclusions(): string[] {
  const lines = readFileSync(join(root, "docs", "infra", "Caddyfile.medioo"), "utf8")
    .split("\n")
    .map((l) => l.replace(/#.*$/, "").trim());
  const start = lines.indexOf("@medioo_preview {");
  if (start < 0) throw new Error("docs/infra/Caddyfile.medioo has no @medioo_preview matcher");
  const out: string[] = [];
  for (let i = start + 1; i < lines.length && lines[i] !== "}"; i++) {
    const m = /^not\s+path\s+(.+)$/.exec(lines[i]!);
    if (m) out.push(...m[1]!.split(/\s+/));
  }
  return out;
}

describe("NEXT_PAGES is what the build serves", () => {
  it("the manifest yields pages, so the comparison below did not read nothing", () => {
    const pages = manifestPages();
    console.log(`manifest pages: ${JSON.stringify(pages)}`);
    expect(pages.length).toBeGreaterThan(0);
  });

  it("every page it leaves out is in the build, so the exclusion is not stale", () => {
    const excluded = notRouted();
    expect(excluded, "the catalog probe names its pages").toEqual(expect.arrayContaining(["/app/catalog-test", "/app/shell-test"]));
    for (const page of excluded) expect(manifestPages(), `${page} is in the manifest`).toContain(page);
  });

  it("a page the build serves but does not route is kept off the preview by Caddy's preview matcher", () => {
    // The middleware rewrites /app/preview/<rest> to /app/<rest>, and the preview gate runs only inside a page's reads
    // of Java. A page that reads nothing from Java (the DR-42 budget probe) would render to any visitor under the
    // preview prefix, so Caddy must keep its preview path on Java. Derived from the build: a not-routed page added
    // later fails here until Caddy excludes it. Next's 404 is not under /app; the catalog never ships (deploy.sh's
    // catalog probe refuses an image that serves it).
    const offPreview = notRouted().filter((p) => p.startsWith("/app/") && !catalogPages().includes(p));
    expect(offPreview, "the probe is among them, so the check did not read nothing").toContain("/app/probe");
    const excluded = previewExclusions();
    for (const page of offPreview) {
      const at = previewPath(page);
      expect(excluded, `${at} is kept on Java`).toContain(at);
      expect(excluded, `${at}/* is kept on Java`).toContain(`${at}/*`);
    }
  });

  it("NEXT_PAGES equals the manifest's pages minus the pages that are not routed", () => {
    const routed = manifestPages().filter((p) => !notRouted().includes(p));
    console.log(`routed pages: ${JSON.stringify(routed)}`);
    expect([...NEXT_PAGES].sort()).toEqual(routed);
  });
});
