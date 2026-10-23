/*
 * The family's theme (plan W10, W10-api): the `baymax_theme` cookie, which only Java's PUT /app/api/preferences sets
 * (Path=/app, HttpOnly). `auto` is the phone's own scheme (no attribute); `light` and `dark` are written on <html> as
 * data-theme by the root layout, which tokens.css reads. No copy here: the browser bundle imports this module.
 */

export const THEMES = ["auto", "light", "dark"] as const;
export type Theme = (typeof THEMES)[number];

const COOKIE = "baymax_theme";

/**
 * The theme of this request: the first `baymax_theme` cookie, `light` or `dark` exactly as written (one pair of
 * surrounding double quotes unwrapped, as for `baymax_lang`), and `auto` for anything else or none, so a value Java
 * would never set paints the phone's own scheme rather than a guess.
 */
export function themeOf(cookieHeader: string | undefined | null): Theme {
  if (!cookieHeader) return "auto";
  for (const part of cookieHeader.split(";")) {
    const at = part.indexOf("=");
    if (at < 0 || part.slice(0, at).trim() !== COOKIE) continue;
    let value = part.slice(at + 1).trim();
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.slice(1, -1);
    return value === "light" || value === "dark" ? value : "auto";
  }
  return "auto";
}

/** The `data-theme` attribute a theme writes on <html>: none for `auto`. */
export function dataThemeOf(theme: Theme): "light" | "dark" | undefined {
  return theme === "auto" ? undefined : theme;
}
