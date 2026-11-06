import { beforeAll } from "vitest";
import { loadedDynamics } from "./next-dynamic";

// Before a test file's tests (after its imports have run every next/dynamic loader they reach): every module those
// loaders asked for is loaded, so a component behind one renders as itself (test/next-dynamic.ts).
beforeAll(async () => {
  await loadedDynamics();
});
