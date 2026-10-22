"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { apiSend } from "@/api/browser";
import { OtpInputView } from "@/components/OtpInput/OtpInputView";
import { href } from "@/routing/paths";
import type { CodeReason, CodeWords } from "./sign-in-words";

/**
 * The code step (plan W3), over `POST /app/api/sign-in/verify`. Each refused code is said in its own line under the
 * field, as the HTML verify page says it; `SIGN_IN_RESTART` (no pending login any more) goes back to the number. A
 * verified code opens the place Java answered (`next`, checked by Java's LocalPath when it was held and again now),
 * inside the preview when this is a preview page and Next serves that place, as page.tsx sends a signed-in visitor on.
 * No `<form>`, and the press and Enter stay off until the island has hydrated, for the reasons SignInFlow gives:
 * without JavaScript the `<noscript>` link offers Java's own form.
 */
export function CodeFlow({ words, numberHref, preview }: { words: CodeWords; numberHref: string; preview: boolean }) {
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const step = useRef<HTMLDivElement>(null);

  useEffect(() => setLive(true), []);

  const verify = async () => {
    setBusy(true);
    setFailed(false);
    // The field's own value: a code typed before the island hydrated is in the field, never in an onInput state.
    const code = step.current?.querySelector<HTMLInputElement>('input[name="code"]')?.value ?? "";
    const result = await apiSend<{ next: string }>("POST", "/app/api/sign-in/verify", { code });
    if (result.kind === "ok" && result.body !== null) {
      location.assign(href(result.body.next, preview));
      return;
    }
    setBusy(false);
    if (result.kind === "refused" && result.reason === "SIGN_IN_RESTART") {
      location.assign(numberHref);
      return;
    }
    // A name Java sends for a refused code has its own line; any other refusal is the generic one.
    if (result.kind === "refused" && Object.hasOwn(words.refusals, result.reason)) {
      setError(words.refusals[result.reason as CodeReason]);
      return;
    }
    setFailed(true);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === "Enter" && !e.nativeEvent.isComposing && live && !busy) {
      e.preventDefault();
      void verify();
    }
  };

  return (
    <div className="sign-in__flow">
      <div ref={step} className="sign-in__step" onKeyDown={onKeyDown}>
        <OtpInputView length={6} words={words.field} name="code" error={error} />
        <button type="button" className="sign-in__primary" data-primary="" disabled={!live || busy} onClick={() => void verify()}>
          {words.enter}
        </button>
      </div>
      <p className="sign-in__status" role="alert">
        {failed ? words.generic : null}
      </p>
    </div>
  );
}
