/** The UI language. Bangla by default; English only by the viewer's choice. */
export type Lang = "bn" | "en";

const COOKIE = "baymax_lang";

/** One pair of surrounding double quotes removed, as Netty's cookie decoder does before Spring hands Lang.of the value. */
function unquoted(value: string): string {
  return value.length >= 2 && value.startsWith('"') && value.endsWith('"') ? value.slice(1, -1) : value;
}

/**
 * The language of this request, from the `baymax_lang` cookie exactly as Java's `Lang.of` reads it: the first cookie of
 * that name, and `"en"` only when its value is exactly `en` (so `EN` or `fr` is Bangla) once one pair of surrounding
 * double quotes is unwrapped, as Netty unwraps `baymax_lang="en"` before Lang.of sees it. `Accept-Language` is never read
 * (master plan, Web platform): a phone set to English still gets the Bangla page until the family chooses otherwise.
 */
export function langOf(cookieHeader: string | undefined): Lang {
  if (!cookieHeader) return "bn";
  for (const part of cookieHeader.split(";")) {
    const at = part.indexOf("=");
    if (at < 0) continue;
    if (part.slice(0, at).trim() !== COOKIE) continue;
    return unquoted(part.slice(at + 1).trim()) === "en" ? "en" : "bn";
  }
  return "bn";
}
