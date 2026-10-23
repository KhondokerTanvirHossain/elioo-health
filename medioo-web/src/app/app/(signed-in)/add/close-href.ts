import { href, pathsFor, reachable } from "@/routing/paths";
import { localPathOr } from "@/routing/local-path";

/** The chat tab, where a sheet opened with no usable `back` closes to (Java's sheet: `back` or /app/home). */
const CHAT = "/app/home";

type Paths = Pick<ReturnType<typeof pathsFor>, "href" | "reachable">;

/**
 * Where the add sheet's close (sheet.close) goes: the page's `?back=`, as Java's links carry it (ChatView.addHref,
 * AppTabs.addHref, DocumentPages.anotherPaper), when it is a page of this app by Java's own rule (LocalPath, here
 * localPathOr) and some server answers it for this viewer; otherwise the chat tab. On the preview a page Next serves
 * is linked inside the preview (`href`).
 */
export function closeHrefFrom(back: string | null, preview: boolean, paths: Paths): string {
  const local = localPathOr(back, CHAT);
  return paths.href(paths.reachable(local, preview) ? local : CHAT, preview);
}

/** closeHrefFrom over this build's lists. */
export function closeHref(back: string | null, preview: boolean): string {
  return closeHrefFrom(back, preview, { href, reachable });
}
