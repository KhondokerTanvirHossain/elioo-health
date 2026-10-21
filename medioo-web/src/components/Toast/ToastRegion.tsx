"use client";

import { useEffect, useState } from "react";

/**
 * The live region a confirmation is announced in, and the toast inside it. A screen reader announces a change to a
 * live region that is already in the page; a region inserted with its text already inside is often not announced
 * (VoiceOver on iOS, NVDA for `status`). So the region always renders, empty on its first render (what the server
 * sends and what the browser inserts on mount), and the message is written into it after mount, and again each time
 * it changes.
 *
 * This is the API for a client caller (a save button, W10): mount it once where the toast sits, keep it mounted, and
 * swap `message` (a string its server parent resolved, or null for none). It imports no copy, so a client caller ships
 * none (eslint and built/client-copy.built.test.ts). Never red and never amber: a toast is never how Medioo says
 * something is urgent. Without JavaScript it stays empty, which is right: what it confirms is a mutation, and every
 * mutation needs JavaScript (DR-42).
 */
export function ToastRegion({ message }: { message: string | null }) {
  const [shown, setShown] = useState<string | null>(null);
  useEffect(() => {
    setShown(message);
  }, [message]);
  return (
    <div className="toast-region" role="status" aria-live="polite" aria-atomic="true">
      {shown !== null && (
        <div className="toast">
          <svg className="toast__shape" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path d="M5 12.5l4.5 4.5L19 7.5" />
          </svg>
          <span className="toast__text">{shown}</span>
        </div>
      )}
    </div>
  );
}
