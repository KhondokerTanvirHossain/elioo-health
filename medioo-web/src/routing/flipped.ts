/**
 * The page patterns Caddy sends to Next (docs/infra/Caddyfile.medioo, held equal by flipped.test.ts). A flip PR (plan
 * Task F) edits this file and the Caddyfile together, and nothing else under src/routing. Every page not listed is
 * Java's, and a Next page not listed is reachable only under the preview prefix.
 *
 * F7 (PO ruling P4, 2026-10-23): `/app/add`, the add sheet (plan W7), GET and HEAD only; POST /app/add stays Java's.
 */
export const FLIPPED: readonly string[] = ["/app/add"];
