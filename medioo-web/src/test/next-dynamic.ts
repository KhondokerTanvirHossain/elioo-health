import { createElement, type ComponentType } from "react";

// Stands in for `next/dynamic` outside Next's build (vitest.config.mts; e2e/conversation-layout.spec.ts). The desktop
// parts load their stylesheets and client islands through next/dynamic loaders (each part's desk-load.tsx), so Next
// links them only on a page that renders the part (DR-42; desktop-chunks.built.test.ts judges that on the build). A test
// of a component judges what it renders, not how its code arrives: here each loaded module is the component itself,
// rendered synchronously once its import has settled, which loadedDynamics() awaits (test/dynamic-setup.ts, before every
// test file's tests). A render before then is refused, loudly, never drawn as nothing.

type Loaded<P> = ComponentType<P> | { default: ComponentType<P> };

const pending: Promise<unknown>[] = [];

export default function dynamic<P extends object>(load: () => Promise<Loaded<P>>): ComponentType<P> {
  let component: ComponentType<P> | null = null;
  pending.push(
    load().then((m) => {
      // A module's default export (`import("./X.desk")`), or the component itself (`.then((m) => m.Zoom)`). Under Node's
      // own import() of a compiled module (Playwright) the namespace's default is that module's exports, so unwrap again.
      let found: unknown = m;
      while (typeof found === "object" && found !== null && "default" in found) found = (found as { default: unknown }).default;
      component = found as ComponentType<P>;
    }),
  );
  function Dynamic(props: P) {
    if (component === null) throw new Error("next/dynamic stand-in: rendered before its module loaded (await loadedDynamics() first)");
    return createElement(component, props);
  }
  return Dynamic;
}

/** Every module a next/dynamic loader has asked for so far, loaded. */
export function loadedDynamics(): Promise<unknown[]> {
  return Promise.all(pending);
}
