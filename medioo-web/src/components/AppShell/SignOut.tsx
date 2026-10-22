"use client";

import { useState } from "react";
import { apiSend } from "@/api/browser";

/**
 * Sign out (plan W4): `POST /app/api/sign-out` (W3-api: Java ends the session and clears the cookie), then the sign-in
 * page, `to` (the preview's when this is a preview page). A send that did not reach Java, or one Java refused, leaves
 * the family signed in and says so (`failed`); a 401 means the session had already ended, and apiSend goes to
 * sign-in. The words are resolved by the server: no copy ships here.
 */
export function SignOut({ label, failed, to }: { label: string; failed: string; to: string }) {
  const [sending, setSending] = useState(false);
  const [said, setSaid] = useState<string | null>(null);

  async function signOut() {
    if (sending) return;
    setSending(true);
    setSaid(null);
    const result = await apiSend("POST", "/app/api/sign-out");
    if (result.kind === "ok") {
      location.assign(to);
      return;
    }
    if (result.kind === "signed-out") return;
    setSending(false);
    setSaid(failed);
  }

  return (
    <>
      <button type="button" className="app-shell__sign-out" disabled={sending} onClick={signOut}>
        {label}
      </button>
      {said !== null && (
        <p className="app-shell__status" role="status">
          {said}
        </p>
      )}
    </>
  );
}
