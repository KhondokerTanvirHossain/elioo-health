import { useId } from "react";
import { t, type CopyKey, type Lang } from "@/copy";
import "./PhoneField.css";

/**
 * The WhatsApp number, as the HTML sign-in page asks for it: a tel field with the sign-in label and hint. Nothing is
 * parsed, reformatted or constrained here (no pattern, no length): Java's PhoneDisplay.normalise decides what a number
 * is, and the page shows the normalised number back for a yes. `error` is the copy key of a refusal the caller
 * received (INVALID_NUMBER is `login.number.invalid`); it marks the field invalid and is read before the hint.
 */
export function PhoneField({
  lang,
  name,
  error,
  defaultValue,
}: {
  lang: Lang;
  name: string;
  error?: CopyKey;
  defaultValue?: string;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  return (
    <div className="phone-field">
      <label className="phone-field__label" htmlFor={id}>
        {t(lang, "login.number")}
      </label>
      <input
        id={id}
        className="phone-field__input"
        type="tel"
        name={name}
        inputMode="tel"
        autoComplete="tel"
        defaultValue={defaultValue}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${errorId} ${hintId}` : hintId}
        required
      />
      {error && (
        <p className="phone-field__error" id={errorId}>
          {t(lang, error)}
        </p>
      )}
      <p className="phone-field__hint" id={hintId}>
        {t(lang, "login.hint")}
      </p>
    </div>
  );
}
