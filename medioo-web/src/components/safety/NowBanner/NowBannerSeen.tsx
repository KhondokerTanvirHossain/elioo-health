"use client";

import { useEffect, useState } from "react";
import { apiSend } from "@/api/browser";
import type { NowBannerJson, NowSeenJson } from "@/api/types";
import { useBannerAnswer } from "./NowBannerHold";

/** The PUT's answer is the banner re-read; anything that is not one (a body that failed to parse) is not an answer. */
function isBanner(body: unknown): body is NowBannerJson {
  if (typeof body !== "object" || body === null) return false;
  const b = body as Record<string, unknown>;
  return typeof b.show === "boolean" && Array.isArray(b.documents) && Array.isArray(b.seen) && typeof b.line_key === "string";
}

/**
 * The "seen" button of the NOW banner, the one part of it that needs JavaScript (DR-42: every mutation does). It sends
 * `PUT /app/api/now-banner/ack` through apiSend with the banner's `seen` list exactly as Java sent it, never rebuilt from
 * the documents, so the acknowledgement covers precisely what was shown (DR-41 item 5).
 *
 * The server records only the entries that match what the banner shows right now and answers 200 with the banner
 * re-read, so a 2xx does not mean "hidden": the answer goes to the hold around the banner (NowBannerHold), which renders
 * it. Nothing is hidden on the tap itself. A refusal or a failed send leaves the banner as it is, says so in a status
 * line, and makes the button usable again; a 401 is apiSend's (the browser goes to sign-in holding this page) and the
 * banner stays rendered while the page leaves, the button still busy.
 *
 * `live` is the server's flag that this banner is a real one (a signed-in page, shell.tsx): without it, or without a
 * hold to hand the answer to, the button is inert and sends nothing (the catalog). The labels arrive as strings so this
 * island ships no copy, and no function crosses from the server.
 *
 * The button appears only once JavaScript runs (DR-42 item 1): without it a NOW result is still read in full, and there
 * is no control that would look like it acknowledged anything.
 */
export function NowBannerSeen({
  seen,
  label,
  failedLabel,
  live,
}: {
  seen: NowSeenJson[];
  label: string;
  failedLabel: string;
  live?: boolean;
}) {
  const answer = useBannerAnswer();
  const [ready, setReady] = useState(false);
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => setReady(true), []);

  async function send() {
    if (!live || !answer || sending) return;
    setSending(true);
    setFailed(false);
    const result = await apiSend<NowBannerJson>("PUT", "/app/api/now-banner/ack", { seen });
    // Signed out: apiSend has already sent the browser to sign-in. The banner stays, and the button stays busy.
    if (result.kind === "signed-out") return;
    setSending(false);
    if (result.kind === "ok" && isBanner(result.body)) answer(result.body);
    else setFailed(true);
  }

  if (!ready) return null;
  return (
    <>
      <button type="button" className="now-banner__seen" disabled={sending} onClick={send}>
        {label}
      </button>
      {failed && (
        <p className="now-banner__status" role="status">
          {failedLabel}
        </p>
      )}
    </>
  );
}
