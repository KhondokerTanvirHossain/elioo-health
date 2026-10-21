import { expect, test } from "@playwright/test";

// The catalog is a development surface (plan W2c): the production build answers its route as any unknown path. The
// shipped image is checked the same way inside its container by deploy.sh's WEB_CATALOG_PROBE.
test("theCatalogIs404InAProductionBuild", async ({ request }) => {
  const response = await request.get("/app/catalog-test");
  expect(response.status(), "GET /app/catalog-test on the production build").toBe(404);
  expect(await response.text(), "the 404 body carries no catalog state").not.toContain("data-catalog-state");
});
