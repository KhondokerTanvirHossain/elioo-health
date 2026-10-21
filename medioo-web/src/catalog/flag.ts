/**
 * True only in a build made with MEDIOO_CATALOG=1 (`pnpm run build:catalog`, output .next-catalog/). next.config.ts
 * inlines the value at build time, so the production image, built without it, answers /app/catalog-test with a 404
 * whatever its runtime environment says (catalog-off.spec.ts; deploy.sh's WEB_CATALOG_PROBE on the shipped container).
 */
export const CATALOG = process.env.MEDIOO_CATALOG === "1";
