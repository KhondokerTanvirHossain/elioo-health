"use client";

import { useState } from "react";
import type { NowSeenJson } from "@/api/types";

/**
 * The "seen" button of the NOW banner, the one part of it that needs JavaScript (DR-42: every mutation does). It sends
 * `PUT /app/api/now-banner/ack` with the banner's `seen` list exactly as Java sent it, never rebuilt from the
 * documents, so the acknowledgement covers precisely what was shown (DR-41 item 5). The label arrives as a string so
 * this island ships no copy; `onSeen` runs only once the server accepted the acknowledgement. A refusal leaves the
 * banner as it is and the button usable again.
 */
export function NowBannerSeen({ seen, label, onSeen }: { seen: NowSeenJson[]; label: string; onSeen?: () => void }) {
  const [sending, setSending] = useState(false);

  async function send() {
    setSending(true);
    try {
      const response = await fetch("/app/api/now-banner/ack", {
        method: "PUT",
        headers: { "Content-Type": "application/json", "X-Medioo": "1" },
        body: JSON.stringify({ seen }),
      });
      if (response.ok) onSeen?.();
    } catch {
      // Offline or refused: nothing is hidden, the banner stays, and the family can tap again.
    } finally {
      setSending(false);
    }
  }

  return (
    <button type="button" className="now-banner__seen" disabled={sending} onClick={send}>
      {label}
    </button>
  );
}
