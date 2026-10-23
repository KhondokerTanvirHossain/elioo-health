/**
 * The page patterns Caddy sends to Next (docs/infra/Caddyfile.medioo, held equal by flipped.test.ts). A flip PR (plan
 * Task F) edits this file and the Caddyfile together, and nothing else under src/routing. Every page not listed is
 * Java's, and a Next page not listed is reachable only under the preview prefix.
 *
 * F7 (DR-49, PO ruling 2026-10-23): `/app/add`, the add sheet (plan W7), GET and HEAD only; POST /app/add stays
 * Java's.
 * F3 (DR-49): the sign-in pages `/app`, `/app/` and `/app/verify`, GET and HEAD only (`@medioo_signin`); every
 * sign-in POST and the no-JS fallback /app/basic* stay Java's.
 * F5 (DR-49, PO ruling 2026-10-23): `/app/home`, the chat page (plan W5), GET and HEAD only; POST /app/home is not a
 * Next route and stays Java's.
 * F10 (DR-49, PO ruling 2026-10-23): `/app/family`, the family tab and settings, and `/app/patients/{id}/profile`, a
 * patient's profile (plan W10), GET and HEAD only. Caddy matches the profile by `path_regexp` (one lower-case uuid
 * segment), which flipped.test.ts reads as this pattern; POST /app/patients/{id}/profile, the profile save, stays
 * Java's.
 * F9 (DR-49, PO ruling 2026-10-23): `/app/health`, the health tab (plan W9), GET and HEAD only; Java never had a page
 * here and routes no POST /app/health. From here the health tab is drawn outside the preview too (TabBar).
 */
export const FLIPPED: readonly string[] = [
  "/app/add",
  "/app",
  "/app/",
  "/app/verify",
  "/app/home",
  "/app/family",
  "/app/patients/{id}/profile",
  "/app/health",
];
