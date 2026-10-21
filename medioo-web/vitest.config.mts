import { fileURLToPath } from "node:url";
import { configDefaults, defineConfig } from "vitest/config";

// *.built.test.ts read the production build and run after it, under vitest.built.config.mts (test:built).
const exclude = [...configDefaults.exclude, "src/**/*.built.test.ts"];

export default defineConfig({
  // tsconfig's @/* path, for the component tests that import @/copy and @/styles like the app does.
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  // tsconfig says jsx "preserve" (Next compiles JSX itself); the tests need it compiled, with React's automatic runtime.
  oxc: { jsx: { runtime: "automatic" } },
  // TSX tests render components (Testing Library) and need a DOM; everything else runs in plain node. Vitest 5 has no
  // environmentMatchGlobs, so the split is two projects over one include each (plan W2c). Unit tests live beside the
  // code; e2e/*.spec.ts belong to Playwright and are picked up by neither.
  test: {
    projects: [
      // e2e/support holds the pure rules the specs use (the DR-42 budget arithmetic), judged here without a browser.
      {
        extends: true,
        test: { name: "node", include: ["src/**/*.test.ts", "e2e/support/**/*.test.ts"], exclude, environment: "node" },
      },
      { extends: true, test: { name: "dom", include: ["src/**/*.test.tsx"], exclude, environment: "jsdom" } },
    ],
  },
});
