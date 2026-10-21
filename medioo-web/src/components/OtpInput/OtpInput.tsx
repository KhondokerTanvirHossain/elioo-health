import { useId } from "react";
import { banglaDigits, t, type Lang } from "@/copy";
import "./OtpInput.css";

/**
 * The sign-in code: one field for all six digits, never six boxes. One field reads as one labelled field to a screen
 * reader, takes a pasted or autofilled code in one go (`autocomplete="one-time-code"`), and needs no script to move
 * between boxes. It accepts ASCII or Bangla digits, as the HTML sign-in page does (SignInPages.verifyPage); the
 * browser only checks the shape, and Java reads the digits. The Bangla digits come from the generated calendar copy
 * (banglaDigits), never typed here.
 */
export function OtpInput({ length, lang, name, defaultValue }: { length: 6; lang: Lang; name: string; defaultValue?: string }) {
  const id = useId();
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
        required
      />
    </div>
  );
}
