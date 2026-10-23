"use client";

import { useState } from "react";
import { apiSend } from "@/api/browser";
import type { Lang } from "@/copy";
import { ChoiceTick } from "./ChoiceTick";

/** Every word the language control says, resolved by the server (family-words.ts): this island ships no copy. */
export type LanguageWords = {
  /** `family.account.lang`: the control's name. */
  title: string;
  /** `lang.bn` and `lang.en`: each language named in itself. */
  bn: string;
  en: string;
  /** `app.err.generic`: the choice never reached Java, or Java refused it. */
  failed: string;
};

const LANGS: readonly Lang[] = ["bn", "en"];

/**
 * Bangla or English (plan W10, B5): `PUT /app/api/language` `{"lang": …}`, which sets the `baymax_lang` cookie Java
 * and the layout read, then the page is loaded again, so every word on it (the server resolved them all) comes back in
 * the new language. Each option is named in its own language and marked so (`lang`), whatever the page's. A send that
 * did not arrive leaves the page as it was and says so. The pressed option carries a tick (ChoiceTick); a send keeps
 * the focus (aria-disabled) and its status region is mounted empty, as ThemeSwitch's.
 */
export function LanguageSwitch({ current, words }: { current: Lang; words: LanguageWords }) {
  const [sending, setSending] = useState(false);
  const [said, setSaid] = useState<string | null>(null);

  async function choose(next: Lang) {
    if (sending || next === current) return;
    setSending(true);
    setSaid(null);
    const result = await apiSend("PUT", "/app/api/language", { lang: next });
    if (result.kind === "signed-out") return;
    if (result.kind === "ok") {
      location.reload();
      return;
    }
    setSending(false);
    setSaid(words.failed);
  }

  return (
    <div className="family-choice-field">
      <p className="family-choice-field__title" id="family-language-title">
        {words.title}
      </p>
      <div className="family-choice" role="group" aria-labelledby="family-language-title">
        {LANGS.map((option) => (
          <button
            key={option}
            type="button"
            className="family-choice__option"
            lang={option}
            aria-pressed={option === current}
            aria-disabled={sending}
            onClick={() => choose(option)}
          >
            {option === current && <ChoiceTick />}
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
