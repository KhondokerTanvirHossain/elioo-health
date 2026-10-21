/**
 * The page patterns this build serves to families (screens plan, Shared interfaces → Paths).
 *
 * `NEXT_PAGES` is the list `e2e/budget.spec.ts` measures, every entry against the DR-42 route ceiling in
 * `e2e/budget-ceilings.json` (S9: the ceilings file holds the two numbers, the page list is this). It is held equal to
 * the build's `.next/app-path-routes-manifest.json` by `built/routes.built.test.ts`, minus Next's 404, the probe and
 * the catalog-only pages, so a page cannot be added without being measured.
 *
 * Empty until W3 adds `/app` and `/app/verify`; each screen PR adds its own.
 */
export const NEXT_PAGES: readonly string[] = [];
