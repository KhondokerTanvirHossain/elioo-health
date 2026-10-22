/**
 * The page patterns Caddy sends to Next (docs/infra/Caddyfile.medioo, held equal by flipped.test.ts). Empty until F3:
 * every page is Java's, and a Next page is reachable only under the preview prefix. A flip PR (plan Task F) edits this
 * file and the Caddyfile together, and nothing else under src/routing.
 */
export const FLIPPED: readonly string[] = [];
