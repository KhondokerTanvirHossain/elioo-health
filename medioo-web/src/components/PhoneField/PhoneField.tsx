import { t, type Lang } from "@/copy";
import { PhoneFieldView, type PhoneFieldWords } from "./PhoneFieldView";

/**
 * The refusals a number can come back with, as the copy key the page says it in: INVALID_NUMBER is
 * `login.number.invalid`. Narrow on purpose, so a caller cannot put arbitrary copy (a NOW sentence, a medicine) under
 * the field as its error line.
 */
export type NumberRefusal = "login.number.invalid";

/** The field's label and hint, as the HTML sign-in page words them (`login.number`, `login.hint`). */
export function phoneFieldWords(lang: Lang): PhoneFieldWords {
  return { label: t(lang, "login.number"), hint: t(lang, "login.hint") };
}

/**
 * The WhatsApp number. It matches the HTML sign-in page (SignInPages.numberPage) in its label (`login.number`), hint
 * (`login.hint`), `type="tel"`, `inputmode="tel"`, `autocomplete="tel"` and `required`, and deliberately leaves out
 * that page's courtesy `pattern` (ASCII or Bangla digits, `+`, space and `-`, 8 to 24 of them): nothing is parsed,
 * reformatted or constrained in the browser (no pattern, no length), so Java's PhoneDisplay.normalise alone decides
 * what a number is, and the page shows the normalised number back for a yes.
 *
 * `error` is the refusal the caller received. It marks the field invalid, is read before the hint (never instead of
 * it), and is said aloud when it arrives: the alert is always in the page, empty until there is a refusal, because a
 * React screen fills it after a fetch into a page that is already open, and a live region announces a change to its
 * content, not its own arrival. The HTML page says the same refusal with `role="alert"`.
 *
 * The server wrapper over PhoneFieldView: it resolves the words and renders the copy-free twin a client island uses.
 */
export function PhoneField({
  lang,
  name,
  error,
  defaultValue,
}: {
  lang: Lang;
  name: string;
  error?: NumberRefusal;
  defaultValue?: string;
}) {
  return (
    <PhoneFieldView
      words={phoneFieldWords(lang)}
      name={name}
      error={error ? t(lang, error) : undefined}
      defaultValue={defaultValue}
    />
  );
}
