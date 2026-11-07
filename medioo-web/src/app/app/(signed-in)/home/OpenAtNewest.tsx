"use client";

import { useEffect } from "react";
import { bringIntoView } from "@/components/Conversation/bring-into-view";

/**
 * Opens the chat page on the newest exchange (the direction contract: "the thread ends on the newest exchange"): once,
 * on mount, the window is scrolled to the end of the page, where the docked composer sits at its own place just after
 * the newest item, so nothing is under it. The page lays out oldest first, so this is all it takes; images that load
 * later can push the end down, so it scrolls once more at `load` unless the family has already scrolled, pressed a key
 * or touched the page. Without JavaScript it does nothing and the page reads from the top.
 *
 * `hold`: a NOW result is on the page (the banner, or a NOW card in the attention stack). The page is then left at the
 * top, where that result is, so it is read first and never scrolled away by the page itself.
 *
 * `within` (the desktop conversation, plan desktop-parity Task 7): the selector of a column that scrolls inside itself,
 * which is scrolled instead of the window, to its end; or, when `at` names an element inside it (the selected paper's
 * item), to that element, centred, or a card taller than the column at its top (bringIntoView). The same `load` rule and the same `hold`.
 */
export function OpenAtNewest({ hold, within, at }: { hold: boolean; within?: string; at?: string | null }) {
  useEffect(() => {
    if (hold) return;
    const end = () => {
      if (within === undefined) return window.scrollTo({ top: document.documentElement.scrollHeight, behavior: "instant" });
      const column = document.querySelector<HTMLElement>(within);
      if (column === null) return;
      const target = at ? column.querySelector<HTMLElement>(at) : null;
      if (target !== null) return bringIntoView(target, column, "open", "instant");
      column.scrollTop = column.scrollHeight;
    };
    end();
    if (document.readyState === "complete") return;
    let moved = false;
    const mark = () => {
      moved = true;
    };
    const onLoad = () => {
      if (!moved) end();
    };
    const signals = ["wheel", "touchstart", "keydown", "pointerdown"] as const;
    for (const s of signals) window.addEventListener(s, mark, { passive: true });
    window.addEventListener("load", onLoad, { once: true });
    return () => {
      for (const s of signals) window.removeEventListener(s, mark);
      window.removeEventListener("load", onLoad);
    };
  }, [hold, within, at]);
  return null;
}
