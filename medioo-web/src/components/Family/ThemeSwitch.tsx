"use client";

import { useState } from "react";
import { apiSend } from "@/api/browser";
import { dataThemeOf, THEMES, type Theme } from "@/app/theme";
import { ChoiceTick } from "./ChoiceTick";

/** Every word the theme control says, resolved by the server (family-words.ts): this island ships no copy. */
export type ThemeWords = {
  /** `theme.title`: the control's name. */
  title: string;
  /** `theme.auto`, `theme.light`, `theme.dark`. */
  auto: string;
  light: string;
  dark: string;
  /** `app.err.generic`: the choice never reached Java, or Java refused it. */
  failed: string;
};

/**
 * Light, dark, or like the phone (plan W10, W10-api, S12): one `PUT /app/api/preferences` `{"theme": …}` per choice,
 * which sets the `baymax_theme` cookie (Path=/app) the root layout reads on every later page; then `data-theme` on
 * <html> is written here at once (removed for `auto`), so the page repaints without a reload. The choice shows as
 * pressed only once Java has it: a refusal or a send that did not arrive leaves the page as it was and says so. The
 * buttons sit on one segmented track that paints --fill and draws --boundary (Q1 (A)); the pressed one carries a tick
 * and a --boundary ring as well as its weight. While a choice is being sent the buttons are aria-disabled (never
 * disabled, which would drop the focus), and the status region is mounted empty, so a refusal is announced.
 */
export function ThemeSwitch({ current, words }: { current: Theme; words: ThemeWords }) {
  const [theme, setTheme] = useState<Theme>(current);
  const [sending, setSending] = useState(false);
  const [said, setSaid] = useState<string | null>(null);

  async function choose(next: Theme) {
    if (sending || next === theme) return;
    setSending(true);
    setSaid(null);
    const result = await apiSend("PUT", "/app/api/preferences", { theme: next });
    if (result.kind === "signed-out") return;
    setSending(false);
    if (result.kind !== "ok") {
      setSaid(words.failed);
      return;
    }
    const attribute = dataThemeOf(next);
    if (attribute === undefined) document.documentElement.removeAttribute("data-theme");
    else document.documentElement.setAttribute("data-theme", attribute);
    setTheme(next);
  }

  return (
    <div className="family-choice-field">
      <p className="family-choice-field__title" id="family-theme-title">
        {words.title}
      </p>
      <div className="family-choice" role="group" aria-labelledby="family-theme-title" data-theme-choice={theme}>
        {THEMES.map((option) => (
          <button
            key={option}
            type="button"
            className="family-choice__option"
            aria-pressed={option === theme}
            aria-disabled={sending}
            onClick={() => choose(option)}
          >
            {option === theme && <ChoiceTick />}
            <span className="family-choice__label">{words[option]}</span>
          </button>
        ))}
      </div>
      <p className="family-choice-field__status" role="status" aria-live="polite" aria-atomic="true">
        {said}
      </p>
    </div>
  );
}
