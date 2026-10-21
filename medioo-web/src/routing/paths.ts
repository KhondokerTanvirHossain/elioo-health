import { FLIPPED } from "./flipped";

/*
 * Where a link may go (plan Wave 3, Shared interfaces → Paths). Three lists, each held equal to its source by a test:
 * NEXT_PAGES to the build's route manifest (built/routes.built.test.ts), JAVA_PAGES to Java's route snapshot
 * docs/infra/app-routes.txt (routes.test.ts), FLIPPED to docs/infra/Caddyfile.medioo (flipped.test.ts). A link is
 * rendered only to a page some server will answer for this viewer (`reachable`), and a preview page links inside the
 * preview (`href`). No copy here: the browser bundle imports this module.
 */

/** The sign-in page with the place to return to. Java checks the value (LocalPath) when it receives it; Next never does. */
export function signInPath(next: string): string {
  return "/app/?next=" + encodeURIComponent(next);
}

/** Java's no-JavaScript sign-in (W3-api, `/app/basic`), linked from the sign-in pages' `<noscript>` (S1). */
export function basicSignInPath(next: string): string {
  return "/app/basic?next=" + encodeURIComponent(next);
}

export const PREVIEW_PREFIX = "/app/preview";

/** `/app/<rest>` under the preview prefix: `previewPath("/app")` is `/app/preview`. */
export function previewPath(path: string): string {
  return PREVIEW_PREFIX + path.slice("/app".length);
}

/**
 * The page patterns this build serves to families (sign-in first; each screen PR adds its own).
 *
 * `NEXT_PAGES` is the list `e2e/budget.spec.ts` measures, every entry against the DR-42 route ceiling in
 * `e2e/budget-ceilings.json` (S9: the ceilings file holds the two numbers, the page list is this). It is held equal to
 * the build's `.next/app-path-routes-manifest.json` by `built/routes.built.test.ts`, minus Next's 404, the probe and
 * the catalog-only pages, so a page cannot be added without being measured.
 */
export const NEXT_PAGES: readonly string[] = ["/app", "/app/verify"];

/** The `GET /app…` page patterns Java serves, as docs/infra/app-routes.txt lists them (routes.test.ts). */
export const JAVA_PAGES: readonly string[] = [
  "/app",
  "/app/",
  "/app/add",
  "/app/basic",
  "/app/basic/",
  "/app/basic/verify",
  "/app/documents/{id}",
  "/app/documents/{id}/all",
  "/app/documents/{id}/delete",
  "/app/documents/{id}/image",
  "/app/documents/{id}/page/{n}",
  "/app/documents/{id}/status",
  "/app/documents/{id}/values/{obs}",
  "/app/family",
  "/app/home",
  "/app/nudges/opt-out",
  "/app/patients/{id}",
  "/app/patients/{id}/profile",
  "/app/timeline",
  "/app/verify",
];

/** The path part of a link, without its query or fragment, and without a trailing slash (`/app/` is `/app`). */
function pathOf(link: string): string {
  const path = link.split(/[?#]/, 1)[0]!;
  return path.length > 1 && path.endsWith("/") ? path.slice(0, -1) : path;
}

/** Whether `path` matches `pattern`, where a `{name}` segment is any one non-empty segment. */
function matches(pattern: string, path: string): boolean {
  const want = pathOf(pattern).split("/");
  const got = pathOf(path).split("/");
  if (want.length !== got.length) return false;
  return want.every((segment, i) => (/^\{\w+\}$/.test(segment) ? got[i] !== "" : segment === got[i]));
}

const inList = (list: readonly string[], path: string) => list.some((pattern) => matches(pattern, path));

/** The link to `path` from a page: inside the preview when this is a preview page and Next serves `path`. */
export function href(path: string, preview: boolean): string {
  return preview && inList(NEXT_PAGES, path) ? previewPath(path) : path;
}

/** Whether a link to `path` reaches a page: Java serves it, Caddy sends it to Next, or Next serves it under the preview. */
export function reachable(path: string, preview: boolean): boolean {
  return inList(JAVA_PAGES, path) || inList(FLIPPED, path) || (preview && inList(NEXT_PAGES, path));
}
