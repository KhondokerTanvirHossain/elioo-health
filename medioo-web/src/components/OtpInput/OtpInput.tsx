import { banglaDigits, t, type Lang } from "@/copy";
import { OtpInputView, type OtpWords } from "./OtpInputView";

/**
 * The refusals a code can come back with, as the copy key the verify page says each in (SignInPages.refusalKey):
 * WRONG and NO_CODE are `login.bad.wrong`, EXPIRED `login.bad.expired`, FORMAT `login.bad.format`, and BURNED
 * `login.bad.burned` with the number of wrong tries it took. Narrow on purpose, so a caller cannot put arbitrary copy
 * under the field as its error line.
 */
export type CodeRefusal =
  | { key: "login.bad.wrong" | "login.bad.expired" | "login.bad.format" }
  | { key: "login.bad.burned"; attempts: number };

/**
 * The sign-in code: one field for all six digits, never six boxes. One field reads as one labelled field to a screen
 * reader, takes a pasted or autofilled code in one go (`autocomplete="one-time-code"`), and needs no script to move
 * between boxes. It accepts ASCII or Bangla digits, as the HTML sign-in page does (SignInPages.verifyPage); the
 * browser only checks the shape, and Java reads the digits. The Bangla digits come from the generated calendar copy
 * (banglaDigits), never typed here.
 *
 * `ttlMinutes` writes the verify page's lifetime line (`login.code.ttl`) under the field and makes it the field's
 * description. `error` is the refusal the caller received, as PhoneField takes one: it marks the field invalid, is
 * read before the lifetime line, and is said aloud when it arrives, from an alert that is always in the page and empty
 * until there is a refusal (a live region announces a change to its content, not its own arrival).
 *
 * The server wrapper over OtpInputView: it resolves the words and renders the copy-free twin a client island uses.
 */
export function OtpInput({
  length,
  lang,
  name,
  defaultValue,
  error,
  ttlMinutes,
}: {
  length: 6;
  lang: Lang;
  name: string;
  defaultValue?: string;
  error?: CodeRefusal;
  ttlMinutes?: number;
}) {
  return (
    <OtpInputView
      length={length}
      words={otpWords(lang, ttlMinutes, length)}
      name={name}
      defaultValue={defaultValue}
      error={error === undefined ? undefined : refusalText(lang, error)}
    />
  );
}

/** The code field's words: its label, the lifetime line when the minutes are known, and the shape of six numerals. */
export function otpWords(lang: Lang, ttlMinutes?: number, length: 6 = 6): OtpWords {
  return {
    label: t(lang, "login.code"),
    ...(ttlMinutes === undefined ? {} : { ttl: t(lang, "login.code.ttl", ttlMinutes) }),
    pattern: `[0-9${banglaDigits("0")}-${banglaDigits("9")}]{${length}}`,
  };
}

/** A refused code's line, as the verify page says it: BURNED with the number of wrong tries it took. */
export function refusalText(lang: Lang, error: CodeRefusal): string {
  return error.key === "login.bad.burned" ? t(lang, error.key, error.attempts) : t(lang, error.key);
}
