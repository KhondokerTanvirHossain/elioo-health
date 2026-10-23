"use client";

import { createContext, useCallback, useContext, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import type { NowBannerJson } from "@/api/types";

/** What the seen button hands its answer to: the banner Java re-read after the acknowledgement. */
type Answer = (next: NowBannerJson) => void;

const HoldContext = createContext<Answer | null>(null);

/** The hold around this banner, or null when there is none (the catalog): the seen button is then inert. */
export function useBannerAnswer(): Answer | null {
  return useContext(HoldContext);
}

/**
 * Holds the state after the seen button (`now.banner.seen`) around the server-rendered banner (plan W5). `children` is
 * NowBanner, rendered on the server from GET /app/api/now-banner; this island only decides whether it stays, and only
 * from Java's answer to the acknowledgement, never from the tap:
 * - `show: false`: the server hid it, so nothing is rendered;
 * - `show: true` (a stale tap: the reviewer released the paper, or a new NOW paper arrived): the page is re-read
 *   (`router.refresh()`), so the banner is rendered again from what Java now says.
 * A refusal, a failed send or a 401 never reaches here: the banner stays (NowBannerSeen says so, or goes to sign-in).
 * The shell keys the hold by the banner Java sent, so a banner that changed on a later navigation starts shown.
 */
export function NowBannerHold({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [hidden, setHidden] = useState(false);
  const answer = useCallback<Answer>(
    (next) => {
      if (next.show) router.refresh();
      else setHidden(true);
    },
    [router],
  );
  if (hidden) return null;
  return <HoldContext.Provider value={answer}>{children}</HoldContext.Provider>;
}
