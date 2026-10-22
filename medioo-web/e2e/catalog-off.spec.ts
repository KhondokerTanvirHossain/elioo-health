import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, test } from "@playwright/test";

// The catalog-only pages are a development surface (plan W2c, W4): the production build answers each as any unknown
// path. The shipped image is checked the same way inside its container by deploy.sh's WEB_CATALOG_PROBE, over the same
// pages: the list is read from deploy.sh's WEB_CATALOG_PAGES, never restated, so a page added there is checked here.
const line = readFileSync(join(__dirname, "..", "..", "deploy.sh"), "utf8")
  .split("\n")
  .find((l) => l.startsWith("WEB_CATALOG_PAGES="));
const PAGES = (/^WEB_CATALOG_PAGES="([^"]*)"/.exec(line ?? "")?.[1] ?? "").split(/\s+/).filter(Boolean);

test("theCatalogPagesAreReadFromDeploySh", () => {
  expect(PAGES, "deploy.sh's WEB_CATALOG_PAGES").toEqual(expect.arrayContaining(["/app/catalog-test", "/app/shell-test"]));
});

for (const path of PAGES) {
  test(`theCatalogIs404InAProductionBuild ${path}`, async ({ request }) => {
    const response = await request.get(path);
    expect(response.status(), `GET ${path} on the production build`).toBe(404);
    const body = await response.text();
    expect(body, "the 404 body carries no catalog state").not.toContain("data-catalog-state");
    expect(body, "the 404 body carries no shell").not.toContain("app-shell");
  });
}
