import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Unit tests live beside the code; e2e/*.spec.ts belong to Playwright and must not be picked up here.
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    environment: "node",
  },
});
