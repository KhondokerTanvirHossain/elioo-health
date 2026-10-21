import { t, type CopyKey, type Lang } from "@/copy";
import { ToastRegion } from "./ToastRegion";
import "./Toast.css";

/**
 * The server convenience for a confirmation that something the family did has happened ("saved", "copied"): the copy
 * key's words, resolved here, handed to `ToastRegion`, which mounts the `role="status"` region empty and writes the
 * words into it after mount, so the region is in the page before its text and the text is announced politely. Never
 * red and never amber. Where it sits and when it goes are the caller's.
 *
 * A client caller (one that shows the toast after its own mutation) renders `ToastRegion` directly with words its
 * server parent resolved: a `"use client"` file may not import this one, which pulls `@/copy` into the browser.
 */
export function Toast({ messageKey, args = [], lang }: { messageKey: CopyKey; args?: Array<string | number>; lang: Lang }) {
  return <ToastRegion message={t(lang, messageKey, ...args)} />;
}
