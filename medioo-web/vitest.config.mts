import { fileURLToPath } from "node:url";
import { configDefaults, defineConfig } from "vitest/config";

// *.built.test.ts read the production build and run after it, under vitest.built.config.mts (test:built).
const exclude = [...configDefaults.exclude, "src/**/*.built.test.ts"];
// *.tz.test.tsx set process.env.TZ themselves (west of UTC) and run in their own project, one process per file
// (forks), so the zone they set is never the one another file's dates are made in (plan W2e-b).
const TZ_TESTS = "src/**/*.tz.test.tsx";

export default defineConfig({
  // tsconfig's @/* path, for the component tests that import @/copy and @/styles like the app does.
  // `server-only` throws outside a React Server Components build, which a unit test of a server module (src/api/java.ts)
  // is; the alias gives those tests an empty module. Next's own build resolves the real package.
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
      "server-only": fileURLToPath(new URL("./src/test/empty-module.ts", import.meta.url)),
    },
  },
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
      { extends: true, test: { name: "dom", include: ["src/**/*.test.tsx"], exclude: [...exclude, TZ_TESTS], environment: "jsdom" } },
      { extends: true, test: { name: "tz", include: [TZ_TESTS], exclude, environment: "jsdom", pool: "forks", isolate: true } },
    ],
  },
});
