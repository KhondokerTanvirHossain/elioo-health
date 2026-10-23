/**
 * The page patterns Caddy sends to Next (docs/infra/Caddyfile.medioo, held equal by flipped.test.ts). A flip PR (plan
 * Task F) edits this file and the Caddyfile together, and nothing else under src/routing. Every page not listed is
 * Java's, and a Next page not listed is reachable only under the preview prefix.
 *
 * F7 (DR-49, PO ruling 2026-10-23): `/app/add`, the add sheet (plan W7), GET and HEAD only; POST /app/add stays
 * Java's.
 * F3 (DR-49): the sign-in pages `/app`, `/app/` and `/app/verify`, GET and HEAD only (`@medioo_signin`); every
 * sign-in POST and the no-JS fallback /app/basic* stay Java's.
 */
export const FLIPPED: readonly string[] = ["/app/add", "/app", "/app/", "/app/verify"];
