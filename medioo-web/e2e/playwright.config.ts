import { randomBytes } from "node:crypto";
import { defineConfig, devices } from "@playwright/test";

// Run after `pnpm build`: the suite exercises the production server, because the CSP, the nonce and the byte budget
// are properties of the built app (next dev injects inline styles and an overlay that production never ships).
const PORT = Number(process.env.E2E_PORT ?? 3217);
export const BASE_URL = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${PORT}`;
// The catalog build (pnpm run build:catalog, .next-catalog/) on the next port: only the catalog project talks to it.
// The phone and desktop projects test the production build, where /app/catalog-test is a 404 (catalog-off.spec.ts).
const CATALOG_PORT = Number(process.env.E2E_CATALOG_PORT ?? PORT + 1);
export const CATALOG_URL = process.env.E2E_CATALOG_URL ?? `http://127.0.0.1:${CATALOG_PORT}`;

// The GIT_SHA the server under test runs with, so csp.spec.ts can require /app/healthz to report exactly it. Set on
// process.env (not a local const) so the workers, which re-read this file, inherit the one value the runner chose.
// Against an external server (E2E_BASE_URL) the caller passes the image's sha as E2E_GIT_SHA; without it that test fails.
// E2E_BUILD_SHA likewise for BUILD_SHA (the image's baked sha in production), a different value so the test sees
// each field come from its own variable.
if (!process.env.E2E_BASE_URL) {
  process.env.E2E_GIT_SHA ??= `e2e-${randomBytes(6).toString("hex")}`;
  process.env.E2E_BUILD_SHA ??= `e2e-build-${randomBytes(6).toString("hex")}`;
}

export default defineConfig({
  testDir: ".",
  // Playwright's own files are *.spec.ts; e2e/support/*.test.ts are Vitest unit tests of the specs' pure rules.
  testMatch: /\.spec\.ts$/,
  outputDir: "../test-results",
  reporter: [["list"], ["html", { outputFolder: "../playwright-report", open: "never" }]],
  forbidOnly: !!process.env.CI,
  retries: 0,
  use: { baseURL: BASE_URL },
  projects: [
    {
      name: "phone",
      testIgnore: /catalog\.spec\.ts/,
      use: { ...devices["Pixel 7"], viewport: { width: 390, height: 844 } },
    },
    {
      name: "desktop",
      // catalog-off.spec.ts asks one question of the production build; the phone project asks it.
      testIgnore: [/catalog\.spec\.ts/, /catalog-off\.spec\.ts/],
      use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } },
    },
    // The component catalog: both viewports and both schemes are set inside catalog.spec.ts (test.use, emulateMedia).
    { name: "catalog", testMatch: /catalog\.spec\.ts/, use: { ...devices["Desktop Chrome"], baseURL: CATALOG_URL } },
  ],
  webServer: [
    ...(process.env.E2E_BASE_URL
      ? []
      : [
          {
            command: `pnpm exec next start -p ${PORT}`,
            cwd: "..",
            url: `${BASE_URL}/app/healthz`,
            env: { GIT_SHA: process.env.E2E_GIT_SHA!, BUILD_SHA: process.env.E2E_BUILD_SHA! },
            // Never reuse: a server someone else started runs with another GIT_SHA, and the healthz test would be
            // right to fail on it.
            reuseExistingServer: false,
            timeout: 60_000,
          },
        ]),
    ...(process.env.E2E_CATALOG_URL
      ? []
      : [
          {
            // next.config.ts reads MEDIOO_CATALOG for the build directory too, so `next start` serves .next-catalog/.
            command: `MEDIOO_CATALOG=1 pnpm exec next start -p ${CATALOG_PORT}`,
            cwd: "..",
            url: `${CATALOG_URL}/app/healthz`,
            reuseExistingServer: false,
            timeout: 60_000,
          },
        ]),
  ],
});
