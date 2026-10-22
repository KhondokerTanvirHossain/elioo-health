import { useId } from "react";
import "./OtpInput.css";

/**
 * The field's words, resolved by a server parent (`otpWords` in OtpInput.tsx): the label, the lifetime line with its
 * minutes already filled in (absent for none), and the pattern, whose Bangla zero and nine come from the generated
 * calendar copy and so cannot be built in a client file.
 */
export type OtpWords = { label: string; ttl?: string; pattern: string };

/**
 * OtpInput's markup without its copy: the twin the sign-in code step renders in the browser, with every word and the
 * refusal line resolved on the server (BURNED's count included). OtpInput is the thin server wrapper that resolves them
 * and renders this (`theWrapperAndTheViewRenderTheSameMarkup`). One field for all six digits, the alert always in the
 * page and empty until a refusal, the refusal read before the lifetime line: as OtpInput's comment says.
 */
export function OtpInputView({
  length,
  words,
  name,
  defaultValue,
  error,
}: {
  length: 6;
  words: OtpWords;
  name: string;
  defaultValue?: string;
  error?: string;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const described = [error ? errorId : null, words.ttl === undefined ? null : hintId].filter(Boolean).join(" ");
  return (
    <div className="otp-input__wrap">
      <label className="otp-input__label" htmlFor={id}>
        {words.label}
      </label>
      <input
        id={id}
        className="otp-input"
        type="text"
        name={name}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={length}
        pattern={words.pattern}
        defaultValue={defaultValue}
        aria-invalid={error ? true : undefined}
        aria-describedby={described || undefined}
        required
      />
      <p className="otp-input__error" id={errorId} role="alert">
        {error ?? null}
      </p>
      {words.ttl !== undefined && (
        <p className="otp-input__hint" id={hintId}>
          {words.ttl}
        </p>
      )}
    </div>
  );
}
