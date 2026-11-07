"use client";

import { useEffect, useRef } from "react";
import { bringIntoView } from "./bring-into-view";

/** How long the chosen card pulses (desktop.html's `.card.pulse`, 900 ms). */
const PULSE_MS = 900;
/** The class that pulses the chosen paper's card (Conversation.css). */
export const PULSE = "conversation__item--pulse";

/**
 * The conversation following the viewer (desktop.html's select(); PO 2026-11-07, "the chat was automatically scrolling up
 * and down"): when the paper in view changes (the strip, the arrows, the keys, a card), its item in the column `within`
 * is brought into view the shortest way (`nearest`; a card taller than the column at its top, bringIntoView), smoothly
 * unless the family asked for less motion, and its card pulses once. Never on opening: OpenAtNewest owns where the
 * column opens, and holds it still at a pinned NOW card. A change is the family's own choice, so it follows even then.
 * Draws nothing.
 */
export function FollowPaper({ within, paperId }: { within: string; paperId: string | null }) {
  const shown = useRef(paperId);
  useEffect(() => {
    if (paperId === shown.current) return;
    shown.current = paperId;
    if (paperId === null) return;
    const column = document.querySelector<HTMLElement>(within);
    const item = column?.querySelector<HTMLElement>(`[data-talk-item="paper"][data-paper="${paperId}"]`);
    if (!column || !item) return;
    const less = typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    bringIntoView(item, column, "follow", less ? "auto" : "smooth");
    item.classList.remove(PULSE);
    void item.offsetWidth; // restart the animation when the same card is chosen twice in a row
    item.classList.add(PULSE);
    const done = window.setTimeout(() => item.classList.remove(PULSE), PULSE_MS);
    return () => window.clearTimeout(done);
  }, [within, paperId]);
  return null;
}
