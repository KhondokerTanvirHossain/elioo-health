import { defineConfig } from "vitest/config";

// Tests over the production build (.next/static/css): run after `pnpm build`, by `pnpm run test:built` (plan W2a).
export default defineConfig({
  test: {
    include: ["src/styles/built/**/*.built.test.ts"],
    environment: "node",
  },
});
