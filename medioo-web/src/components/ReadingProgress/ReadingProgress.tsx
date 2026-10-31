"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { apiGet } from "@/api/browser";
import { announcement, judge, MAX_CHECKS, waitBefore } from "@/api/reading-check";
import type { PaperStatusJson } from "@/api/types";
import { clearBannerOnly } from "@/components/safety/NowBanner/banner-only";

/** What the check says, resolved by its server parent from the bundle: each settled status's words, and the link's. */
export type ReadingWords = { settled: Readonly<Record<string, string>>; again: string };

/** A paper being read: which one, the plain link to its own page (offered after the cap), and the words. */
export type ReadingCheck = { documentId: string; again: string; words: ReadingWords };

/**
 * The paper page's reading check (plan W6; PO ruling P6, 2026-10-30; WCAG F41), by Medioo's one reading of P6
 * (src/api/reading-check.ts), as the papers page's QuietCheck: with JavaScript the page never reloads itself. It asks
 * `GET …/status`, which records nothing, never the paper itself, which records a DOCUMENT_VIEW (plan Review Focus 4):
 * at Java's 5 s for the first half minute and then less often, nothing while the tab is hidden (and at once when it is
 * seen again), one request at a time. When the paper is read (or gone), the page is rendered again once
 * (`router.refresh()`, one read of the paper, the result's), the checks stop, and a read is said once in a polite live
 * region. After MAX_CHECKS it stops and offers a plain link to this page instead. A signed-out answer is apiGet's
 * sign-in hop, and stops the checks. Without JavaScript none of this runs and the reading page's `<noscript>` reload
 * moves it on, as Java's does.
 *
 * It sits before the page's own content in the paper's `<article>`, on every status, so the instance (and what its
 * region said) outlives the refresh that swaps the reading page for the result; with no paper being read (`check` null)
 * and nothing said it draws nothing.
 *
 * That one refresh is always a real read, never a banner-only one (banner-only.ts): a stale seen tap's marker still
 * set when the paper finishes would get the page an empty main, the holder would keep "being read", and with the checks
 * stopped the page would never move on. So the marker is cleared first.
 */
export function ReadingProgress({ check }: { check: ReadingCheck | null }) {
  const router = useRouter();
  // What the region says, numbered by settle, in a node keyed by the number (QuietCheck's rule): a write a screen reader
  // hears; a check that finds nothing new writes nothing.
  const [said, setSaid] = useState({ n: 0, text: "" });
  const [gaveUp, setGaveUp] = useState(false);
  // Once read (or gone) the paper is never asked again, even while the refresh is still on its way.
  const [settled, setSettled] = useState<string | null>(null);
  const latest = useRef({ check, router });
  useEffect(() => {
    latest.current = { check, router };
  });
  const id = check && check.documentId !== settled ? check.documentId : null;

  useEffect(() => {
    if (id === null) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let asking = false;
    let over = false;
    let done = 0;

    const visible = () => document.visibilityState !== "hidden";
    const later = () => {
      clearTimeout(timer);
      timer = visible() && !over ? setTimeout(ask, waitBefore(done)) : undefined;
    };
    const stop = (giveUp: boolean) => {
      over = true;
      if (giveUp) setGaveUp(true);
    };
    const ask = async () => {
      timer = undefined;
      if (over || asking || !visible()) return;
      if (done >= MAX_CHECKS) return stop(true);
      asking = true;
      const answer = await apiGet<PaperStatusJson>(`/app/api/documents/${id}/status`);
      asking = false;
      if (over) return;
      done += 1;
      if (answer.kind === "signed-out") return stop(false);
      const seen = judge(answer);
      if (seen.kind === "settled" || seen.kind === "gone") {
        stop(false);
        const text = announcement([seen], latest.current.check?.words.settled ?? {});
        setSettled(id);
        if (text !== "") setSaid((before) => ({ n: before.n + 1, text }));
        clearBannerOnly();
        latest.current.router.refresh();
        return;
      }
      if (done >= MAX_CHECKS) return stop(true);
      later();
    };
    const onVisibility = () => {
      if (!visible()) {
        clearTimeout(timer);
        timer = undefined;
      } else if (timer === undefined && !asking && !over) {
        void ask();
      }
    };

    document.addEventListener("visibilitychange", onVisibility);
    later();
    return () => {
      over = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [id]);

  if (check === null && said.text === "") return null;
  return (
    <div className="paper__quiet">
      <p className="paper__said" role="status">
        {said.text !== "" && <span key={said.n}>{said.text}</span>}
      </p>
      {gaveUp && check && (
        <a className="paper__action paper__again" href={check.again}>
          {check.words.again}
        </a>
      )}
    </div>
  );
}
