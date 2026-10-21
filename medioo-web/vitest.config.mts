import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // Unit tests live beside the code; e2e/*.spec.ts belong to Playwright and must not be picked up here.
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    // *.built.test.ts read the production build and run after it, under vitest.built.config.mts (test:built).
    exclude: [...configDefaults.exclude, "src/**/*.built.test.ts"],
    environment: "node",
  },
});
