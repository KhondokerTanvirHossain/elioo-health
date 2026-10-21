"use client";

import { useState } from "react";
import type { NowBannerJson, NowSeenJson } from "@/api/types";

/** The PUT's answer is the banner re-read; anything that is not one (a body that failed to parse) is not an answer. */
function isBanner(body: unknown): body is NowBannerJson {
  if (typeof body !== "object" || body === null) return false;
  const b = body as Record<string, unknown>;
  return typeof b.show === "boolean" && Array.isArray(b.documents) && Array.isArray(b.seen) && typeof b.line_key === "string";
}

/**
 * The "seen" button of the NOW banner, the one part of it that needs JavaScript (DR-42: every mutation does). It sends
 * `PUT /app/api/now-banner/ack` with the banner's `seen` list exactly as Java sent it, never rebuilt from the
 * documents, so the acknowledgement covers precisely what was shown (DR-41 item 5).
 *
 * The server records only the entries that match what the banner shows right now and answers 200 with the banner
 * re-read, so a 2xx does not mean "hidden": `onSeen` receives that answer, and the caller renders it (a stale tap, after
 * the reviewer released the paper or a new NOW paper arrived, answers `show: true` and the banner stays). A refusal or a
 * failed send leaves the banner as it is, says so in a status line, and makes the button usable again. With no
 * `onSeen` there is nobody to hand the answer to, so the button is inert and sends nothing (the catalog). The labels
 * arrive as strings so this island ships no copy.
 */
export function NowBannerSeen({
  seen,
  label,
  failedLabel,
  onSeen,
}: {
  seen: NowSeenJson[];
  label: string;
  failedLabel: string;
  onSeen?: (next: NowBannerJson) => void;
}) {
  const [sending, setSending] = useState(false);
  const [failed, setFailed] = useState(false);

  async function send() {
    if (!onSeen || sending) return;
    setSending(true);
    setFailed(false);
    let next: NowBannerJson | null = null;
    try {
      const response = await fetch("/app/api/now-banner/ack", {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Medioo": "1" },
        body: JSON.stringify({ seen }),
      });
      if (response.ok) {
        const body: unknown = await response.json();
        if (isBanner(body)) next = body;
      }
    } catch {
      // Offline, or an answer that is not JSON: nothing is hidden, the banner stays, and the family can tap again.
    } finally {
      setSending(false);
    }
    if (next) onSeen(next);
    else setFailed(true);
  }

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
