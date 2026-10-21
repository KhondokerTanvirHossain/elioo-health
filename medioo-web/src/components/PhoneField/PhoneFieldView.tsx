import { useId } from "react";
import "./PhoneField.css";

/** The field's words, resolved by a server parent (`phoneFieldWords` in PhoneField.tsx). */
export type PhoneFieldWords = { label: string; hint: string };

/**
 * PhoneField's markup without its copy: the twin a client island renders (the sign-in number step), with the words
 * and the refusal line already resolved on the server, so no copy ships to the browser. PhoneField is the thin server
 * wrapper that resolves them and renders this, so the two are one field (`theWrapperAndTheViewRenderTheSameMarkup`).
 * Everything PhoneField's comment says about the field holds here: no pattern, no length, nothing parsed in the
 * browser; the alert is always in the page, empty until there is a refusal.
 */
export function PhoneFieldView({
  words,
  name,
  error,
  defaultValue,
}: {
  words: PhoneFieldWords;
  name: string;
  error?: string;
  defaultValue?: string;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  return (
    <div className="phone-field">
      <label className="phone-field__label" htmlFor={id}>
        {words.label}
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
      <p className="phone-field__error" id={errorId} role="alert">
        {error ?? null}
      </p>
      <p className="phone-field__hint" id={hintId}>
        {words.hint}
      </p>
    </div>
  );
}
