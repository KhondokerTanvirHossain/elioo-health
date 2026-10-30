import { t, type Lang } from "@/copy";

/**
 * The words of the tel:999 link under an emergency answer (`now.call999`), for a server parent to resolve: the thread
 * renders them itself, and the chat page hands them to the composer, a client island that ships no copy. A safety line
 * is named only here (eslint), and AskAnswer draws it only when Java's `call_999` says so. Kept out of AskAnswer.tsx,
 * which the composer imports into the browser: this module reads the copy.
 */
export function call999Words(lang: Lang): string {
  return t(lang, "now.call999");
}
