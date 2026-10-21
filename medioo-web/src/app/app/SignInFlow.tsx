"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { apiSend } from "@/api/browser";
import { PhoneFieldView } from "@/components/PhoneField/PhoneFieldView";
import type { SignInWords } from "./sign-in-words";

type Answer = { confirm: { number_display: string } } | { sent: boolean };

/**
 * The number step, then the number shown back for a yes (plan W3), over `POST /app/api/sign-in/request`. Nothing is
 * requested until the family says yes to the number Java normalised and showed back (`confirm.number_display`, shown
 * verbatim); only that press sends `confirmed: true`. `next` is passed to Java as the page received it: Java's LocalPath
 * judges it, Next never does. A sent or a not-sent answer both go to the code page, which reads which one it was from
 * Java (`sign-in/state`), so this island never says a code was sent.
 *
 * No `<form>`: without JavaScript a form would submit the number into a URL or post it somewhere, and every mutation
 * needs JavaScript (DR-42). The press is a button and Enter in the field, and both stay off until the island has
 * hydrated (`live`), so the server markup a browser without JavaScript keeps never offers a press that does nothing;
 * that browser is offered Java's own form by the page's `<noscript>` link, above the step.
 *
 * Focus follows the step (a screen reader user is told the number is now shown for a yes, which is this flow's safety
 * step): the number shown back takes focus when it appears, and the field takes it back on login.number.change or a refused number.
 * A refusal after the yes is set only once the field has mounted again, so its alert announces a change.
 */
export function SignInFlow({ words, next, verifyHref }: { words: SignInWords; next: string | null; verifyHref: string }) {
  const [live, setLive] = useState(false);
  const [number, setNumber] = useState("");
  const [shown, setShown] = useState<string | null>(null);
  const [error, setError] = useState<string | undefined>(undefined);
  const [queuedError, setQueuedError] = useState<string | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const step = useRef<HTMLDivElement>(null);
  const numberBack = useRef<HTMLParagraphElement>(null);
  const wasShown = useRef(false);

  useEffect(() => setLive(true), []);

  useEffect(() => {
    if (shown !== null) {
      numberBack.current?.focus();
    } else if (wasShown.current) {
      step.current?.querySelector<HTMLInputElement>('input[name="whatsapp_number"]')?.focus();
    }
    wasShown.current = shown !== null;
  }, [shown]);

  useEffect(() => {
    if (shown === null && queuedError !== undefined) {
      setError(queuedError);
      setQueuedError(undefined);
    }
  }, [shown, queuedError]);

  const send = async (confirmed: boolean) => {
    // The field's own value while it is on the page: a number typed before the island hydrated (a slow phone) never
    // reached onInput, but it is in the field. On the confirm step the field is gone and `number` holds what was sent.
    const field = step.current?.querySelector<HTMLInputElement>('input[name="whatsapp_number"]');
    const typed = field ? field.value : number;
    setNumber(typed);
    setBusy(true);
    setFailed(false);
    const body = { whatsapp_number: typed, ...(confirmed ? { confirmed: true } : {}), ...(next === null ? {} : { next }) };
    const result = await apiSend<Answer>("POST", "/app/api/sign-in/request", body);
    setBusy(false);
    if (result.kind === "refused" && result.reason === "INVALID_NUMBER") {
      if (shown === null) {
        setError(words.invalid);
      } else {
        setError(undefined);
        setQueuedError(words.invalid);
        setShown(null);
      }
      return;
    }
    if (result.kind !== "ok" || result.body === null) {
      setFailed(true);
      return;
    }
    if ("confirm" in result.body) {
      setError(undefined);
      setShown(result.body.confirm.number_display);
      return;
    }
    setBusy(true);
    location.assign(verifyHref);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === "Enter" && !e.nativeEvent.isComposing && live && !busy && shown === null) {
      e.preventDefault();
      void send(false);
    }
  };

  return (
    <div className="sign-in__flow">
      {shown === null ? (
        <div
          ref={step}
          className="sign-in__step"
          onKeyDown={onKeyDown}
          onInput={(e) => setNumber((e.target as HTMLInputElement).value)}
        >
          <PhoneFieldView words={words.field} name="whatsapp_number" error={error} defaultValue={number} />
          <button type="button" className="sign-in__primary" data-primary="" disabled={!live || busy} onClick={() => void send(false)}>
            {words.next}
          </button>
        </div>
      ) : (
        <div className="sign-in__step">
          <p ref={numberBack} className="sign-in__number" data-number-back="" tabIndex={-1}>
            {shown}
          </p>
          <p className="sign-in__question">{words.confirm}</p>
          <button type="button" className="sign-in__primary" data-primary="" disabled={busy} onClick={() => void send(true)}>
            {words.yes}
          </button>
          <button type="button" className="sign-in__quiet" disabled={busy} onClick={() => setShown(null)}>
            {words.change}
          </button>
        </div>
      )}
      <p className="sign-in__status" role="alert">
        {failed ? words.generic : null}
      </p>
    </div>
  );
}
