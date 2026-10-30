"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { apiGet } from "@/api/browser";
import type { PaperStatusJson } from "@/api/types";
import { announcement, judge, MAX_CHECKS, waitBefore, type Seen } from "@/api/reading-check";

/** What the check says, resolved by its server parent from the bundle: each settled status's words, and the link's. */
export type QuietWords = { settled: Readonly<Record<string, string>>; again: string };

/**
 * The papers page's quiet check (PO ruling P6, 2026-10-30; WCAG F41). With JavaScript the page never reloads itself:
 * this asks Java for the status of each paper still being read (`GET /app/api/documents/{id}/status`, which records
 * nothing), at Java's 5 s for the first half minute and then less often, and when one has been read (or is gone) it
 * re-renders the page's data once (`router.refresh()`: one more render, one more TIMELINE_VIEW, as a reload was) and
 * says so once in a polite live region. It asks nothing while nothing is being read or the tab is hidden (and asks at
 * once when the tab is seen again), and after MAX_CHECKS it stops and offers a plain link to this page instead.
 * Without JavaScript none of this runs and the page's `<noscript>` refresh reloads it, as Java's does. Nothing moves.
 */
export function QuietCheck({ reading, again, words }: { reading: string[]; again: string; words: QuietWords }) {
  const router = useRouter();
  // What the region says, numbered by settle. The words sit in a node keyed by the number, so each paper read is a new
  // write a screen reader hears, even when it says what the one before said ("Read", then "Read" again for a second
  // paper); a check that finds nothing new writes nothing.
  const [said, setSaid] = useState({ n: 0, text: "" });
  const [gaveUp, setGaveUp] = useState(false);
  // The papers this has found read (or gone): never asked again and never re-rendered for again, even while the
  // re-render that drops them from `reading` is still on its way (a slow phone network). Until it lands the checks go on
  // for the other papers only, so one paper read is one re-render and one TIMELINE_VIEW, however long it takes.
  const [settled, setSettled] = useState<ReadonlySet<string>>(() => new Set());
  // The latest words and router, read when needed, so a re-render's new objects never restart the checks.
  const latest = useRef({ words, router });
  useEffect(() => {
    latest.current = { words, router };
  });
  // One key per set of papers still to check: a re-render that lists the same papers keeps the same checks going.
  const key = reading.filter((id) => !settled.has(id)).join(",");
  // How many checks this set of papers has had: the cap is the set's, never reset by a re-render that lists the same.
  const count = useRef({ key, done: 0 });

  useEffect(() => {
    const ids = key === "" ? [] : key.split(",");
    if (count.current.key !== key) count.current = { key, done: 0 };
    if (ids.length === 0) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let asking = false;
    let over = false;

    const visible = () => document.visibilityState !== "hidden";
    const later = () => {
      clearTimeout(timer);
      timer = visible() && !over ? setTimeout(check, waitBefore(count.current.done)) : undefined;
    };
    const check = async () => {
      timer = undefined;
      if (over || asking || !visible()) return;
      if (count.current.done >= MAX_CHECKS) {
        over = true;
        setGaveUp(true);
        return;
      }
      asking = true;
      const seen: Seen[] = await Promise.all(ids.map((id) => apiGet<PaperStatusJson>(`/app/api/documents/${id}/status`).then(judge)));
      asking = false;
      if (over) return;
      count.current.done += 1;
      const done = ids.filter((_, i) => seen[i]?.kind === "settled" || seen[i]?.kind === "gone");
      if (done.length > 0) {
        // These leave the checks (the key changes, so the others are checked on without them), the page is asked to
        // re-render once, and the region is written once.
        over = true;
        const text = announcement(seen, latest.current.words.settled);
        setSettled((before) => new Set([...before, ...done]));
        if (text !== "") setSaid((before) => ({ n: before.n + 1, text }));
        latest.current.router.refresh();
        return;
      }
      if (count.current.done >= MAX_CHECKS) {
        over = true;
        setGaveUp(true);
        return;
      }
      later();
    };
    const onVisibility = () => {
      if (!visible()) {
        clearTimeout(timer);
        timer = undefined;
      } else if (timer === undefined && !asking && !over) {
        void check();
      }
    };

    document.addEventListener("visibilitychange", onVisibility);
    later();
    return () => {
      over = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, [key]);

  return (
    <div className="papers__quiet">
      <p className="papers__said" role="status">
        {said.text !== "" && <span key={said.n}>{said.text}</span>}
      </p>
      {gaveUp && (
        <a className="papers__again" href={again}>
          {words.again}
        </a>
      )}
    </div>
  );
}
