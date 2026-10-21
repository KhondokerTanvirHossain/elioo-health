/** The UI language. Bangla by default; English only by the viewer's choice. */
export type Lang = "bn" | "en";

const COOKIE = "baymax_lang";

/**
 * The language of this request, from the `baymax_lang` cookie exactly as Java's `Lang.of` reads it: the first cookie of
 * that name, and `"en"` only when its value is exactly `en` (so `EN` or `fr` is Bangla). `Accept-Language` is never read
 * (master plan, Web platform): a phone set to English still gets the Bangla page until the family chooses otherwise.
 */
export function langOf(cookieHeader: string | undefined): Lang {
  if (!cookieHeader) return "bn";
  for (const part of cookieHeader.split(";")) {
    const at = part.indexOf("=");
    if (at < 0) continue;
    if (part.slice(0, at).trim() !== COOKIE) continue;
    return part.slice(at + 1).trim() === "en" ? "en" : "bn";
  }
  return "bn";
}
