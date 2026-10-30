"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { apiGet } from "@/api/browser";
import { READING_STATUSES, type PaperStatusJson } from "@/api/types";
import { clearBannerOnly } from "@/components/safety/NowBanner/banner-only";
import { POLL_SECONDS } from "./poll";

/** How often the reading page asks (DocumentPages.POLL_SECONDS). */
export const POLL_MS = POLL_SECONDS * 1_000;

const reading = (status: string) => (READING_STATUSES as readonly string[]).includes(status);

/**
 * The reading page's poll (plan W6; DocumentPages' progress.js): every five seconds it asks `GET …/status`, which
 * records nothing, never the paper itself, which records a DOCUMENT_VIEW (plan Review Focus 4). When the paper is no
 * longer being read, the page is rendered again once (`router.refresh()`, one read of the paper, the result's), and
 * the poll stops; a paper gone in the meantime is refreshed too, so the page says so. A signed-out answer is apiGet's
 * sign-in hop. One request at a time: the next waits for the answer to the last. It renders nothing.
 *
 * That one refresh is always a real read, never a banner-only one (banner-only.ts): a stale seen tap's marker still
 * set when the paper finishes would get the page an empty main, the holder would keep "being read", and with the poll
 * stopped the page would never move on. So the marker is cleared first.
 */
export function ReadingProgress({ documentId, every = POLL_MS }: { documentId: string; every?: number }) {
  const router = useRouter();
  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const ask = async () => {
      const answer = await apiGet<PaperStatusJson>(`/app/api/documents/${documentId}/status`);
      if (stopped) return;
      const done = answer.kind === "ok" && answer.body !== null && !reading(answer.body.status);
      const gone = answer.kind === "refused" && answer.status === 404;
      if (done || gone) {
        stopped = true;
        clearBannerOnly();
        router.refresh();
        return;
      }
      if (answer.kind === "signed-out") return;
      timer = setTimeout(ask, every);
    };
    timer = setTimeout(ask, every);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [documentId, every, router]);
  return null;
}
