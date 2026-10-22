import { randomBytes } from "node:crypto";
import { join } from "node:path";
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

// Stub Java (e2e/support/java-stub.mjs): `next start` reads it as JAVA_INTERNAL_URL, and browser-api.ts reads its
// request log. Set on process.env so the workers, which re-read this file, see the same port.
const STUB_PORT = Number(process.env.E2E_STUB_PORT ?? PORT + 73);
process.env.E2E_PORT ??= String(PORT);
process.env.E2E_STUB_URL ??= `http://127.0.0.1:${STUB_PORT}`;
// The fixtures Java's contract tests wrote, which stub Java and browser-api.ts answer from (support/scenarios.mjs).
process.env.E2E_FIXTURES ??= join(__dirname, "..", "fixtures", "api");

// The Next server's own output, teed to a file the `logs` project reads after every other page test has run (plan W3:
// no screen text in the server log). Under test-results/, which git ignores.
const SERVER_LOG = "test-results/next-server.log";

// Never in the gates: the screenshot board (BOARD=1), published privately, never committed.
const BOARD = process.env.BOARD === "1";
// shell.spec.ts runs in the catalog project: the shell's page (/app/shell-test) exists only in the catalog build (W4).
const PAGE_IGNORE = [/catalog\.spec\.ts/, /server-log\.spec\.ts/, /board\.spec\.ts/, /shell\.spec\.ts/];

export default defineConfig({
  testDir: ".",
  // Playwright's own files are *.spec.ts; e2e/support/*.test.ts are Vitest unit tests of the specs' pure rules.
  testMatch: /\.spec\.ts$/,
  outputDir: "../test-results",
  reporter: [["list"], ["html", { outputFolder: "../playwright-report", open: "never" }]],
  forbidOnly: !!process.env.CI,
  retries: 0,
  use: { baseURL: BASE_URL },
  projects: BOARD
    ? [{ name: "board", testMatch: /board\.spec\.ts/, use: { ...devices["Desktop Chrome"] } }]
    : [
        {
          name: "phone",
          testIgnore: PAGE_IGNORE,
          use: { ...devices["Pixel 7"], viewport: { width: 390, height: 844 } },
        },
        {
          name: "desktop",
          // catalog-off.spec.ts asks one question of the production build; the phone project asks it.
          testIgnore: [...PAGE_IGNORE, /catalog-off\.spec\.ts/],
          use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } },
        },
        // The component catalog: both viewports and both schemes are set inside catalog.spec.ts (test.use, emulateMedia).
        // The shell (W4) too: its page is catalog-only, and its tests set their own widths.
        {
          name: "catalog",
          testMatch: [/catalog\.spec\.ts/, /shell\.spec\.ts/],
          use: { ...devices["Desktop Chrome"], baseURL: CATALOG_URL },
        },
        // After every page test: the Next server log those tests produced.
        { name: "logs", testMatch: /server-log\.spec\.ts/, dependencies: ["phone", "desktop"] },
      ],
  webServer: [
    ...(process.env.E2E_BASE_URL
      ? []
      : [
          {
            command: `node e2e/support/java-stub.mjs`,
            cwd: "..",
            url: `${process.env.E2E_STUB_URL}/__requests`,
            env: { STUB_PORT: String(STUB_PORT), E2E_ORIGIN: new URL(BASE_URL).origin, E2E_FIXTURES: process.env.E2E_FIXTURES! },
            reuseExistingServer: false,
            timeout: 30_000,
          },
          {
            // `exec` so the server, not a shell, is the process Playwright stops; its output goes to the log file too.
            command: `mkdir -p test-results && exec pnpm exec next start -p ${PORT} > ${SERVER_LOG} 2>&1`,
            cwd: "..",
            url: `${BASE_URL}/app/healthz`,
            env: {
              GIT_SHA: process.env.E2E_GIT_SHA!,
              BUILD_SHA: process.env.E2E_BUILD_SHA!,
              JAVA_INTERNAL_URL: process.env.E2E_STUB_URL!,
            },
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
            // The shell's page (/app/shell-test, W4) reads GET /app/api/me from the same stub Java as the pages.
            env: { JAVA_INTERNAL_URL: process.env.E2E_STUB_URL! },
            reuseExistingServer: false,
            timeout: 60_000,
          },
        ]),
  ],
});
