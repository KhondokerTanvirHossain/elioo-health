import "server-only";
import { headers } from "next/headers";
import { notFound, redirect } from "next/navigation";
import { cache } from "react";
import { signInPath } from "@/routing/paths";
import { requestContext } from "./request";

/*
 * The one module that reads Java from the Next server (plan Wave 3, Shared interfaces → Server reads). Only this file
 * names JAVA_INTERNAL_URL (scripts/check-no-server-mutation.sh rule 5), so rule 3 sees every method Next sends Java:
 * GET, always. It forwards the incoming `cookie` header and no other (no Origin, no Authorization, not the preview
 * mark), reads with `cache: "no-store"`, and is wrapped in React `cache()` so a layout and a page in one request read
 * one path once. It logs nothing: an error names Java's status and the route pattern, never a body, a query or an id.
 */

/** A Java answer a page can act on: the body of a 200, or the `{"reason"}` of a refusal. Anything else throws. */
export type JavaAnswer<T> =
  | { status: 200; body: T }
  | { status: 400 | 401 | 403 | 404 | 409 | 413 | 415; reason: string };

const REFUSALS = new Set([400, 401, 403, 404, 409, 413, 415]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ME = "/app/api/me";

/** The path as a route pattern: every UUID segment is `{id}`, and the query is dropped. */
function pattern(path: string): string {
  return path
    .split("?", 1)[0]!
    .split("/")
    .map((segment) => (UUID.test(segment) ? "{id}" : segment))
    .join("/");
}

const read = cache(async (path: string): Promise<JavaAnswer<unknown>> => {
  const { preview, here } = await requestContext();
  if (preview && path !== ME) {
    // A preview page reads nothing but `me` until `me` says this family previews (BAYMAX_WEB_PREVIEW_FAMILIES).
    const me = await read(ME);
    if (me.status === 401) redirect(signInPath(here));
    if (me.status !== 200 || (me.body as { preview?: unknown } | null)?.preview !== true) notFound();
  }
  const cookie = (await headers()).get("cookie");
  let response: Response;
  try {
    response = await fetch(`${process.env.JAVA_INTERNAL_URL}${path}`, {
      method: "GET",
      headers: cookie ? { cookie } : {},
      cache: "no-store",
    });
  } catch {
    throw new Error(`java unreachable ${pattern(path)}`);
  }
  if (response.status === 200) {
    // Parsed inside a try: a SyntaxError's message quotes the start of the body, which must never reach the log.
    let body: unknown;
    try {
      body = await response.json();
    } catch {
      throw new Error(`java 200 ${pattern(path)}`);
    }
    return { status: 200, body };
  }
  if (REFUSALS.has(response.status)) {
    const reason = await response.json().then(
      (b: { reason?: unknown } | null) => (typeof b?.reason === "string" ? b.reason : null),
      () => null,
    );
    if (reason !== null) return { status: response.status as 400, reason };
  }
  throw new Error(`java ${response.status} ${pattern(path)}`);
});

/** `GET ${JAVA_INTERNAL_URL}${path}` with the request's cookie, behind the preview gate. */
export function javaGet<T>(path: string): Promise<JavaAnswer<T>> {
  return read(path) as Promise<JavaAnswer<T>>;
}

/**
 * The body of a signed-in read: a 401 `UNAUTHENTICATED` is the sign-in page holding `here` (requestContext().here, so
 * a preview page returns to its preview path), a 404 is the not-found page, and any other refusal throws by name.
 */
export function signedIn<T>(answer: JavaAnswer<T>, here: string): T {
  if (answer.status === 200) return answer.body;
  if (answer.status === 401 && answer.reason === "UNAUTHENTICATED") redirect(signInPath(here));
  if (answer.status === 404) notFound();
  throw new Error(`java ${answer.status} ${answer.reason}`);
}
