import { defineConfig, devices } from "@playwright/test";

// Run after `pnpm build`: the suite exercises the production server, because the CSP, the nonce and the byte budget
// are properties of the built app (next dev injects inline styles and an overlay that production never ships).
const PORT = Number(process.env.E2E_PORT ?? 3217);
export const BASE_URL = process.env.E2E_BASE_URL ?? `http://127.0.0.1:${PORT}`;

export default defineConfig({
  testDir: ".",
  outputDir: "../test-results",
  reporter: [["list"], ["html", { outputFolder: "../playwright-report", open: "never" }]],
  forbidOnly: !!process.env.CI,
  retries: 0,
  use: { baseURL: BASE_URL },
  projects: [
    { name: "phone", use: { ...devices["Pixel 7"], viewport: { width: 390, height: 844 } } },
    { name: "desktop", use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } } },
  ],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: `pnpm exec next start -p ${PORT}`,
        cwd: "..",
        url: `${BASE_URL}/app/healthz`,
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
});
