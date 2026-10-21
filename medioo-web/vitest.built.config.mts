import { defineConfig } from "vitest/config";

// Tests over the builds (.next/static, .next-catalog/static): run after `pnpm build` and `pnpm build:catalog`, by
// `pnpm run test:built` (plan W2a; the catalog build since W2d-a).
export default defineConfig({
  test: {
    include: ["src/styles/built/**/*.built.test.ts"],
    environment: "node",
    // Print what the checks judged (fillPainters, controlSelectors) on a green run too, so an empty list is seen.
    silent: false,
  },
});
