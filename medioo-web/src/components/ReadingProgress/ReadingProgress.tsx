"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { apiGet } from "@/api/browser";
import { announcement, judge, MAX_CHECKS, waitBefore, type Seen } from "@/api/reading-check";
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
 * seen again), one request at a time, and one check per paper however many views of it are mounted (joinCheck). When the paper is read (or gone), the page is rendered again once
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
/** What a mounted check hears from its paper's one run: the paper read (or gone), the cap reached, the refresh to do. */
type Listener = { settled: (seen: Seen) => void; gaveUp: () => void; refresh: () => void };
type Run = { listeners: Set<Listener>; stop: () => void };

/**
 * The checks running now, one per paper (review of plan desktop-parity Task 8, finding 2). A page can mount two views of
 * the same paper being read (the desktop screen's viewer beside the phone shell's paper page); each mounts a
 * ReadingProgress, and every one of them joins the paper's one run here instead of starting its own. So one paper is
 * one GET /status at a time, whichever surfaces show it, and its settle is one refresh (one read of the paper, one
 * view), heard by every view, each saying it in its own region. The run ends when its last view leaves, or when it
 * settles, gives up or is signed out; a view mounted after that starts a new one.
 */
const runs = new Map<string, Run>();

function joinCheck(id: string, listener: Listener): () => void {
  let run = runs.get(id);
  if (run === undefined) {
    run = startRun(id);
    runs.set(id, run);
  }
  const joined = run;
  joined.listeners.add(listener);
  return () => {
    joined.listeners.delete(listener);
    if (joined.listeners.size === 0) {
      joined.stop();
      if (runs.get(id) === joined) runs.delete(id);
    }
  };
}

function startRun(id: string): Run {
  const listeners = new Set<Listener>();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let asking = false;
  let over = false;
  let done = 0;

  const visible = () => document.visibilityState !== "hidden";
  const later = () => {
    clearTimeout(timer);
    timer = visible() && !over ? setTimeout(ask, waitBefore(done)) : undefined;
  };
  const end = () => {
    over = true;
    clearTimeout(timer);
    document.removeEventListener("visibilitychange", onVisibility);
    if (runs.get(id) === run) runs.delete(id);
  };
  const stop = (giveUp: boolean) => {
    end();
    if (giveUp) for (const l of [...listeners]) l.gaveUp();
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
      const heard = [...listeners];
      for (const l of heard) l.settled(seen);
      clearBannerOnly();
      heard[0]?.refresh();
      return;
    }
    if (done >= MAX_CHECKS) return stop(true);
    later();
  };
  function onVisibility() {
    if (!visible()) {
      clearTimeout(timer);
      timer = undefined;
    } else if (timer === undefined && !asking && !over) {
      void ask();
    }
  }

  document.addEventListener("visibilitychange", onVisibility);
  const run: Run = { listeners, stop: end };
  later();
  return run;
}

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
    return joinCheck(id, {
      settled: (seen) => {
        const text = announcement([seen], latest.current.check?.words.settled ?? {});
        setSettled(id);
        if (text !== "") setSaid((before) => ({ n: before.n + 1, text }));
      },
      gaveUp: () => setGaveUp(true),
      refresh: () => latest.current.router.refresh(),
    });
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
