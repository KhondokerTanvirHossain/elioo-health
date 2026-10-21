import { useId } from "react";
import { banglaDigits, t, type Lang } from "@/copy";
import "./OtpInput.css";

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
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const described = [error ? errorId : null, ttlMinutes === undefined ? null : hintId].filter(Boolean).join(" ");
  return (
    <div className="otp-input__wrap">
      <label className="otp-input__label" htmlFor={id}>
        {t(lang, "login.code")}
      </label>
      <input
        id={id}
        className="otp-input"
        type="text"
        name={name}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={length}
        pattern={`[0-9${banglaDigits("0")}-${banglaDigits("9")}]{${length}}`}
        defaultValue={defaultValue}
        aria-invalid={error ? true : undefined}
        aria-describedby={described || undefined}
        required
      />
      <p className="otp-input__error" id={errorId} role="alert">
        {error === undefined ? null : error.key === "login.bad.burned" ? t(lang, error.key, error.attempts) : t(lang, error.key)}
      </p>
      {ttlMinutes !== undefined && (
        <p className="otp-input__hint" id={hintId}>
          {t(lang, "login.code.ttl", ttlMinutes)}
        </p>
      )}
    </div>
  );
}
