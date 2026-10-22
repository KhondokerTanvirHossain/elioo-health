import "server-only";
import { headers } from "next/headers";
import { langOf, type Lang } from "@/copy";

/**
 * What a page knows about the request it is rendering, from the headers the middleware set (src/middleware.ts): the
 * language (the `baymax_lang` cookie, as Java's Lang.of reads it), whether this is a preview request (the middleware's
 * own `x-medioo-preview: 1`; a client's copy is deleted before the page sees it), and `here`, the path and query the
 * browser asked for, preview prefix included, so a sign-in hop returns to the page the family was on.
 */
export async function requestContext(): Promise<{ lang: Lang; preview: boolean; here: string }> {
  const h = await headers();
  return {
    lang: langOf(h.get("cookie") ?? undefined),
    preview: h.get("x-medioo-preview") === "1",
    here: h.get("x-medioo-path") ?? "/app",
  };
}
