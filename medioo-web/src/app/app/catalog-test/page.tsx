import { notFound } from "next/navigation";
import type { Lang } from "@/copy";
import { CATALOG } from "@/catalog/flag";
import { STATES } from "@/catalog/states";

const LANGS: readonly Lang[] = ["bn", "en"];

/**
 * The component catalog (plan W2c): every registered state of every component, each in Bangla and then in English,
 * one under the other, inside `<section data-catalog-state="<component>/<state>">` for catalog.spec.ts to measure.
 * Fictional data only. It exists only in a catalog build (MEDIOO_CATALOG=1); everywhere else it is a 404 before
 * anything renders. The headings are the registry's identifiers, not copy. `data-catalog-states` is the registry's
 * length, so the spec can require every registered state on the page without importing the registry (whose components
 * import CSS, which Playwright's loader cannot).
 */
export default function CatalogTestPage() {
  if (!CATALOG) notFound();
  return (
    <main data-catalog-states={STATES.length}>
      <h1 lang="en">catalog-test</h1>
      {STATES.map(({ component, state, render, empty }) =>
        LANGS.map((lang) => (
          <section
            key={`${component}/${state}/${lang}`}
            data-catalog-state={`${component}/${state}`}
            data-catalog-empty={empty ? "" : undefined}
            lang={lang}
          >
            <h2 lang="en">
              {component}/{state} ({lang})
            </h2>
            {render(lang)}
          </section>
        )),
      )}
    </main>
  );
}
